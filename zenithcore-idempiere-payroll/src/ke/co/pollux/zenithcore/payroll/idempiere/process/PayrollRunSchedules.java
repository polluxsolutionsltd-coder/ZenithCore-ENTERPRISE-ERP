package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.adempiere.base.annotation.Process;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;

/**
 * Statutory Schedules (button on Payroll Run). For an approved or completed payroll, writes one CSV
 * per remittance (PAYE/P10, NSSF, SHIF, Housing Levy, NITA, HELB) plus the bank transfer list and a
 * summary, zips them, attaches the zip to the run and offers it for download.
 *
 * <p>These are working schedules with the data each return needs, a total row, and the remittance
 * totals. They are not yet the official upload layouts of iTax, the NSSF and SHA portals, the Housing
 * Levy, NITA, HELB or the employer's bank: those layouts are pending (docs/pending-critical.md, CRIT-02)
 * and will be added as data once the current templates are on file.
 */
@Process
public class PayrollRunSchedules extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current payroll run record
    }

    @Override
    protected String doIt() throws Exception {
        PO run = PayrollOutput.approvedRun(getCtx(), getRecord_ID(), get_TrxName());
        List<PayrollOutput.Line> lines = PayrollOutput.lines(getCtx(), run, get_TrxName());
        String key = PayrollOutput.periodKey(run);
        String period = PayrollOutput.period(run);

        Map<String, String> files = new LinkedHashMap<>();
        Map<String, BigDecimal> remit = new LinkedHashMap<>();

        files.put("paye-p10-" + key + ".csv", csv(lines, l -> true, remit, "PAYE (KRA)", "ZC_PAYE",
                col("KRA PIN", l -> l.text("ZC_KRAPin")),
                amt("Basic salary", "ZC_BasicSalary"), amt("House allowance", "ZC_HouseAllowance"),
                amt("Commuter allowance", "ZC_CommuterAllowance"), amt("Overtime", "ZC_Overtime"),
                amt("Other allowances", "ZC_OtherAllowances"), amt("Total cash pay", "ZC_GrossPay"),
                amt("Non-cash benefits", "ZC_NonCashBenefits"), amt("Taxable non-cash benefits", "ZC_TaxableNonCash"),
                amt("Allowable pension (NSSF + pension)", "ZC_AllowablePension"), amt("SHIF", "ZC_SHIF"),
                amt("Housing Levy", "ZC_HousingLevy"), amt("Post-retirement medical fund", "ZC_PostRetirementMedical"),
                amt("Mortgage interest", "ZC_MortgageInterest"), amt("Taxable pay", "ZC_TaxablePay"),
                amt("Tax charged", "ZC_TaxCharged"), amt("Personal relief", "ZC_PersonalRelief"),
                amt("Insurance relief", "ZC_InsuranceRelief"), amt("PAYE", "ZC_PAYE")));

        files.put("nssf-" + key + ".csv", csv(lines, l -> true, remit, "NSSF (employee + employer)", null,
                col("National ID", l -> l.text("ZC_NationalIDNo")), col("KRA PIN", l -> l.text("ZC_KRAPin")),
                col("NSSF number", l -> l.text("ZC_NSSFNo")), amt("Gross pay", "ZC_GrossPay"),
                amt("Tier I employee", "ZC_NSSFTier1"), amt("Tier II employee", "ZC_NSSFTier2"),
                amt("Employee total", "ZC_NSSF"), amt("Employer total", "ZC_EmployerNSSF"),
                sum("Total", "ZC_NSSF", "ZC_EmployerNSSF")));
        remit.put("NSSF (employee + employer)", total(lines, "ZC_NSSF", "ZC_EmployerNSSF"));

        files.put("shif-" + key + ".csv", csv(lines, l -> true, remit, "SHIF (SHA)", "ZC_SHIF",
                col("National ID", l -> l.text("ZC_NationalIDNo")), col("KRA PIN", l -> l.text("ZC_KRAPin")),
                col("SHA number", l -> l.text("ZC_SHANo")), amt("Gross pay", "ZC_GrossPay"), amt("SHIF", "ZC_SHIF")));

        files.put("housing-levy-" + key + ".csv", csv(lines, l -> true, remit, "Housing Levy (employee + employer)", null,
                col("National ID", l -> l.text("ZC_NationalIDNo")), col("KRA PIN", l -> l.text("ZC_KRAPin")),
                amt("Gross pay", "ZC_GrossPay"), amt("Employee levy", "ZC_HousingLevy"),
                amt("Employer levy", "ZC_EmployerHousingLevy"), sum("Total", "ZC_HousingLevy", "ZC_EmployerHousingLevy")));
        remit.put("Housing Levy (employee + employer)", total(lines, "ZC_HousingLevy", "ZC_EmployerHousingLevy"));

        files.put("nita-" + key + ".csv", csv(lines, l -> true, remit, "NITA levy", "ZC_NITA",
                col("KRA PIN", l -> l.text("ZC_KRAPin")), amt("NITA levy", "ZC_NITA")));

        files.put("helb-" + key + ".csv", csv(lines, l -> l.amt("ZC_HELB").signum() > 0, remit, "HELB", "ZC_HELB",
                col("National ID", l -> l.text("ZC_NationalIDNo")), col("KRA PIN", l -> l.text("ZC_KRAPin")),
                amt("HELB deduction", "ZC_HELB")));

        files.put("bank-transfers-" + key + ".csv", csv(lines, l -> l.amt("ZC_NetPay").signum() > 0, remit,
                "Net salaries (bank)", "ZC_NetPay",
                col("Bank", l -> l.text("ZC_BankName")), col("Account number", l -> l.text("ZC_BankAccountNo")),
                amt("Net pay", "ZC_NetPay"), col("Reference", l -> "Salary " + period)));

        StringBuilder summary = new StringBuilder();
        summary.append(row("Remittance", "Amount", "Employees"));
        for (Map.Entry<String, BigDecimal> e : remit.entrySet()) {
            summary.append(row(e.getKey(), money(e.getValue()), ""));
        }
        summary.append(row("Payroll run", run.get_ValueAsString("Name"), String.valueOf(lines.size())));
        summary.append(row("Status", "CO".equals(run.get_ValueAsString("ZC_PayrollStatus")) ? "Completed" : "Approved", ""));
        files.put("summary-" + key + ".csv", summary.toString());

        files.put("README.txt", "Statutory schedules for " + PayrollOutput.employerName(getCtx(), run) + ", " + period
                + ", produced by ZenithCore Enterprise ERP from the payroll lines as calculated.\r\n\r\n"
                + "These are working schedules: each file has the data its return needs, one row per employee and a total row.\r\n"
                + "They are NOT yet the official upload layouts for iTax (P10), the NSSF and SHA portals, the Housing Levy,\r\n"
                + "NITA, HELB or the bank. Until those layouts are added (pending item CRIT-02), key or paste the figures\r\n"
                + "into the official templates and reconcile each total to summary-" + key + ".csv.\r\n");

        File zip = new File(System.getProperty("java.io.tmpdir"), "statutory-schedules-" + key + "-run" + run.get_ID() + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                // UTF-8 with byte-order mark so Excel opens names correctly
                if (f.getKey().endsWith(".csv")) {
                    z.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                }
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        PayrollOutput.deliver(getCtx(), run, zip, processUI, get_TrxName());
        for (Map.Entry<String, BigDecimal> e : remit.entrySet()) {
            addLog(e.getKey() + ": " + money(e.getValue()));
        }
        return (files.size() - 1) + " schedules for " + period + " created and attached to the payroll run: " + zip.getName();
    }

    /** A schedule column. */
    private record Col(String header, Function<PayrollOutput.Line, String> value, String[] sumColumns) {
    }

    private static Col col(String header, Function<PayrollOutput.Line, String> value) {
        return new Col(header, value, null);
    }

    private static Col amt(String header, String column) {
        return new Col(header, l -> money(l.amt(column)), new String[] {column});
    }

    private static Col sum(String header, String... columns) {
        return new Col(header, l -> money(sumOf(l, columns)), columns);
    }

    private static BigDecimal sumOf(PayrollOutput.Line l, String... columns) {
        BigDecimal t = BigDecimal.ZERO;
        for (String c : columns) {
            t = t.add(l.amt(c));
        }
        return t;
    }

    private static BigDecimal total(List<PayrollOutput.Line> lines, String... columns) {
        return lines.stream().map(l -> sumOf(l, columns)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Employee number and name, then the given columns; a total row; and the remittance total recorded. */
    private static String csv(List<PayrollOutput.Line> lines, Predicate<PayrollOutput.Line> include,
            Map<String, BigDecimal> remit, String remitName, String remitColumn, Col... cols) {
        List<PayrollOutput.Line> rows = lines.stream().filter(include).toList();
        StringBuilder sb = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("Employee no.", "Name"));
        for (Col c : cols) {
            head.add(c.header());
        }
        sb.append(row(head.toArray(String[]::new)));
        for (PayrollOutput.Line l : rows) {
            List<String> v = new ArrayList<>(List.of(l.employeeNo(), l.name()));
            for (Col c : cols) {
                v.add(c.value().apply(l));
            }
            sb.append(row(v.toArray(String[]::new)));
        }
        List<String> t = new ArrayList<>(List.of("TOTAL", rows.size() + " employees"));
        for (Col c : cols) {
            t.add(c.sumColumns() == null ? "" : money(total(rows, c.sumColumns())));
        }
        sb.append(row(t.toArray(String[]::new)));
        if (remitColumn != null) {
            remit.put(remitName, total(rows, remitColumn));
        }
        return sb.toString();
    }

    private static String row(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            String v = values[i] == null ? "" : values[i];
            if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
                v = "'" + v; // keep spreadsheet programs from treating text as a formula
            }
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            sb.append(i > 0 ? "," : "").append(v);
        }
        return sb.append("\r\n").toString();
    }

    /** Plain amount with two decimals and no thousands separator, so spreadsheets read it as a number. */
    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
