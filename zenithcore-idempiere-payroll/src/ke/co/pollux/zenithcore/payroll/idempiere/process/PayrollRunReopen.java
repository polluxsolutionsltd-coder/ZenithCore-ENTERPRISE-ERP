package ke.co.pollux.zenithcore.payroll.idempiere.process;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

/**
 * Reopen Payroll (button on Payroll Run). Returns an approved payroll that is not yet posted to Drafted,
 * unlocked, when a different person has requested it with a reason. The approval is cleared, so the
 * payroll must be calculated and approved again. The request is recorded in the description, and the
 * request fields are cleared so a later reopening needs a new request.
 */
@Process
public class PayrollRunReopen extends SvrProcess {

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
        if (!"AP".equals(run.get_ValueAsString("ZC_PayrollStatus"))) {
            throw new AdempiereUserError("Only an approved payroll that is not yet posted can be reopened");
        }
        int requestedBy = run.get_ValueAsInt("ZC_ReopenRequestedUser_ID");
        if (requestedBy <= 0) {
            throw new AdempiereUserError("Request the reopening first, with the reason");
        }
        if (requestedBy == getAD_User_ID()) {
            throw new AdempiereUserError("You requested this. A different person must reopen the payroll.");
        }
        PayrollRunReverse.checkNoLaterPayroll(run, get_TrxName());

        String reason = run.get_ValueAsString("ZC_ReopenReason");
        String desc = run.get_ValueAsString("Description");
        String note = (desc == null || desc.isEmpty() ? "" : desc + " | ") + "Reopened by "
                + MUser.getNameOfUser(getAD_User_ID()) + " at the request of " + MUser.getNameOfUser(requestedBy) + ": "
                + reason;
        PayrollRunCalculate.set(run, "Description", note.length() <= 255 ? note : note.substring(note.length() - 255));
        PayrollRunCalculate.set(run, "ZC_PayrollStatus", "DR");
        PayrollRunCalculate.set(run, "ZC_ApprovedUser_ID", null);
        PayrollRunCalculate.set(run, "ZC_ReopenRequestedUser_ID", null);
        PayrollRunCalculate.set(run, "ZC_ReopenReason", null);
        PayrollRunCalculate.set(run, "Processed", Boolean.FALSE);
        run.saveEx();
        addLog("Reopened. Reason: " + reason);
        return "Payroll reopened at the request of " + MUser.getNameOfUser(requestedBy)
                + ". Correct the pay data, then calculate and approve it again.";
    }
}
