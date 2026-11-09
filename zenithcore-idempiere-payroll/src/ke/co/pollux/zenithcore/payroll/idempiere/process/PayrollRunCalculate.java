package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MBPartner;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.payroll.ke.DeductionArrears;
import ke.co.pollux.zenithcore.payroll.ke.EmployeePayInput;
import ke.co.pollux.zenithcore.payroll.ke.KenyaPayrollEngine;
import ke.co.pollux.zenithcore.payroll.ke.Money;
import ke.co.pollux.zenithcore.payroll.ke.PayResult;
import ke.co.pollux.zenithcore.payroll.ke.PrePayrollChecks;

/**
 * Calculate Payroll (button on Payroll Run). Runs the pre-payroll checks, then calculates every
 * active, unsuspended employee whose start and end dates cover the pay date, and replaces the
 * payroll lines. Blocking checks stop the calculation; their results are saved on the run.
 */
@Process
public class PayrollRunCalculate extends SvrProcess {

    static final String RUN = "ZC_PayrollRun";
    static final String LINE = "ZC_PayrollLine";
    static final String EMP = "ZC_EmployeePay";

    /** Pay data copied onto each line at calculation, so payslips and schedules show what was calculated. */
    static final String[] SNAPSHOT = {"ZC_BasicSalary", "ZC_HouseAllowance", "ZC_CommuterAllowance", "ZC_Overtime",
            "ZC_OtherAllowances", "ZC_NonCashBenefits", "ZC_PostRetirementMedical", "ZC_MortgageInterest", "ZC_KRAPin",
            "ZC_NSSFNo", "ZC_SHANo", "ZC_NationalIDNo", "ZC_Department", "ZC_BankName", "ZC_BankAccountNo"};

    @Override
    protected void prepare() {
        // no parameters: runs on the current payroll run record
    }

