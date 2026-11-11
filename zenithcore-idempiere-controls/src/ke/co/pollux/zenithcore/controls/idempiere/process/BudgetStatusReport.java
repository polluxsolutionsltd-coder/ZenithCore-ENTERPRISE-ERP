package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.process.SvrProcess;
import org.compiere.util.Env;

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

import ke.co.pollux.zenithcore.controls.BudgetCheck;
import ke.co.pollux.zenithcore.controls.idempiere.BudgetLedger;

/**
 * Budget Status Report (menu). For a financial year, and optionally one cost centre: each approved budget
 * line with budget, spent, committed, available and percentage used, computed exactly as the budget
 * check computes them. Downloads a PDF.
 */
@Process
public class BudgetStatusReport extends SvrProcess {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE);
    private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font TDB = new Font(Font.HELVETICA, 8, Font.BOLD, Color.BLACK);
    private static final Font WARN = new Font(Font.HELVETICA, 8, Font.BOLD, RED);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);

    @Parameter(name = "C_Year_ID")
    private int yearId;

    @Parameter(name = "C_Activity_ID")
    private int activityId;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        int clientId = Env.getAD_Client_ID(getCtx());
        BudgetLedger ledger = new BudgetLedger(getCtx(), clientId, get_TrxName());
        Map<String, BudgetCheck.Position> pos = ledger.positions(clientId, yearId, ledger.budgetKeys(clientId, yearId, activityId),
                null, 0);
        String year = ledger.yearName(yearId);
        String currency = ledger.schema().getC_Currency().getISO_Code();
        File file = new File(System.getProperty("java.io.tmpdir"), "budget-status-" + year.replace('/', '-')
                + (activityId > 0 ? "-cc" + activityId : "") + ".pdf");

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        BigDecimal tb = BigDecimal.ZERO, ts = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Budget status " + year);
            doc.addCreator("ZenithCore Enterprise ERP");
            doc.open();
            PdfPTable head = new PdfPTable(1);
            head.setWidthPercentage(100);
            PdfPCell hc = new PdfPCell();
            hc.addElement(new Paragraph(MClient.get(getCtx()).getName(), TITLE));
            hc.addElement(new Paragraph("BUDGET STATUS  |  FINANCIAL YEAR " + year + "  |  " + currency
                    + (activityId > 0 ? "  |  " + BudgetLedger.costCentreLabel(activityId).toUpperCase(Locale.ENGLISH) : ""), SUB));
            hc.setBackgroundColor(NAVY);
            hc.setBorder(Rectangle.BOTTOM);
            hc.setBorderColor(CYAN);
            hc.setBorderWidthBottom(3);
            hc.setPadding(10);
            hc.setPaddingTop(4);
            head.addCell(hc);
            doc.add(head);

            PdfPTable t = new PdfPTable(new float[] {2.4f, 3, 1.5f, 1.5f, 1.5f, 1.5f, 0.8f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10);
            t.setHeaderRows(1);
            for (String h : new String[] {"Cost centre", "Account", "Budget", "Spent", "Committed", "Available", "Used"}) {
                PdfPCell c = cell(h, TH, h.equals("Cost centre") || h.equals("Account") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                c.setBorderColor(CYAN);
                c.setBorderWidthBottom(1.5f);
                t.addCell(c);
            }
            for (Map.Entry<String, BudgetCheck.Position> e : pos.entrySet()) {
                String[] k = e.getKey().split(":");
                BudgetCheck.Position p = e.getValue();
                boolean over = p.available().signum() < 0;
                t.addCell(cell(BudgetLedger.costCentreLabel(Integer.parseInt(k[0])), TD, Element.ALIGN_LEFT));
                t.addCell(cell(ledger.accountLabel(Integer.parseInt(k[1])), TD, Element.ALIGN_LEFT));
                t.addCell(cell(money(p.budget()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(p.spent()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(p.committed()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(p.available()), over ? WARN : TD, Element.ALIGN_RIGHT));
                t.addCell(cell(pct(p), over ? WARN : TD, Element.ALIGN_RIGHT));
                tb = tb.add(p.budget());
                ts = ts.add(p.spent());
                tc = tc.add(p.committed());
            }
            BudgetCheck.Position total = new BudgetCheck.Position(tb, ts, tc);
            String[] tot = {"Total", "", money(tb), money(ts), money(tc), money(total.available()), pct(total)};
            for (int i = 0; i < tot.length; i++) {
                PdfPCell c = cell(tot[i], TDB, i < 2 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                c.setBackgroundColor(SHADE);
                t.addCell(c);
            }
            doc.add(t);
            Paragraph foot = new Paragraph("Budget: approved budgets only. Spent: actual postings in the year (debits less "
                    + "credits). Committed: completed requisitions not yet on an LPO and completed LPO lines not yet invoiced. "
                    + "Items kept in stock are not budget-controlled. Produced by ZenithCore Enterprise ERP.", SMALL);
            foot.setSpacingBefore(12);
            doc.add(foot);
            doc.close();
        }
        if (processUI != null) {
            processUI.download(file);
        }
        return pos.size() + " budget lines, budget " + money(tb) + ", available " + money(tb.subtract(ts).subtract(tc)) + ": "
                + file.getName();
    }

    private static String pct(BudgetCheck.Position p) {
        if (p.budget().signum() == 0) {
            return "-";
        }
        return p.spent().add(p.committed()).multiply(BigDecimal.valueOf(100))
                .divide(p.budget(), 0, java.math.RoundingMode.HALF_UP) + "%";
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
