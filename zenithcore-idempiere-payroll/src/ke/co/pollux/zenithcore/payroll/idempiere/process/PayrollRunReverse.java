package ke.co.pollux.zenithcore.payroll.idempiere.process;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MJournal;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.DocAction;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

/**
 * Reverse Payroll (button on Payroll Run). Reverses the GL journal of a completed payroll and marks the
 * payroll Reversed. Needs a reversal request from a different person. A payroll cannot be reversed
 * once a later payroll of the same organization is approved, because arrears have moved on to it;
 * reverse the later one first. A reversed payroll is no longer a source of arrears, and a corrected
 * payroll can be calculated for the same pay date.
 */
@Process
public class PayrollRunReverse extends SvrProcess {

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
        if (!"CO".equals(run.get_ValueAsString("ZC_PayrollStatus"))) {
            throw new AdempiereUserError("Only a completed (posted) payroll can be reversed");
        }
        int requestedBy = run.get_ValueAsInt("ZC_ReversalRequestedUser_ID");
        if (requestedBy <= 0) {
            throw new AdempiereUserError("Request the reversal first, with the reason");
        }
        if (requestedBy == getAD_User_ID()) {
            throw new AdempiereUserError("You requested this reversal. A different person must reverse the payroll.");
        }
        checkNoLaterPayroll(run, get_TrxName());

        MJournal journal = new MJournal(getCtx(), run.get_ValueAsInt("GL_Journal_ID"), get_TrxName());
        if (journal.get_ID() <= 0) {
            throw new AdempiereUserError("This payroll has no GL journal to reverse");
        }
        if (!journal.processIt(DocAction.ACTION_Reverse_Correct)) {
            throw new AdempiereUserError("GL journal " + journal.getDocumentNo() + " could not be reversed: "
                    + journal.getProcessMsg());
        }
        journal.saveEx();
        MJournal reversal = new MJournal(getCtx(), journal.getReversal_ID(), get_TrxName());

        PayrollRunCalculate.set(run, "ZC_PayrollStatus", "RV");
        PayrollRunCalculate.set(run, "ZC_ReversedUser_ID", getAD_User_ID());
        String desc = run.get_ValueAsString("Description");
        String note = (desc == null || desc.isEmpty() ? "" : desc + " | ") + "Reversed by GL journal " + reversal.getDocumentNo();
        PayrollRunCalculate.set(run, "Description", note.length() <= 255 ? note : note.substring(note.length() - 255));
        run.saveEx();
        addLog(reversal.getGL_Journal_ID(), reversal.getDateAcct(), null, "Reversal GL journal " + reversal.getDocumentNo(),
                reversal.get_Table_ID(), reversal.getGL_Journal_ID());
        return "Payroll reversed: GL journal " + journal.getDocumentNo() + " reversed by " + reversal.getDocumentNo()
                + ". Requested by " + MUser.getNameOfUser(requestedBy) + ", reversed by "
                + MUser.getNameOfUser(getAD_User_ID()) + ". Calculate a corrected payroll if needed.";
    }

    /** Refuses when a later payroll of the same organization is approved or completed. */
    static void checkNoLaterPayroll(PO run, String trx) {
        String later = DB.getSQLValueStringEx(trx,
                "SELECT MIN(Name) FROM ZC_PayrollRun WHERE AD_Client_ID=? AND AD_Org_ID=? AND ZC_PayrollRun_ID<>? "
                        + "AND ZC_PayrollStatus IN ('AP','CO') AND ZC_PayDate>?",
                run.getAD_Client_ID(), run.getAD_Org_ID(), run.get_ID(), run.get_Value("ZC_PayDate"));
        if (later != null) {
            throw new AdempiereUserError("A later payroll (" + later + ") is already approved. Reverse it first.");
        }
    }
}
