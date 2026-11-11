package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.sql.Timestamp;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

/**
 * Approve Payroll (button on Payroll Run). Only a calculated payroll can be approved; the person
 * who calculated it cannot approve it; and it cannot be approved if any included employee's pay
 * data changed after the calculation.
 */
@Process
public class PayrollRunApprove extends SvrProcess {

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
        if (!"CA".equals(status)) {
            throw new AdempiereUserError("DR".equals(status) ? "Calculate the payroll before approving it"
                    : "This payroll is already " + ("AP".equals(status) ? "approved" : "completed"));
        }
        int calculatedBy = run.get_ValueAsInt("ZC_CalculatedUser_ID");
        if (calculatedBy == getAD_User_ID()) {
            throw new AdempiereUserError("You calculated this payroll. A different person must approve it.");
        }
        Timestamp changed = DB.getSQLValueTSEx(get_TrxName(),
                "SELECT MAX(e.Updated) FROM ZC_EmployeePay e JOIN ZC_PayrollLine l ON l.ZC_EmployeePay_ID=e.ZC_EmployeePay_ID "
                        + "WHERE l.ZC_PayrollRun_ID=? AND e.Updated > l.Created", run.get_ID());
        if (changed != null) {
            throw new AdempiereUserError("Employee pay data changed after this payroll was calculated. Recalculate it first.");
        }
        PayrollRunCalculate.set(run, "ZC_PayrollStatus", "AP");
        PayrollRunCalculate.set(run, "ZC_ApprovedUser_ID", getAD_User_ID());
        PayrollRunCalculate.set(run, "Processed", Boolean.TRUE);
        run.saveEx();
        return "Payroll approved. Calculated by " + MUser.getNameOfUser(calculatedBy) + ", approved by "
                + MUser.getNameOfUser(getAD_User_ID()) + ".";
    }
}
