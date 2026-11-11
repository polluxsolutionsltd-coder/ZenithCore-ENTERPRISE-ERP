package ke.co.pollux.zenithcore.controls.idempiere;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.compiere.model.MClient;
import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.util.DB;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Utilities;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import ke.co.pollux.zenithcore.controls.AssetRegister;
import ke.co.pollux.zenithcore.controls.AssetRegister.Condition;
import ke.co.pollux.zenithcore.controls.AssetRegister.Line;
import ke.co.pollux.zenithcore.controls.AssetRegister.Result;
import ke.co.pollux.zenithcore.controls.AssetRegister.Summary;

/** Asset labels, verification sheets and exception reports, and the verification data behind them. */
public final class AssetPdf {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Font TITLE = new Font(Font.HELVETICA, 14, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.WHITE);
    private static final Font H = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);
    private static final Font TH = new Font(Font.HELVETICA, 7, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font TDB = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    private static final Font NOTE = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
    private static final Font BAD = new Font(Font.HELVETICA, 8, Font.BOLD, RED);

    private AssetPdf() {
    }

    // ------------------------------------------------------------------ labels

    /**
     * One label: the number in large type (asset tag, or lot or serial number), the name, a smaller line
     * under the number, and what the QR code holds (the tag itself for assets).
     */
    public record Label(String tag, String name, String group, String qr) {
        public Label(String tag, String name, String group) {
            this(tag, name, group, tag);
        }
    }

    /**
     * A4 sheet of 24 labels, 3 across and 8 down, 63.5 x 33.9 mm (fits common 24-up label stock). Each
     * label: QR code of the tag on the left; institution, asset name, tag number in large type and the
     * owner text on the right.
     */
    public static byte[] labels(String institution, String ownerText, List<Label> labels) throws Exception {
        float mm = Utilities.millimetersToPoints(1);
        float w = 63.5f * mm, h = 33.9f * mm, left = 7.2f * mm, top = 13.1f * mm, gap = 2.5f * mm;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.open();
        PdfContentByte cb = writer.getDirectContent();
        BaseFont reg = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
        BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, false);
        float pageH = PageSize.A4.getHeight();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0 && i % 24 == 0) {
                doc.newPage();
            }
            int k = i % 24, col = k % 3, row = k / 3;
            float x = left + col * (w + gap), y = pageH - top - (row + 1) * h;
            Label l = labels.get(i);
            // light outline to cut by, if printed on plain paper
            cb.setColorStroke(new Color(0xDD, 0xDD, 0xDD));
            cb.setLineWidth(0.3f);
            cb.rectangle(x, y, w, h);
            cb.stroke();
            float qr = 24 * mm;
            qr(cb, l.qr(), x + 2.5f * mm, y + (h - qr) / 2, qr);
            float tx = x + 2.5f * mm + qr + 2 * mm, tw = w - (tx - x) - 2 * mm;
            cb.setColorFill(NAVY);
            cb.rectangle(x, y + h - 1.6f * mm, w, 1.6f * mm);
            cb.fill();
            text(cb, bold, 6, NAVY, fit(bold, institution, 6, tw), tx, y + h - 6 * mm);
            List<String> name = wrap(reg, l.name(), 7, tw, 2);
            for (int n = 0; n < name.size(); n++) {
                text(cb, reg, 7, Color.BLACK, name.get(n), tx, y + h - 10 * mm - n * 3 * mm);
            }
            text(cb, bold, 10, Color.BLACK, fit(bold, l.tag(), 10, tw), tx, y + 9.5f * mm);
            text(cb, reg, 5, Color.DARK_GRAY, fit(reg, l.group(), 5, tw), tx, y + 6.3f * mm);
            List<String> own = wrap(reg, ownerText, 4.6f, tw, 2);
            for (int n = 0; n < own.size(); n++) {
                text(cb, reg, 4.6f, Color.DARK_GRAY, own.get(n), tx, y + 3.6f * mm - n * 1.9f * mm);
            }
        }
        if (labels.isEmpty()) {
            doc.add(new Paragraph("Nothing to label.", TD));
        }
        doc.close();
        return out.toByteArray();
    }

    /** QR code (error correction M, no quiet zone inside the box) drawn as vector squares. */
    static void qr(PdfContentByte cb, String text, float x, float y, float size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        float cell = size / m.getWidth();
        cb.setColorFill(Color.BLACK);
        for (int r = 0; r < m.getHeight(); r++) {
            for (int c = 0; c < m.getWidth(); c++) {
                if (m.get(c, r)) {
                    cb.rectangle(x + c * cell, y + size - (r + 1) * cell, cell + 0.01f, cell + 0.01f);
                }
            }
        }
        cb.fill();
    }

    private static void text(PdfContentByte cb, BaseFont f, float size, Color c, String s, float x, float y) {
        cb.beginText();
        cb.setFontAndSize(f, size);
        cb.setColorFill(c);
        cb.setTextMatrix(x, y);
        cb.showText(s == null ? "" : s);
        cb.endText();
    }

    private static String fit(BaseFont f, String s, float size, float width) {
        if (s == null) {
            return "";
        }
        String t = s;
        while (t.length() > 1 && f.getWidthPoint(t, size) > width) {
            t = t.substring(0, t.length() - 2) + "…";
        }
        return t;
    }

    private static List<String> wrap(BaseFont f, String s, float size, float width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (f.getWidthPoint(next, size) <= width || line.length() == 0) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (out.size() == maxLines) {
                    break;
                }
            }
        }
        if (out.size() < maxLines && line.length() > 0) {
            out.add(line.toString());
        }
        if (out.size() == maxLines) {
            out.set(maxLines - 1, fit(f, out.get(maxLines - 1), size, width));
        }
        return out;
    }

    // ------------------------------------------------------------ verification

    /** A verification and its lines, read for the rule and the reports. */
    public static final class Verification {
        public final PO header;
        public final List<Line> lines = new ArrayList<>();
        public final Map<Integer, Integer> lineIds = new LinkedHashMap<>();
        public final Map<Integer, String[]> extra = new LinkedHashMap<>();   // line -> serial no, custodian name, found-at name
        public final Map<Integer, Integer> assets = new LinkedHashMap<>();   // line -> A_Asset_ID
        public final Map<Integer, Integer> foundAt = new LinkedHashMap<>();  // line -> ZC_AssetLocation_ID

        public Verification(PO header, String trx) {
            this.header = header;
            OpeningBalanceData.query(trx, "SELECT l.Line, CASE WHEN l.A_Asset_ID>0 THEN COALESCE(a.ZC_TagNo, a.Value) || ' ' || a.Name END, "
                    + "l.ZC_TagNo, l.ZC_VerifyResult, l.ZC_ExpectedLocation, fl.Name, l.ZC_Condition, l.Description, "
                    + "COALESCE(l.ZC_AssetCustodian_ID,0), l.ZC_AssetVerificationLine_ID, a.SerNo, u.Name, COALESCE(l.A_Asset_ID,0), "
                    + "COALESCE(l.ZC_AssetLocation_ID,0), a.Name FROM ZC_AssetVerificationLine l "
                    + "LEFT JOIN A_Asset a ON a.A_Asset_ID=l.A_Asset_ID LEFT JOIN ZC_AssetLocation fl ON fl.ZC_AssetLocation_ID=l.ZC_AssetLocation_ID "
                    + "LEFT JOIN AD_User u ON u.AD_User_ID=l.ZC_AssetCustodian_ID WHERE l.ZC_AssetVerification_ID=? AND l.IsActive='Y' "
                    + "ORDER BY l.ZC_ExpectedLocation NULLS LAST, l.Line", rs -> {
                        lines.add(new Line(rs.getInt(1), rs.getString(2), rs.getString(3), Result.of(rs.getString(4)), rs.getString(5),
                                rs.getString(6), Condition.of(rs.getString(7)), rs.getString(8), rs.getInt(9)));
                        lineIds.put(rs.getInt(1), rs.getInt(10));
                        extra.put(rs.getInt(1), new String[] {nn(rs.getString(11)), nn(rs.getString(12)), nn(rs.getString(6)),
                            nn(rs.getString(15))});
                        assets.put(rs.getInt(1), rs.getInt(13));
                        foundAt.put(rs.getInt(1), rs.getInt(14));
                    }, header.get_ID());
        }
    }

    /** Field sheets: one section per expected location, with blank result boxes and signatures. */
    public static byte[] sheets(Verification v, String trx) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 30);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        footer(w, v.header, "Verification sheet");
        doc.open();
        Map<String, List<Line>> byLocation = new LinkedHashMap<>();
        for (Line l : v.lines) {
            byLocation.computeIfAbsent(l.expected() == null ? "(no location)" : l.expected(), k -> new ArrayList<>()).add(l);
        }
        if (byLocation.isEmpty()) {
            byLocation.put(locationName(v.header, trx), List.of());
        }
        boolean first = true;
        for (Map.Entry<String, List<Line>> e : byLocation.entrySet()) {
            if (!first) {
                doc.newPage();
            }
            first = false;
            String responsible = DB.getSQLValueStringEx(trx, "SELECT u.Name FROM ZC_AssetLocation l JOIN AD_User u ON "
                    + "u.AD_User_ID=l.ZC_ResponsibleUser_ID WHERE l.Name=? AND l.AD_Client_ID=?", e.getKey(), v.header.getAD_Client_ID());
            band(doc, v.header, "ASSET VERIFICATION SHEET  |  " + e.getKey(), "Responsible person: " + nn(responsible)
                    + "  |  Verified by: " + user(v.header.get_ValueAsInt("ZC_VerifierUser_ID")) + "  |  Date: " + date(v.header));
            Paragraph how = new Paragraph("Tick one result for every asset: F found here, E found elsewhere (write where), N not found. "
                    + "Condition: G good, F fair, P poor, U unserviceable. Check the tag number on the asset against the sheet.", NOTE);
            how.setSpacingBefore(4);
            doc.add(how);
            PdfPTable t = table(new float[] {0.5f, 1.6f, 3.6f, 1.6f, 1.8f, 0.45f, 0.45f, 0.45f, 0.4f, 0.4f, 0.4f, 0.4f, 3.2f},
                    "#", "Tag", "Asset", "Serial no.", "Custodian", "F", "E", "N", "G", "F", "P", "U", "Found at / remarks");
            int n = 0;
            for (Line l : e.getValue()) {
                String[] x = v.extra.get(l.lineNo());
                t.addCell(cell(String.valueOf(++n), TD));
                t.addCell(cell(nn(l.tag()), TDB));
                t.addCell(cell(x[3], TD));
                t.addCell(cell(x[0], TD));
                t.addCell(cell(x[1], TD));
                for (int b = 0; b < 7; b++) {
                    t.addCell(box());
                }
                t.addCell(cell("", TD));
            }
            doc.add(t);
            Paragraph un = new Paragraph("Assets found here that are not on this sheet (tagged or untagged)", H);
            un.setSpacingBefore(8);
            doc.add(un);
            PdfPTable u = table(new float[] {1.6f, 4f, 1.6f, 0.4f, 0.4f, 0.4f, 0.4f, 4f}, "Tag (if any)", "Description (make, model)",
                    "Serial no.", "G", "F", "P", "U", "Remarks");
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 8; c++) {
                    PdfPCell b = c >= 3 && c <= 6 ? box() : cell(" ", TD);
                    b.setMinimumHeight(16);
                    u.addCell(b);
                }
            }
            doc.add(u);
            signatures(doc, "Verified by", "Responsible person / custodian", "Board of survey (chair)");
        }
        doc.close();
        return out.toByteArray();
    }

    /** Exceptions after the results are in: what Finance and the board of survey must act on. */
    public static byte[] exceptions(Verification v, String trx) throws Exception {
        Summary s = AssetRegister.summary(v.lines);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 26, 32);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        footer(w, v.header, "Verification exceptions");
        doc.open();
        band(doc, v.header, "ASSET VERIFICATION EXCEPTIONS  |  " + locationName(v.header, trx), "Verified by: "
                + user(v.header.get_ValueAsInt("ZC_VerifierUser_ID")) + "  |  Date: " + date(v.header) + "  |  Status: "
                + ("CO".equals(v.header.get_ValueAsString("ZC_VerificationStatus")) ? "Completed" : "Drafted (results may change)"));
        PdfPTable sum = table(new float[] {1, 1, 1, 1, 1, 1}, "Assets on the register", "Found", "Found elsewhere", "Not found",
                "Not in register", "For board of survey");
        int onRegister = (int) v.lines.stream().filter(l -> l.asset() != null).count();
        for (Object o : new Object[] {onRegister, s.byResult().get(Result.FOUND), s.byResult().get(Result.FOUND_ELSEWHERE),
            s.byResult().get(Result.NOT_FOUND), s.byResult().get(Result.NOT_IN_REGISTER), s.forSurvey().size()}) {
            PdfPCell c = cell(String.valueOf(o), new Font(Font.HELVETICA, 12, Font.BOLD, NAVY));
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            sum.addCell(c);
        }
        sum.setSpacingBefore(8);
        doc.add(sum);
        long open = v.lines.stream().filter(l -> l.result() == null).count();
        if (open > 0) {
            doc.add(new Paragraph(open + " line(s) have no result yet.", BAD));
        }

        section(doc, "1. Not found: investigate; report losses to the accounting officer and the board of survey",
                new float[] {1.6f, 3f, 2f, 1.8f, 3.6f}, new String[] {"Tag", "Asset", "Expected at", "Custodian", "Search done"},
                s.notFound(), l -> new String[] {nn(l.tag()), v.extra.get(l.lineNo())[3], nn(l.expected()), v.extra.get(l.lineNo())[1],
                    nn(l.remarks())});
        section(doc, "2. Found elsewhere: the register now shows where each was found; confirm the transfer or return the asset",
                new float[] {1.6f, 3f, 2f, 2f, 3f}, new String[] {"Tag", "Asset", "Expected at", "Found at", "Remarks"},
                s.elsewhere(), l -> new String[] {nn(l.tag()), v.extra.get(l.lineNo())[3], nn(l.expected()), nn(l.found()), nn(l.remarks())});
        section(doc, "3. Poor or unserviceable: for the board of survey (repair, redeploy or dispose)",
                new float[] {1.6f, 3f, 1.4f, 2f, 3.6f}, new String[] {"Tag", "Asset", "Condition", "Location", "Remarks"},
                s.forSurvey(), l -> new String[] {nn(l.tag()), l.asset() == null ? nn(l.remarks()) : v.extra.get(l.lineNo())[3],
                    l.condition() == Condition.POOR ? "Poor" : "Unserviceable", l.result() == Result.FOUND ? nn(l.expected()) : nn(l.found()),
                    nn(l.remarks())});
        section(doc, "4. Not in the register: identify, value and add to the register, or trace the owner",
                new float[] {1.6f, 2f, 1.4f, 5f}, new String[] {"Tag", "Found at", "Condition", "Description"},
                s.unrecorded(), l -> new String[] {nn(l.tag()), nn(l.found()), l.condition() == null ? "" : l.condition().name().charAt(0)
                    + l.condition().name().substring(1).toLowerCase(), nn(l.remarks())});
        signatures(doc, "Verified by", "Finance (asset accountant)", "Board of survey (chair)");
        doc.close();
        return out.toByteArray();
    }

    interface Cols {
        String[] of(Line l);
    }

    private static void section(Document doc, String title, float[] widths, String[] headers, List<Line> lines, Cols cols)
            throws Exception {
        Paragraph p = new Paragraph(title, H);
        p.setSpacingBefore(12);
        doc.add(p);
        if (lines.isEmpty()) {
            doc.add(new Paragraph("None.", TD));
            return;
        }
        PdfPTable t = table(widths, headers);
        for (Line l : lines) {
            for (String c : cols.of(l)) {
                t.addCell(cell(c, TD));
            }
        }
        doc.add(t);
    }

    private static void band(Document doc, PO h, String title, String sub) throws Exception {
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(MClient.get(h.getCtx(), h.getAD_Client_ID()).getName() + "  |  " + h.get_ValueAsString("Name"), SUB));
        c.addElement(new Paragraph(title, TITLE));
        c.addElement(new Paragraph(sub, SUB));
        c.setBackgroundColor(NAVY);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(CYAN);
        c.setBorderWidthBottom(3);
        c.setPadding(8);
        c.setPaddingTop(2);
        band.addCell(c);
        doc.add(band);
    }

    private static void footer(PdfWriter w, PO h, String what) {
        String text = MClient.get(h.getCtx(), h.getAD_Client_ID()).getName() + "  |  " + what + "  |  " + h.get_ValueAsString("Name")
                + "  |  ZenithCore Enterprise ERP";
        w.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter writer, Document document) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT, new Phrase(text, NOTE), document.leftMargin(),
                        16, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), NOTE),
                        document.getPageSize().getWidth() - document.rightMargin(), 16, 0);
            }
        });
    }

    private static void signatures(Document doc, String... who) throws Exception {
        PdfPTable s = new PdfPTable(who.length);
        s.setWidthPercentage(100);
        s.setSpacingBefore(18);
        s.setKeepTogether(true);
        for (String x : who) {
            PdfPCell c = new PdfPCell(new Phrase("\n\n_______________________________\n" + x + "\nName, signature and date", TD));
            c.setBorder(Rectangle.NO_BORDER);
            s.addCell(c);
        }
        doc.add(s);
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.setSpacingBefore(4);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, TH));
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(CYAN);
            c.setBorderWidthBottom(1.5f);
            c.setPadding(3);
            t.addCell(c);
        }
        return t;
    }

    private static PdfPCell cell(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setPadding(3);
        c.setMinimumHeight(15);
        return c;
    }

    private static PdfPCell box() {
        PdfPCell c = new PdfPCell(new Phrase(" ", TD));
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(Color.GRAY);
        c.setBorderWidth(0.5f);
        c.setMinimumHeight(15);
        return c;
    }

    static String locationName(PO h, String trx) {
        int loc = h.get_ValueAsInt("ZC_AssetLocation_ID");
        return loc <= 0 ? "All locations" : DB.getSQLValueStringEx(trx, "SELECT Name FROM ZC_AssetLocation WHERE ZC_AssetLocation_ID=?", loc);
    }

    private static String date(PO h) {
        Timestamp t = (Timestamp) h.get_Value("DateDoc");
        return t == null ? "" : t.toLocalDateTime().toLocalDate().toString();
    }

    private static String user(int id) {
        return BoardPackReport.userName(id);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    /** Writes the PDF to a temporary file named after the record. */
    public static File file(String name, byte[] pdf) throws Exception {
        File f = new File(System.getProperty("java.io.tmpdir"), name.replaceAll("[^A-Za-z0-9.-]+", "-"));
        Files.write(f.toPath(), pdf);
        return f;
    }

    public static String labelText(int client) {
        return MSysConfig.getValue("ZC_ASSET_LABEL_TEXT", "", client);
    }
}