    @Override
    protected String doIt() throws Exception {
        PO run = MTable.get(getCtx(), RUN).getPO(getRecord_ID(), get_TrxName());
        if (run == null || run.get_ID() <= 0) {
            throw new AdempiereUserError("Save the payroll run before calculating it");
        }
        String status = run.get_ValueAsString("ZC_PayrollStatus");
        if ("AP".equals(status) || "CO".equals(status)) {
            throw new AdempiereUserError("This payroll is " + ("AP".equals(status) ? "approved" : "completed")
                    + " and cannot be recalculated");
        }
        Timestamp payTs = (Timestamp) run.get_Value("ZC_PayDate");
        LocalDate payDate = payTs.toLocalDateTime().toLocalDate();
        checkSequence(run, payTs);

        String where = "IsActive='Y' AND ZC_IsSuspended='N' AND ZC_StartDate<=? AND (ZC_EndDate IS NULL OR ZC_EndDate>=?)";
        List<Object> params = new ArrayList<>(List.of(payTs, payTs));
        if (run.getAD_Org_ID() > 0) {
            where += " AND AD_Org_ID=?";
            params.add(run.getAD_Org_ID());
        }
        List<PO> employees = new Query(getCtx(), EMP, where, get_TrxName())
                .setParameters(params).setClient_ID().setOrderBy("ZC_EmployeePay_ID").list();
        if (employees.isEmpty()) {
            throw new AdempiereUserError("No active employees with pay data for this pay date");
        }

        // Pre-payroll checks
        List<PrePayrollChecks.EmployeeRecord> records = new ArrayList<>();
        for (PO e : employees) {
            MBPartner bp = MBPartner.get(getCtx(), e.get_ValueAsInt("C_BPartner_ID"));
            records.add(new PrePayrollChecks.EmployeeRecord(bp.getValue(), bp.getName(), e.get_ValueAsString("ZC_KRAPin"),
                    e.get_ValueAsString("ZC_NSSFNo"), e.get_ValueAsString("ZC_SHANo"), e.get_ValueAsString("ZC_BankAccountNo")));
        }
        List<PrePayrollChecks.CheckResult> checks = PrePayrollChecks.run(records);
        String checkText = checks.stream()
                .map(c -> c.severity() + "  " + c.check() + ": " + c.detail())
                .collect(Collectors.joining("\n"));
        if (PrePayrollChecks.hasBlockers(checks)) {
            // Save the results outside this transaction, which is rolled back on error
            DB.executeUpdateEx("UPDATE ZC_PayrollRun SET ZC_CheckResults=? WHERE ZC_PayrollRun_ID=?",
                    new Object[] {truncate(checkText), run.get_ID()}, null);
            throw new AdempiereUserError("Pre-payroll checks failed. Nothing was calculated.\n"
                    + checks.stream().filter(c -> c.severity() == PrePayrollChecks.Severity.BLOCK)
                            .map(c -> c.check() + ": " + c.detail()).collect(Collectors.joining("\n")));
        }

        // Calculate
        DB.executeUpdateEx("DELETE FROM ZC_PayrollLine WHERE ZC_PayrollRun_ID=?", new Object[] {run.get_ID()}, get_TrxName());
        KenyaPayrollEngine engine = KenyaPayrollEngine.withKenyaDefaults();
        BigDecimal gross = Money.ZERO, paye = Money.ZERO, nssf = Money.ZERO, shif = Money.ZERO, levy = Money.ZERO,
                net = Money.ZERO, cost = Money.ZERO;
        for (PO e : employees) {
            Arrears prev = previousArrears(run, e.get_ID(), payTs);
            DeductionArrears.BroughtForward bf = DeductionArrears.bringForward(input(e), prev.amounts(), prev.source());
            PayResult r = engine.calculate(bf.input(), payDate);
            Map<String, BigDecimal> carry = DeductionArrears.toCarry(r);
            PO line = MTable.get(getCtx(), LINE).getPO(0, get_TrxName());
            line.setAD_Org_ID(run.getAD_Org_ID());
            set(line, "ZC_PayrollRun_ID", run.get_ID());
            set(line, "ZC_EmployeePay_ID", e.get_ID());
            set(line, "C_BPartner_ID", e.get_ValueAsInt("C_BPartner_ID"));
            set(line, "ZC_GrossPay", r.grossPay());
            set(line, "ZC_TaxableNonCash", r.taxableNonCashBenefits());
            set(line, "ZC_NSSFTier1", r.nssfTier1());
            set(line, "ZC_NSSFTier2", r.nssfTier2());
            set(line, "ZC_NSSF", r.nssf());
            set(line, "ZC_SHIF", r.shif());
            set(line, "ZC_HousingLevy", r.housingLevy());
            set(line, "ZC_AllowablePension", r.allowablePension());
            set(line, "ZC_TaxablePay", r.taxablePay());
            set(line, "ZC_TaxCharged", r.taxCharged());
            set(line, "ZC_PersonalRelief", r.personalRelief());
            set(line, "ZC_InsuranceRelief", r.insuranceRelief());
            set(line, "ZC_PAYE", r.paye());
            set(line, "ZC_HELB", r.applied(KenyaPayrollEngine.HELB));
            set(line, "ZC_PensionContribution", r.applied(KenyaPayrollEngine.PENSION));
            set(line, "ZC_InsurancePremium", r.applied(KenyaPayrollEngine.INSURANCE));
            set(line, "ZC_StaffLoan", r.applied(KenyaPayrollEngine.STAFF_LOAN));
            set(line, "ZC_SACCO", r.applied(KenyaPayrollEngine.SACCO));
            set(line, "ZC_UnionDues", r.applied(KenyaPayrollEngine.UNION));
            set(line, "ZC_CarriedForward", r.carriedForward().stream()
                    .map(PayResult.AppliedDeduction::shortfall).reduce(Money.ZERO, BigDecimal::add));
            set(line, "ZC_TotalDeductions", r.totalDeductions());
            set(line, "ZC_NetPay", r.netPay());
            set(line, "ZC_EmployerNSSF", r.employerNssf());
            set(line, "ZC_EmployerHousingLevy", r.employerHousingLevy());
            set(line, "ZC_NITA", r.nita());
            set(line, "ZC_EmployerCost", r.employerCost());
            List<String> trace = new ArrayList<>(bf.trace());
            trace.addAll(r.trace());
            DeductionArrears.notCarried(r).forEach((name, amt) -> trace.add("  " + name + ": " + Money.fmt(amt)
                    + " not deducted and not carried forward: refer to HR"));
            set(line, "ZC_Trace", truncate(String.join("\n", trace)));
            set(line, "ZC_BroughtForward", bf.total());
            for (Map.Entry<String, String> a : ARREARS.entrySet()) {
                set(line, a.getValue(), carry.getOrDefault(a.getKey(), Money.ZERO));
            }
            for (String col : SNAPSHOT) {
                set(line, col, e.get_Value(col));
            }
            line.saveEx();

            gross = gross.add(r.grossPay());
            paye = paye.add(r.paye());
            nssf = nssf.add(r.nssf());
            shif = shif.add(r.shif());
            levy = levy.add(r.housingLevy());
            net = net.add(r.netPay());
            cost = cost.add(r.employerCost());
            addLog(MBPartner.get(getCtx(), e.get_ValueAsInt("C_BPartner_ID")).getName() + ": gross " + Money.fmt(r.grossPay())
                    + ", PAYE " + Money.fmt(r.paye()) + ", net " + Money.fmt(r.netPay())
                    + (r.carriedForward().isEmpty() ? "" : " (two-thirds rule applied)"));
        }

        set(run, "ZC_PayrollStatus", "CA");
        set(run, "ZC_CalculatedUser_ID", getAD_User_ID());
        set(run, "ZC_ApprovedUser_ID", null);
        set(run, "ZC_EmployeeCount", employees.size());
        set(run, "ZC_TotalGross", gross);
        set(run, "ZC_TotalPAYE", paye);
        set(run, "ZC_TotalNSSF", nssf);
        set(run, "ZC_TotalSHIF", shif);
        set(run, "ZC_TotalHousingLevy", levy);
        set(run, "ZC_TotalNet", net);
        set(run, "ZC_TotalEmployerCost", cost);
        set(run, "ZC_CheckResults", truncate(checkText));
        run.saveEx();
        return "Calculated " + employees.size() + " employees. Gross " + Money.fmt(gross) + ", PAYE " + Money.fmt(paye)
                + ", net pay " + Money.fmt(net) + ". Next: approval by someone other than you.";
    }

