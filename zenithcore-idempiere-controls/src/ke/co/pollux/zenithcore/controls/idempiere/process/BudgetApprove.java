package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.sql.Timestamp;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.SecondPersonApproval;

/**
 * Approve Budget (button on Budgets). An approved budget counts in budget checks and is locked, lines
 * included. The person who prepared the budget cannot approve it, and an empty budget cannot be approved.
 */
@Process
public class BudgetApprove extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current budget
    }

    @Override
    protected String doIt() throws Exception {
        PO b = MTable.get(getCtx(), "ZC_Budget").getPO(getRecord_ID(), get_TrxName());
        if (b == null || b.get_ID() <= 0) {
            throw new AdempiereUserError("Open a budget first");
        }
        if (b.get_ValueAsBoolean("Processed")) {
            throw new AdempiereUserError("This budget is already approved");
        }
        BigDecimal total = DB.getSQLValueBDEx(get_TrxName(),
                "SELECT COALESCE(SUM(ZC_Amount),0) FROM ZC_BudgetLine WHERE ZC_Budget_ID=? AND IsActive='Y'", b.get_ID());
        int lines = DB.getSQLValueEx(get_TrxName(), "SELECT COUNT(*) FROM ZC_BudgetLine WHERE ZC_Budget_ID=? AND IsActive='Y'",
                b.get_ID());
        if (lines == 0) {
            throw new AdempiereUserError("Add budget lines before approving the budget");
        }
        ControlResult r = SecondPersonApproval.check("budget " + b.get_ValueAsString("Name"), b.getCreatedBy(),
                getAD_User_ID(), total, BigDecimal.ZERO);
        if (!r.allowed()) {
            throw new AdempiereUserError(r.message());
        }
        b.set_ValueNoCheck("ZC_ApprovedUser_ID", getAD_User_ID());
        b.set_ValueNoCheck("ZC_DateApproved", new Timestamp(System.currentTimeMillis()));
        b.set_ValueNoCheck("Processed", Boolean.TRUE);
        b.saveEx();
        DB.executeUpdateEx("UPDATE ZC_BudgetLine SET Processed='Y' WHERE ZC_Budget_ID=?", new Object[] {b.get_ID()},
                get_TrxName());
        return "Budget approved: " + lines + " lines, " + String.format(java.util.Locale.ROOT, "%,.2f", total)
                + ". Approved by " + MUser.getNameOfUser(getAD_User_ID()) + ".";
    }
}
