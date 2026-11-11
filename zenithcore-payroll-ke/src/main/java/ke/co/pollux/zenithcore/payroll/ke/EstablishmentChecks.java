package ke.co.pollux.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.CheckResult;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks.Severity;

/**
 * Establishment control: no one is paid unless they hold an approved position that is in force on the
 * pay date, belongs to the same organization, is not filled beyond its approved headcount, and whose
 * salary ceiling (if set) covers the basic salary. Every failure blocks the payroll.
 */
public final class EstablishmentChecks {

    /**
     * An approved position. The code identifies it; the title (which may include a human-readable code)
     * is what messages show.
     *
     * @param maxBasicSalary salary ceiling for the position, or null for none
     * @param validTo        last day the position is approved, or null for open-ended
     */
    public record Position(String code, String title, int orgId, int approvedHeadcount, BigDecimal maxBasicSalary,
                           LocalDate validFrom, LocalDate validTo, boolean active) {

        boolean inForceOn(LocalDate date) {
            return active && (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo));
        }
    }

    /** An employee to be paid, with the position they hold (null if none). */
    public record Assignment(String employeeId, String name, int orgId, String positionCode, BigDecimal basicSalary) {
    }

    private EstablishmentChecks() {
    }

    public static List<CheckResult> run(List<Assignment> employees, List<Position> positions, LocalDate payDate) {
        Map<String, Position> byCode = positions.stream()
                .collect(Collectors.toMap(Position::code, p -> p, (a, b) -> a, LinkedHashMap::new));
        List<CheckResult> results = new ArrayList<>();

        List<String> noPosition = new ArrayList<>();
        List<String> notInForce = new ArrayList<>();
        List<String> otherOrg = new ArrayList<>();
        List<String> overCeiling = new ArrayList<>();
        Map<Position, List<Assignment>> filled = new LinkedHashMap<>();
        for (Assignment e : employees) {
            Position p = e.positionCode() == null ? null : byCode.get(e.positionCode());
            if (p == null) {
                noPosition.add(label(e));
                continue;
            }
            if (!p.inForceOn(payDate)) {
                notInForce.add(label(e) + " (" + p.title() + ")");
                continue;
            }
            if (p.orgId() != 0 && p.orgId() != e.orgId()) {
                otherOrg.add(label(e) + " (" + p.title() + ")");
            }
            if (p.maxBasicSalary() != null && p.maxBasicSalary().signum() > 0
                    && e.basicSalary().compareTo(p.maxBasicSalary()) > 0) {
                overCeiling.add(label(e) + ": basic " + Money.fmt(e.basicSalary()) + " above " + p.title() + " ceiling "
                        + Money.fmt(p.maxBasicSalary()));
            }
            filled.computeIfAbsent(p, k -> new ArrayList<>()).add(e);
        }
        List<String> over = filled.entrySet().stream()
                .filter(f -> f.getValue().size() > f.getKey().approvedHeadcount())
                .map(f -> f.getKey().title() + ": " + f.getValue().size() + " paid, "
                        + f.getKey().approvedHeadcount() + " approved ("
                        + f.getValue().stream().map(EstablishmentChecks::label).collect(Collectors.joining(", ")) + ")")
                .toList();

        results.add(result("Approved position", noPosition, "Every employee holds an approved position", "No approved position: "));
        results.add(result("Position in force", notInForce, "All positions in force on the pay date",
                "Position not approved on the pay date: "));
        results.add(result("Position organization", otherOrg, "All positions belong to the employee's organization",
                "Position belongs to another organization: "));
        results.add(result("Approved headcount", over, "No position filled beyond its approved headcount",
                "Over establishment: "));
        results.add(result("Salary ceiling", overCeiling, "All basic salaries within their position's ceiling", ""));
        return results;
    }

    private static CheckResult result(String check, List<String> problems, String pass, String prefix) {
        return problems.isEmpty()
                ? new CheckResult(check, Severity.PASS, pass)
                : new CheckResult(check, Severity.BLOCK, prefix + String.join("; ", problems));
    }

    private static String label(Assignment e) {
        return e.employeeId() + " " + e.name();
    }
}
