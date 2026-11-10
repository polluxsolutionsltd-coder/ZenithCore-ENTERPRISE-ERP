package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock reports and the expired-stock rule.
 * <ul>
 * <li>Expiry: stock by lot, bucketed by days to expiry (expired, within 30, 31-90, 91-180, later, no date),
 * soonest first, so the store issues first-expiry-first-out and plans disposals.</li>
 * <li>Slow and non-moving: stock with no issue for a long time, or more months of cover than policy allows.
 * Thresholds are the client's policy settings, passed in.</li>
 * <li>Wastage: stock written off (expired, damaged, theft, obsolete) and rejected on receipt, by reason,
 * store and item.</li>
 * <li>Expired stock is not shipped, used or moved into a normal store; it can only be written off or moved to
 * quarantine or rejected locations.</li>
 * </ul>
 */
public final class StockAnalysis {

    public static final String CONTROL = "Expired stock";

    private StockAnalysis() {
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ expiry

    public enum ExpiryBucket {
        EXPIRED("Expired"), D30("Expires within 30 days"), D90("31-90 days"), D180("91-180 days"), LATER("Over 180 days"),
        NO_DATE("No expiry date");

        public final String label;

        ExpiryBucket(String label) {
            this.label = label;
        }
    }

    /** Stock of one lot in one store. expiry = last day it may be used; null if none recorded. */
    public record Lot(String store, String product, String lot, LocalDate expiry, BigDecimal qty, BigDecimal unitCost) {
        public BigDecimal value() {
            return money(qty.multiply(unitCost == null ? BigDecimal.ZERO : unitCost));
        }
    }

    /** The day after the expiry date the stock is expired; a lot expiring today is still usable today. */
    public static ExpiryBucket bucket(LocalDate expiry, LocalDate asAt) {
        if (expiry == null) {
            return ExpiryBucket.NO_DATE;
        }
        long days = ChronoUnit.DAYS.between(asAt, expiry);
        return days < 0 ? ExpiryBucket.EXPIRED : days <= 30 ? ExpiryBucket.D30 : days <= 90 ? ExpiryBucket.D90
                : days <= 180 ? ExpiryBucket.D180 : ExpiryBucket.LATER;
    }

    public record ExpiryReport(List<Lot> lots, Map<ExpiryBucket, BigDecimal> valueByBucket, Map<ExpiryBucket, Integer> lotsByBucket) {
    }

    /** Lots soonest expiry first (no date last), with value and count per bucket. */
    public static ExpiryReport expiry(List<Lot> lots, LocalDate asAt) {
        Map<ExpiryBucket, BigDecimal> value = new EnumMap<>(ExpiryBucket.class);
        Map<ExpiryBucket, Integer> count = new EnumMap<>(ExpiryBucket.class);
        for (ExpiryBucket b : ExpiryBucket.values()) {
            value.put(b, BigDecimal.ZERO.setScale(2));
            count.put(b, 0);
        }
        List<Lot> sorted = new ArrayList<>();
        for (Lot l : lots) {
            if (l.qty().signum() <= 0) {
                continue;
            }
            sorted.add(l);
            ExpiryBucket b = bucket(l.expiry(), asAt);
            value.merge(b, l.value(), BigDecimal::add);
            count.merge(b, 1, Integer::sum);
        }
        sorted.sort(Comparator.comparing(Lot::expiry, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Lot::product).thenComparing(Lot::store));
        return new ExpiryReport(sorted, value, count);
    }

    // ------------------------------------------------------------ slow moving

    public enum Movement {
        NON_MOVING("Non-moving"), SLOW("Slow-moving"), NORMAL("Moving");

        public final String label;

        Movement(String label) {
            this.label = label;
        }
    }

    /** An item in a store: on hand, cost, last issue (null if never issued) and quantity issued in the last 12 months. */
    public record Item(String store, String product, BigDecimal onHand, BigDecimal unitCost, LocalDate lastIssue, BigDecimal issued12m) {
        public BigDecimal value() {
            return money(onHand.multiply(unitCost == null ? BigDecimal.ZERO : unitCost));
        }
    }

    /** Policy: days without an issue for slow and for non-moving, and the most months of cover that is normal. */
    public record Policy(int slowDays, int nonMovingDays, BigDecimal maxCoverMonths) {
    }

    public record Classified(Item item, Movement movement, Long daysSinceIssue, BigDecimal coverMonths, String reason) {
    }

    /**
     * Months of cover = on hand / average monthly issues over the last 12 months (null when nothing was
     * issued). Non-moving: never issued, or no issue for nonMovingDays. Slow: no issue for slowDays, or cover
     * above the maximum.
     */
    public static Classified classify(Item i, LocalDate asAt, Policy p) {
        Long days = i.lastIssue() == null ? null : ChronoUnit.DAYS.between(i.lastIssue(), asAt);
        BigDecimal cover = i.issued12m() == null || i.issued12m().signum() <= 0 ? null
                : i.onHand().multiply(BigDecimal.valueOf(12)).divide(i.issued12m(), 1, RoundingMode.HALF_UP);
        if (days == null) {
            return new Classified(i, Movement.NON_MOVING, null, cover, "never issued");
        }
        if (days >= p.nonMovingDays()) {
            return new Classified(i, Movement.NON_MOVING, days, cover, "no issue for " + days + " days");
        }
        if (days >= p.slowDays()) {
            return new Classified(i, Movement.SLOW, days, cover, "no issue for " + days + " days");
        }
        if (cover != null && cover.compareTo(p.maxCoverMonths()) > 0) {
            return new Classified(i, Movement.SLOW, days, cover, cover + " months of cover");
        }
        return new Classified(i, Movement.NORMAL, days, cover, "");
    }

    /** Slow and non-moving items only, largest value first. */
    public static List<Classified> slowMoving(List<Item> items, LocalDate asAt, Policy p) {
        List<Classified> out = new ArrayList<>();
        for (Item i : items) {
            if (i.onHand().signum() <= 0) {
                continue;
            }
            Classified c = classify(i, asAt, p);
            if (c.movement() != Movement.NORMAL) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing((Classified c) -> c.movement().ordinal()).thenComparing(c -> c.item().value(),
                Comparator.reverseOrder()));
        return out;
    }

    // ---------------------------------------------------------------- wastage

    /** A loss: written off with a reason, or rejected on receipt (source tells which). */
    public record Loss(LocalDate date, String store, String product, String reason, String source, BigDecimal qty, BigDecimal value) {
    }

    public record Wastage(BigDecimal total, Map<String, BigDecimal> byReason, Map<String, BigDecimal> byStore,
                          List<Map.Entry<String, BigDecimal>> topItems) {
    }

    /** Totals by reason and store (largest first) and the items with the largest losses. */
    public static Wastage wastage(List<Loss> losses, int top) {
        Map<String, BigDecimal> reason = new LinkedHashMap<>(), store = new LinkedHashMap<>(), item = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Loss l : losses) {
            BigDecimal v = money(l.value());
            total = total.add(v);
            reason.merge(l.reason(), v, BigDecimal::add);
            store.merge(l.store(), v, BigDecimal::add);
            item.merge(l.product(), v, BigDecimal::add);
        }
        return new Wastage(total, sortDesc(reason), sortDesc(store), item.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).limit(top).toList());
    }

    private static Map<String, BigDecimal> sortDesc(Map<String, BigDecimal> m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ------------------------------------------------------------ expired rule

    public enum IssueKind {
        SHIPMENT, INTERNAL_USE, MOVE_TO_NORMAL, MOVE_TO_HOLD
    }

    /**
     * A document line taking stock out: what kind, the lot's expiry, for internal use whether the reason is a
     * write-off, and whether the product's expiry is tracked but no lot was chosen (iDempiere would then pick
     * lots itself after this check, possibly expired ones).
     */
    public record Issue(int lineNo, String product, String lot, LocalDate expiry, IssueKind kind, boolean writeOff,
                        boolean lotMissing) {
    }

    /** Expired lots may be written off or moved to quarantine/rejected locations, nothing else. */
    public static ControlResult checkExpired(String label, List<Issue> lines, LocalDate date) {
        List<String> reasons = new ArrayList<>();
        for (Issue l : lines) {
            if (l.lotMissing() && l.kind() != IssueKind.MOVE_TO_HOLD) {
                reasons.add(label + " line " + l.lineNo() + ": " + l.product() + " has expiry dates; choose the lot (first to expire first)");
                continue;
            }
            if (l.expiry() == null || !l.expiry().isBefore(date)) {
                continue;
            }
            String what = label + " line " + l.lineNo() + ": " + l.product() + (l.lot() == null || l.lot().isBlank() ? "" : " lot " + l.lot())
                    + " expired on " + l.expiry();
            switch (l.kind()) {
                case SHIPMENT -> reasons.add(what + "; it cannot be supplied");
                case INTERNAL_USE -> {
                    if (!l.writeOff()) {
                        reasons.add(what + "; it can only be written off (use the Expired reason)");
                    }
                }
                case MOVE_TO_NORMAL -> reasons.add(what + "; move it to a quarantine or rejected location, not a normal one");
                default -> {
                    // moving expired stock into quarantine or rejected locations is how it is set aside
                }
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }
}
