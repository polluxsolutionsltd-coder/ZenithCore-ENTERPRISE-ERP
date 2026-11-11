package ke.co.pollux.zenithcore.controls.idempiere.process;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

/**
 * Load Assets (button on Asset Verification). One line per active asset at the verification's location
 * (and group), with its tag, expected location and custodian. Replaces earlier lines while no result has
 * been recorded.
 */
@Process
public class AssetVerificationLoad extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO h = MTable.get(getCtx(), "ZC_AssetVerification").getPO(getRecord_ID(), trx);
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Save the verification first");
        }
        if ("CO".equals(h.get_ValueAsString("ZC_VerificationStatus"))) {
            throw new AdempiereUserError("This verification is completed");
        }
        int results = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM ZC_AssetVerificationLine WHERE ZC_AssetVerification_ID=? "
                + "AND ZC_VerifyResult IS NOT NULL", h.get_ID());
        if (results > 0) {
            throw new AdempiereUserError(results + " result(s) are already recorded; loading again would lose them");
        }
        DB.executeUpdateEx("DELETE FROM ZC_AssetVerificationLine WHERE ZC_AssetVerification_ID=?", new Object[] {h.get_ID()}, trx);
        int location = h.get_ValueAsInt("ZC_AssetLocation_ID"), group = h.get_ValueAsInt("A_Asset_Group_ID");
        int n = DB.executeUpdateEx("INSERT INTO ZC_AssetVerificationLine (ZC_AssetVerificationLine_ID, AD_Client_ID, AD_Org_ID, IsActive, "
                + "Created, CreatedBy, Updated, UpdatedBy, ZC_AssetVerificationLine_UU, ZC_AssetVerification_ID, Line, A_Asset_ID, ZC_TagNo, "
                + "ZC_ExpectedLocation, ZC_AssetCustodian_ID, Processed) "
                + "SELECT nextIDFunc((SELECT AD_Sequence_ID FROM AD_Sequence WHERE Name='ZC_AssetVerificationLine' AND IsTableID='Y')::int,'N'), "
                + "a.AD_Client_ID, ?, 'Y', now(), ?, now(), ?, gen_random_uuid(), ?, 10*ROW_NUMBER() OVER (ORDER BY l.Name NULLS LAST, "
                + "a.ZC_TagNo NULLS LAST, a.Value), a.A_Asset_ID, a.ZC_TagNo, l.Name, a.ZC_AssetCustodian_ID, 'N' FROM A_Asset a "
                + "LEFT JOIN ZC_AssetLocation l ON l.ZC_AssetLocation_ID=a.ZC_AssetLocation_ID WHERE a.AD_Client_ID=? AND a.IsActive='Y' "
                + "AND a.IsDisposed='N' AND (?=0 OR a.ZC_AssetLocation_ID=?) AND (?=0 OR a.A_Asset_Group_ID=?)",
                new Object[] {h.getAD_Org_ID(), getAD_User_ID(), getAD_User_ID(), h.get_ID(), h.getAD_Client_ID(), location, location,
                    group, group}, trx);
        int untagged = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM ZC_AssetVerificationLine WHERE ZC_AssetVerification_ID=? "
                + "AND ZC_TagNo IS NULL", h.get_ID());
        return n + " assets loaded" + (untagged > 0 ? "; " + untagged + " have no tag yet (run Assign Asset Tags)" : "")
                + ". Print the verification sheets.";
    }
}
