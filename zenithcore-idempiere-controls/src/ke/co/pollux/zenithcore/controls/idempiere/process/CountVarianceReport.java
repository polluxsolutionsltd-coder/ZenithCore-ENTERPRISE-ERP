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
import java.util.Map;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MCost;
import org.compiere.model.MProduct;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
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

import ke.co.pollux.zenithcore.controls.BlindCount;

/**
 * Stock Count Variance Report (menu). For submitted and posted counts dated in the period (optionally one
 * store): every line with a difference (book, counted, difference, value at current cost, reason, counted
 * by), then totals by store and by cause. PDF. It shows book quantities, so give it to custodians and
 * Finance, not to counters.
 */
@Process
public class CountVarianceReport extends SvrProcess {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE);
    private static final Font H = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);
    private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font TDB = new Font(Font.HELVETICA, 8, Font.BOLD, Color.BLACK);
    private static final Font NEG = new Font(Font.HELVETICA, 8, Font.NORMAL, RED);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);

    @Parameter(name = "DateFrom")
    private Timestamp from;
    @Parameter(name = "DateTo")
    private Timestamp to;
    @Parameter(name = "M_Warehouse_ID")
    private int warehouseId;

    private record Row(String count, String date, BlindCount.VarianceLine line, String countedBy) {
    }

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        List<Row> rows = new ArrayList<>();
        String sql = "SELECT i.DocumentNo, i.MovementDate, w.Name, l.M_Product_ID, l.M_AttributeSetInstance_ID, l.QtyBook, "
                + "l.QtyCount, r.Name, u.Name FROM M_InventoryLine l JOIN M_Inventory i ON i.M_Inventory_ID=l.M_Inventory_ID "
                + "JOIN C_DocType d ON d.C_DocType_ID=i.C_DocType_ID JOIN M_Warehouse w ON w.M_Warehouse_ID=i.M_Warehouse_ID "
                + "LEFT JOIN ZC_AdjustmentReason r ON r.ZC_AdjustmentReason_ID=l.ZC_AdjustmentReason_ID "
                + "LEFT JOIN AD_User u ON u.AD_User_ID=l.ZC_CountedUser_ID "
                + "WHERE i.AD_Client_ID=? AND d.DocSubTypeInv='PI' AND (i.ZC_CountStatus='S' OR i.DocStatus IN ('CO','CL')) "
                + "AND i.DocStatus<>'VO' AND i.MovementDate BETWEEN ? AND ? AND l.QtyCount<>l.QtyBook"
                + (warehouseId > 0 ? " AND i.M_Warehouse_ID=" + warehouseId : "") + " ORDER BY w.Name, i.DocumentNo, l.Line";
        try (PreparedStatement ps = DB.prepareStatement(sql, get_TrxName())) {
            ps.setInt(1, Env.getAD_Client_ID(getCtx()));
            ps.setTimestamp(2, from);
            ps.setTimestamp(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MProduct p = MProduct.get(rs.getInt(4));
                    BigDecimal cost = MCost.getCurrentCost(p, rs.getInt(5), get_TrxName());
                    rows.add(new Row(rs.getString(1), rs.getTimestamp(2).toLocalDateTime().toLocalDate().toString(),
                            new BlindCount.VarianceLine(rs.getString(3), p.getName(), rs.getBigDecimal(6), rs.getBigDecimal(7),
                                    cost == null ? BigDecimal.ZERO : cost, rs.getString(8)), rs.getString(9)));
                }
            }
        }
        List<BlindCount.VarianceLine> lines = rows.stream().map(Row::line).toList();
        String period = from.toLocalDateTime().toLocalDate() + " to " + to.toLocalDateTime().toLocalDate();
        File file = new File(System.getProperty("java.io.tmpdir"), "count-variance-" + from.toLocalDateTime().toLocalDate()
                + "-" + to.toLocalDateTime().toLocalDate() + ".pdf");
        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Stock count variances " + period);
            doc.open();
            PdfPTable head = new PdfPTable(1);
            head.setWidthPercentage(100);
            PdfPCell hc = new PdfPCell();
            hc.addElement(new Paragraph(MClient.get(getCtx()).getName(), TITLE));
            hc.addElement(new Paragraph("STOCK COUNT VARIANCES  |  " + period, SUB));
            hc.setBackgroundColor(NAVY);
            hc.setBorder(Rectangle.BOTTOM);
            hc.setBorderColor(CYAN);
            hc.setBorderWidthBottom(3);
            hc.setPadding(10);
            hc.setPaddingTop(4);
            head.addCell(hc);
            doc.add(head);

            PdfPTable t = table(new float[] {1.6f, 1.1f, 1.6f, 2.4f, 0.9f, 0.9f, 0.9f, 1.3f, 1.6f, 1.5f},
                    "Store", "Count", "Date", "Item", "Book", "Counted", "Diff.", "Value", "Reason", "Counted by");
            for (Row r : rows) {
                BlindCount.VarianceLine l = r.line();
                Font f = l.value().signum() < 0 ? NEG : TD;
                for (String v : new String[] {l.store(), r.count(), r.date(), l.item(), qty(l.book()), qty(l.counted()),
                        qty(l.difference()), money(l.value()), l.cause() == null ? "No reason given" : l.cause(),
                        r.countedBy() == null ? "" : r.countedBy()}) {
                    t.addCell(cell(v, f, v.matches("-?[0-9,.]+") ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT));
                }
            }
            doc.add(t);
            for (boolean byCause : new boolean[] {false, true}) {
                Paragraph h = new Paragraph(byCause ? "By cause" : "By store", H);
                h.setSpacingBefore(12);
                doc.add(h);
                PdfPTable s = table(new float[] {3, 1, 1.5f, 1.5f, 1.5f}, byCause ? "Cause" : "Store", "Lines", "Gains",
                        "Losses", "Net");
                for (Map.Entry<String, BlindCount.Totals> e : BlindCount.totals(lines, byCause).entrySet()) {
                    s.addCell(cell(e.getKey(), TD, Element.ALIGN_LEFT));
                    s.addCell(cell(String.valueOf(e.getValue().lines()), TD, Element.ALIGN_RIGHT));
                    s.addCell(cell(money(e.getValue().gains()), TD, Element.ALIGN_RIGHT));
                    s.addCell(cell(money(e.getValue().losses()), NEG, Element.ALIGN_RIGHT));
                    s.addCell(cell(money(e.getValue().net()), TDB, Element.ALIGN_RIGHT));
                }
                doc.add(s);
            }
            Paragraph foot = new Paragraph("Submitted and posted counts only; lines without a difference are left out. Value: "
                    + "difference at current cost. This report shows book quantities: not for counters. Produced by "
                    + "ZenithCore Enterprise ERP.", SMALL);
            foot.setSpacingBefore(12);
            doc.add(foot);
            doc.close();
        }
        if (processUI != null) {
            processUI.download(file);
        }
        BigDecimal net = lines.stream().map(BlindCount.VarianceLine::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.size() + " lines with a difference, net " + money(net) + ": " + file.getName();
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell c = cell(h, TH, Element.ALIGN_LEFT);
            c.setBorderColor(CYAN);
            c.setBorderWidthBottom(1.5f);
            t.addCell(c);
        }
        return t;
    }

    private static PdfPCell cell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
