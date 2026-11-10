package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MPeriod;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Withholding Tax Register (menu). Every withholding deduction on completed supplier invoices dated in the
 * month: invoice, supplier and KRA PIN, type, base, rate, amount, and whether and when the invoice was
 * paid. Withholding tax is due to KRA within five working days after payment, so the payment date tells
 * Finance when each amount must be remitted. CSV with totals by type.
 *
 * <p>This is a working register, not the iTax upload layout; that layout waits for the current KRA template
 * (see docs/pending-critical.md, CRIT-02).
 */
@Process
public class WithholdingRegister extends SvrProcess {

    @Parameter(name = "C_Period_ID")
    private int periodId;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        MPeriod p = MPeriod.get(periodId);
        StringBuilder csv = new StringBuilder();
        csv.append(row("Invoice date", "Invoice", "Supplier", "Supplier KRA PIN", "Kind", "Type", "Type of payment", "Base",
                "Rate %", "Amount withheld", "Invoice paid", "Last payment date"));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        int n = 0;
        String sql = "SELECT i.DateInvoiced, i.DocumentNo, bp.Name, bp.TaxID, t.ZC_TaxKind, t.Value, t.Name, l.ZC_WHTBase, "
                + "-l.LineNetAmt, i.IsPaid, (SELECT MAX(ah.DateTrx) FROM C_AllocationLine al JOIN C_AllocationHdr ah "
                + "ON ah.C_AllocationHdr_ID=al.C_AllocationHdr_ID WHERE al.C_Invoice_ID=i.C_Invoice_ID AND ah.DocStatus IN ('CO','CL')) "
                + "FROM C_InvoiceLine l JOIN C_Invoice i ON i.C_Invoice_ID=l.C_Invoice_ID JOIN C_BPartner bp ON bp.C_BPartner_ID=i.C_BPartner_ID "
                + "JOIN ZC_WHTType t ON t.ZC_WHTType_ID=l.ZC_WHTType_ID WHERE l.ZC_IsWHTLine='Y' AND i.AD_Client_ID=? "
                + "AND i.DocStatus IN ('CO','CL') AND i.DateInvoiced BETWEEN ? AND ? ORDER BY t.ZC_TaxKind, t.Value, i.DateInvoiced, i.DocumentNo";
        try (PreparedStatement ps = DB.prepareStatement(sql, get_TrxName())) {
            ps.setInt(1, Env.getAD_Client_ID(getCtx()));
            ps.setTimestamp(2, p.getStartDate());
            ps.setTimestamp(3, p.getEndDate());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal base = nz(rs.getBigDecimal(8)), amt = nz(rs.getBigDecimal(9));
                    String rate = base.signum() == 0 ? "" : amt.multiply(BigDecimal.valueOf(100))
                            .divide(base, 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                    csv.append(row(day(rs.getTimestamp(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                            "V".equals(rs.getString(5)) ? "Withholding VAT" : "Withholding income tax", rs.getString(6),
                            rs.getString(7), money(base), rate, money(amt), "Y".equals(rs.getString(10)) ? "Yes" : "No",
                            day(rs.getTimestamp(11))));
                    totals.merge(rs.getString(6) + " " + rs.getString(7), amt, BigDecimal::add);
                    n++;
                }
            }
        }
        csv.append("\r\n");
        for (Map.Entry<String, BigDecimal> t : totals.entrySet()) {
            csv.append(row("TOTAL", t.getKey(), "", "", "", "", "", "", "", money(t.getValue()), "", ""));
        }
        csv.append("\r\n").append(row("Remit each amount to KRA within five working days after the invoice is paid. "
                + "Working register, not the iTax upload layout (CRIT-02)."));
        File f = new File(System.getProperty("java.io.tmpdir"), "withholding-register-" + p.getName().replace(' ', '-') + ".csv");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(f.toPath(), all);
        if (processUI != null) {
            processUI.download(f);
        }
        for (Map.Entry<String, BigDecimal> t : totals.entrySet()) {
            addLog(t.getKey() + ": " + String.format(Locale.ROOT, "%,.2f", t.getValue()));
        }
        return n + " deductions in " + p.getName() + ": " + f.getName();
    }

    private static String row(String... v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            String x = v[i] == null ? "" : v[i];
            if (x.startsWith("=") || x.startsWith("+") || x.startsWith("@")) {
                x = "'" + x;
            }
            if (x.contains(",") || x.contains("\"")) {
                x = "\"" + x.replace("\"", "\"\"") + "\"";
            }
            sb.append(i > 0 ? "," : "").append(x);
        }
        return sb.append("\r\n").toString();
    }

    private static String day(Timestamp t) {
        return t == null ? "" : t.toLocalDateTime().toLocalDate().toString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