    /** Deductions carried forward, and the line column that holds each. */
    static final Map<String, String> ARREARS = Map.of(KenyaPayrollEngine.HELB, "ZC_HELBArrears",
            KenyaPayrollEngine.STAFF_LOAN, "ZC_StaffLoanArrears", KenyaPayrollEngine.SACCO, "ZC_SACCOArrears",
            KenyaPayrollEngine.UNION, "ZC_UnionArrears");

    record Arrears(Map<String, BigDecimal> amounts, String source) {
    }

    /**
     * Arrears carried out of the employee's most recent approved or completed payroll before this pay
     * date. Arrears are only ever taken from the employee's latest line, so they are brought forward once.
     */
    private Arrears previousArrears(PO run, int employeePayId, Timestamp payDate) {
        List<Object> row = DB.getSQLValueObjectsEx(get_TrxName(),
                "SELECT l.ZC_HELBArrears, l.ZC_StaffLoanArrears, l.ZC_SACCOArrears, l.ZC_UnionArrears, r.Name "
                        + "FROM ZC_PayrollLine l JOIN ZC_PayrollRun r ON r.ZC_PayrollRun_ID=l.ZC_PayrollRun_ID "
                        + "WHERE l.ZC_EmployeePay_ID=? AND r.ZC_PayrollRun_ID<>? AND r.AD_Client_ID=? "
                        + "AND r.ZC_PayrollStatus IN ('AP','CO') AND r.ZC_PayDate<? "
                        + "ORDER BY r.ZC_PayDate DESC, r.ZC_PayrollRun_ID DESC FETCH FIRST 1 ROWS ONLY",
                employeePayId, run.get_ID(), run.getAD_Client_ID(), payDate);
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (row == null || row.isEmpty()) {
            return new Arrears(m, "");
        }
        String[] names = {KenyaPayrollEngine.HELB, KenyaPayrollEngine.STAFF_LOAN, KenyaPayrollEngine.SACCO,
                KenyaPayrollEngine.UNION};
        for (int i = 0; i < names.length; i++) {
            BigDecimal v = (BigDecimal) row.get(i);
            if (v != null && v.signum() > 0) {
                m.put(names[i], v);
            }
        }
        return new Arrears(m, (String) row.get(4));
    }

