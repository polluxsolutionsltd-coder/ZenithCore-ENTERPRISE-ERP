package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MInventory;
import org.compiere.model.MInventoryLine;
import org.compiere.model.MProduct;
import org.compiere.model.MStorageOnHand;
import org.compiere.model.MSysConfig;
import org.compiere.model.MWarehouse;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.BlindCount;
import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.StoresControl;

/**
 * Submit Count (button on Blind Stock Count). Every line must be counted, and when blind counting is on,
 * not by the store's custodians. Then, for each line, the book quantity is taken from stock at this
 * moment and the counted quantity becomes the count. The counters can no longer change the lines; the
 * custodian gives reasons for differences and the count is posted under the stores controls.
 */
@Process
public class CountSubmit extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current count
    }

    @Override
    protected String doIt() throws Exception {
        MInventory inv = new MInventory(getCtx(), getRecord_ID(), get_TrxName());
        if (inv.get_ID() <= 0) {
            throw new AdempiereUserError("Open a count first");
        }
        if ("S".equals(inv.get_ValueAsString("ZC_CountStatus"))) {
            throw new AdempiereUserError("This count is already submitted");
        }
        if (inv.isProcessed()) {
            throw new AdempiereUserError("This count is already posted");
        }
        MWarehouse w = MWarehouse.get(inv.getM_Warehouse_ID());
        StoresControl.Store store = new StoresControl.Store(w.getName(), w.get_ValueAsInt("ZC_CustodianUser_ID"),
                w.get_ValueAsInt("ZC_DeputyCustodianUser_ID"));
        MInventoryLine[] lines = inv.getLines(true);
        List<BlindCount.CountLine> check = new ArrayList<>();
        for (MInventoryLine l : lines) {
            check.add(new BlindCount.CountLine(l.getLine(), MProduct.get(l.getM_Product_ID()).getName(),
                    (BigDecimal) l.get_Value("ZC_QtyCounted"), l.get_ValueAsInt("ZC_CountedUser_ID")));
        }
        ControlResult r = BlindCount.submit("Count " + inv.getDocumentNo(), store, check,
                MSysConfig.getBooleanValue("ZC_BLIND_COUNT", false, inv.getAD_Client_ID()));
        if (!r.allowed()) {
            throw new AdempiereUserError(r.message());
        }
        int differences = 0;
        for (MInventoryLine l : lines) {
            BigDecimal book = MStorageOnHand.getQtyOnHandForLocator(l.getM_Product_ID(), l.getM_Locator_ID(),
                    l.getM_AttributeSetInstance_ID(), get_TrxName());
            BigDecimal counted = (BigDecimal) l.get_Value("ZC_QtyCounted");
            l.setQtyBook(book == null ? BigDecimal.ZERO : book);
            l.setQtyCount(counted);
            l.setQtyEntered(counted);
            l.saveEx();
            if (l.getQtyBook().compareTo(counted) != 0) {
                differences++;
            }
        }
        inv.set_ValueNoCheck("ZC_CountStatus", "S");
        inv.saveEx();
        return "Count " + inv.getDocumentNo() + " submitted: " + lines.length + " lines, " + differences
                + " with a difference. The custodian now gives a reason for each difference and posts the count.";
    }
}
