package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MDocType;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MLocator;
import org.compiere.model.MMovement;
import org.compiere.model.MMovementLine;
import org.compiere.model.MProduct;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.MWarehouse;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.GoodsInspection;
import ke.co.pollux.zenithcore.controls.StoresControl;

/**
 * Complete Inspection (button on Goods Inspection). Checks the inspection (every unit accepted or
 * rejected, reasons for rejections, an inspector who is neither the receiver nor a custodian), then
 * creates a draft stock movement from the quarantine location: accepted quantities to the store's normal
 * location, rejected quantities to its rejected location. The store keeper completes the movement when
 * the goods are put away. The inspection is locked.
 */
@Process
public class InspectionComplete extends SvrProcess {

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
            throw new AdempiereUserError("This inspection is already completed");
        }
        MInOut receipt = new MInOut(getCtx(), insp.get_ValueAsInt("M_InOut_ID"), get_TrxName());
        if (!MInOut.DOCSTATUS_Completed.equals(receipt.getDocStatus()) && !MInOut.DOCSTATUS_Closed.equals(receipt.getDocStatus())) {
            throw new AdempiereUserError("Goods received note " + receipt.getDocumentNo() + " is not completed");
        }
        int others = DB.getSQLValueEx(get_TrxName(), "SELECT COUNT(*) FROM ZC_Inspection WHERE M_InOut_ID=? AND Processed='Y' "
                + "AND ZC_Inspection_ID<>?", receipt.getM_InOut_ID(), insp.get_ID());
        if (others > 0) {
            throw new AdempiereUserError("Goods received note " + receipt.getDocumentNo() + " has already been inspected");
        }
        MWarehouse w = MWarehouse.get(receipt.getM_Warehouse_ID());
        StoresControl.Store store = new StoresControl.Store(w.getName(), w.get_ValueAsInt("ZC_CustodianUser_ID"),
                w.get_ValueAsInt("ZC_DeputyCustodianUser_ID"));
        List<PO> lines = new Query(getCtx(), "ZC_InspectionLine", "ZC_Inspection_ID=?", get_TrxName())
                .setParameters(insp.get_ID()).setOrderBy("ZC_InspectionLine_ID").list();
        List<GoodsInspection.InspectedLine> check = new ArrayList<>();
        for (PO l : lines) {
            MInOutLine rl = new MInOutLine(getCtx(), l.get_ValueAsInt("M_InOutLine_ID"), get_TrxName());
            check.add(new GoodsInspection.InspectedLine(rl.getLine(), MProduct.get(l.get_ValueAsInt("M_Product_ID")).getName(),
                    (BigDecimal) l.get_Value("ZC_QtyReceived"), (BigDecimal) l.get_Value("ZC_QtyAccepted"),
                    (BigDecimal) l.get_Value("ZC_QtyRejected"), l.get_ValueAsString("ZC_RejectReason")));
        }
        ControlResult r = GoodsInspection.complete("Inspection of " + receipt.getDocumentNo(), check, getAD_User_ID(),
                receipt.getCreatedBy(), store);
        if (!r.allowed()) {
            throw new AdempiereUserError(r.message());
        }

        int normal = locator(w, "N"), rejected = locator(w, "R");
        MMovement mv = new MMovement(getCtx(), 0, get_TrxName());
        mv.setAD_Org_ID(receipt.getAD_Org_ID());
        mv.setC_DocType_ID(MDocType.getOfDocBaseType(getCtx(), MDocType.DOCBASETYPE_MaterialMovement)[0].getC_DocType_ID());
        mv.setMovementDate(new Timestamp(System.currentTimeMillis()));
        mv.setDescription("Put-away after inspection of goods received note " + receipt.getDocumentNo());
        mv.set_ValueNoCheck("ZC_Inspection_ID", insp.get_ID());
        mv.saveEx();
        int lineNo = 0;
        BigDecimal acc = BigDecimal.ZERO, rej = BigDecimal.ZERO;
        for (PO l : lines) {
            MInOutLine rl = new MInOutLine(getCtx(), l.get_ValueAsInt("M_InOutLine_ID"), get_TrxName());
            BigDecimal a = (BigDecimal) l.get_Value("ZC_QtyAccepted"), x = (BigDecimal) l.get_Value("ZC_QtyRejected");
            if (a.signum() > 0) {
                if (normal <= 0) {
                    throw new AdempiereUserError(w.getName() + " has no normal location to put accepted goods away");
                }
                line(mv, lineNo += 10, rl, normal, a);
                acc = acc.add(a);
            }
            if (x.signum() > 0) {
                if (rejected <= 0) {
                    throw new AdempiereUserError(w.getName() + " has no rejected location; set one up in Warehouse and Locators");
                }
                line(mv, lineNo += 10, rl, rejected, x);
                rej = rej.add(x);
            }
            l.set_ValueNoCheck("Processed", Boolean.TRUE);
            l.saveEx();
        }
        insp.set_ValueNoCheck("ZC_InspectionStatus", "CO");
        insp.set_ValueNoCheck("ZC_InspectorUser_ID", getAD_User_ID());
        insp.set_ValueNoCheck("ZC_DateInspected", new Timestamp(System.currentTimeMillis()));
        insp.set_ValueNoCheck("M_Movement_ID", mv.getM_Movement_ID());
        insp.set_ValueNoCheck("Processed", Boolean.TRUE);
        insp.saveEx();
        return "Inspection completed by " + MUser.getNameOfUser(getAD_User_ID()) + ": accepted "
                + acc.stripTrailingZeros().toPlainString() + ", rejected " + rej.stripTrailingZeros().toPlainString()
                + ". Put-away movement " + mv.getDocumentNo() + " is ready for the store keeper to complete.";
    }

    private void line(MMovement mv, int lineNo, MInOutLine rl, int to, BigDecimal qty) {
        MMovementLine ml = new MMovementLine(mv);
        ml.setLine(lineNo);
        ml.setM_Product_ID(rl.getM_Product_ID());
        ml.setM_AttributeSetInstance_ID(rl.getM_AttributeSetInstance_ID());
        ml.setM_AttributeSetInstanceTo_ID(rl.getM_AttributeSetInstance_ID());
        ml.setM_Locator_ID(rl.getM_Locator_ID());
        ml.setM_LocatorTo_ID(to);
        ml.setMovementQty(qty);
        ml.saveEx();
    }

    /** The store's default location of the given use, else the first one. */
    private int locator(MWarehouse w, String use) {
        MLocator d = w.getDefaultLocator();
        if (d != null && use.equals(nz(d.get_ValueAsString("ZC_LocatorUse")))) {
            return d.getM_Locator_ID();
        }
        return DB.getSQLValueEx(get_TrxName(), "SELECT MIN(M_Locator_ID) FROM M_Locator WHERE M_Warehouse_ID=? AND IsActive='Y' "
                + "AND COALESCE(ZC_LocatorUse,'N')=?", w.getM_Warehouse_ID(), use);
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "N" : s;
    }
}
