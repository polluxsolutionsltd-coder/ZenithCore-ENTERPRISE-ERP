package ke.co.pollux.zenithcore.payroll.ke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DeductionArrearsTest {

    private static final LocalDate NOV_2026 = LocalDate.of(2026, 11, 28);
    private static final LocalDate DEC_2026 = LocalDate.of(2026, 12, 23);
    private final KenyaPayrollEngine engine = KenyaPayrollEngine.withKenyaDefaults();

    private static void money(String expected, BigDecimal actual, String what) {
        assertEquals(new BigDecimal(expected).setScale(2), actual, what);
    }

    private static EmployeePayInput e004(String basic) {
        return EmployeePayInput.builder("E004").basic(basic).staffLoan("6000").sacco("5000").build();
    }

    @Test
    void sacco_shortfall_is_carried_and_partly_recovered_next_month() {
        PayResult nov = engine.calculate(e004("18000"), NOV_2026);
        Map<String, BigDecimal> carry = DeductionArrears.toCarry(nov);
        assertEquals(1, carry.size());
        money("845", carry.get(KenyaPayrollEngine.SACCO), "November SACCO carried");

        DeductionArrears.BroughtForward bf = DeductionArrears.bringForward(e004("18000"), carry, "November 2026");
        money("845", bf.total(), "brought forward");
        money("5845", bf.input().sacco(), "SACCO due in December");
        assertTrue(bf.trace().get(0).contains("Brought forward from November 2026: SACCO 845.00"), bf.trace().toString());

        PayResult dec = engine.calculate(bf.input(), DEC_2026);
        // Limit 12,000; statutory 1,845; staff loan 6,000; SACCO gets the remaining 4,155 again
        money("4155", dec.applied(KenyaPayrollEngine.SACCO), "December SACCO deducted");
        money("1690", DeductionArrears.toCarry(dec).get(KenyaPayrollEngine.SACCO), "December SACCO carried");
        money("6000", dec.netPay(), "December net pay");
    }

    @Test
    void arrears_are_cleared_when_there_is_room() {
        PayResult nov = engine.calculate(e004("18000"), NOV_2026);
        DeductionArrears.BroughtForward bf = DeductionArrears.bringForward(e004("24000"), DeductionArrears.toCarry(nov),
                "November 2026");
        PayResult dec = engine.calculate(bf.input(), DEC_2026);
        // Gross 24,000: NSSF 1,440, SHIF 660, Housing Levy 360, PAYE 0; limit 16,000
        money("5845", dec.applied(KenyaPayrollEngine.SACCO), "SACCO including arrears");
        assertTrue(DeductionArrears.toCarry(dec).isEmpty(), "nothing left to carry");
        money("9695", dec.netPay(), "net pay");
    }

    @Test
    void pension_shortfall_is_reported_but_not_carried() {
        PayResult r = engine.calculate(EmployeePayInput.builder("X").basic("18000").helb("9000").pension("5000").build(),
                NOV_2026);
        // Limit 12,000; statutory 1,845; HELB 9,000; pension gets 1,155 of 5,000
        money("1155", r.applied(KenyaPayrollEngine.PENSION), "pension deducted");
        assertTrue(DeductionArrears.toCarry(r).isEmpty(), "pension is not carried");
        money("3845", DeductionArrears.notCarried(r).get(KenyaPayrollEngine.PENSION), "pension shortfall for HR");
    }

    @Test
    void no_arrears_leaves_input_unchanged() {
        EmployeePayInput in = e004("18000");
        DeductionArrears.BroughtForward bf = DeductionArrears.bringForward(in, Map.of(), "none");
        assertEquals(in, bf.input());
        money("0", bf.total(), "total");
        assertTrue(bf.trace().isEmpty());
    }

    @Test
    void rejects_arrears_that_are_not_carried_or_negative() {
        EmployeePayInput in = e004("18000");
        assertThrows(IllegalArgumentException.class,
                () -> DeductionArrears.bringForward(in, Map.of(KenyaPayrollEngine.PENSION, new BigDecimal("100")), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> DeductionArrears.bringForward(in, Map.of(KenyaPayrollEngine.SACCO, new BigDecimal("-1")), "x"));
    }

    @Test
    void all_four_debt_deductions_are_carried() {
        assertEquals(java.util.Set.of("HELB", "Staff loan", "SACCO", "Union dues"), DeductionArrears.CARRIED);
        DeductionArrears.BroughtForward bf = DeductionArrears.bringForward(e004("18000"),
                Map.of(KenyaPayrollEngine.HELB, new BigDecimal("100"), KenyaPayrollEngine.STAFF_LOAN, new BigDecimal("200"),
                        KenyaPayrollEngine.SACCO, new BigDecimal("300"), KenyaPayrollEngine.UNION, new BigDecimal("0.004")),
                "x");
        money("100", bf.input().helb(), "HELB");
        money("6200", bf.input().staffLoan(), "staff loan");
        money("5300", bf.input().sacco(), "SACCO");
        money("0", bf.input().unionDues(), "union rounds to zero");
        money("600", bf.total(), "total");
    }
}
