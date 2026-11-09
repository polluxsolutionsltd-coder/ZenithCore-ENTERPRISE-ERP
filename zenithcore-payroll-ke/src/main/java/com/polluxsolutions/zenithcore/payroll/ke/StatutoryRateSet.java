package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The complete set of Kenyan statutory payroll parameters in force for a date range.
 * Values are monthly unless stated. A change in law is a new rate set, not a code change.
 *
 * @param effectiveFrom              first pay date this set applies to (inclusive)
 * @param effectiveTo                last pay date this set applies to (inclusive); {@code null} if open-ended
 * @param description                what changed and the legal basis, for audit
 * @param payeBands                  monthly PAYE bands, lowest first; the last band must be unbounded
 * @param personalRelief             monthly personal relief
 * @param insuranceReliefRate        insurance relief as a share of qualifying premiums
 * @param insuranceReliefCap         monthly cap on insurance relief
 * @param nssfRate                   NSSF rate per side (employee, and employer matching)
 * @param nssfLowerEarningsLimit     Tier I ceiling (lower earnings limit)
 * @param nssfUpperEarningsLimit     Tier II ceiling (upper earnings limit)
 * @param shifRate                   SHIF rate on gross salary
 * @param shifMinimum                minimum monthly SHIF contribution
 * @param housingLevyRate            Affordable Housing Levy rate per side
 * @param nitaPerEmployee            NITA levy per employee per month (employer only)
 * @param pensionDeductionCap        cap on allowable pension (NSSF plus registered scheme) for PAYE
 * @param postRetirementMedicalCap   cap on allowable post-retirement medical fund contributions
 * @param mortgageInterestCap        cap on allowable mortgage interest
 * @param nonCashBenefitThreshold    non-cash benefits at or below this total are not taxable; above it, the whole amount is
 * @param maxDeductionNumerator      numerator of the maximum share of wages that may be deducted
 * @param maxDeductionDenominator    denominator of that share (2 and 3 for the two-thirds rule)
 */
public record StatutoryRateSet(
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String description,
        List<TaxBand> payeBands,
        BigDecimal personalRelief,
        BigDecimal insuranceReliefRate,
        BigDecimal insuranceReliefCap,
        BigDecimal nssfRate,
        BigDecimal nssfLowerEarningsLimit,
        BigDecimal nssfUpperEarningsLimit,
        BigDecimal shifRate,
        BigDecimal shifMinimum,
        BigDecimal housingLevyRate,
        BigDecimal nitaPerEmployee,
        BigDecimal pensionDeductionCap,
        BigDecimal postRetirementMedicalCap,
        BigDecimal mortgageInterestCap,
        BigDecimal nonCashBenefitThreshold,
        int maxDeductionNumerator,
        int maxDeductionDenominator) {

    public StatutoryRateSet {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo is before effectiveFrom");
        }
        payeBands = List.copyOf(payeBands);
        if (payeBands.isEmpty() || !payeBands.get(payeBands.size() - 1).isUnbounded()) {
            throw new IllegalArgumentException("The last PAYE band must be unbounded");
        }
        for (int i = 0; i < payeBands.size() - 1; i++) {
            if (payeBands.get(i).isUnbounded()) {
                throw new IllegalArgumentException("Only the last PAYE band may be unbounded");
            }
        }
        if (nssfUpperEarningsLimit.compareTo(nssfLowerEarningsLimit) < 0) {
            throw new IllegalArgumentException("NSSF upper earnings limit is below the lower limit");
        }
        if (maxDeductionNumerator <= 0 || maxDeductionDenominator <= 0 || maxDeductionNumerator > maxDeductionDenominator) {
            throw new IllegalArgumentException("Invalid maximum deduction share");
        }
    }

    public boolean covers(LocalDate payDate) {
        return !payDate.isBefore(effectiveFrom) && (effectiveTo == null || !payDate.isAfter(effectiveTo));
    }

    public boolean overlaps(StatutoryRateSet other) {
        LocalDate thisEnd = effectiveTo == null ? LocalDate.MAX : effectiveTo;
        LocalDate otherEnd = other.effectiveTo == null ? LocalDate.MAX : other.effectiveTo;
        return !effectiveFrom.isAfter(otherEnd) && !other.effectiveFrom.isAfter(thisEnd);
    }
}
