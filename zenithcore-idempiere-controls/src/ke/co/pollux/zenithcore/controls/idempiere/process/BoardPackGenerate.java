package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAttachment;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.idempiere.BoardPackReport;

/**
 * Generate Board Pack (button on Board Packs). Builds the pack for the period from the ledger and related
 * records, attaches it to the record as a new version (earlier versions stay attached) and downloads it.
 * An approved pack cannot be generated again.
 */
@Process
public class BoardPackGenerate extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO pack = MTable.get(getCtx(), "ZC_BoardPack").getPO(getRecord_ID(), trx);
        if (pack == null || pack.get_ID() <= 0) {
            throw new AdempiereUserError("Save the board pack first");
        }
        if ("AP".equals(pack.get_ValueAsString("ZC_BoardPackStatus"))) {
            throw new AdempiereUserError("This board pack is approved; create a new one to report again");
        }
        int version = pack.get_ValueAsInt("ZC_Version") + 1;
        BoardPackReport report = new BoardPackReport(pack, trx);
        byte[] pdf = report.render(version, BoardPackReport.userName(getAD_User_ID()));
        String name = "board-pack-" + report.periodName.replaceAll("[^A-Za-z0-9]+", "-") + "-v" + version + ".pdf";
        MAttachment att = MAttachment.get(getCtx(), pack.get_Table_ID(), pack.get_ID(), trx);
        if (att == null) {
            att = new MAttachment(getCtx(), pack.get_Table_ID(), pack.get_ID(), pack.get_UUID(), trx);
        }
        att.addEntry(name, pdf);
        att.saveEx();
        pack.set_ValueNoCheck("ZC_Version", version);
        pack.set_ValueNoCheck("ZC_BoardPackStatus", "GE");
        pack.set_ValueNoCheck("ZC_PreparedUser_ID", getAD_User_ID());
        pack.set_ValueNoCheck("ZC_DateGenerated", new Timestamp(System.currentTimeMillis()));
        pack.saveEx();
        File file = new File(System.getProperty("java.io.tmpdir"), name);
        Files.write(file.toPath(), pdf);
        if (processUI != null) {
            processUI.download(file);
        }
        String warn = report.position.balances() ? "" : " Warning: the ledger does not balance at the period end.";
        return "Version " + version + " attached: surplus to date " + BoardPackReport.money(report.key.surplusYtd()) + ", cash "
                + BoardPackReport.money(report.cash) + "." + warn + " " + name;
    }
}
