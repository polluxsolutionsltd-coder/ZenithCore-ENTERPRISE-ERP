package ke.co.pollux.zenithcore.payroll.idempiere.process;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

/**
 * Request Reopen (button on Payroll Run). The first half of reopening an approved payroll that is not
 * yet posted: records who asks for it and why. A different person must then run Reopen Payroll.
 */
@Process
public class PayrollRunRequestReopen extends SvrProcess {

    @Parameter(name = "ZC_ReopenReason")
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
        String status = run.get_ValueAsString("ZC_PayrollStatus");
        if (!"AP".equals(status)) {
            throw new AdempiereUserError("CO".equals(status)
                    ? "This payroll is posted. Use Request Reversal instead."
                    : "Only an approved payroll that is not yet posted can be reopened");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new AdempiereUserError("Give the reason for reopening (at least 10 characters)");
        }
        PayrollRunReverse.checkNoLaterPayroll(run, get_TrxName());
        PayrollRunCalculate.set(run, "ZC_ReopenReason", reason.trim());
        PayrollRunCalculate.set(run, "ZC_ReopenRequestedUser_ID", getAD_User_ID());
        run.saveEx();
        return "Reopening requested by " + MUser.getNameOfUser(getAD_User_ID())
                + ". A different person must now run Reopen Payroll.";
    }
}
