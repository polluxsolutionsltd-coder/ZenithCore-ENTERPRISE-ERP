package com.polluxsolutions.zenithcore.payroll.ke;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worked payslips for November 2026 (NSSF Year 4). Expected values were calculated by hand;
 * a Kenyan payroll specialist must confirm them before go-live.
 */
class KenyaPayrollEngineTest {

    private static final LocalDate NOV_2026 = LocalDate.of(2026, 11, 28);
    private final KenyaPayrollEngine engine = KenyaPayrollEngine.withKenyaDefaults();

    private static void money(String expected, BigDecimal actual, String what) {
        assertEquals(new BigDecimal(expected).setScale(2), actual, what);
    }

    @Test
    void technicianInThe30PercentBand() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E001")
                .basic("62000").house("12000").commuter("4000").sacco("3000").unionDues("500").build(), NOV_2026);
        money("78000", r.grossPay(), "gross");
        money("540", r.nssfTier1(), "NSSF Tier I");
        money("4140", r.nssfTier2(), "NSSF Tier II");
        money("4680", r.nssf(), "NSSF");
        money("2145", r.shif(), "SHIF");
        money("1170", r.housingLevy(), "Housing Levy");
        money("70005", r.taxablePay(), "taxable pay");
        money("15784.85", r.taxCharged(), "tax charged");
        money("13384.85", r.paye(), "PAYE");
        money("53120.15", r.netPay(), "net pay");
        money("83900", r.employerCost(), "employer cost");
        assertTrue(r.carriedForward().isEmpty());
    }

    @Test
    void halfCentRoundsUpOnTaxCharged() {
        // Taxable 60,581.25: tax 2,400 + 2,083.25 + 8,474.475 = 12,957.725 -> 12,957.73
        PayResult r = engine.calculate(EmployeePayInput.builder("E002")
                .basic("48000").house("9000").commuter("4000").overtime("6500").sacco("2000").build(), NOV_2026);
        money("67500", r.grossPay(), "gross");
        money("60581.25", r.taxablePay(), "taxable pay");
        money("12957.73", r.taxCharged(), "tax charged");
        money("10557.73", r.paye(), "PAYE");
        money("48023.52", r.netPay(), "net pay");
    }

    @Test
    void twoThirdsRuleCapsSaccoAndCarriesForward() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E004")
                .basic("18000").staffLoan("6000").sacco("5000").build(), NOV_2026);
        money("1080", r.nssf(), "NSSF");
        money("495", r.shif(), "SHIF");
        money("270", r.housingLevy(), "Housing Levy");
        money("16155", r.taxablePay(), "taxable pay");
        money("0", r.paye(), "PAYE floors at zero");
        money("12000", r.deductionLimit(), "two-thirds limit");
        money("6000", r.applied(KenyaPayrollEngine.STAFF_LOAN), "loan applied in full");
        money("4155", r.applied(KenyaPayrollEngine.SACCO), "SACCO reduced");
        assertEquals(1, r.carriedForward().size());
        money("845", r.carriedForward().get(0).shortfall(), "SACCO carried forward");
        money("6000", r.netPay(), "net pay is one third of gross");
        assertFalse(r.statutoryExceedsLimit());
    }

    @Test
    void pensionInsuranceReliefAndMortgageInterest() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E006")
                .basic("82000").house("15000").commuter("5000")
                .pension("10000").insurancePremium("3000").mortgageInterest("25000").build(), NOV_2026);
        money("6120", r.nssf(), "NSSF");
        money("16120", r.allowablePension(), "NSSF + pension");
        money("25000", r.allowableMortgageInterest(), "mortgage interest");
        money("56545", r.taxablePay(), "taxable pay");
        money("450", r.insuranceRelief(), "insurance relief 15%");
        money("8896.85", r.paye(), "PAYE");
        money("69648.15", r.netPay(), "net pay after pension and premium");
    }

    @Test
    void taxableCarBenefitPensionAndPostRetirementMedical() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E008")
                .basic("180000").house("30000").commuter("10000")
                .nonCashBenefits("12000").pension("20000").postRetirementMedical("5000").build(), NOV_2026);
        money("220000", r.grossPay(), "gross");
        money("6480", r.nssf(), "NSSF at Year 4 maximum");
        money("12000", r.taxableNonCashBenefits(), "car benefit above 5,000 is taxable in full");
        money("26480", r.allowablePension(), "NSSF + pension under cap");
        money("191170", r.taxablePay(), "taxable pay");
        money("49734.35", r.paye(), "PAYE");
        money("134435.65", r.netPay(), "net pay");
    }

    @Test
    void topEarnerReaches32Point5PercentBand() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E010")
                .basic("520000").house("60000").commuter("20000").nonCashBenefits("25000").build(), NOV_2026);
        money("600000", r.grossPay(), "gross");
        money("16500", r.shif(), "SHIF");
        money("9000", r.housingLevy(), "Housing Levy");
        money("593020", r.taxablePay(), "taxable pay");
        assertEquals(4, r.bandTaxes().size());
        money("30231.50", r.bandTaxes().get(3).tax(), "tax in the 32.5% band");
        money("172614.85", r.paye(), "PAYE");
        money("395405.15", r.netPay(), "net pay");
    }

    @Test
    void internPaysShifMinimumAndNoPaye() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E014").basic("10000").build(), NOV_2026);
        money("540", r.nssfTier1(), "Tier I");
        money("60", r.nssfTier2(), "Tier II");
        money("300", r.shif(), "SHIF minimum applies (2.75% would be 275)");
        money("150", r.housingLevy(), "Housing Levy");
        money("8950", r.taxablePay(), "taxable pay");
        money("0", r.paye(), "PAYE");
        money("8950", r.netPay(), "net pay");
        money("10800", r.employerCost(), "employer cost incl. NITA 50");
    }

    @Test
    void nonCashBenefitsAtThresholdAreNotTaxable() {
        PayResult at = engine.calculate(EmployeePayInput.builder("A").basic("45000").nonCashBenefits("5000").build(), NOV_2026);
        PayResult over = engine.calculate(EmployeePayInput.builder("B").basic("45000").nonCashBenefits("5000.01").build(), NOV_2026);
        money("0", at.taxableNonCashBenefits(), "exactly 5,000 not taxable");
        money("5000.01", over.taxableNonCashBenefits(), "one cent over: whole amount taxable");
        money("5000.01", over.taxablePay().subtract(at.taxablePay()), "taxable pay difference");
    }

    @Test
    void pensionAllowanceIsCappedAt30000() {
        PayResult r = engine.calculate(EmployeePayInput.builder("P").basic("300000").pension("40000").build(), NOV_2026);
        money("6480", r.nssf(), "NSSF");
        money("30000", r.allowablePension(), "cap applies");
        money("40000", r.applied(KenyaPayrollEngine.PENSION), "full pension still deducted from pay");
    }

    @Test
    void insuranceReliefIsCappedAt5000() {
        PayResult r = engine.calculate(EmployeePayInput.builder("I").basic("400000").insurancePremium("50000").build(), NOV_2026);
        money("5000", r.insuranceRelief(), "15% of 50,000 is 7,500, capped at 5,000");
    }

    @Test
    void mortgageAndPostRetirementMedicalAreCapped() {
        PayResult r = engine.calculate(EmployeePayInput.builder("M").basic("400000")
                .mortgageInterest("45000").postRetirementMedical("20000").build(), NOV_2026);
        money("30000", r.allowableMortgageInterest(), "mortgage cap");
        money("15000", r.allowablePostRetirementMedical(), "post-retirement medical cap");
    }

    @Test
    void nssfYear3LimitsApplyBeforeFebruary2026() {
        PayResult r = engine.calculate(EmployeePayInput.builder("Y3").basic("100000").build(), LocalDate.of(2025, 11, 28));
        money("480", r.nssfTier1(), "Tier I on 8,000");
        money("3840", r.nssfTier2(), "Tier II on 8,001 to 72,000");
        money("4320", r.nssf(), "Year 3 maximum");
        PayResult y4 = engine.calculate(EmployeePayInput.builder("Y4").basic("100000").build(), LocalDate.of(2026, 2, 1));
        money("6000", y4.nssf(), "Year 4: 540 + 5,460");
    }

    @Test
    void payDateWithoutRatesIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> engine.calculate(EmployeePayInput.builder("X").basic("50000").build(), LocalDate.of(2024, 6, 28)));
    }

    @Test
    void negativeAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EmployeePayInput.builder("N").basic("-1").build());
    }

    @Test
    void deductionsAndNetAlwaysAddUpToGross() {
        PayResult r = engine.calculate(EmployeePayInput.builder("S").basic("95000").house("15000").commuter("5000")
                .helb("3500").staffLoan("10000").sacco("4000").unionDues("300").build(), NOV_2026);
        BigDecimal sumOther = r.otherDeductions().stream().map(PayResult.AppliedDeduction::applied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        money(r.grossPay().toPlainString(), r.netPay().add(r.statutoryDeductions()).add(sumOther), "gross = net + deductions");
        money(r.totalDeductions().toPlainString(), r.statutoryDeductions().add(sumOther), "total deductions");
    }

    @Test
    void traceExplainsTheCalculation() {
        PayResult r = engine.calculate(EmployeePayInput.builder("E004").basic("18000").staffLoan("6000").sacco("5000").build(), NOV_2026);
        String trace = String.join("\n", r.trace());
        assertTrue(trace.contains("Taxable pay: 16,155.00"), trace);
        assertTrue(trace.contains("SACCO: 845.00 carried forward"), trace);
        assertTrue(trace.contains("Net pay: 6,000.00"), trace);
    }
}
