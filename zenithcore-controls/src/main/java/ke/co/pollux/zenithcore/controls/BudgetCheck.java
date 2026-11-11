package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget check: a requisition, LPO, supplier invoice or payment voucher may only be completed if, for
 * every cost centre and account it charges, the approved budget for the financial year (plus any
 * tolerance) still covers what has been spent, what is committed on open requisitions and LPOs, and
 * this document.
 */
public final class BudgetCheck {

    public static final String CONTROL = "Budget check";

    /** A budget line: cost centre and account, as shown to users (for example "WS Workshop", "61200 Repairs"). */
    public record Key(String costCentre, String account) {
        @Override
        public String toString() {
            return costCentre + " / " + account;
        }
    }

    /** Where a budget line stands before this document. */
    public record Position(BigDecimal budget, BigDecimal spent, BigDecimal committed) {
        public BigDecimal available() {
            return budget.subtract(spent).subtract(committed);
        }
    }

    /** What this document would add; a null cost centre means the line has none. */
    public record Demand(String line, String costCentre, String account, BigDecimal amount) {
        Key key() {
            return new Key(costCentre, account);
        }
    }

    private BudgetCheck() {
    }

    /**
     * @param positions    budget positions by key; a key with no position has no budget
     * @param tolerancePct overrun allowed, as a percentage of the budget (0 = none)
     */
    public static ControlResult check(String documentLabel, List<Demand> demands, Map<Key, Position> positions,
            BigDecimal tolerancePct) {
        List<String> reasons = new ArrayList<>();
        Map<Key, BigDecimal> totals = new LinkedHashMap<>();
        for (Demand d : demands) {
            if (d.costCentre() == null || d.costCentre().isBlank()) {
                reasons.add(d.line() + " has no cost centre");
                continue;
            }
            totals.merge(d.key(), d.amount(), BigDecimal::add);
        }
        BigDecimal tol = tolerancePct == null ? BigDecimal.ZERO : tolerancePct;
        for (Map.Entry<Key, BigDecimal> t : totals.entrySet()) {
            Position p = positions.get(t.getKey());
            BigDecimal amount = t.getValue().setScale(2, RoundingMode.HALF_UP);
            if (p == null || p.budget().signum() == 0) {
                reasons.add("no approved budget for " + t.getKey() + " (" + documentLabel + " needs "
                        + Amounts.fmt(amount) + ")");
                continue;
            }
            BigDecimal limit = p.budget().multiply(BigDecimal.ONE.add(tol.movePointLeft(2)))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal after = p.spent().add(p.committed()).add(amount);
            if (after.compareTo(limit) > 0) {
                reasons.add(t.getKey() + ": budget " + Amounts.fmt(p.budget())
                        + (tol.signum() > 0 ? " (+" + tol.stripTrailingZeros().toPlainString() + "%)" : "")
                        + ", spent " + Amounts.fmt(p.spent()) + ", committed " + Amounts.fmt(p.committed())
                        + ", available " + Amounts.fmt(limit.subtract(p.spent()).subtract(p.committed()))
                        + "; " + documentLabel + " needs " + Amounts.fmt(amount) + ", over by "
                        + Amounts.fmt(after.subtract(limit)));
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }
}
