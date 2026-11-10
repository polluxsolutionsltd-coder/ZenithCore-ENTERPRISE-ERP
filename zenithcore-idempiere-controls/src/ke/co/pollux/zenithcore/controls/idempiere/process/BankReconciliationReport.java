package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MBankAccount;
import org.compiere.model.MClient;
import org.compiere.model.MClientInfo;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import ke.co.pollux.zenithcore.controls.BankMatch;
import ke.co.pollux.zenithcore.controls.BankMatch.Outstanding;

/**
 * Bank Reconciliation Statement (menu) for one bank or M-Pesa account at a date. PDF.
 * <ul>
 * <li>Balance per bank statement: ending balance of the last completed statement dated on or before the date.</li>
 * <li>Payments not yet presented and receipts not yet credited: completed payments and receipts dated on or
 * before the date that are not on a completed statement dated on or before the date.</li>
 * <li>Balance per cash book: general ledger balance, at the date, of the account's bank asset and bank in
 * transit accounts. Each bank or M-Pesa account needs its own pair; the report warns if they are shared.</li>
 * </ul>
 */
@Process
public class BankReconciliationReport extends SvrProcess {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Color GREEN = new Color(0x1E, 0x7A, 0x34);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE);
    private static final Font H = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);
    private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font TDS = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.DARK_GRAY);
    private static final Font TDB = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);
    private static final Font BAD = new Font(Font.HELVETICA, 9, Font.BOLD, RED);
    private static final Font OK = new Font(Font.HELVETICA, 9, Font.BOLD, GREEN);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);

    @Parameter(name = "C_BankAccount_ID")
    private int accountId;
    @Parameter(name = "DateTo")
    private Timestamp asAt;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        MBankAccount acct = new MBankAccount(getCtx(), accountId, trx);
        String date = asAt.toLocalDateTime().toLocalDate().toString();

        BigDecimal bank = BigDecimal.ZERO;
        String statement = "no completed statement on or before " + date;
        try (PreparedStatement ps = DB.prepareStatement("SELECT EndingBalance, Name, StatementDate FROM C_BankStatement "
                + "WHERE C_BankAccount_ID=? AND DocStatus IN ('CO','CL') AND StatementDate<=? ORDER BY StatementDate DESC, "
                + "C_BankStatement_ID DESC", trx)) {
            ps.setInt(1, accountId);
            ps.setTimestamp(2, asAt);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    bank = rs.getBigDecimal(1);
                    statement = rs.getString(2) + ", " + rs.getTimestamp(3).toLocalDateTime().toLocalDate();
                }
            }
        }

        List<Outstanding> unpresented = new ArrayList<>();
        List<Outstanding> uncredited = new ArrayList<>();
        String sql = "SELECT p.DateTrx, p.DocumentNo || COALESCE(' / ' || COALESCE(NULLIF(p.ZC_TrxReference,''), NULLIF(p.CheckNo,'')), ''), bp.Name, p.PayAmt, p.IsReceipt "
                + "FROM C_Payment p JOIN C_BPartner bp ON bp.C_BPartner_ID=p.C_BPartner_ID WHERE p.C_BankAccount_ID=? "
                + "AND p.DocStatus IN ('CO','CL') AND p.DateTrx<=? AND NOT EXISTS (SELECT 1 FROM C_BankStatementLine bl "
                + "JOIN C_BankStatement b ON b.C_BankStatement_ID=bl.C_BankStatement_ID WHERE b.DocStatus IN ('CO','CL') "
                + "AND b.StatementDate<=? AND (bl.C_Payment_ID=p.C_Payment_ID OR (p.C_DepositBatch_ID>0 "
                + "AND bl.C_DepositBatch_ID=p.C_DepositBatch_ID))) ORDER BY p.DateTrx, p.DocumentNo";
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, accountId);
            ps.setTimestamp(2, asAt);
            ps.setTimestamp(3, asAt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Outstanding o = new Outstanding(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getString(2),
                            rs.getString(3), rs.getBigDecimal(4));
                    ("Y".equals(rs.getString(5)) ? uncredited : unpresented).add(o);
                }
            }
        }

        int schema = MClientInfo.get(getCtx(), acct.getAD_Client_ID()).getC_AcctSchema1_ID();
        String accounts = "SELECT vc.Account_ID FROM C_BankAccount_Acct a JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID "
                + "IN (a.B_Asset_Acct, a.B_InTransit_Acct) WHERE a.C_BankAccount_ID=? AND a.C_AcctSchema_ID=?";
        BigDecimal book = DB.getSQLValueBDEx(trx, "SELECT COALESCE(SUM(AmtAcctDr-AmtAcctCr),0) FROM Fact_Acct WHERE "
                + "C_AcctSchema_ID=? AND PostingType='A' AND DateAcct<=? AND Account_ID IN (" + accounts + ")", schema, asAt,
                accountId, schema);
        String glAccounts = DB.getSQLValueStringEx(trx, "SELECT string_agg(DISTINCT ev.Value || ' ' || ev.Name, ', ') FROM "
                + "C_ElementValue ev WHERE ev.C_ElementValue_ID IN (" + accounts + ")", accountId, schema);
        String shared = DB.getSQLValueStringEx(trx, "SELECT string_agg(DISTINCT ba.AccountNo, ', ') FROM C_BankAccount_Acct a "
                + "JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID IN (a.B_Asset_Acct, a.B_InTransit_Acct) "
                + "JOIN C_BankAccount ba ON ba.C_BankAccount_ID=a.C_BankAccount_ID WHERE a.C_AcctSchema_ID=? "
                + "AND a.C_BankAccount_ID<>? AND ba.IsActive='Y' AND vc.Account_ID IN (" + accounts + ")", schema, accountId,
                accountId, schema);
        int unposted = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM C_Payment WHERE C_BankAccount_ID=? AND DocStatus IN ('CO','CL') "
                + "AND Posted<>'Y' AND DateAcct<=?", accountId, asAt)
                + DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM C_BankStatement WHERE C_BankAccount_ID=? AND DocStatus IN ('CO','CL') "
                        + "AND Posted<>'Y' AND DateAcct<=?", accountId, asAt);

        BankMatch.Reconciliation r = BankMatch.reconcile(bank, unpresented, uncredited, book);
        String name = acct.getName() == null ? acct.getAccountNo() : acct.getName();
        File file = new File(System.getProperty("java.io.tmpdir"), "bank-reconciliation-" + acct.getAccountNo().replaceAll("[^A-Za-z0-9]", "")
                + "-" + date + ".pdf");
        Document doc = new Document(PageSize.A4, 36, 36, 30, 30);
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Bank reconciliation " + name + " " + date);
            doc.open();
            PdfPTable head = new PdfPTable(1);
            head.setWidthPercentage(100);
            PdfPCell hc = new PdfPCell();
            hc.addElement(new Paragraph(MClient.get(getCtx()).getName(), TITLE));
            hc.addElement(new Paragraph("BANK RECONCILIATION STATEMENT  |  " + name + " (" + acct.getAccountNo() + ")  |  as at "
                    + date, SUB));
            hc.setBackgroundColor(NAVY);
            hc.setBorder(Rectangle.BOTTOM);
            hc.setBorderColor(CYAN);
            hc.setBorderWidthBottom(3);
            hc.setPadding(10);
            hc.setPaddingTop(4);
            head.addCell(hc);
            doc.add(head);

            PdfPTable t = new PdfPTable(new float[] {1.2f, 2.2f, 3f, 1.6f, 1.6f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10);
            row(t, "Balance as per bank statement (" + statement + ")", "", money(bank), TDB);
            row(t, "Less: payments not yet presented", "", "", H);
            for (Outstanding o : unpresented) {
                item(t, o);
            }
            row(t, "Total payments not yet presented", "(" + money(r.unpresented()) + ")", "", TD);
            row(t, "Add: receipts not yet credited", "", "", H);
            for (Outstanding o : uncredited) {
                item(t, o);
            }
            row(t, "Total receipts not yet credited", money(r.uncredited()), "", TD);
            row(t, "Adjusted bank balance", "", money(r.adjustedBank()), TDB);
            row(t, "Balance as per cash book (" + (glAccounts == null ? "no bank accounts set in accounting" : glAccounts) + ")",
                    "", money(r.cashBook()), TDB);
            row(t, r.balanced() ? "Difference: none, reconciled" : "Unexplained difference (cash book less adjusted bank balance)", "",
                    money(r.difference()), r.balanced() ? OK : BAD);
            doc.add(t);

            List<String> notes = new ArrayList<>();
            if (shared != null) {
                notes.add("The cash book accounts are also used by bank account(s) " + shared
                        + ". Give each bank and M-Pesa account its own bank asset and in-transit accounts, or the cash book "
                        + "balance includes the other accounts.");
            }
            if (unposted > 0) {
                notes.add(unposted + " completed payment(s) or statement(s) for this account are not yet posted to the ledger.");
            }
            for (String n : notes) {
                Paragraph p = new Paragraph("Note: " + n, BAD);
                p.setSpacingBefore(6);
                doc.add(p);
            }

            PdfPTable sign = new PdfPTable(3);
            sign.setWidthPercentage(100);
            sign.setSpacingBefore(36);
            for (String s : new String[] {"Prepared by (cashier / accountant)", "Checked by", "Approved by (Finance head)"}) {
                PdfPCell c = new PdfPCell(new Phrase("\n\n__________________________\n" + s + "\nName, signature and date", TDS));
                c.setBorder(Rectangle.NO_BORDER);
                sign.addCell(c);
            }
            doc.add(sign);
            Paragraph foot = new Paragraph("Bank balance: ending balance of the last completed statement. Outstanding items: "
                    + "completed payments and receipts not on a completed statement by the date. Cash book: general ledger "
                    + "balance of the account's bank asset and in-transit accounts. Produced by ZenithCore Enterprise ERP.", SMALL);
            foot.setSpacingBefore(14);
            doc.add(foot);
            doc.close();
        }
        if (processUI != null) {
            processUI.download(file);
        }
        return (r.balanced() ? "Reconciled" : "Unexplained difference " + money(r.difference())) + ": " + unpresented.size()
                + " unpresented, " + uncredited.size() + " uncredited. " + file.getName();
    }

    private static void row(PdfPTable t, String label, String sub, String total, Font f) {
        PdfPCell c = cell(label, f, Element.ALIGN_LEFT);
        c.setColspan(3);
        t.addCell(c);
        t.addCell(cell(sub, f, Element.ALIGN_RIGHT));
        t.addCell(cell(total, f, Element.ALIGN_RIGHT));
    }

    private static void item(PdfPTable t, Outstanding o) {
        t.addCell(cell(o.date().toString(), TDS, Element.ALIGN_LEFT));
        t.addCell(cell(o.documentNo(), TDS, Element.ALIGN_LEFT));
        t.addCell(cell(o.party(), TDS, Element.ALIGN_LEFT));
        t.addCell(cell(money(o.amount()), TDS, Element.ALIGN_RIGHT));
        t.addCell(cell("", TDS, Element.ALIGN_RIGHT));
    }

    private static PdfPCell cell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
