package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Full breakdown of one employee's monthly pay. Every statutory figure is kept so that the
 * payslip, P10, NSSF, SHIF, Housing Levy, NITA and HELB files and the GL journal can be produced
 * from it without recalculating.
 */
public record PayResult(
        String employeeId,
        LocalDate payDate,
        StatutoryRateSet rates,
        BigDecimal grossPay,
        BigDecimal nonCashBenefits,
        BigDecimal taxableNonCashBenefits,
        BigDecimal nssfTier1,
        BigDecimal nssfTier2,
        BigDecimal nssf,
        BigDecimal shif,
        BigDecimal housingLevy,
        BigDecimal pensionContribution,
        BigDecimal allowablePension,
        BigDecimal allowablePostRetirementMedical,
        BigDecimal allowableMortgageInterest,
        BigDecimal taxablePay,
        List<BandTax> bandTaxes,
        BigDecimal taxCharged,
        BigDecimal personalRelief,
        BigDecimal insurancePremium,
        BigDecimal insuranceRelief,
        BigDecimal paye,
        BigDecimal statutoryDeductions,
        BigDecimal deductionLimit,
        List<AppliedDeduction> otherDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        BigDecimal employerNssf,
        BigDecimal employerHousingLevy,
        BigDecimal nita,
        BigDecimal employerCost) {

    /** Tax charged on one PAYE band. */
    public record BandTax(BigDecimal amount, BigDecimal rate, BigDecimal tax) {
    }

    /**
     * A deduction after statutory deductions, in priority order.
     *
     * @param name      deduction name
     * @param requested amount due this month
     * @param applied   amount actually deducted after the two-thirds rule
     */
    public record AppliedDeduction(String name, BigDecimal requested, BigDecimal applied) {
        public BigDecimal shortfall() {
            return Money.round(requested.subtract(applied));
        }
    }

    public PayResult {
        bandTaxes = List.copyOf(bandTaxes);
        otherDeductions = List.copyOf(otherDeductions);
    }

    /** Amount applied for a named deduction, or zero. */
    public BigDecimal applied(String name) {
        return find(name).map(AppliedDeduction::applied).orElse(Money.ZERO);
    }

    public Optional<AppliedDeduction> find(String name) {
        return otherDeductions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** Deductions that could not be taken in full because of the two-thirds rule; carry them forward. */
    public List<AppliedDeduction> carriedForward() {
        return otherDeductions.stream().filter(d -> d.shortfall().signum() > 0).toList();
    }

    /** True if statutory deductions alone exceed the two-thirds limit (needs HR review). */
    public boolean statutoryExceedsLimit() {
        return statutoryDeductions.compareTo(deductionLimit) > 0;
    }

    /** Step-by-step explanation for payroll staff, employees and auditors. */
    public List<String> trace() {
        List<String> t = new ArrayList<>();
        t.add("Gross cash pay: " + Money.fmt(grossPay));
        if (nonCashBenefits.signum() > 0) {
            t.add(taxableNonCashBenefits.signum() > 0
                    ? "Non-cash benefits " + Money.fmt(nonCashBenefits) + " exceed " + Money.fmt(rates.nonCashBenefitThreshold())
                            + ": taxable in full, + " + Money.fmt(taxableNonCashBenefits)
                    : "Non-cash benefits " + Money.fmt(nonCashBenefits) + " are within "
                            + Money.fmt(rates.nonCashBenefitThreshold()) + ": not taxable");
        }
        t.add("NSSF: Tier I " + Money.fmt(nssfTier1) + " + Tier II " + Money.fmt(nssfTier2) + " = " + Money.fmt(nssf));
        t.add("Less allowable pension (NSSF " + Money.fmt(nssf)
                + (pensionContribution.signum() > 0 ? " + pension " + Money.fmt(pensionContribution) : "")
                + ", cap " + Money.fmt(rates.pensionDeductionCap()) + "): - " + Money.fmt(allowablePension));
        t.add("Less SHIF (" + pct(rates.shifRate()) + ", minimum " + Money.fmt(rates.shifMinimum()) + "): - " + Money.fmt(shif));
        t.add("Less Affordable Housing Levy (" + pct(rates.housingLevyRate()) + "): - " + Money.fmt(housingLevy));
        if (allowablePostRetirementMedical.signum() > 0) {
            t.add("Less post-retirement medical fund (cap " + Money.fmt(rates.postRetirementMedicalCap()) + "): - "
                    + Money.fmt(allowablePostRetirementMedical));
        }
        if (allowableMortgageInterest.signum() > 0) {
            t.add("Less mortgage interest (cap " + Money.fmt(rates.mortgageInterestCap()) + "): - "
                    + Money.fmt(allowableMortgageInterest));
        }
        t.add("Taxable pay: " + Money.fmt(taxablePay));
        for (BandTax b : bandTaxes) {
            t.add("  " + Money.fmt(b.amount()) + " at " + pct(b.rate()) + " = " + Money.fmt(b.tax()));
        }
        t.add("Tax charged: " + Money.fmt(taxCharged));
        t.add("Less personal relief: - " + Money.fmt(personalRelief));
        if (insuranceRelief.signum() > 0) {
            t.add("Less insurance relief (" + pct(rates.insuranceReliefRate()) + " of " + Money.fmt(insurancePremium)
                    + ", cap " + Money.fmt(rates.insuranceReliefCap()) + "): - " + Money.fmt(insuranceRelief));
        }
        t.add("PAYE: " + Money.fmt(paye));
        t.add("Two-thirds rule: deductions may not exceed " + Money.fmt(deductionLimit)
                + "; deducted " + Money.fmt(totalDeductions));
        for (AppliedDeduction d : carriedForward()) {
            t.add("  " + d.name() + ": " + Money.fmt(d.shortfall()) + " carried forward");
        }
        if (statutoryExceedsLimit()) {
            t.add("  Statutory deductions alone exceed the limit: refer to HR");
        }
        t.add("Net pay: " + Money.fmt(netPay));
        return t;
    }

    private static String pct(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }
}
