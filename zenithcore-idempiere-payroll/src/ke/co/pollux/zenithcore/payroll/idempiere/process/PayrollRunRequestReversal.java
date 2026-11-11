package ke.co.pollux.zenithcore.payroll.idempiere.process;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

/**
 * Request Reversal (button on Payroll Run). The first half of reversing a completed payroll: records
 * who asks for it and why. A different person must then run Reverse Payroll.
 */
@Process
public class PayrollRunRequestReversal extends SvrProcess {

    @Parameter(name = "ZC_ReversalReason")
    private String reason;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
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
        if (reason == null || reason.trim().length() < 10) {
            throw new AdempiereUserError("Give the reason for the reversal (at least 10 characters)");
        }
        PayrollRunReverse.checkNoLaterPayroll(run, get_TrxName());
        PayrollRunCalculate.set(run, "ZC_ReversalReason", reason.trim());
        PayrollRunCalculate.set(run, "ZC_ReversalRequestedUser_ID", getAD_User_ID());
        run.saveEx();
        return "Reversal requested by " + MUser.getNameOfUser(getAD_User_ID())
                + ". A different person must now run Reverse Payroll.";
    }
}
