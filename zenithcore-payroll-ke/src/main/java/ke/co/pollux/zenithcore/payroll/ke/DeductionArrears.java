package ke.co.pollux.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carry-forward of deductions that the two-thirds rule stopped in one month (arrears).
 *
 * <p>Only recoveries of debts are carried forward: HELB, staff loan, SACCO and union dues. Pension
 * contributions and insurance premiums are not: a missed contribution or premium belongs to its own
 * month and affects tax relief, so it is left to HR and the scheme to settle. Arrears brought forward
 * are added to the month's amount due and go through the two-thirds rule again; anything still not
 * deducted carries forward again.
 */
public final class DeductionArrears {

    /** Deductions whose shortfall is carried forward to the next payroll. */
    public static final Set<String> CARRIED = Set.of(KenyaPayrollEngine.HELB, KenyaPayrollEngine.STAFF_LOAN,
            KenyaPayrollEngine.SACCO, KenyaPayrollEngine.UNION);

    private DeductionArrears() {
    }

    /** The pay input with arrears added to the amounts due, and trace lines explaining it. */
    public record BroughtForward(EmployeePayInput input, BigDecimal total, List<String> trace) {
    }

    /**
     * Adds arrears brought forward from the previous payroll to this month's deductions.
     *
     * @param in      this month's pay input
     * @param arrears amounts carried forward last month, by deduction name; others are rejected
     * @param source  where they came from, for the trace (for example the previous payroll's name)
     */
    public static BroughtForward bringForward(EmployeePayInput in, Map<String, BigDecimal> arrears, String source) {
        Map<String, BigDecimal> a = new LinkedHashMap<>();
        BigDecimal total = Money.ZERO;
        List<String> trace = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : arrears.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!CARRIED.contains(e.getKey())) {
                throw new IllegalArgumentException("Arrears are not carried forward for " + e.getKey());
            }
            if (e.getValue().signum() < 0) {
                throw new IllegalArgumentException("Arrears cannot be negative: " + e.getKey());
            }
            BigDecimal v = Money.round(e.getValue());
            if (v.signum() == 0) {
                continue;
            }
            a.put(e.getKey(), v);
            total = total.add(v);
            trace.add("Brought forward from " + source + ": " + e.getKey() + " " + Money.fmt(v));
        }
        if (a.isEmpty()) {
            return new BroughtForward(in, Money.ZERO, List.of());
        }
        EmployeePayInput out = new EmployeePayInput(in.employeeId(), in.basicSalary(), in.houseAllowance(),
                in.commuterAllowance(), in.overtime(), in.otherAllowances(), in.nonCashBenefits(),
                in.pensionContribution(), in.insurancePremium(), in.postRetirementMedical(), in.mortgageInterest(),
                plus(in.helb(), a.get(KenyaPayrollEngine.HELB)), plus(in.staffLoan(), a.get(KenyaPayrollEngine.STAFF_LOAN)),
                plus(in.sacco(), a.get(KenyaPayrollEngine.SACCO)), plus(in.unionDues(), a.get(KenyaPayrollEngine.UNION)));
        return new BroughtForward(out, Money.round(total), List.copyOf(trace));
    }

    /** Arrears to carry to the next payroll: shortfalls of the carried deductions only. */
    public static Map<String, BigDecimal> toCarry(PayResult r) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (PayResult.AppliedDeduction d : r.carriedForward()) {
            if (CARRIED.contains(d.name())) {
                m.put(d.name(), d.shortfall());
            }
        }
        return m;
    }

    /** Shortfalls that are not carried forward (pension, insurance), for HR to settle. */
    public static Map<String, BigDecimal> notCarried(PayResult r) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (PayResult.AppliedDeduction d : r.carriedForward()) {
            if (!CARRIED.contains(d.name())) {
                m.put(d.name(), d.shortfall());
            }
        }
        return m;
    }

    private static BigDecimal plus(BigDecimal a, BigDecimal b) {
        return b == null ? a : Money.round(a.add(b));
    }
}
