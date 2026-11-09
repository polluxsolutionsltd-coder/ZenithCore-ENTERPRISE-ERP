package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.polluxsolutions.zenithcore.payroll.ke.Money.max;
import static com.polluxsolutions.zenithcore.payroll.ke.Money.min;
import static com.polluxsolutions.zenithcore.payroll.ke.Money.round;

/**
 * Calculates Kenyan monthly pay for one employee using the statutory rate set in force on the pay date.
 *
 * <p>Order of calculation:
 * <ol>
 *   <li>Gross cash pay; non-cash benefits added to taxable income only if they exceed the threshold.</li>
 *   <li>NSSF Tier I and Tier II on gross cash pay.</li>
 *   <li>SHIF (with minimum) and Affordable Housing Levy on gross cash pay.</li>
 *   <li>Taxable pay = gross + taxable non-cash benefits - allowable pension (NSSF + registered pension, capped)
 *       - SHIF - Housing Levy - post-retirement medical (capped) - mortgage interest (capped).</li>
 *   <li>Tax by band, less personal relief and insurance relief (capped); PAYE cannot be negative.</li>
 *   <li>Other deductions in priority order (HELB, pension, insurance premium, staff loan, SACCO, union dues),
 *       each limited so that total deductions do not exceed two-thirds of gross cash pay; any shortfall is
 *       reported for carry-forward.</li>
 * </ol>
 * Each statutory component is rounded to the cent, half-up. Tax is summed across bands before rounding.
 */
public final class KenyaPayrollEngine {

    /** Deduction names, in the order they are applied after statutory deductions. */
    public static final String HELB = "HELB";
    public static final String PENSION = "Pension";
    public static final String INSURANCE = "Insurance premium";
    public static final String STAFF_LOAN = "Staff loan";
    public static final String SACCO = "SACCO";
    public static final String UNION = "Union dues";

    private final StatutoryRateTable rateTable;

    public KenyaPayrollEngine(StatutoryRateTable rateTable) {
        this.rateTable = Objects.requireNonNull(rateTable, "rateTable");
    }

    public static KenyaPayrollEngine withKenyaDefaults() {
        return new KenyaPayrollEngine(StatutoryRateTable.kenyaDefaults());
    }

    public PayResult calculate(EmployeePayInput in, LocalDate payDate) {
        StatutoryRateSet r = rateTable.forDate(payDate);
        BigDecimal gross = in.grossCashPay();

        BigDecimal nonCash = in.nonCashBenefits();
        BigDecimal taxableNonCash = nonCash.compareTo(r.nonCashBenefitThreshold()) > 0 ? nonCash : Money.ZERO;

        BigDecimal tier1 = round(min(gross, r.nssfLowerEarningsLimit()).multiply(r.nssfRate()));
        BigDecimal tier2Base = max(Money.ZERO, min(gross, r.nssfUpperEarningsLimit()).subtract(r.nssfLowerEarningsLimit()));
        BigDecimal tier2 = round(tier2Base.multiply(r.nssfRate()));
        BigDecimal nssf = round(tier1.add(tier2));

        BigDecimal shif = max(round(gross.multiply(r.shifRate())), r.shifMinimum());
        BigDecimal housingLevy = round(gross.multiply(r.housingLevyRate()));

        BigDecimal allowablePension = min(nssf.add(in.pensionContribution()), r.pensionDeductionCap());
        BigDecimal allowablePrmf = min(in.postRetirementMedical(), r.postRetirementMedicalCap());
        BigDecimal allowableMortgage = min(in.mortgageInterest(), r.mortgageInterestCap());

        BigDecimal taxable = round(max(Money.ZERO, gross.add(taxableNonCash)
                .subtract(allowablePension).subtract(shif).subtract(housingLevy)
                .subtract(allowablePrmf).subtract(allowableMortgage)));

        List<PayResult.BandTax> bands = new ArrayList<>();
        BigDecimal remaining = taxable;
        BigDecimal exactTax = BigDecimal.ZERO;
        for (TaxBand band : r.payeBands()) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal portion = band.isUnbounded() ? remaining : min(remaining, band.width());
            BigDecimal bandTax = portion.multiply(band.rate());
            bands.add(new PayResult.BandTax(round(portion), band.rate(), round(bandTax)));
            exactTax = exactTax.add(bandTax);
            remaining = remaining.subtract(portion);
        }
        BigDecimal taxCharged = round(exactTax);
        BigDecimal insuranceRelief = min(round(in.insurancePremium().multiply(r.insuranceReliefRate())), r.insuranceReliefCap());
        BigDecimal paye = max(Money.ZERO, taxCharged.subtract(r.personalRelief()).subtract(insuranceRelief));

        BigDecimal statutory = round(nssf.add(shif).add(housingLevy).add(paye));
        BigDecimal limit = gross.multiply(BigDecimal.valueOf(r.maxDeductionNumerator()))
                .divide(BigDecimal.valueOf(r.maxDeductionDenominator()), 2, RoundingMode.HALF_UP);

        Map<String, BigDecimal> requested = new java.util.LinkedHashMap<>();
        requested.put(HELB, in.helb());
        requested.put(PENSION, in.pensionContribution());
        requested.put(INSURANCE, in.insurancePremium());
        requested.put(STAFF_LOAN, in.staffLoan());
        requested.put(SACCO, in.sacco());
        requested.put(UNION, in.unionDues());

        List<PayResult.AppliedDeduction> other = new ArrayList<>();
        BigDecimal used = statutory;
        for (Map.Entry<String, BigDecimal> e : requested.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            BigDecimal room = max(Money.ZERO, limit.subtract(used));
            BigDecimal applied = min(e.getValue(), room);
            used = used.add(applied);
            other.add(new PayResult.AppliedDeduction(e.getKey(), e.getValue(), applied));
        }
        BigDecimal totalDeductions = round(used);
        BigDecimal net = round(gross.subtract(totalDeductions));

        BigDecimal employerNssf = nssf;
        BigDecimal employerHousingLevy = housingLevy;
        BigDecimal nita = r.nitaPerEmployee();
        BigDecimal employerCost = round(gross.add(employerNssf).add(employerHousingLevy).add(nita));

        return new PayResult(in.employeeId(), payDate, r, gross, nonCash, taxableNonCash, tier1, tier2, nssf, shif,
                housingLevy, in.pensionContribution(), allowablePension, allowablePrmf, allowableMortgage, taxable,
                bands, taxCharged, r.personalRelief(), in.insurancePremium(), insuranceRelief, paye, statutory, limit,
                other, totalDeductions, net, employerNssf, employerHousingLevy, nita, employerCost);
    }
}
