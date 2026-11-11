package ke.co.pollux.zenithcore.payroll.ke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.payroll.ke.EstablishmentChecks.Assignment;
import ke.co.pollux.zenithcore.payroll.ke.EstablishmentChecks.Position;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.CheckResult;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.Severity;

class EstablishmentChecksTest {

    private static final LocalDate PAY = LocalDate.of(2026, 12, 23);
    private static final int ORG = 50001;

    private static Position pos(String code, int headcount, String ceiling) {
        return new Position(code, "Title " + code, ORG, headcount, ceiling == null ? null : new BigDecimal(ceiling),
                LocalDate.of(2026, 1, 1), null, true);
    }

    private static Assignment emp(String id, String position, String basic) {
        return new Assignment(id, "Name " + id, ORG, position, new BigDecimal(basic));
    }

    private static CheckResult check(List<CheckResult> r, String name) {
        return r.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void all_within_establishment_passes() {
        List<CheckResult> r = EstablishmentChecks.run(
                List.of(emp("E001", "WS-TECH", "62000"), emp("E004", "WS-TECH", "18000")),
                List.of(pos("WS-TECH", 2, "70000")), PAY);
        assertFalse(PrePayrollChecks.hasBlockers(r), r.toString());
        assertEquals(5, r.size());
    }

    @Test
    void employee_without_position_blocks() {
        List<CheckResult> r = EstablishmentChecks.run(List.of(emp("E009", null, "30000"), emp("E010", "NOPE", "30000")),
                List.of(pos("WS-TECH", 2, null)), PAY);
        CheckResult c = check(r, "Approved position");
        assertEquals(Severity.BLOCK, c.severity());
        assertTrue(c.detail().contains("E009") && c.detail().contains("E010"), c.detail());
    }

    @Test
    void headcount_exactly_full_passes_and_one_over_blocks() {
        List<Position> p = List.of(pos("CC-AGENT", 2, null));
        assertEquals(Severity.PASS, check(EstablishmentChecks.run(
                List.of(emp("A", "CC-AGENT", "1"), emp("B", "CC-AGENT", "1")), p, PAY), "Approved headcount").severity());
        CheckResult over = check(EstablishmentChecks.run(
                List.of(emp("A", "CC-AGENT", "1"), emp("B", "CC-AGENT", "1"), emp("C", "CC-AGENT", "1")), p, PAY),
                "Approved headcount");
        assertEquals(Severity.BLOCK, over.severity());
        assertTrue(over.detail().contains("Title CC-AGENT: 3 paid, 2 approved"), over.detail());
    }

    @Test
    void salary_at_ceiling_passes_and_one_cent_over_blocks() {
        List<Position> p = List.of(pos("WS-TECH", 5, "70000"));
        assertEquals(Severity.PASS,
                check(EstablishmentChecks.run(List.of(emp("A", "WS-TECH", "70000.00")), p, PAY), "Salary ceiling").severity());
        CheckResult c = check(EstablishmentChecks.run(List.of(emp("A", "WS-TECH", "70000.01")), p, PAY), "Salary ceiling");
        assertEquals(Severity.BLOCK, c.severity());
        assertTrue(c.detail().contains("70,000.01 above Title WS-TECH ceiling 70,000.00"), c.detail());
    }

    @Test
    void zero_or_missing_ceiling_means_no_ceiling() {
        assertEquals(Severity.PASS, check(EstablishmentChecks.run(List.of(emp("A", "X", "999999")),
                List.of(pos("X", 1, "0")), PAY), "Salary ceiling").severity());
        assertEquals(Severity.PASS, check(EstablishmentChecks.run(List.of(emp("A", "X", "999999")),
                List.of(pos("X", 1, null)), PAY), "Salary ceiling").severity());
    }

    @Test
    void position_validity_includes_first_and_last_day() {
        Position p = new Position("T", "Temp", ORG, 1, null, PAY, PAY, true);
        assertEquals(Severity.PASS, check(EstablishmentChecks.run(List.of(emp("A", "T", "1")), List.of(p), PAY),
                "Position in force").severity());
        assertEquals(Severity.BLOCK, check(EstablishmentChecks.run(List.of(emp("A", "T", "1")), List.of(p), PAY.plusDays(1)),
                "Position in force").severity());
        assertEquals(Severity.BLOCK, check(EstablishmentChecks.run(List.of(emp("A", "T", "1")), List.of(p), PAY.minusDays(1)),
                "Position in force").severity());
        Position inactive = new Position("T", "Temp", ORG, 1, null, null, null, false);
        assertEquals(Severity.BLOCK, check(EstablishmentChecks.run(List.of(emp("A", "T", "1")), List.of(inactive), PAY),
                "Position in force").severity());
    }

    @Test
    void position_of_another_organization_blocks_but_shared_position_passes() {
        Position other = new Position("X", "X", 50002, 1, null, null, null, true);
        assertEquals(Severity.BLOCK, check(EstablishmentChecks.run(List.of(emp("A", "X", "1")), List.of(other), PAY),
                "Position organization").severity());
        Position shared = new Position("X", "X", 0, 1, null, null, null, true);
        assertEquals(Severity.PASS, check(EstablishmentChecks.run(List.of(emp("A", "X", "1")), List.of(shared), PAY),
                "Position organization").severity());
    }
}
