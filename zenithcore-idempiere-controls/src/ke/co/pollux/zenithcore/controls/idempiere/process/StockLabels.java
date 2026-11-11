package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MInOut;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Print Stock Labels (menu): one label per lot or serial number, from a goods received note or from the
 * stock on hand in a store. The label shows the product, lot or serial number in large type and the expiry
 * date; the QR code holds {@code P:<product code>|L:<lot>|S:<serial>|E:<yyyy-mm-dd>} (parts without a value
 * are left out), so a phone or scanner reads the same as the label.
 */
@Process
public class StockLabels extends SvrProcess {

    @Parameter(name = "M_InOut_ID")
    private int receipt;
    @Parameter(name = "M_Warehouse_ID")
    private int warehouse;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    /** What the QR code holds. */
    public static String qr(String product, String lot, String serial, Timestamp expiry) {
        StringBuilder sb = new StringBuilder("P:").append(product);
        if (lot != null && !lot.isBlank()) {
            sb.append("|L:").append(lot);
        }
        if (serial != null && !serial.isBlank()) {
            sb.append("|S:").append(serial);
        }
        if (expiry != null) {
            sb.append("|E:").append(expiry.toLocalDateTime().toLocalDate());
        }
        return sb.toString();
    }

    @Override
    protected String doIt() throws Exception {
        if ((receipt > 0) == (warehouse > 0)) {
            throw new AdempiereUserError("Choose either a goods received note or a store");
        }
        String sel = "SELECT p.Value, p.Name, asi.Lot, asi.SerNo, asi.GuaranteeDate";
        String sql = receipt > 0
                ? sel + ", io.DocumentNo, io.MovementDate FROM M_InOutLine l JOIN M_InOut io ON io.M_InOut_ID=l.M_InOut_ID "
                        + "JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID JOIN M_AttributeSetInstance asi ON "
                        + "asi.M_AttributeSetInstance_ID=l.M_AttributeSetInstance_ID WHERE l.M_InOut_ID=? AND l.M_AttributeSetInstance_ID>0 "
                        + "AND l.IsActive='Y' ORDER BY l.Line"
                : sel + ", NULL, NULL FROM M_StorageOnHand s JOIN M_Locator lo ON lo.M_Locator_ID=s.M_Locator_ID "
                        + "JOIN M_Product p ON p.M_Product_ID=s.M_Product_ID JOIN M_AttributeSetInstance asi ON "
                        + "asi.M_AttributeSetInstance_ID=s.M_AttributeSetInstance_ID WHERE lo.M_Warehouse_ID=? AND s.M_AttributeSetInstance_ID>0 "
                        + "GROUP BY p.Value, p.Name, asi.Lot, asi.SerNo, asi.GuaranteeDate HAVING SUM(s.QtyOnHand)>0 "
                        + "ORDER BY p.Value, asi.GuaranteeDate NULLS LAST, asi.Lot, asi.SerNo";
        List<AssetPdf.Label> labels = new ArrayList<>();
        String[] grn = new String[1];
        OpeningBalanceData.query(get_TrxName(), sql, rs -> {
            String lot = rs.getString(3), ser = rs.getString(4);
            Timestamp exp = rs.getTimestamp(5);
            if ((lot == null || lot.isBlank()) && (ser == null || ser.isBlank()) && exp == null) {
                return; // nothing to label beyond the product
            }
            String number = ser != null && !ser.isBlank() ? "SN " + ser : lot != null && !lot.isBlank() ? "LOT " + lot : "";
            String line = (exp == null ? "No expiry date" : "EXPIRES " + exp.toLocalDateTime().toLocalDate()) + "  |  " + rs.getString(1);
            labels.add(new AssetPdf.Label(number, rs.getString(2), line, qr(rs.getString(1), lot, ser, exp)));
            if (rs.getString(6) != null) {
                grn[0] = "Received on GRN " + rs.getString(6) + " of " + rs.getTimestamp(7).toLocalDateTime().toLocalDate();
            }
        }, receipt > 0 ? receipt : warehouse);
        String owner = grn[0] != null ? grn[0] : "Store stock";
        String name = receipt > 0 ? "stock-labels-" + new MInOut(getCtx(), receipt, get_TrxName()).getDocumentNo() + ".pdf" : "stock-labels.pdf";
        File f = AssetPdf.file(name, AssetPdf.labels(MClient.get(getCtx()).getName(), owner, labels));
        if (processUI != null) {
            processUI.download(f);
        }
        return labels.size() + " label(s) on " + Math.max(1, (labels.size() + 23) / 24) + " sheet(s). " + f.getName();
    }
}
