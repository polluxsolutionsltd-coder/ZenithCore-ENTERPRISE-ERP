package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.adempiere.util.IProcessUI;
import org.compiere.model.MAttachment;
import org.compiere.model.MBPartner;
import org.compiere.model.MOrg;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.Env;

/**
 * Shared loading and delivery for payroll outputs (payslips, statutory schedules). Outputs are only
 * produced for an approved or completed run, from the lines as calculated.
 */
final class PayrollOutput {

    private PayrollOutput() {
    }

    /** A payroll line with the employee's number and name. */
    record Line(PO po, String employeeNo, String name) {
        BigDecimal amt(String column) {
            Object v = po.get_Value(column);
            return v == null ? Env.ZERO : ((BigDecimal) v).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        String text(String column) {
            String v = po.get_ValueAsString(column);
            return v == null ? "" : v;
        }
    }

    static PO approvedRun(java.util.Properties ctx, int runId, String trx) {
        PO run = MTable.get(ctx, PayrollRunCalculate.RUN).getPO(runId, trx);
        if (run == null || run.get_ID() <= 0) {
            throw new AdempiereUserError("Open a payroll run first");
        }
        String status = run.get_ValueAsString("ZC_PayrollStatus");
        if (!"AP".equals(status) && !"CO".equals(status)) {
            throw new AdempiereUserError("Payslips and schedules are produced only for an approved or completed payroll");
        }
        return run;
    }

    static List<Line> lines(java.util.Properties ctx, PO run, String trx) {
        List<PO> pos = new Query(ctx, PayrollRunCalculate.LINE, "ZC_PayrollRun_ID=?", trx)
                .setParameters(run.get_ID()).list();
        if (pos.isEmpty()) {
            throw new AdempiereUserError("This payroll has no lines");
        }
        return pos.stream().map(p -> {
            MBPartner bp = MBPartner.get(ctx, p.get_ValueAsInt("C_BPartner_ID"));
            return new Line(p, bp.getValue(), bp.getName());
        }).sorted((a, b) -> a.employeeNo().compareTo(b.employeeNo())).toList();
    }

    static String employerName(java.util.Properties ctx, PO run) {
        return run.getAD_Org_ID() > 0 ? MOrg.get(ctx, run.getAD_Org_ID()).getName()
                : org.compiere.model.MClient.get(ctx, run.getAD_Client_ID()).getName();
    }

    /** ISO code of the client's primary accounting currency, for example KES. */
    static String currency(java.util.Properties ctx, PO run) {
        return org.compiere.model.MClientInfo.get(ctx, run.getAD_Client_ID()).getMAcctSchema1().getC_Currency().getISO_Code();
    }

    static String period(PO run) {
        Timestamp pay = (Timestamp) run.get_Value("ZC_PayDate");
        return pay.toLocalDateTime().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
    }

    static String periodKey(PO run) {
        Timestamp pay = (Timestamp) run.get_Value("ZC_PayDate");
        return pay.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** Attaches the file to the run (replacing an earlier file of the same name) and offers it for download. */
    static void deliver(java.util.Properties ctx, PO run, File file, IProcessUI ui, String trx) {
        MAttachment att = MAttachment.get(ctx, run.get_Table_ID(), run.get_ID(), trx);
        if (att == null) {
            att = new MAttachment(ctx, run.get_Table_ID(), run.get_ID(), run.get_UUID(), trx);
        }
        for (int i = att.getEntryCount() - 1; i >= 0; i--) {
            if (file.getName().equals(att.getEntryName(i))) {
                att.deleteEntry(i);
            }
        }
        att.addEntry(file);
        att.saveEx();
        if (ui != null) {
            ui.download(file);
        }
    }
}
