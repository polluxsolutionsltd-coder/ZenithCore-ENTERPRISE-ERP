package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.controls.AssetRegister;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;

/** Print Exceptions (button on Asset Verification). PDF. */
@Process
public class AssetVerificationExceptions extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        PO h = MTable.get(getCtx(), "ZC_AssetVerification").getPO(getRecord_ID(), get_TrxName());
        AssetPdf.Verification v = new AssetPdf.Verification(h, get_TrxName());
        File f = AssetPdf.file("asset-verification-exceptions-" + h.get_ValueAsString("Name") + ".pdf",
                AssetPdf.exceptions(v, get_TrxName()));
        if (processUI != null) {
            processUI.download(f);
        }
        return AssetRegister.summary(v.lines).exceptions() + " exception(s). " + f.getName();
    }
}
