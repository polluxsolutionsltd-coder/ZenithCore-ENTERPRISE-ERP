package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Goods received inspection with quarantine. Stock items are received into a quarantine location and
 * cannot be issued, moved or used until inspected. An inspector who neither received the goods nor keeps
 * the store accepts or rejects every unit, giving a reason for each rejection. Accepted goods are then put
 * away; rejected goods go to a rejected location to be returned to the supplier.
 */
public final class GoodsInspection {

    public static final String CONTROL = "Goods inspection";

    /** How a store location is used. Codes are those stored in the database. */
    public enum LocationUse {
        NORMAL("N"), QUARANTINE("Q"), REJECTED("R");

        public final String code;

        LocationUse(String code) {
            this.code = code;
        }

        public static LocationUse of(String code) {
            return "Q".equals(code) ? QUARANTINE : "R".equals(code) ? REJECTED : NORMAL;
        }
    }

    /** A receipt or stock line and the location it uses. */
    public record StockLine(int lineNo, String item, String location, LocationUse use) {
    }

    /** One inspected line. */
    public record InspectedLine(int lineNo, String item, BigDecimal received, BigDecimal accepted, BigDecimal rejected,
                                String rejectReason) {
    }

    private GoodsInspection() {
    }

    /** Receiving stock items: every line must go to a quarantine location until inspected. */
    public static ControlResult receive(List<StockLine> lines) {
        List<String> reasons = new ArrayList<>();
        for (StockLine l : lines) {
            if (l.use() != LocationUse.QUARANTINE) {
                reasons.add("line " + l.lineNo() + " (" + l.item() + ") must be received into a quarantine location, not "
                        + l.location());
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /** Issuing, moving or using stock: not from quarantine (uninspected) or rejected locations. */
    public static ControlResult use(List<StockLine> lines, String action) {
        List<String> reasons = new ArrayList<>();
        for (StockLine l : lines) {
            if (l.use() == LocationUse.QUARANTINE) {
                reasons.add("line " + l.lineNo() + " (" + l.item() + "): stock in " + l.location()
                        + " is not yet inspected and cannot be " + action);
            } else if (l.use() == LocationUse.REJECTED) {
                reasons.add("line " + l.lineNo() + " (" + l.item() + "): stock in " + l.location()
                        + " was rejected and cannot be " + action + "; return it to the supplier");
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /**
     * Completing an inspection.
     *
     * @param receivedBy user who recorded the goods received note
     */
    public static ControlResult complete(String label, List<InspectedLine> lines, int inspector, int receivedBy,
            StoresControl.Store store) {
        List<String> reasons = new ArrayList<>();
        if (lines.isEmpty()) {
            reasons.add(label + " has no lines");
        }
        if (inspector == receivedBy) {
            reasons.add("you recorded the goods received note, so a different person must inspect the goods");
        } else if (store.isCustodian(inspector)) {
            reasons.add("custodians of " + store.name() + " cannot inspect goods received into it");
        }
        for (InspectedLine l : lines) {
            String at = "line " + l.lineNo() + " (" + l.item() + ")";
            if (l.accepted().signum() < 0 || l.rejected().signum() < 0) {
                reasons.add(at + ": quantities cannot be negative");
            } else if (l.accepted().add(l.rejected()).compareTo(l.received()) != 0) {
                reasons.add(at + ": accepted " + q(l.accepted()) + " + rejected " + q(l.rejected()) + " must equal received "
                        + q(l.received()));
            }
            if (l.rejected().signum() > 0 && (l.rejectReason() == null || l.rejectReason().isBlank())) {
                reasons.add(at + ": give the reason for rejecting " + q(l.rejected()));
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    private static String q(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
