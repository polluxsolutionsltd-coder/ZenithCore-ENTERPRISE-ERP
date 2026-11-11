package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.sql.Timestamp;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAttachment;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.AuditQueryClosure;
import ke.co.pollux.zenithcore.controls.ControlResult;

/**
 * Close Audit Query (button on Audit Query Register). Needs a management response, at least one
 * evidence file attached to the query, and a closer who is not the person responsible for it. Closing
 * locks the record.
 */
@Process
public class AuditQueryClose extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current audit query
    }

    @Override
    protected String doIt() throws Exception {
        PO q = MTable.get(getCtx(), "ZC_AuditQuery").getPO(getRecord_ID(), get_TrxName());
        if (q == null || q.get_ID() <= 0) {
            throw new AdempiereUserError("Open an audit query first");
        }
        if ("CL".equals(q.get_ValueAsString("ZC_AuditQueryStatus"))) {
            throw new AdempiereUserError("This audit query is already closed");
        }
        MAttachment att = MAttachment.get(getCtx(), q.get_Table_ID(), q.get_ID(), q.get_UUID(), get_TrxName());
        int files = att == null ? 0 : att.getEntryCount();
        String ref = q.get_ValueAsString("Value");
        ControlResult r = AuditQueryClosure.check(ref, q.get_ValueAsString("ZC_ManagementResponse"), files,
                q.get_ValueAsInt("ZC_ResponsibleUser_ID"), getAD_User_ID());
        if (!r.allowed()) {
            throw new AdempiereUserError(r.message());
        }
        q.set_ValueNoCheck("ZC_AuditQueryStatus", "CL");
        q.set_ValueNoCheck("ZC_ClosedUser_ID", getAD_User_ID());
        q.set_ValueNoCheck("ZC_DateClosed", new Timestamp(System.currentTimeMillis()));
        q.set_ValueNoCheck("Processed", Boolean.TRUE);
        q.saveEx();
        return "Audit query " + ref + " closed by " + MUser.getNameOfUser(getAD_User_ID()) + " with " + files
                + " evidence file" + (files == 1 ? "" : "s") + ".";
    }
}
