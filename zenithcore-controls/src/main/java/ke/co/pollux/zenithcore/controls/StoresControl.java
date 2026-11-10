package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stores controls:
 * <ul>
 *   <li>Named custodians: only a store's custodian or deputy may receive into it, issue from it, move stock
 *       out of it, or prepare its stock counts and adjustments.</li>
 *   <li>Reason-coded adjustments: every count difference and every adjustment line needs a reason.</li>
 *   <li>Write-off approval: a write-off (loss, damage, expiry, theft, obsolescence) from a set value needs
 *       approval by someone who is neither the preparer nor the store's custodian or deputy.</li>
 * </ul>
 */
public final class StoresControl {

    public static final String CONTROL = "Stores control";

    /** A store and its custodians (user ids; 0 = not set). */
    public record Store(String name, int custodian, int deputy) {
        boolean isCustodian(int user) {
            return user > 0 && (user == custodian || user == deputy);
        }

        boolean hasCustodian() {
            return custodian > 0 || deputy > 0;
        }
    }

    /**
     * One adjustment line.
     *
     * @param change     quantity added (positive) or removed (negative) by this line
     * @param unitCost   current cost of one unit
     * @param reasonCode reason code, null if none
     * @param writeOff   true if the reason is a write-off reason
     */
    public record AdjustmentLine(int lineNo, String item, BigDecimal change, BigDecimal unitCost, String reasonCode,
                                 boolean writeOff) {
    }

    private StoresControl() {
    }

    /** Receiving, issuing or moving stock: the user must be a custodian of the store. */
    public static ControlResult custodian(Store store, int user, String action) {
        if (!store.hasCustodian()) {
            return ControlResult.block(CONTROL, List.of(store.name() + " has no custodian; name one before stock can be "
                    + action));
        }
        if (!store.isCustodian(user)) {
            return ControlResult.block(CONTROL, List.of("only the custodian of " + store.name() + " or their deputy can "
                    + action + " this store"));
        }
        return ControlResult.pass(CONTROL);
    }

    /** Value written off: the cost of quantities removed on lines with a write-off reason. */
    public static BigDecimal writeOffValue(List<AdjustmentLine> lines) {
        BigDecimal v = BigDecimal.ZERO;
        for (AdjustmentLine l : lines) {
            if (l.writeOff() && l.change().signum() < 0) {
                v = v.add(l.change().negate().multiply(l.unitCost()));
            }
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * A stock count or adjustment.
     *
     * @param preparer         user who prepared the document
     * @param approver         user completing it
     * @param writeOffFrom     write-offs at or above this value need independent approval; zero = all write-offs
     */
    public static ControlResult adjustment(String documentLabel, Store store, List<AdjustmentLine> lines, int preparer,
            int approver, BigDecimal writeOffFrom) {
        List<String> reasons = new ArrayList<>();
        if (!store.hasCustodian()) {
            reasons.add(store.name() + " has no custodian; name one before stock can be adjusted");
        } else if (!store.isCustodian(preparer)) {
            reasons.add(documentLabel + " was not prepared by the custodian of " + store.name() + " or their deputy");
        }
        for (AdjustmentLine l : lines) {
            if (l.change().signum() != 0 && (l.reasonCode() == null || l.reasonCode().isBlank())) {
                reasons.add("line " + l.lineNo() + " (" + l.item() + ") changes stock by "
                        + l.change().stripTrailingZeros().toPlainString() + " without a reason");
            }
        }
        BigDecimal value = writeOffValue(lines);
        BigDecimal from = writeOffFrom == null ? BigDecimal.ZERO : writeOffFrom;
        if (value.signum() > 0 && value.compareTo(from) >= 0 && (approver == preparer || store.isCustodian(approver))) {
            reasons.add(documentLabel + " writes off " + Amounts.fmt(value)
                    + (from.signum() > 0 ? " (approval needed from " + Amounts.fmt(from) + ")" : "")
                    + "; it must be approved by someone other than the preparer and the custodians of " + store.name());
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }
}
