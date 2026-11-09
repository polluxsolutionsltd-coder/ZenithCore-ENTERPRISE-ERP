package com.polluxsolutions.zenithcore.payroll.ke;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatutoryRateTableTest {

    private final StatutoryRateTable table = StatutoryRateTable.kenyaDefaults();

    @Test
    void picksTheSetInForceOnThePayDate() {
        assertEquals(Money.of("8000"), table.forDate(LocalDate.of(2026, 1, 31)).nssfLowerEarningsLimit());
        assertEquals(Money.of("9000"), table.forDate(LocalDate.of(2026, 2, 1)).nssfLowerEarningsLimit());
        assertEquals(Money.of("108000"), table.forDate(LocalDate.of(2026, 11, 28)).nssfUpperEarningsLimit());
    }

    @Test
    void aChangeInLawIsANewRateSetNotACodeChange() {
        StatutoryRateSet current = table.forDate(LocalDate.of(2026, 11, 28));
        StatutoryRateSet closed = copy(current, current.effectiveFrom(), LocalDate.of(2026, 12, 31), current.personalRelief());
        StatutoryRateSet future = copy(current, LocalDate.of(2027, 1, 1), null, Money.of("2600"));
        StatutoryRateTable updated = new StatutoryRateTable(List.of(table.sets().get(0), closed, future));

        KenyaPayrollEngine engine = new KenyaPayrollEngine(updated);
        EmployeePayInput in = EmployeePayInput.builder("E").basic("80000").build();
        BigDecimal dec = engine.calculate(in, LocalDate.of(2026, 12, 28)).paye();
        BigDecimal jan = engine.calculate(in, LocalDate.of(2027, 1, 28)).paye();
        assertEquals(Money.of("200"), dec.subtract(jan), "an illustrative 200 increase in relief lowers PAYE by 200");
    }

    @Test
    void overlappingSetsAreRejected() {
        StatutoryRateSet current = table.forDate(LocalDate.of(2026, 11, 28));
        StatutoryRateSet overlapping = copy(current, LocalDate.of(2026, 6, 1), null, current.personalRelief());
        assertThrows(IllegalArgumentException.class, () -> table.with(overlapping));
    }

    @Test
    void lastBandMustBeUnbounded() {
        StatutoryRateSet s = table.forDate(LocalDate.of(2026, 11, 28));
        assertThrows(IllegalArgumentException.class, () -> new StatutoryRateSet(s.effectiveFrom(), null, "bad",
                List.of(TaxBand.of("24000", "0.10")), s.personalRelief(), s.insuranceReliefRate(), s.insuranceReliefCap(),
                s.nssfRate(), s.nssfLowerEarningsLimit(), s.nssfUpperEarningsLimit(), s.shifRate(), s.shifMinimum(),
                s.housingLevyRate(), s.nitaPerEmployee(), s.pensionDeductionCap(), s.postRetirementMedicalCap(),
                s.mortgageInterestCap(), s.nonCashBenefitThreshold(), 2, 3));
    }

    private static StatutoryRateSet copy(StatutoryRateSet s, LocalDate from, LocalDate to, BigDecimal relief) {
        return new StatutoryRateSet(from, to, "test", s.payeBands(), relief, s.insuranceReliefRate(),
                s.insuranceReliefCap(), s.nssfRate(), s.nssfLowerEarningsLimit(), s.nssfUpperEarningsLimit(), s.shifRate(),
                s.shifMinimum(), s.housingLevyRate(), s.nitaPerEmployee(), s.pensionDeductionCap(),
                s.postRetirementMedicalCap(), s.mortgageInterestCap(), s.nonCashBenefitThreshold(),
                s.maxDeductionNumerator(), s.maxDeductionDenominator());
    }
}
