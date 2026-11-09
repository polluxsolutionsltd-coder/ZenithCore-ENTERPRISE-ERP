package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.adempiere.base.annotation.Process;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;

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

/**
 * Print Payslips (button on Payroll Run). One A4 payslip per employee, from the payroll lines as
 * calculated, for an approved or completed payroll. The PDF is attached to the run and offered for
 * download.
 */
@Process
public class PayrollRunPayslips extends SvrProcess {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, Color.WHITE);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE);
    private static final Font HEAD = new Font(Font.HELVETICA, 9, Font.BOLD, NAVY);
    private static final Font LABEL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.DARK_GRAY);
    private static final Font VALUE = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);
    private static final Font NET = new Font(Font.HELVETICA, 12, Font.BOLD, Color.WHITE);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);

    private String currency;

    @Override
    protected void prepare() {
        // no parameters: runs on the current payroll run record
    }

    @Override
    protected String doIt() throws Exception {
        PO run = PayrollOutput.approvedRun(getCtx(), getRecord_ID(), get_TrxName());
        List<PayrollOutput.Line> lines = PayrollOutput.lines(getCtx(), run, get_TrxName());
        String employer = PayrollOutput.employerName(getCtx(), run);
        String period = PayrollOutput.period(run);
        currency = PayrollOutput.currency(getCtx(), run);

        File file = new File(System.getProperty("java.io.tmpdir"), "payslips-" + PayrollOutput.periodKey(run) + "-run"
                + run.get_ID() + ".pdf");
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try (FileOutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Payslips " + period);
            doc.addCreator("ZenithCore Enterprise ERP");
            doc.open();
            boolean first = true;
            for (PayrollOutput.Line l : lines) {
                if (!first) {
                    doc.newPage();
                }
                first = false;
                payslip(doc, l, employer, period, run);
            }
            doc.close();
        }
        PayrollOutput.deliver(getCtx(), run, file, processUI, get_TrxName());
        return lines.size() + " payslips for " + period + " created and attached to the payroll run: " + file.getName();
    }

    private void payslip(Document doc, PayrollOutput.Line l, String employer, String period, PO run) throws Exception {
        // Header band
        PdfPTable head = new PdfPTable(new float[] {3, 2});
        head.setWidthPercentage(100);
        PdfPCell left = new PdfPCell();
        left.addElement(new Paragraph(employer, TITLE));
        left.addElement(new Paragraph("PAYSLIP  |  " + period.toUpperCase(Locale.ENGLISH), SUB));
        PdfPCell right = new PdfPCell();
        Paragraph p = new Paragraph("CONFIDENTIAL", SUB);
        p.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(p);
        Paragraph run1 = new Paragraph(run.get_ValueAsString("Name"), SUB);
        run1.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(run1);
        for (PdfPCell c : new PdfPCell[] {left, right}) {
            c.setBackgroundColor(NAVY);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(CYAN);
            c.setBorderWidthBottom(3);
            c.setPadding(10);
            c.setPaddingTop(4);
            head.addCell(c);
        }
        doc.add(head);
        doc.add(spacer());

        // Employee details
        PdfPTable emp = new PdfPTable(new float[] {1.2f, 2, 1.2f, 2});
        emp.setWidthPercentage(100);
        kv(emp, "Employee", l.name());
        kv(emp, "Employee no.", l.employeeNo());
        kv(emp, "KRA PIN", l.text("ZC_KRAPin"));
        kv(emp, "National ID", l.text("ZC_NationalIDNo"));
        kv(emp, "NSSF no.", l.text("ZC_NSSFNo"));
        kv(emp, "SHA no.", l.text("ZC_SHANo"));
        kv(emp, "Department", l.text("ZC_Department"));
        kv(emp, "Pay date", new java.text.SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(run.get_Value("ZC_PayDate")));
        doc.add(emp);
        doc.add(spacer());

        // Earnings and deductions side by side
        PdfPTable two = new PdfPTable(2);
        two.setWidthPercentage(100);
        PdfPTable earn = section("EARNINGS");
        row(earn, "Basic salary", l.amt("ZC_BasicSalary"));
        row(earn, "House allowance", l.amt("ZC_HouseAllowance"));
        row(earn, "Commuter allowance", l.amt("ZC_CommuterAllowance"));
        row(earn, "Overtime", l.amt("ZC_Overtime"));
        row(earn, "Other allowances", l.amt("ZC_OtherAllowances"));
        total(earn, "Gross pay", l.amt("ZC_GrossPay"));
        if (l.amt("ZC_NonCashBenefits").signum() > 0) {
            row(earn, "Non-cash benefits (not paid in cash)", l.amt("ZC_NonCashBenefits"));
            row(earn, "  of which taxable", l.amt("ZC_TaxableNonCash"));
        }

        PdfPTable ded = section("DEDUCTIONS");
        row(ded, "PAYE", l.amt("ZC_PAYE"));
        row(ded, "NSSF", l.amt("ZC_NSSF"));
        row(ded, "SHIF", l.amt("ZC_SHIF"));
        row(ded, "Affordable Housing Levy", l.amt("ZC_HousingLevy"));
        row(ded, "HELB", l.amt("ZC_HELB"));
        row(ded, "Pension", l.amt("ZC_PensionContribution"));
        row(ded, "Insurance premium", l.amt("ZC_InsurancePremium"));
        row(ded, "Staff loan", l.amt("ZC_StaffLoan"));
        row(ded, "SACCO", l.amt("ZC_SACCO"));
        row(ded, "Union dues", l.amt("ZC_UnionDues"));
        total(ded, "Total deductions", l.amt("ZC_TotalDeductions"));
        two.addCell(wrap(earn));
        two.addCell(wrap(ded));
        doc.add(two);

        // Net pay
        PdfPTable net = new PdfPTable(new float[] {3, 2});
        net.setWidthPercentage(100);
        net.setSpacingBefore(6);
        PdfPCell nl = new PdfPCell(new Phrase("NET PAY  " + bankText(l), NET));
        PdfPCell nv = new PdfPCell(new Phrase(currency + " " + money(l.amt("ZC_NetPay")), NET));
        nv.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : new PdfPCell[] {nl, nv}) {
            c.setBackgroundColor(NAVY);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(8);
            net.addCell(c);
        }
        doc.add(net);
        if (l.amt("ZC_CarriedForward").signum() > 0) {
            Paragraph cf = new Paragraph("Two-thirds rule: deductions of " + currency + " " + money(l.amt("ZC_CarriedForward"))
                    + " could not be taken this month and are carried forward.", LABEL);
            cf.setSpacingBefore(4);
            doc.add(cf);
        }
        doc.add(spacer());

        // Tax computation and employer contributions
        PdfPTable two2 = new PdfPTable(2);
        two2.setWidthPercentage(100);
        PdfPTable tax = section("PAYE COMPUTATION");
        row(tax, "Gross pay", l.amt("ZC_GrossPay"));
        row(tax, "Add taxable non-cash benefits", l.amt("ZC_TaxableNonCash"));
        row(tax, "Less allowable pension (NSSF + pension)", l.amt("ZC_AllowablePension"));
        row(tax, "Less SHIF", l.amt("ZC_SHIF"));
        row(tax, "Less Housing Levy", l.amt("ZC_HousingLevy"));
        BigDecimal other = l.amt("ZC_GrossPay").add(l.amt("ZC_TaxableNonCash")).subtract(l.amt("ZC_AllowablePension"))
                .subtract(l.amt("ZC_SHIF")).subtract(l.amt("ZC_HousingLevy")).subtract(l.amt("ZC_TaxablePay"));
        if (other.signum() > 0) {
            row(tax, "Less post-retirement medical / mortgage interest", other);
        }
        total(tax, "Taxable pay", l.amt("ZC_TaxablePay"));
        row(tax, "Tax charged", l.amt("ZC_TaxCharged"));
        row(tax, "Less personal relief", l.amt("ZC_PersonalRelief"));
        row(tax, "Less insurance relief", l.amt("ZC_InsuranceRelief"));
        total(tax, "PAYE", l.amt("ZC_PAYE"));

        PdfPTable er = section("EMPLOYER CONTRIBUTIONS (NOT DEDUCTED)");
        row(er, "NSSF (employer)", l.amt("ZC_EmployerNSSF"));
        row(er, "Housing Levy (employer)", l.amt("ZC_EmployerHousingLevy"));
        row(er, "NITA levy", l.amt("ZC_NITA"));
        total(er, "Total cost to employer", l.amt("ZC_EmployerCost"));
        two2.addCell(wrap(tax));
        two2.addCell(wrap(er));
        doc.add(two2);

        Paragraph foot = new Paragraph("Produced by ZenithCore Enterprise ERP. Report any error on this payslip to the payroll office.", SMALL);
        foot.setSpacingBefore(14);
        doc.add(foot);
    }

    private static String bankText(PayrollOutput.Line l) {
        String bank = l.text("ZC_BankName");
        String acct = l.text("ZC_BankAccountNo");
        if (bank.isEmpty() && acct.isEmpty()) {
            return "";
        }
        String masked = acct.length() > 4 ? "****" + acct.substring(acct.length() - 4) : acct;
        return "(" + (bank + " " + masked).trim() + ")";
    }

    private static Paragraph spacer() {
        Paragraph s = new Paragraph(" ", SMALL);
        s.setLeading(6);
        return s;
    }

    private static void kv(PdfPTable t, String k, String v) {
        PdfPCell a = new PdfPCell(new Phrase(k, LABEL));
        PdfPCell b = new PdfPCell(new Phrase(v == null || v.isEmpty() ? "-" : v, BOLD));
        for (PdfPCell c : new PdfPCell[] {a, b}) {
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(SHADE);
            c.setPadding(3);
            t.addCell(c);
        }
    }

    private static PdfPTable section(String title) {
        PdfPTable t = new PdfPTable(new float[] {3, 1.4f});
        t.setWidthPercentage(100);
        t.setExtendLastRow(false);
        PdfPCell h = new PdfPCell(new Phrase(title, HEAD));
        h.setColspan(2);
        h.setBorder(Rectangle.BOTTOM);
        h.setBorderColor(CYAN);
        h.setBorderWidthBottom(1.5f);
        h.setPaddingBottom(4);
        t.addCell(h);
        return t;
    }

    private static void row(PdfPTable t, String label, BigDecimal v) {
        if (v.signum() == 0 && !label.startsWith("Less") && !label.startsWith("Add") && !label.equals("PAYE")) {
            return;
        }
        cell(t, label, LABEL, Element.ALIGN_LEFT, null);
        cell(t, money(v), VALUE, Element.ALIGN_RIGHT, null);
    }

    private static void total(PdfPTable t, String label, BigDecimal v) {
        cell(t, label, BOLD, Element.ALIGN_LEFT, SHADE);
        cell(t, money(v), BOLD, Element.ALIGN_RIGHT, SHADE);
    }

    private static void cell(PdfPTable t, String text, Font f, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        if (bg != null) {
            c.setBackgroundColor(bg);
        }
        t.addCell(c);
    }

    private static PdfPCell wrap(PdfPTable inner) {
        PdfPCell c = new PdfPCell();
        c.addElement(inner); // composite mode: the inner table keeps its own height
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingLeft(0);
        c.setPaddingTop(0);
        c.setPaddingRight(8);
        return c;
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
