package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blind stock counts. Counters record what they find without seeing the book quantity. A count is
 * submitted only when every line has been counted, by people who are not the store's custodians; only a
 * submitted count can be posted. The variance analysis compares counted with book quantities at cost
 * and groups differences by cause (the adjustment reason).
 */
public final class BlindCount {

    public static final String CONTROL = "Blind count";

    /** One line on the count sheet; counted is null until someone enters a count. */
    public record CountLine(int lineNo, String item, BigDecimal counted, int countedBy) {
    }

    /** A counted line for variance analysis. */
    public record VarianceLine(String store, String item, BigDecimal book, BigDecimal counted, BigDecimal unitCost,
                               String cause) {
        public BigDecimal difference() {
            return counted.subtract(book);
        }

        public BigDecimal value() {
            return difference().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** Totals by cause (or store): count of lines with a difference, gains, losses and net value. */
    public record Totals(int lines, BigDecimal gains, BigDecimal losses) {
        public BigDecimal net() {
            return gains.add(losses);
        }
    }

    private BlindCount() {
    }

    /** Submitting a count sheet: all lines counted; none by a custodian of the store when counts must be independent. */
    public static ControlResult submit(String documentLabel, StoresControl.Store store, List<CountLine> lines,
            boolean independent) {
        List<String> reasons = new ArrayList<>();
        if (lines.isEmpty()) {
            reasons.add(documentLabel + " has no lines");
        }
        List<String> uncounted = new ArrayList<>();
        List<String> byCustodian = new ArrayList<>();
        for (CountLine l : lines) {
            if (l.counted() == null) {
                uncounted.add(String.valueOf(l.lineNo()));
            } else if (independent && store.isCustodian(l.countedBy())) {
                byCustodian.add(String.valueOf(l.lineNo()));
            }
        }
        if (!uncounted.isEmpty()) {
            reasons.add("not counted yet: line" + (uncounted.size() > 1 ? "s " : " ") + String.join(", ", uncounted)
                    + " (enter 0 for an item not found)");
        }
        if (!byCustodian.isEmpty()) {
            reasons.add("counted by a custodian of " + store.name() + ": line" + (byCustodian.size() > 1 ? "s " : " ")
                    + String.join(", ", byCustodian) + "; a different person must count them");
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /** Posting a count: it must have been submitted. */
    public static ControlResult complete(String documentLabel, boolean submitted) {
        return submitted ? ControlResult.pass(CONTROL)
                : ControlResult.block(CONTROL, List.of(documentLabel + " has not been submitted by the counters"));
    }

    /** Totals of differences, grouped by the given key (cause or store); lines without a difference are left out. */
    public static Map<String, Totals> totals(List<VarianceLine> lines, boolean byCause) {
        Map<String, Totals> m = new LinkedHashMap<>();
        for (VarianceLine l : lines) {
            if (l.difference().signum() == 0) {
                continue;
            }
            String key = byCause ? (l.cause() == null || l.cause().isBlank() ? "No reason given" : l.cause()) : l.store();
            BigDecimal v = l.value();
            Totals t = m.getOrDefault(key, new Totals(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));
            m.put(key, new Totals(t.lines() + 1, v.signum() > 0 ? t.gains().add(v) : t.gains(),
                    v.signum() < 0 ? t.losses().add(v) : t.losses()));
        }
        return m;
    }
}
