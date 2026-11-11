package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.idempiere.BoardPackReport;

/**
 * Approve Board Pack (button on Board Packs). The approver is not the person who generated the latest
 * version. The approved copy is that exact version with an approval stamp on every page, so what the
 * board receives is what was reviewed. The pack is then locked.
 */
@Process
public class BoardPackApprove extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO pack = MTable.get(getCtx(), "ZC_BoardPack").getPO(getRecord_ID(), trx);
        if (pack == null || pack.get_ID() <= 0) {
            throw new AdempiereUserError("Open a board pack first");
        }
        String status = pack.get_ValueAsString("ZC_BoardPackStatus");
        if ("AP".equals(status)) {
            throw new AdempiereUserError("This board pack is already approved");
        }
        if (!"GE".equals(status)) {
            throw new AdempiereUserError("Generate the board pack first");
        }
        if (pack.get_ValueAsInt("ZC_PreparedUser_ID") == getAD_User_ID()) {
            throw new AdempiereUserError("You generated this version, so someone else must approve it");
        }
        int version = pack.get_ValueAsInt("ZC_Version");
        MAttachment att = MAttachment.get(getCtx(), pack.get_Table_ID(), pack.get_ID(), trx);
        MAttachmentEntry latest = null;
        if (att != null) {
            for (MAttachmentEntry e : att.getEntries()) {
                if (e.getName().endsWith("-v" + version + ".pdf")) {
                    latest = e;
                }
            }
        }
        if (latest == null) {
            throw new AdempiereUserError("Version " + version + " is no longer attached; generate the board pack again");
        }
        String who = BoardPackReport.userName(getAD_User_ID());
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        byte[] approved = BoardPackReport.stamp(latest.getData(), "APPROVED FOR RELEASE: " + who + ", " + when + " (version "
                + version + ")");
        String name = latest.getName().replace(".pdf", "-approved.pdf");
        att.addEntry(name, approved);
        att.saveEx();
        pack.set_ValueNoCheck("ZC_BoardPackStatus", "AP");
        pack.set_ValueNoCheck("ZC_ApprovedUser_ID", getAD_User_ID());
        pack.set_ValueNoCheck("ZC_DateApproved", new Timestamp(System.currentTimeMillis()));
        pack.set_ValueNoCheck("Processed", Boolean.TRUE);
        pack.saveEx();
        File file = new File(System.getProperty("java.io.tmpdir"), name);
        Files.write(file.toPath(), approved);
        if (processUI != null) {
            processUI.download(file);
        }
        return "Version " + version + " approved for release and locked. " + name;
    }
}
