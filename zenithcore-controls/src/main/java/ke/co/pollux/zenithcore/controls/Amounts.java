package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Money formatting for control messages: two decimals, thousands separators. */
final class Amounts {

    private Amounts() {
    }

    static String fmt(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }
}
