package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.AssetRegister;
import ke.co.pollux.zenithcore.controls.AssetRegister.Line;
import ke.co.pollux.zenithcore.controls.AssetRegister.Result;
import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;

/**
 * Complete Verification (button on Asset Verification). Checks every line; then for each asset seen
 * records its condition and the verification date, and for each found elsewhere moves it in the register
 * to where it was found. Assets not found stay where they were, for investigation. Locks the verification
 * and prints the exceptions.
 */
@Process
public class AssetVerificationComplete extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO h = MTable.get(getCtx(), "ZC_AssetVerification").getPO(getRecord_ID(), trx);
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Open a verification first");
        }
        if ("CO".equals(h.get_ValueAsString("ZC_VerificationStatus"))) {
            throw new AdempiereUserError("This verification is already completed");
        }
        AssetPdf.Verification v = new AssetPdf.Verification(h, trx);
        ControlResult r = AssetRegister.complete(v.lines, h.get_ValueAsInt("ZC_VerifierUser_ID"),
                MSysConfig.getValue("ZC_ASSET_TAG_PREFIX", "ZC-", h.getAD_Client_ID()));
        if (!r.allowed()) {
            throw new AdempiereUserError(r.message());
        }
        int moved = 0;
        for (Line l : v.lines) {
            int asset = v.assets.get(l.lineNo());
            if (asset <= 0 || l.result() == Result.NOT_FOUND) {
                continue;
            }
            DB.executeUpdateEx("UPDATE A_Asset SET ZC_Condition=?, ZC_DateVerified=?, Updated=now(), UpdatedBy=? WHERE A_Asset_ID=?",
                    new Object[] {l.condition().code, h.get_Value("DateDoc"), getAD_User_ID(), asset}, trx);
            if (l.result() == Result.FOUND_ELSEWHERE) {
                DB.executeUpdateEx("UPDATE A_Asset SET ZC_AssetLocation_ID=? WHERE A_Asset_ID=?",
                        new Object[] {v.foundAt.get(l.lineNo()), asset}, trx);
                moved++;
            }
        }
        DB.executeUpdateEx("UPDATE ZC_AssetVerificationLine SET Processed='Y' WHERE ZC_AssetVerification_ID=?",
                new Object[] {h.get_ID()}, trx);
        h.set_ValueNoCheck("ZC_VerificationStatus", "CO");
        h.set_ValueNoCheck("Processed", Boolean.TRUE);
        h.saveEx();
        AssetRegister.Summary s = AssetRegister.summary(v.lines);
        File f = AssetPdf.file("asset-verification-exceptions-" + h.get_ValueAsString("Name") + ".pdf", AssetPdf.exceptions(v, trx));
        if (processUI != null) {
            processUI.download(f);
        }
        return "Completed: " + s.byResult().get(Result.FOUND) + " found, " + moved + " moved in the register, "
                + s.notFound().size() + " not found, " + s.unrecorded().size() + " not in the register, " + s.forSurvey().size()
                + " for the board of survey. " + f.getName();
    }
}