    /**
     * Payrolls of one organization are calculated in date order, so arrears move forward exactly once:
     * an earlier payroll that is not yet approved, or a later one that is already approved, stops this one.
     */
    private void checkSequence(PO run, Timestamp payDate) {
        String earlier = DB.getSQLValueStringEx(get_TrxName(),
                "SELECT MAX(Name) FROM ZC_PayrollRun WHERE AD_Client_ID=? AND AD_Org_ID=? AND ZC_PayrollRun_ID<>? "
                        + "AND IsActive='Y' AND ZC_PayrollStatus IN ('DR','CA') AND ZC_PayDate<?",
                run.getAD_Client_ID(), run.getAD_Org_ID(), run.get_ID(), payDate);
        if (earlier != null) {
            throw new AdempiereUserError("An earlier payroll (" + earlier + ") is not yet approved. "
                    + "Approve it, or delete it if it is not needed, before calculating this one.");
        }
        String later = DB.getSQLValueStringEx(get_TrxName(),
                "SELECT MIN(Name) FROM ZC_PayrollRun WHERE AD_Client_ID=? AND AD_Org_ID=? AND ZC_PayrollRun_ID<>? "
                        + "AND ZC_PayrollStatus IN ('AP','CO') AND ZC_PayDate>?",
                run.getAD_Client_ID(), run.getAD_Org_ID(), run.get_ID(), payDate);
        if (later != null) {
            throw new AdempiereUserError("A later payroll (" + later + ") is already approved. "
                    + "A payroll cannot be calculated for an earlier date.");
        }
    }

    static EmployeePayInput input(PO e) {
        return new EmployeePayInput(String.valueOf(e.get_ID()),
                amount(e, "ZC_BasicSalary"), amount(e, "ZC_HouseAllowance"), amount(e, "ZC_CommuterAllowance"),
                amount(e, "ZC_Overtime"), amount(e, "ZC_OtherAllowances"), amount(e, "ZC_NonCashBenefits"),
                amount(e, "ZC_PensionContribution"), amount(e, "ZC_InsurancePremium"),
                amount(e, "ZC_PostRetirementMedical"), amount(e, "ZC_MortgageInterest"), amount(e, "ZC_HELB"),
                amount(e, "ZC_StaffLoan"), amount(e, "ZC_SACCO"), amount(e, "ZC_UnionDues"));
    }

    private static BigDecimal amount(PO po, String column) {
        Object v = po.get_Value(column);
        return v == null ? null : (BigDecimal) v;
    }

    /** Payroll columns are read-only in the window, so values are set without the updateable check. */
    static void set(PO po, String column, Object value) {
        if (!po.set_ValueNoCheck(column, value)) {
            throw new IllegalStateException("Could not set " + column + " on " + po.get_TableName());
        }
    }

    private static String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 3990) + "\n...";
    }
}
