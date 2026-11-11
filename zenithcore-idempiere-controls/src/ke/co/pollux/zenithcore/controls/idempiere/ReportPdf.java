package ke.co.pollux.zenithcore.controls.idempiere;

import java.awt.Color;
import java.time.LocalDate;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

/** Shared layout for ZenithCore PDF reports: brand colours, title band, footer and table cells. */
public final class ReportPdf {

    public static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    public static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    public static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    public static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    public static final Color AMBER = new Color(0xB3, 0x6B, 0x00);
    public static final Font TITLE = new Font(Font.HELVETICA, 14, Font.BOLD, Color.WHITE);
    public static final Font SUB = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.WHITE);
    public static final Font H = new Font(Font.HELVETICA, 10, Font.BOLD, NAVY);
    public static final Font TH = new Font(Font.HELVETICA, 7, Font.BOLD, NAVY);
    public static final Font TD = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    public static final Font TDB = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    public static final Font TDR = new Font(Font.HELVETICA, 7.5f, Font.BOLD, RED);
    public static final Font TDA = new Font(Font.HELVETICA, 7.5f, Font.BOLD, AMBER);
    public static final Font NOTE = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
    public static final Font BIG = new Font(Font.HELVETICA, 11, Font.BOLD, NAVY);

    private ReportPdf() {
    }


    public static void band(Document doc, String institution, String title, String sub) throws Exception {
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(institution, SUB));
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

    public static void footer(PdfWriter w, String institution, String what) {
        String text = institution + "  |  " + what + "  |  printed " + LocalDate.now() + "  |  ZenithCore Enterprise ERP";
        w.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter writer, Document document) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT, new Phrase(text, NOTE), document.leftMargin(), 16, 0);
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), NOTE),
                        document.getPageSize().getWidth() - document.rightMargin(), 16, 0);
            }
        });
    }

    public static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(headers.length == 0 ? 0 : 1);
        t.setSpacingBefore(4);
        for (String h : headers) {
            t.addCell(head(h));
        }
        return t;
    }

    public static PdfPCell head(String h) {
        PdfPCell c = new PdfPCell(new Phrase(h, TH));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(CYAN);
        c.setBorderWidthBottom(1.5f);
        c.setPadding(3);
        return c;
    }

    public static PdfPCell cell(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setPadding(3);
        c.setMinimumHeight(14);
        return c;
    }

    public static PdfPCell num(String text, Font f) {
        PdfPCell c = cell(text, f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }
}
