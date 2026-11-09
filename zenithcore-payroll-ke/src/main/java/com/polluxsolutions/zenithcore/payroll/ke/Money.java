package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Money helpers. All amounts are KES with scale 2, rounded half-up. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() {
    }

    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    public static BigDecimal of(long value) {
        return round(BigDecimal.valueOf(value));
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Null-safe: null becomes zero; the result always has scale 2. */
    public static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : round(value);
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** Formats as 1,234.56. */
    public static String fmt(BigDecimal value) {
        return String.format(Locale.ROOT, "%,.2f", value);
    }
}
