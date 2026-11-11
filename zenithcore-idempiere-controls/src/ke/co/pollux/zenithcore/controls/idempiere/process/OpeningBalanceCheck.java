package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
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

import ke.co.pollux.zenithcore.controls.OpeningBalance;
import ke.co.pollux.zenithcore.controls.OpeningBalance.ControlTotal;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Item;
import ke.co.pollux.zenithcore.controls.OpeningBalance.ItemType;
import ke.co.pollux.zenithcore.controls.OpeningBalance.TbLine;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Check and Reconcile (button on Opening Balances). Runs every check, writes the opening amount and a
 * check note on each line and item, sets the status to Reconciled (or back to Drafted), and prints the
 * reconciliation used for sign-off: trial balance from legacy to opening, adjustments with reasons, audited
 * figures, control accounts against sub-ledgers, problems, and after posting the clearing account balance.
 */
@Process
public class OpeningBalanceCheck extends SvrProcess {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Color GREEN = new Color(0x1E, 0x7A, 0x34);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE);
    private static final Font H = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);
    private static final Font TH = new Font(Font.HELVETICA, 7, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.BLACK);
    private static final Font TDB = new Font(Font.HELVETICA, 7, Font.BOLD, Color.BLACK);
    private static final Font BAD = new Font(Font.HELVETICA, 8, Font.NORMAL, RED);
    private static final Font BADB = new Font(Font.HELVETICA, 9, Font.BOLD, RED);
    private static final Font OK = new Font(Font.HELVETICA, 9, Font.BOLD, GREEN);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO h = MTable.get(getCtx(), "ZC_OpeningBalance").getPO(getRecord_ID(), trx);
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Open an opening balance first");
        }
        OpeningBalanceData data = new OpeningBalanceData(h, trx);
        OpeningBalance.Result r = data.check();
        String status = h.get_ValueAsString("ZC_OBStatus");
        if (!"SO".equals(status) && !"PO".equals(status)) {
            data.writeResults(r, trx);
            h.set_ValueNoCheck("ZC_OBStatus", r.ok() ? "RC" : "DR");
            h.saveEx();
        }
        File pdf = print(h, data, r, trx);
        if (processUI != null) {
            processUI.download(pdf);
        }
        if (!r.ok()) {
            addLog(String.join(" | ", r.problems()));
        }
        String next = "SO".equals(status) ? ", signed off: ready to post" : "PO".equals(status) ? ", posted" : ", ready for sign-off";
        return (r.ok() ? "Reconciled: debits and credits " + money(r.debits()) + next
                : r.problems().size() + " problem(s); see the check result on each line and the PDF") + ". " + pdf.getName();
    }

    private File print(PO h, OpeningBalanceData data, OpeningBalance.Result r, String trx) throws Exception {
        String name = h.get_ValueAsString("Name");
        Timestamp cut = (Timestamp) h.get_Value("ZC_CutoverDate");
        File file = new File(System.getProperty("java.io.tmpdir"), "opening-balances-" + name.replaceAll("[^A-Za-z0-9]+", "-")
                + ".pdf");
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 26, 26);
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Opening balances " + name);
            doc.open();
            PdfPTable head = new PdfPTable(1);
            head.setWidthPercentage(100);
            PdfPCell hc = new PdfPCell();
            hc.addElement(new Paragraph(MClient.get(getCtx()).getName(), TITLE));
            hc.addElement(new Paragraph("OPENING BALANCES  |  " + name + "  |  at " + cut.toLocalDateTime().toLocalDate()
                    + "  |  status: " + statusName(h.get_ValueAsString("ZC_OBStatus")), SUB));
            hc.setBackgroundColor(NAVY);
            hc.setBorder(Rectangle.BOTTOM);
            hc.setBorderColor(CYAN);
            hc.setBorderWidthBottom(3);
            hc.setPadding(10);
            hc.setPaddingTop(4);
            head.addCell(hc);
            doc.add(head);
            String src = h.get_ValueAsString("Description");
            if (!src.isBlank()) {
                Paragraph p = new Paragraph("Source: " + src, TD);
                p.setSpacingBefore(6);
                doc.add(p);
            }

            doc.add(heading("Trial balance (debit positive, credit negative)"));
            PdfPTable t = table(new float[] {0.9f, 2.4f, 2.6f, 1.4f, 1.3f, 1.4f, 1.4f, 1.2f, 3.2f}, "Legacy", "Legacy name",
                    "New account", "Legacy", "Adjustment", "Opening", "Audited", "Difference", "Check");
            BigDecimal tl = BigDecimal.ZERO, ta = BigDecimal.ZERO, to = BigDecimal.ZERO;
            for (TbLine l : data.lines) {
                BigDecimal adj = l.adjustment() == null ? BigDecimal.ZERO : l.adjustment();
                String diff = l.audited() == null ? "" : money(l.opening().subtract(l.audited()));
                String note = DB.getSQLValueStringEx(trx, "SELECT ZC_CheckNote FROM ZC_OpeningBalanceLine WHERE ZC_OpeningBalanceLine_ID=?",
                        data.lineIds.get(l.lineNo()));
                boolean bad = note != null && !"OK".equals(note);
                t.addCell(cell(l.legacyCode(), TD, Element.ALIGN_LEFT));
                t.addCell(cell(nn(l.legacyName()), TD, Element.ALIGN_LEFT));
                t.addCell(cell(l.accountId() > 0 ? nn(l.account()) : "not mapped", l.accountId() > 0 ? TD : BAD, Element.ALIGN_LEFT));
                t.addCell(cell(money(l.legacy()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(adj.signum() == 0 ? "" : money(adj), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(l.opening()), TDB, Element.ALIGN_RIGHT));
                t.addCell(cell(l.audited() == null ? "" : money(l.audited()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(diff, !diff.isEmpty() && l.opening().compareTo(l.audited()) != 0 ? BAD : TD, Element.ALIGN_RIGHT));
                t.addCell(cell(note == null ? "" : note, bad ? BAD : TD, Element.ALIGN_LEFT));
                tl = tl.add(l.legacy());
                ta = ta.add(adj);
                to = to.add(l.opening());
            }
            t.addCell(total("Net (must be nil)", 3));
            t.addCell(cell(money(tl), TDB, Element.ALIGN_RIGHT));
            t.addCell(cell(money(ta), TDB, Element.ALIGN_RIGHT));
            t.addCell(cell(money(to), to.signum() == 0 ? TDB : BAD, Element.ALIGN_RIGHT));
            t.addCell(total("Debits " + money(r.debits()) + "  |  Credits " + money(r.credits()), 3));
            doc.add(t);

            doc.add(heading("Adjustments"));
            PdfPTable a = table(new float[] {1, 3, 1.5f, 6}, "Legacy", "New account", "Amount", "Reason");
            int adjCount = 0;
            for (TbLine l : data.lines) {
                if (l.adjustment() != null && l.adjustment().signum() != 0) {
                    a.addCell(cell(l.legacyCode(), TD, Element.ALIGN_LEFT));
                    a.addCell(cell(nn(l.account()), TD, Element.ALIGN_LEFT));
                    a.addCell(cell(money(l.adjustment()), TD, Element.ALIGN_RIGHT));
                    a.addCell(cell(nn(l.reason()), l.reason() == null || l.reason().isBlank() ? BAD : TD, Element.ALIGN_LEFT));
                    adjCount++;
                }
            }
            if (adjCount == 0) {
                a.addCell(total("No adjustments", 4));
            }
            doc.add(a);

            doc.add(heading("Control accounts and sub-ledgers"));
            PdfPTable c = table(new float[] {4, 2, 2, 2}, "Control account", "Trial balance", "Sub-ledger items", "Difference");
            for (ControlTotal ct : r.controls()) {
                c.addCell(cell(ct.account(), TD, Element.ALIGN_LEFT));
                c.addCell(cell(money(ct.trialBalance()), TD, Element.ALIGN_RIGHT));
                c.addCell(cell(money(ct.subLedger()), TD, Element.ALIGN_RIGHT));
                c.addCell(cell(money(ct.difference()), ct.difference().signum() == 0 ? TDB : BAD, Element.ALIGN_RIGHT));
            }
            doc.add(c);
            Map<ItemType, int[]> count = new EnumMap<>(ItemType.class);
            Map<ItemType, BigDecimal> sum = new EnumMap<>(ItemType.class);
            for (Item i : data.items) {
                if (i.type() != null) {
                    count.computeIfAbsent(i.type(), k -> new int[1])[0]++;
                    sum.merge(i.type(), i.amount() == null ? BigDecimal.ZERO : i.amount(), BigDecimal::add);
                }
            }
            StringBuilder sb = new StringBuilder("Sub-ledger items: ");
            for (ItemType it : count.keySet()) {
                sb.append(it.code).append(' ').append(count.get(it)[0]).append(" (").append(money(sum.get(it))).append(")  ");
            }
            doc.add(new Paragraph(sb.toString(), TD));

            if (!r.ok()) {
                doc.add(heading("Problems to clear before sign-off"));
                for (String p : r.problems()) {
                    doc.add(new Paragraph("- " + p, BAD));
                }
            } else {
                Paragraph ok = new Paragraph("All checks passed.", OK);
                ok.setSpacingBefore(8);
                doc.add(ok);
            }

            int journal = h.get_ValueAsInt("GL_Journal_ID");
            if (journal > 0) {
                int clearing = data.clearingAccount(trx);
                BigDecimal bal = DB.getSQLValueBDEx(trx, "SELECT COALESCE(SUM(AmtAcctDr-AmtAcctCr),0) FROM Fact_Acct WHERE "
                        + "C_AcctSchema_ID=? AND Account_ID=? AND PostingType='A'", data.schema, clearing);
                Paragraph p = new Paragraph("Posted: opening journal " + DB.getSQLValueStringEx(trx,
                        "SELECT DocumentNo FROM GL_Journal WHERE GL_Journal_ID=?", journal) + ". Clearing account balance in the ledger: "
                        + money(bal) + (bal.signum() == 0 ? " (nil)" : " (load the remaining sub-ledgers, or check unposted documents)"),
                        bal.signum() == 0 ? OK : BADB);
                p.setSpacingBefore(8);
                doc.add(p);
            }

            PdfPTable sign = new PdfPTable(3);
            sign.setWidthPercentage(100);
            sign.setSpacingBefore(26);
            int signer = h.get_ValueAsInt("ZC_SignedOffUser_ID");
            String[] who = {"Prepared by: " + MUser.getNameOfUser(h.getCreatedBy()),
                    "Signed off by: " + (signer > 0 ? MUser.getNameOfUser(signer) + ", " + h.get_Value("ZC_DateSignedOff") : ""),
                    "Approved by (Finance head / audit committee)"};
            for (String s : who) {
                PdfPCell sc = new PdfPCell(new Phrase("\n\n__________________________\n" + s + "\nSignature and date", TD));
                sc.setBorder(Rectangle.NO_BORDER);
                sign.addCell(sc);
            }
            doc.add(sign);
            Paragraph foot = new Paragraph("Opening = legacy + adjustment. Control accounts are posted through the clearing "
                    + "account by their sub-ledger documents; the clearing account ends at nil once every sub-ledger is "
                    + "loaded. Produced by ZenithCore Enterprise ERP.", SMALL);
            foot.setSpacingBefore(10);
            doc.add(foot);
            doc.close();
        }
        return file;
    }

    static String statusName(String s) {
        return switch (s) {
            case "RC" -> "Reconciled";
            case "SO" -> "Signed off";
            case "PO" -> "Posted";
            default -> "Drafted";
        };
    }

    private static Paragraph heading(String s) {
        Paragraph p = new Paragraph(s, H);
        p.setSpacingBefore(10);
        return p;
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell c = cell(h, TH, Element.ALIGN_LEFT);
            c.setBorderColor(CYAN);
            c.setBorderWidthBottom(1.5f);
            t.addCell(c);
        }
        return t;
    }

    private static PdfPCell total(String text, int span) {
        PdfPCell c = cell(text, TDB, Element.ALIGN_LEFT);
        c.setColspan(span);
        return c;
    }

    private static PdfPCell cell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        return c;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
