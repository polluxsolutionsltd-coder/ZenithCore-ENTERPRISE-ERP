package ke.co.pollux.zenithcore.controls.idempiere.process;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MProduct;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

/**
 * Load Receipt Lines (button on Goods Inspection). Adds one line per stock item on the goods received note,
 * with the quantity received; accepted and rejected start at zero. Lines already loaded are not repeated.
 */
@Process
public class InspectionLoad extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current inspection
    }

    @Override
    protected String doIt() throws Exception {
        PO insp = MTable.get(getCtx(), "ZC_Inspection").getPO(getRecord_ID(), get_TrxName());
        if (insp == null || insp.get_ID() <= 0) {
            throw new AdempiereUserError("Open an inspection first");
        }
        if (insp.get_ValueAsBoolean("Processed")) {
            throw new AdempiereUserError("This inspection is completed");
        }
        MInOut receipt = new MInOut(getCtx(), insp.get_ValueAsInt("M_InOut_ID"), get_TrxName());
        int added = 0;
        for (MInOutLine rl : receipt.getLines(true)) {
            if (rl.getM_Product_ID() <= 0 || !MProduct.get(rl.getM_Product_ID()).isStocked()) {
                continue;
            }
            int exists = DB.getSQLValueEx(get_TrxName(),
                    "SELECT COUNT(*) FROM ZC_InspectionLine WHERE ZC_Inspection_ID=? AND M_InOutLine_ID=?", insp.get_ID(),
                    rl.getM_InOutLine_ID());
            if (exists > 0) {
                continue;
            }
            PO l = MTable.get(getCtx(), "ZC_InspectionLine").getPO(0, get_TrxName());
            l.setAD_Org_ID(insp.getAD_Org_ID());
            l.set_ValueNoCheck("ZC_Inspection_ID", insp.get_ID());
            l.set_ValueNoCheck("M_InOutLine_ID", rl.getM_InOutLine_ID());
            l.set_ValueNoCheck("M_Product_ID", rl.getM_Product_ID());
            l.set_ValueNoCheck("ZC_QtyReceived", rl.getMovementQty());
            l.saveEx();
            added++;
        }
        return added + " line" + (added == 1 ? "" : "s") + " loaded from goods received note " + receipt.getDocumentNo()
                + ". Enter the accepted and rejected quantities.";
    }
}
