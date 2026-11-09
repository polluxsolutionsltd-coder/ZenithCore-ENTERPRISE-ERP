package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MClientInfo;
import org.compiere.model.MConversionType;
import org.compiere.model.MDocType;
import org.compiere.model.MGLCategory;
import org.compiere.model.MJournal;
import org.compiere.model.MJournalLine;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Complete Payroll (button on Payroll Run). Posts an approved payroll to the general ledger as one
 * completed GL journal, using the Payroll Accounts (Kenya) setup of the client's primary accounting
 * schema, then marks the run Completed. The person who calculated the payroll cannot complete it.
 *
 * <p>Journal: debit salaries with gross pay and employer statutory cost with employer NSSF, employer
 * Housing Levy and NITA; credit each statutory and voluntary deduction to its payable account and net
 * pay to net salaries payable. The journal must balance to the cent or nothing is posted.
 */
@Process
public class PayrollRunComplete extends SvrProcess {

    static final String ACCT = "ZC_PayrollAcct";

    @Override
    protected void prepare() {
        // no parameters: runs on the current payroll run record
    }

    @Override
    protected String doIt() throws Exception {
        PO run = MTable.get(getCtx(), PayrollRunCalculate.RUN).getPO(getRecord_ID(), get_TrxName());
        if (run == null || run.get_ID() <= 0) {
            throw new AdempiereUserError("Open a payroll run first");
        }
        String status = run.get_ValueAsString("ZC_PayrollStatus");
        if (!"AP".equals(status)) {
            throw new AdempiereUserError("CO".equals(status) ? "This payroll is already completed"
                    : "Only an approved payroll can be completed");
        }
        int calculatedBy = run.get_ValueAsInt("ZC_CalculatedUser_ID");
        if (calculatedBy == getAD_User_ID()) {
            throw new AdempiereUserError("You calculated this payroll. A different person must complete it.");
        }

        MAcctSchema schema = MClientInfo.get(getCtx(), run.getAD_Client_ID()).getMAcctSchema1();
        PO acct = new Query(getCtx(), ACCT, "C_AcctSchema_ID=?", get_TrxName())
                .setClient_ID().setOnlyActiveRecords(true).setParameters(schema.getC_AcctSchema_ID()).first();
        if (acct == null) {
            throw new AdempiereUserError("Set up Payroll Accounts (Kenya) for accounting schema " + schema.getName()
                    + " before completing a payroll");
        }

        Map<String, BigDecimal> t = totals(run.get_ID());
        BigDecimal employerCost = t.get("ZC_EmployerNSSF").add(t.get("ZC_EmployerHousingLevy")).add(t.get("ZC_NITA"));

        List<Entry> entries = new ArrayList<>();
        entries.add(dr(acct, "ZC_SalariesExpenseAcct_ID", t.get("ZC_GrossPay"), "Gross pay"));
        entries.add(dr(acct, "ZC_EmployerCostExpenseAcct_ID", employerCost, "Employer NSSF, Housing Levy and NITA"));
        entries.add(cr(acct, "ZC_PAYEPayableAcct_ID", t.get("ZC_PAYE"), "PAYE"));
        entries.add(cr(acct, "ZC_NSSFPayableAcct_ID", t.get("ZC_NSSF").add(t.get("ZC_EmployerNSSF")),
                "NSSF, employee and employer"));
        entries.add(cr(acct, "ZC_SHIFPayableAcct_ID", t.get("ZC_SHIF"), "SHIF"));
        entries.add(cr(acct, "ZC_HousingLevyPayableAcct_ID",
                t.get("ZC_HousingLevy").add(t.get("ZC_EmployerHousingLevy")), "Housing Levy, employee and employer"));
        entries.add(cr(acct, "ZC_NITAPayableAcct_ID", t.get("ZC_NITA"), "NITA levy"));
        entries.add(cr(acct, "ZC_HELBPayableAcct_ID", t.get("ZC_HELB"), "HELB"));
        entries.add(cr(acct, "ZC_PensionInsPayableAcct_ID",
                t.get("ZC_PensionContribution").add(t.get("ZC_InsurancePremium")), "Pension and insurance"));
        entries.add(cr(acct, "ZC_StaffLoanAcct_ID", t.get("ZC_StaffLoan"), "Staff loan recoveries"));
        entries.add(cr(acct, "ZC_SaccoUnionPayableAcct_ID", t.get("ZC_SACCO").add(t.get("ZC_UnionDues")),
                "SACCO and union dues"));
        entries.add(cr(acct, "ZC_NetSalariesPayableAcct_ID", t.get("ZC_NetPay"), "Net pay"));

        BigDecimal debits = entries.stream().map(e -> e.dr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().map(e -> e.cr).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new AdempiereUserError("Payroll journal does not balance: debits " + debits + ", credits " + credits
                    + ". Recalculate the payroll.");
        }

        MJournal journal = new MJournal(getCtx(), 0, get_TrxName());
        journal.setAD_Org_ID(run.getAD_Org_ID());
        journal.setC_AcctSchema_ID(schema.getC_AcctSchema_ID());
        journal.setC_Currency_ID(schema.getC_Currency_ID());
        journal.setC_ConversionType_ID(MConversionType.getDefault(run.getAD_Client_ID()));
        MDocType[] docTypes = MDocType.getOfDocBaseType(getCtx(), MDocType.DOCBASETYPE_GLJournal);
        if (docTypes.length == 0) {
            throw new AdempiereUserError("No GL Journal document type is set up for this client");
        }
        journal.setC_DocType_ID(docTypes[0].getC_DocType_ID());
        int category = acct.get_ValueAsInt("GL_Category_ID");
        journal.setGL_Category_ID(category > 0 ? category : MGLCategory.getDefault(getCtx(), MGLCategory.CATEGORYTYPE_Manual).getGL_Category_ID());
        journal.setDateDoc((java.sql.Timestamp) run.get_Value("ZC_PayDate"));
        journal.setDateAcct((java.sql.Timestamp) run.get_Value("ZC_PayDate"));
        journal.setPostingType(MJournal.POSTINGTYPE_Actual);
        journal.setDescription("Payroll: " + run.get_ValueAsString("Name"));
        journal.saveEx();

        int lineNo = 0;
        for (Entry e : entries) {
            if (e.dr.signum() == 0 && e.cr.signum() == 0) {
                continue;
            }
            MJournalLine line = new MJournalLine(journal);
            line.setLine(lineNo += 10);
            line.setAD_Org_ID(run.getAD_Org_ID());
            line.setAccount_ID(e.account);
            line.setAmtSourceDr(e.dr);
            line.setAmtSourceCr(e.cr);
            line.setDescription(e.text);
            line.saveEx();
        }

        if (!journal.processIt(DocAction.ACTION_Complete)) {
            throw new AdempiereUserError("GL journal " + journal.getDocumentNo() + " could not be completed: "
                    + journal.getProcessMsg());
        }
        journal.saveEx();

        PayrollRunCalculate.set(run, "ZC_PayrollStatus", "CO");
        PayrollRunCalculate.set(run, "ZC_PostedUser_ID", getAD_User_ID());
        PayrollRunCalculate.set(run, "GL_Journal_ID", journal.getGL_Journal_ID());
        run.saveEx();
        addLog(journal.getGL_Journal_ID(), journal.getDateAcct(), debits, "GL journal " + journal.getDocumentNo(),
                journal.get_Table_ID(), journal.getGL_Journal_ID());
        return "Payroll completed and posted: GL journal " + journal.getDocumentNo() + ", " + schema.getC_Currency().getISO_Code() + " "
                + String.format("%,.2f", debits.setScale(2, RoundingMode.HALF_UP)) + ". Posted by " + MUser.getNameOfUser(getAD_User_ID()) + ".";
    }

