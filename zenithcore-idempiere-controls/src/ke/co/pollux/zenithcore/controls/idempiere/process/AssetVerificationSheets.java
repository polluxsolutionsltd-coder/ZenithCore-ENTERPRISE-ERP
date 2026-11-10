package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;

/** Print Verification Sheets (button on Asset Verification). PDF. */
@Process
public class AssetVerificationSheets extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        PO h = MTable.get(getCtx(), "ZC_AssetVerification").getPO(getRecord_ID(), get_TrxName());
        AssetPdf.Verification v = new AssetPdf.Verification(h, get_TrxName());
        File f = AssetPdf.file("asset-verification-sheets-" + h.get_ValueAsString("Name") + ".pdf", AssetPdf.sheets(v, get_TrxName()));
        if (processUI != null) {
            processUI.download(f);
        }
        return v.lines.size() + " assets on the sheets. " + f.getName();
    }
}
