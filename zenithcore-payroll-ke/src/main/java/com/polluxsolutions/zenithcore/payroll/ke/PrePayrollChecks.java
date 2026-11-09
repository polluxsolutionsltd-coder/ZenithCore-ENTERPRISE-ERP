package com.polluxsolutions.zenithcore.payroll.ke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks run before a payroll is calculated. Any {@link Severity#BLOCK} result must stop calculation.
 * These catch common ghost-worker patterns: shared bank accounts, shared KRA PINs and employees
 * without statutory registration.
 */
public final class PrePayrollChecks {

    /** Identity data needed for the checks. */
    public record EmployeeRecord(String id, String name, String kraPin, String nssfNumber, String shaNumber,
                                 String bankAccount) {
    }

    public enum Severity { PASS, WARNING, BLOCK }

    public record CheckResult(String check, Severity severity, String detail) {
    }

    /** Individual KRA PIN: A, nine digits, a letter. */
    private static final Pattern KRA_PIN = Pattern.compile("^A\\d{9}[A-Z]$");

    private PrePayrollChecks() {
    }

    public static List<CheckResult> run(List<EmployeeRecord> employees) {
        List<CheckResult> results = new ArrayList<>();
        results.add(duplicates("Duplicate bank accounts", employees, e -> normalise(e.bankAccount())));
        results.add(duplicates("Duplicate KRA PINs", employees, e -> normalise(e.kraPin())));

        List<String> badPin = employees.stream()
                .filter(e -> e.kraPin() == null || !KRA_PIN.matcher(e.kraPin().trim().toUpperCase(Locale.ROOT)).matches())
                .map(PrePayrollChecks::label).toList();
        results.add(badPin.isEmpty()
                ? new CheckResult("KRA PIN format", Severity.PASS, "All PINs valid")
                : new CheckResult("KRA PIN format", Severity.BLOCK, "Missing or invalid PIN: " + String.join(", ", badPin)));

        List<String> missing = employees.stream()
                .filter(e -> blank(e.nssfNumber()) || blank(e.shaNumber()))
                .map(PrePayrollChecks::label).toList();
        results.add(missing.isEmpty()
                ? new CheckResult("NSSF and SHA numbers", Severity.PASS, "All present")
                : new CheckResult("NSSF and SHA numbers", Severity.BLOCK, "Missing for: " + String.join(", ", missing)));

        List<String> noBank = employees.stream().filter(e -> blank(e.bankAccount())).map(PrePayrollChecks::label).toList();
        results.add(noBank.isEmpty()
                ? new CheckResult("Bank details", Severity.PASS, "All present")
                : new CheckResult("Bank details", Severity.BLOCK, "Missing for: " + String.join(", ", noBank)));
        return results;
    }

    public static boolean hasBlockers(List<CheckResult> results) {
        return results.stream().anyMatch(r -> r.severity() == Severity.BLOCK);
    }

    private static CheckResult duplicates(String name, List<EmployeeRecord> employees, Function<EmployeeRecord, String> key) {
        Map<String, List<EmployeeRecord>> groups = employees.stream()
                .filter(e -> !key.apply(e).isEmpty())
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        List<String> clashes = groups.values().stream()
                .filter(g -> g.size() > 1)
                .map(g -> g.stream().map(PrePayrollChecks::label).collect(Collectors.joining(" and ")))
                .toList();
        return clashes.isEmpty()
                ? new CheckResult(name, Severity.PASS, "None found")
                : new CheckResult(name, Severity.BLOCK, String.join("; ", clashes));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String label(EmployeeRecord e) {
        return e.id() + " " + e.name();
    }
}
