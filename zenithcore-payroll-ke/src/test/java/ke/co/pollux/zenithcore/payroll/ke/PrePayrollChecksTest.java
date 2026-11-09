package ke.co.pollux.zenithcore.payroll.ke;

import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.CheckResult;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.EmployeeRecord;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrePayrollChecksTest {

    private static final EmployeeRecord KAMAU =
            new EmployeeRecord("E001", "Joseph Kamau", "A000004100K", "NS5200000", "SHA7700000", "0170-1234-5678");
    private static final EmployeeRecord ACHIENG =
            new EmployeeRecord("E002", "Sarah Achieng", "A000004237K", "NS5200913", "SHA7700311", "1102-3344-556");

    private static CheckResult find(List<CheckResult> results, String name) {
        return results.stream().filter(r -> r.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void cleanPayrollPasses() {
        List<CheckResult> results = PrePayrollChecks.run(List.of(KAMAU, ACHIENG));
        assertFalse(PrePayrollChecks.hasBlockers(results), results.toString());
    }

    @Test
    void sharedBankAccountAndMissingNssfBlockPayroll() {
        EmployeeRecord ghost = new EmployeeRecord("E005", "Peter Otieno", "A000004648K", "", "SHA7701244", "0170 1234 5678");
        List<CheckResult> results = PrePayrollChecks.run(List.of(KAMAU, ACHIENG, ghost));
        assertTrue(PrePayrollChecks.hasBlockers(results));
        CheckResult bank = find(results, "Duplicate bank accounts");
        assertEquals(Severity.BLOCK, bank.severity());
        assertTrue(bank.detail().contains("E001 Joseph Kamau and E005 Peter Otieno"), bank.detail());
        assertEquals(Severity.BLOCK, find(results, "NSSF and SHA numbers").severity());
    }

    @Test
    void duplicateAndInvalidKraPinsBlockPayroll() {
        List<EmployeeRecord> list = new ArrayList<>(List.of(KAMAU, ACHIENG));
        list.add(new EmployeeRecord("E009", "Copy", "A000004100K", "NS1", "SHA1", "9999"));
        list.add(new EmployeeRecord("E010", "Bad PIN", "12345", "NS2", "SHA2", "8888"));
        List<CheckResult> results = PrePayrollChecks.run(list);
        assertEquals(Severity.BLOCK, find(results, "Duplicate KRA PINs").severity());
        assertTrue(find(results, "KRA PIN format").detail().contains("E010 Bad PIN"));
    }
}
