package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One monthly PAYE band.
 *
 * @param width monthly width of the band in KES; {@code null} for the top, unbounded band
 * @param rate  tax rate as a decimal, for example 0.25 for 25%
 */
public record TaxBand(BigDecimal width, BigDecimal rate) {

    public TaxBand {
        Objects.requireNonNull(rate, "rate");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Tax rate must be between 0 and 1: " + rate);
        }
        if (width != null && width.signum() <= 0) {
            throw new IllegalArgumentException("Band width must be positive: " + width);
        }
    }

    public static TaxBand of(String width, String rate) {
        return new TaxBand(width == null ? null : new BigDecimal(width), new BigDecimal(rate));
    }

    public boolean isUnbounded() {
        return width == null;
    }
}
