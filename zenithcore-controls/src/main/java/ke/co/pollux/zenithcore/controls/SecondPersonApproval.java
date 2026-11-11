package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.util.List;

/**
 * Second-person approval: the person who prepared a document cannot complete (approve) it. A rule
 * can apply from an amount upwards; below it, one person may prepare and complete.
 */
public final class SecondPersonApproval {

    public static final String CONTROL = "Second-person approval";

    private SecondPersonApproval() {
    }

    /**
     * @param documentLabel for example "GL journal 1007"
     * @param preparedBy    user who created the document
     * @param completingBy  user trying to complete it
     * @param amount        document amount (absolute value is used)
     * @param fromAmount    rule applies from this amount; zero or null means always
     */
    public static ControlResult check(String documentLabel, int preparedBy, int completingBy, BigDecimal amount,
            BigDecimal fromAmount) {
        BigDecimal a = amount == null ? BigDecimal.ZERO : amount.abs();
        boolean applies = fromAmount == null || fromAmount.signum() <= 0 || a.compareTo(fromAmount) >= 0;
        if (!applies || preparedBy != completingBy) {
            return ControlResult.pass(CONTROL);
        }
        String why = fromAmount == null || fromAmount.signum() <= 0 ? ""
                : " (amount " + Amounts.fmt(a) + " is at or above " + Amounts.fmt(fromAmount) + ")";
        return ControlResult.block(CONTROL, List.of("you prepared " + documentLabel
                + ", so a different person must complete it" + why));
    }
}