    /** Sum of the payroll lines' amount columns used by the journal. */
    private Map<String, BigDecimal> totals(int runId) {
        String[] cols = {"ZC_GrossPay", "ZC_PAYE", "ZC_NSSF", "ZC_SHIF", "ZC_HousingLevy", "ZC_HELB",
                "ZC_PensionContribution", "ZC_InsurancePremium", "ZC_StaffLoan", "ZC_SACCO", "ZC_UnionDues", "ZC_NetPay",
                "ZC_EmployerNSSF", "ZC_EmployerHousingLevy", "ZC_NITA"};
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.length; i++) {
            sql.append(i > 0 ? "," : "").append("COALESCE(SUM(").append(cols[i]).append("),0)");
        }
        sql.append(" FROM ZC_PayrollLine WHERE ZC_PayrollRun_ID=?");
        List<Object> row = DB.getSQLValueObjectsEx(get_TrxName(), sql.toString(), runId);
        if (row == null || ((BigDecimal) row.get(0)).signum() == 0) {
            throw new AdempiereUserError("This payroll has no lines to post");
        }
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < cols.length; i++) {
            m.put(cols[i], ((BigDecimal) row.get(i)).setScale(2, RoundingMode.HALF_UP));
        }
        return m;
    }

    private record Entry(int account, BigDecimal dr, BigDecimal cr, String text) {
    }

    private static Entry dr(PO acct, String col, BigDecimal amt, String text) {
        return new Entry(account(acct, col), amt, Env.ZERO, text);
    }

    private static Entry cr(PO acct, String col, BigDecimal amt, String text) {
        return new Entry(account(acct, col), Env.ZERO, amt, text);
    }

    private static int account(PO acct, String col) {
        int id = acct.get_ValueAsInt(col);
        if (id <= 0) {
            throw new AdempiereUserError("Payroll Accounts (Kenya) has no account for " + col);
        }
        return id;
    }
}
