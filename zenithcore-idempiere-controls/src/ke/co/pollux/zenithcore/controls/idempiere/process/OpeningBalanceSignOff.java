package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.sql.Timestamp;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.OpeningBalance;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Sign Off (button on Opening Balances). Runs the checks again; if they pass and the user did not prepare
 * the opening balances, records who signed off and when, and locks the header, lines and items.
 */
@Process
public class OpeningBalanceSignOff extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO h = MTable.get(getCtx(), "ZC_OpeningBalance").getPO(getRecord_ID(), trx);
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Open an opening balance first");
        }
        OpeningBalanceData.requireOpen(h);
        OpeningBalanceData data = new OpeningBalanceData(h, trx);
        OpeningBalance.Result r = data.check();
        data.writeResults(r, trx);
        ControlResult c = OpeningBalance.signOff(r, h.getCreatedBy(), getAD_User_ID());
        if (!c.allowed()) {
            h.set_ValueNoCheck("ZC_OBStatus", r.ok() ? "RC" : "DR");
            h.saveEx();
            commitEx();
            throw new AdempiereUserError(c.message());
        }
        for (String t : new String[] {"ZC_OpeningBalanceLine", "ZC_OpeningItem"}) {
            DB.executeUpdateEx("UPDATE " + t + " SET Processed='Y' WHERE ZC_OpeningBalance_ID=?", new Object[] {h.get_ID()}, trx);
        }
        h.set_ValueNoCheck("ZC_OBStatus", "SO");
        h.set_ValueNoCheck("ZC_SignedOffUser_ID", getAD_User_ID());
        h.set_ValueNoCheck("ZC_DateSignedOff", new Timestamp(System.currentTimeMillis()));
        h.set_ValueNoCheck("Processed", Boolean.TRUE);
        h.saveEx();
        return "Signed off: debits and credits " + OpeningBalanceCheck.money(r.debits()) + ". The opening balances are locked "
                + "and can now be posted.";
    }
}
