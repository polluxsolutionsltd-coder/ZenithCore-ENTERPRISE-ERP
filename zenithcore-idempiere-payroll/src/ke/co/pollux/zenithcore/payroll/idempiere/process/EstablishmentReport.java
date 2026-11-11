package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MOrg;
import org.compiere.model.MBPartner;
import org.compiere.model.PO;
import org.compiere.model.Query;
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

import ke.co.pollux.zenithcore.payroll.ke.EstablishmentChecks;
import ke.co.pollux.zenithcore.payroll.ke.EstablishmentSummary;

/**
 * Establishment Report (menu: ZenithCore). As at a date, for one organization or all: each approved
 * position with approved, filled, vacant and over-filled posts, salary ceiling, and the monthly basic and
 * fixed pay (basic plus house, commuter and other allowances) of the people in post; then everyone in
 * post without an approved position in force. Downloads a PDF.
 */
@Process
public class EstablishmentReport extends SvrProcess {

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
    private static final Font HEAD = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);

    @Parameter(name = "DateTrx")
    private Timestamp asAt;

    @Parameter(name = "AD_Org_ID")
    private int orgId;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        LocalDate date = (asAt == null ? new Timestamp(System.currentTimeMillis()) : asAt).toLocalDateTime().toLocalDate();
        String orgWhere = orgId > 0 ? " AND AD_Org_ID IN (0," + orgId + ")" : "";

        List<EstablishmentChecks.Position> positions = new ArrayList<>();
        for (PO p : new Query(getCtx(), PayrollRunCalculate.POSITION, "1=1" + orgWhere, get_TrxName()).setClient_ID()
                .setOrderBy("Value").list()) {
            Timestamp from = (Timestamp) p.get_Value("ValidFrom");
            Timestamp to = (Timestamp) p.get_Value("ValidTo");
            positions.add(new EstablishmentChecks.Position(String.valueOf(p.get_ID()),
                    p.get_ValueAsString("Value") + " " + p.get_ValueAsString("Name"), p.getAD_Org_ID(),
                    p.get_ValueAsInt("ZC_ApprovedHeadcount"), (BigDecimal) p.get_Value("ZC_MaxBasicSalary"),
                    from == null ? null : from.toLocalDateTime().toLocalDate(),
                    to == null ? null : to.toLocalDateTime().toLocalDate(), p.isActive()));
        }

        Timestamp ts = Timestamp.valueOf(date.atStartOfDay());
        List<EstablishmentSummary.Holder> holders = new ArrayList<>();
        for (PO e : new Query(getCtx(), PayrollRunCalculate.EMP, "IsActive='Y' AND ZC_IsSuspended='N' AND ZC_StartDate<=? "
                + "AND (ZC_EndDate IS NULL OR ZC_EndDate>=?)" + (orgId > 0 ? " AND AD_Org_ID=" + orgId : ""), get_TrxName())
                .setClient_ID().setParameters(ts, ts).list()) {
            MBPartner bp = MBPartner.get(getCtx(), e.get_ValueAsInt("C_BPartner_ID"));
            int pos = e.get_ValueAsInt("ZC_Position_ID");
            BigDecimal basic = amt(e, "ZC_BasicSalary");
            BigDecimal fixed = basic.add(amt(e, "ZC_HouseAllowance")).add(amt(e, "ZC_CommuterAllowance"))
                    .add(amt(e, "ZC_OtherAllowances"));
            holders.add(new EstablishmentSummary.Holder(bp.getValue(), bp.getName(), pos > 0 ? String.valueOf(pos) : null,
                    basic, fixed));
        }
        holders.sort((a, b) -> a.employeeId().compareTo(b.employeeId()));
        EstablishmentSummary.Summary s = EstablishmentSummary.of(positions, holders, date);

        String scope = orgId > 0 ? MOrg.get(getCtx(), orgId).getName() : MClient.get(getCtx()).getName() + " (all organizations)";
        String when = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
        File file = new File(System.getProperty("java.io.tmpdir"), "establishment-" + date + (orgId > 0 ? "-org" + orgId : "") + ".pdf");
        write(file, s, scope, when);
        if (processUI != null) {
            processUI.download(file);
        }
        addLog("Approved " + s.approved() + ", filled " + s.filled() + ", vacant " + s.vacant() + ", over " + s.over()
                + ", without position " + s.withoutPosition().size());
        return "Establishment report as at " + when + ": " + file.getName();
    }

    private void write(File file, EstablishmentSummary.Summary s, String scope, String when) throws Exception {
        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Establishment report " + when);
            doc.addCreator("ZenithCore Enterprise ERP");
            doc.open();

            PdfPTable head = new PdfPTable(1);
            head.setWidthPercentage(100);
            PdfPCell hc = new PdfPCell();
            hc.addElement(new Paragraph(scope, TITLE));
            hc.addElement(new Paragraph("ESTABLISHMENT REPORT  |  AS AT " + when.toUpperCase(Locale.ENGLISH), SUB));
            hc.setBackgroundColor(NAVY);
            hc.setBorder(Rectangle.BOTTOM);
            hc.setBorderColor(CYAN);
            hc.setBorderWidthBottom(3);
            hc.setPadding(10);
            hc.setPaddingTop(4);
            head.addCell(hc);
            doc.add(head);

            PdfPTable t = new PdfPTable(new float[] {3.2f, 1, 1, 1, 1, 1.4f, 1.5f, 1.5f, 3.6f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10);
            t.setHeaderRows(1);
            for (String h : new String[] {"Position", "Approved", "Filled", "Vacant", "Over", "Ceiling (basic)",
                    "Basic / month", "Fixed pay / month", "In post"}) {
                PdfPCell c = cell(h, TH, h.equals("Position") || h.equals("In post") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                c.setBorder(Rectangle.BOTTOM);
                c.setBorderColor(CYAN);
                c.setBorderWidthBottom(1.5f);
                t.addCell(c);
            }
            for (EstablishmentSummary.Row r : s.rows()) {
                BigDecimal ceiling = r.position().maxBasicSalary();
                t.addCell(cell(r.position().title(), TD, Element.ALIGN_LEFT));
                t.addCell(cell(String.valueOf(r.position().approvedHeadcount()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(String.valueOf(r.filled()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(String.valueOf(r.vacant()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(String.valueOf(r.over()), r.over() > 0 ? WARN : TD, Element.ALIGN_RIGHT));
                t.addCell(cell(ceiling == null || ceiling.signum() == 0 ? "-" : money(ceiling), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(r.basicCost()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(money(r.fixedCost()), TD, Element.ALIGN_RIGHT));
                t.addCell(cell(r.holders().isEmpty() ? "-" : String.join(", ", r.holders()), TD, Element.ALIGN_LEFT));
            }
            String[] tot = {"Total", String.valueOf(s.approved()), String.valueOf(s.filled()), String.valueOf(s.vacant()),
                    String.valueOf(s.over()), "", money(s.basicCost()), money(s.fixedCost()), ""};
            for (int i = 0; i < tot.length; i++) {
                PdfPCell c = cell(tot[i], TDB, i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                c.setBackgroundColor(SHADE);
                t.addCell(c);
            }
            doc.add(t);

            Paragraph h2 = new Paragraph("In post without an approved position (" + s.withoutPosition().size() + ")", HEAD);
            h2.setSpacingBefore(14);
            doc.add(h2);
            doc.add(new Paragraph(s.withoutPosition().isEmpty() ? "None." : String.join(", ", s.withoutPosition())
                    + ". Payroll is blocked for these employees until each holds an approved position.",
                    s.withoutPosition().isEmpty() ? TD : WARN));

            Paragraph foot = new Paragraph("In post: active, not suspended, start and end dates covering the date. "
                    + "Fixed pay: basic plus house, commuter and other allowances; overtime and employer costs are not "
                    + "included. Produced by ZenithCore Enterprise ERP.", SMALL);
            foot.setSpacingBefore(14);
            doc.add(foot);
            doc.close();
        }
    }

    private static BigDecimal amt(PO po, String col) {
        Object v = po.get_Value(col);
        return v == null ? Env.ZERO : (BigDecimal) v;
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
