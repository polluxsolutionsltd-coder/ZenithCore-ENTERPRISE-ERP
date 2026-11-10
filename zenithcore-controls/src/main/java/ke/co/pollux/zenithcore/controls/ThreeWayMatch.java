package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Three-way match for supplier invoices: every invoice line must come from an LPO line, the quantity
 * invoiced (with earlier invoices on the same LPO line) must not exceed the quantity received on goods
 * received notes, and the invoice price must not exceed the LPO price by more than the tolerance.
 * Service lines (no stock received) need the LPO line and the price check only.
 */
public final class ThreeWayMatch {

    public static final String CONTROL = "Three-way match";

    /**
     * One invoice line with its LPO and goods-received figures.
     *
     * @param orderLine          true if the line references an LPO line
     * @param stocked            true if the item is received into stock (needs a goods received note)
     * @param received           quantity received on completed goods received notes for the LPO line
     * @param invoicedBefore     quantity on other completed invoices for the LPO line
     */
    public record Line(int lineNo, String item, boolean orderLine, boolean stocked, BigDecimal invoiced,
                       BigDecimal received, BigDecimal invoicedBefore, BigDecimal invoicePrice, BigDecimal orderPrice) {
    }

    private ThreeWayMatch() {
    }

    /**
     * @param priceTolerancePct allowed price excess over the LPO price, in percent (0 = none)
     */
    public static ControlResult check(List<Line> lines, BigDecimal priceTolerancePct) {
        List<String> reasons = new ArrayList<>();
        BigDecimal tol = priceTolerancePct == null ? BigDecimal.ZERO : priceTolerancePct;
        for (Line l : lines) {
            String at = "line " + l.lineNo() + " (" + l.item() + ")";
            if (!l.orderLine()) {
                reasons.add(at + " is not on an LPO");
                continue;
            }
            if (l.stocked()) {
                BigDecimal total = l.invoicedBefore().add(l.invoiced());
                if (total.compareTo(l.received()) > 0) {
                    reasons.add(at + ": " + qty(total) + " invoiced" + (l.invoicedBefore().signum() > 0
                            ? " (including " + qty(l.invoicedBefore()) + " on earlier invoices)" : "")
                            + " but only " + qty(l.received()) + " received");
                }
            }
            BigDecimal limit = l.orderPrice().multiply(BigDecimal.ONE.add(tol.movePointLeft(2)))
                    .setScale(2, RoundingMode.HALF_UP);
            if (l.invoicePrice().compareTo(limit) > 0) {
                reasons.add(at + ": invoice price " + Amounts.fmt(l.invoicePrice()) + " is above the LPO price "
                        + Amounts.fmt(l.orderPrice()) + (tol.signum() > 0 ? " plus " + tol.stripTrailingZeros().toPlainString() + "%" : ""));
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }
}
