package ke.co.pollux.zenithcore.controls.idempiere;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.compiere.model.MAcctSchema;
import org.compiere.model.MClient;
import org.compiere.model.MClientInfo;
import org.compiere.model.MCurrency;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.util.DB;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import ke.co.pollux.zenithcore.controls.BoardPack;
import ke.co.pollux.zenithcore.controls.BoardPack.Ageing;
import ke.co.pollux.zenithcore.controls.BoardPack.Balance;
import ke.co.pollux.zenithcore.controls.BoardPack.KeyFigures;
import ke.co.pollux.zenithcore.controls.BoardPack.Kind;
import ke.co.pollux.zenithcore.controls.BoardPack.OpenItem;
import ke.co.pollux.zenithcore.controls.BoardPack.Row;
import ke.co.pollux.zenithcore.controls.BoardPack.Statement;
import ke.co.pollux.zenithcore.controls.BoardPack.Type;
import ke.co.pollux.zenithcore.controls.BoardPack.UnitBalance;
import ke.co.pollux.zenithcore.controls.BoardPack.UnitResult;

/**
 * Reads the ledger and related records for a board pack and renders the PDF. Sources: Fact_Acct (actual
 * postings, primary accounting schema), approved ZenithCore budgets (pro rata to date), open customer and
 * supplier invoices at the period end, bank accounts and statements, and the audit query register.
 */
public final class BoardPackReport {

    private static final Color NAVY = new Color(0x00, 0x38, 0x7F);
    private static final Color BLUE = new Color(0x00, 0x88, 0xC0);
    private static final Color CYAN = new Color(0x00, 0xB8, 0xF0);
    private static final Color SHADE = new Color(0xEE, 0xF4, 0xFA);
    private static final Color TILE = new Color(0xF4, 0xF8, 0xFC);
    private static final Color RED = new Color(0xB0, 0x1E, 0x1E);
    private static final Color GREEN = new Color(0x1E, 0x7A, 0x34);
    private static final Font TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, Color.WHITE);
    private static final Font COVER = new Font(Font.HELVETICA, 11, Font.NORMAL, Color.WHITE);
    private static final Font H1 = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
    private static final Font TH = new Font(Font.HELVETICA, 7.5f, Font.BOLD, NAVY);
    private static final Font TD = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font TDB = new Font(Font.HELVETICA, 8, Font.BOLD, Color.BLACK);
    private static final Font TDH = new Font(Font.HELVETICA, 8, Font.BOLD, BLUE);
    private static final Font NEG = new Font(Font.HELVETICA, 8, Font.NORMAL, RED);
    private static final Font NEGB = new Font(Font.HELVETICA, 8, Font.BOLD, RED);
    private static final Font POS = new Font(Font.HELVETICA, 8, Font.NORMAL, GREEN);
    private static final Font TILE_L = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.DARK_GRAY);
    private static final Font TILE_V = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font NOTE = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
    private static final Font WARN = new Font(Font.HELVETICA, 8, Font.BOLD, RED);

    private final PO pack;
    private final String trx;
    private final int client;
    private final int schema;
    private final String currency;

    public LocalDate periodStart, periodEnd, yearStart, priorStart, priorEnd;
    public String periodName;
    public int periodsElapsed, periodsInYear;
    public final List<Balance> balances = new ArrayList<>();
    public String unitDimension;
    public final List<UnitResult> units = new ArrayList<>();
    public final List<String[]> costCentres = new ArrayList<>();
    public final List<Object[]> banks = new ArrayList<>();
    public BigDecimal cash = BigDecimal.ZERO;
    public Ageing receivables, payables;
    public final List<String[]> openQueries = new ArrayList<>();
    public final Map<String, Integer> queriesByRisk = new LinkedHashMap<>();
    public Statement performance, position;
    public KeyFigures key;

    public BoardPackReport(PO pack, String trx) {
        this.pack = pack;
        this.trx = trx;
        this.client = pack.getAD_Client_ID();
        this.schema = MClientInfo.get(pack.getCtx(), client).getC_AcctSchema1_ID();
        this.currency = MCurrency.getISO_Code(pack.getCtx(), MAcctSchema.get(schema).getC_Currency_ID());
        load();
    }

    private static LocalDate d(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toLocalDate();
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay());
    }

    private void load() {
        int period = pack.get_ValueAsInt("C_Period_ID");
        int[] year = new int[1];
        OpeningBalanceData.query(trx, "SELECT StartDate, EndDate, C_Year_ID, Name FROM C_Period WHERE C_Period_ID=?", rs -> {
            periodStart = d(rs.getTimestamp(1));
            periodEnd = d(rs.getTimestamp(2));
            year[0] = rs.getInt(3);
            periodName = rs.getString(4);
        }, period);
        yearStart = d(DB.getSQLValueTSEx(trx, "SELECT MIN(StartDate) FROM C_Period WHERE C_Year_ID=? AND PeriodType='S'", year[0]));
        periodsInYear = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM C_Period WHERE C_Year_ID=? AND PeriodType='S'", year[0]);
        periodsElapsed = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM C_Period WHERE C_Year_ID=? AND PeriodType='S' AND StartDate<=?",
                year[0], ts(periodStart));
        priorStart = yearStart.minusYears(1);
        priorEnd = periodEnd.minusYears(1);
        Timestamp pS = ts(periodStart), pE = ts(periodEnd.plusDays(1)), yS = ts(yearStart), qS = ts(priorStart),
                qE = ts(priorEnd.plusDays(1));

        // chart tree: group each account under its ancestor at the chosen level
        int level = Math.max(1, pack.get_ValueAsInt("ZC_GroupLevel"));
        int tree = DB.getSQLValueEx(trx, "SELECT e.AD_Tree_ID FROM C_AcctSchema_Element ase JOIN C_Element e ON "
                + "e.C_Element_ID=ase.C_Element_ID WHERE ase.C_AcctSchema_ID=? AND ase.ElementType='AC'", schema);
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, String> name = new HashMap<>();
        OpeningBalanceData.query(trx, "SELECT Node_ID, Parent_ID FROM AD_TreeNode WHERE AD_Tree_ID=?",
                rs -> parent.put(rs.getInt(1), rs.getInt(2)), tree);
        Map<Integer, String[]> acct = new HashMap<>();
        OpeningBalanceData.query(trx, "SELECT ev.C_ElementValue_ID, ev.Value, ev.Name, ev.AccountType FROM C_ElementValue ev "
                + "JOIN C_AcctSchema_Element ase ON ase.C_Element_ID=ev.C_Element_ID AND ase.ElementType='AC' WHERE ase.C_AcctSchema_ID=?",
                rs -> {
                    name.put(rs.getInt(1), rs.getString(3));
                    acct.put(rs.getInt(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                }, schema);

        Map<Integer, BigDecimal[]> amounts = new LinkedHashMap<>();
        OpeningBalanceData.query(trx, "SELECT f.Account_ID, "
                + "SUM(CASE WHEN f.DateAcct>=? AND f.DateAcct<? THEN f.AmtAcctDr-f.AmtAcctCr ELSE 0 END), "
                + "SUM(CASE WHEN f.DateAcct>=? AND f.DateAcct<? THEN f.AmtAcctDr-f.AmtAcctCr ELSE 0 END), "
                + "SUM(CASE WHEN f.DateAcct>=? AND f.DateAcct<? THEN f.AmtAcctDr-f.AmtAcctCr ELSE 0 END), "
                + "SUM(f.AmtAcctDr-f.AmtAcctCr), "
                + "SUM(CASE WHEN f.DateAcct<? THEN f.AmtAcctDr-f.AmtAcctCr ELSE 0 END) "
                + "FROM Fact_Acct f WHERE f.C_AcctSchema_ID=? AND f.PostingType='A' AND f.DateAcct<? GROUP BY f.Account_ID",
                rs -> amounts.put(rs.getInt(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)}),
                pS, pE, yS, pE, qS, qE, yS, schema, pE);
        Map<Integer, BigDecimal> budget = new HashMap<>();
        OpeningBalanceData.query(trx, "SELECT bl.Account_ID, SUM(bl.ZC_Amount) FROM ZC_BudgetLine bl JOIN ZC_Budget b ON "
                + "b.ZC_Budget_ID=bl.ZC_Budget_ID WHERE b.AD_Client_ID=? AND b.C_Year_ID=? AND b.ZC_ApprovedUser_ID IS NOT NULL "
                + "AND b.IsActive='Y' AND bl.IsActive='Y' GROUP BY bl.Account_ID",
                rs -> budget.put(rs.getInt(1), BoardPack.budgetToDate(rs.getBigDecimal(2), periodsElapsed, periodsInYear)),
                client, year[0]);
        for (int id : budget.keySet()) {
            amounts.putIfAbsent(id, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO});
        }
        for (Map.Entry<Integer, BigDecimal[]> e : amounts.entrySet()) {
            String[] a = acct.get(e.getKey());
            if (a == null || "M".equals(a[2])) {
                continue;
            }
            Type type = Type.valueOf(a[2]);
            BigDecimal[] v = e.getValue();
            balances.add(new Balance(group(e.getKey(), parent, name, level), a[0] + " " + a[1], type, v[0], v[1],
                    budget.get(e.getKey()), v[2], v[3], v[4]));
        }
        performance = BoardPack.performance(balances);
        position = BoardPack.position(balances);

        // business units: User List 1
        unitDimension = DB.getSQLValueStringEx(trx, "SELECT Name FROM C_AcctSchema_Element WHERE C_AcctSchema_ID=? "
                + "AND ElementType='U1' AND IsActive='Y'", schema);
        if (unitDimension != null) {
            List<UnitBalance> ub = new ArrayList<>();
            OpeningBalanceData.query(trx, "SELECT u.Name, ev.AccountType, SUM(f.AmtAcctDr-f.AmtAcctCr) FROM Fact_Acct f "
                    + "JOIN C_ElementValue ev ON ev.C_ElementValue_ID=f.Account_ID LEFT JOIN C_ElementValue u ON "
                    + "u.C_ElementValue_ID=f.User1_ID WHERE f.C_AcctSchema_ID=? AND f.PostingType='A' AND ev.AccountType IN ('R','E') "
                    + "AND f.DateAcct>=? AND f.DateAcct<? GROUP BY u.Name, ev.AccountType",
                    rs -> ub.add(new UnitBalance(rs.getString(1), Type.valueOf(rs.getString(2)), rs.getBigDecimal(3))), schema, yS, pE);
            units.addAll(BoardPack.units(ub));
        }

        // budget execution by cost centre
        OpeningBalanceData.query(trx, "SELECT COALESCE(a.Name,'(no cost centre)'), SUM(bl.ZC_Amount), SUM((SELECT "
                + "COALESCE(SUM(f.AmtAcctDr-f.AmtAcctCr),0) FROM Fact_Acct f WHERE f.C_AcctSchema_ID=? AND f.PostingType='A' "
                + "AND f.Account_ID=bl.Account_ID AND COALESCE(f.C_Activity_ID,0)=COALESCE(bl.C_Activity_ID,0) AND f.DateAcct>=? "
                + "AND f.DateAcct<?)) FROM ZC_BudgetLine bl JOIN ZC_Budget b ON b.ZC_Budget_ID=bl.ZC_Budget_ID LEFT JOIN C_Activity a "
                + "ON a.C_Activity_ID=bl.C_Activity_ID WHERE b.AD_Client_ID=? AND b.C_Year_ID=? AND b.ZC_ApprovedUser_ID IS NOT NULL "
                + "AND b.IsActive='Y' AND bl.IsActive='Y' GROUP BY 1 ORDER BY 1", rs -> {
                    BigDecimal annual = rs.getBigDecimal(2), actual = rs.getBigDecimal(3);
                    BigDecimal toDate = BoardPack.budgetToDate(annual, periodsElapsed, periodsInYear);
                    costCentres.add(new String[] {rs.getString(1), money(annual), money(toDate), money(actual),
                            toDate.signum() == 0 ? "" : BoardPack.pct(actual, toDate) + "%", money(annual.subtract(actual))});
                }, schema, yS, pE, client, year[0]);

        // cash and bank
        OpeningBalanceData.query(trx, "SELECT ba.Name, ba.AccountNo, (SELECT COALESCE(SUM(f.AmtAcctDr-f.AmtAcctCr),0) FROM Fact_Acct f "
                + "WHERE f.C_AcctSchema_ID=? AND f.PostingType='A' AND f.DateAcct<? AND f.Account_ID IN (SELECT vc.Account_ID FROM "
                + "C_BankAccount_Acct x JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID IN (x.B_Asset_Acct, x.B_InTransit_Acct) "
                + "WHERE x.C_BankAccount_ID=ba.C_BankAccount_ID AND x.C_AcctSchema_ID=?)), "
                + "(SELECT MAX(s.StatementDate) FROM C_BankStatement s WHERE s.C_BankAccount_ID=ba.C_BankAccount_ID AND s.DocStatus IN "
                + "('CO','CL') AND s.StatementDate<?), "
                + "(SELECT s.EndingBalance FROM C_BankStatement s WHERE s.C_BankAccount_ID=ba.C_BankAccount_ID AND s.DocStatus IN "
                + "('CO','CL') AND s.StatementDate<? ORDER BY s.StatementDate DESC, s.C_BankStatement_ID DESC FETCH FIRST 1 ROWS ONLY), "
                + "(SELECT COUNT(*) FROM C_Payment p WHERE p.C_BankAccount_ID=ba.C_BankAccount_ID AND p.DocStatus IN ('CO','CL') "
                + "AND p.DateTrx<? AND NOT EXISTS (SELECT 1 FROM C_BankStatementLine bl JOIN C_BankStatement b ON "
                + "b.C_BankStatement_ID=bl.C_BankStatement_ID WHERE bl.C_Payment_ID=p.C_Payment_ID AND b.DocStatus IN ('CO','CL') "
                + "AND b.StatementDate<?)) FROM C_BankAccount ba WHERE ba.AD_Client_ID=? AND ba.IsActive='Y' ORDER BY ba.Name", rs -> {
                    banks.add(new Object[] {rs.getString(1) + " " + rs.getString(2), rs.getBigDecimal(3), d(rs.getTimestamp(4)),
                            rs.getBigDecimal(5), rs.getInt(6)});
                }, schema, pE, schema, pE, pE, pE, pE, client);
        for (Object[] b : banks) {
            cash = cash.add((BigDecimal) b[1]);
        }

        // open invoices at the period end
        List<OpenItem> ar = new ArrayList<>(), ap = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT bp.Name, paymentTermDueDate(i.C_PaymentTerm_ID, i.DateInvoiced), "
                + "ABS(invoiceOpenToDate(i.C_Invoice_ID, 0, ?::date)) * CASE WHEN dt.DocBaseType IN ('ARC','APC') THEN -1 ELSE 1 END, "
                + "i.IsSOTrx FROM C_Invoice i JOIN C_DocType dt ON dt.C_DocType_ID=i.C_DocType_ID JOIN C_BPartner bp ON "
                + "bp.C_BPartner_ID=i.C_BPartner_ID WHERE i.AD_Client_ID=? AND i.DocStatus IN ('CO','CL') AND i.DateAcct<? "
                + "AND i.C_Currency_ID=?", rs -> {
                    BigDecimal open = rs.getBigDecimal(3);
                    if (open != null && open.signum() != 0) {
                        LocalDate due = rs.getTimestamp(2) == null ? null : d(rs.getTimestamp(2));
                        ("Y".equals(rs.getString(4)) ? ar : ap).add(new OpenItem(rs.getString(1), due == null ? periodEnd : due, open));
                    }
                }, ts(periodEnd), client, pE, MAcctSchema.get(schema).getC_Currency_ID());
        receivables = BoardPack.ageing(ar, periodEnd, 10);
        payables = BoardPack.ageing(ap, periodEnd, 10);
        key = BoardPack.keyFigures(performance, cash, receivables, payables,
                ChronoUnit.DAYS.between(yearStart, periodEnd) + 1);

        // audit queries still open at the period end
        OpeningBalanceData.query(trx, "SELECT q.Value, q.Name, COALESCE(rl.Name, q.ZC_RiskRating), q.ZC_DueDate, u.Name FROM ZC_AuditQuery q "
                + "LEFT JOIN AD_User u ON u.AD_User_ID=q.ZC_ResponsibleUser_ID LEFT JOIN AD_Column c ON c.AD_Table_ID="
                + "(SELECT AD_Table_ID FROM AD_Table WHERE TableName='ZC_AuditQuery') AND c.ColumnName='ZC_RiskRating' "
                + "LEFT JOIN AD_Ref_List rl ON rl.AD_Reference_ID=c.AD_Reference_Value_ID AND rl.Value=q.ZC_RiskRating "
                + "WHERE q.AD_Client_ID=? AND q.IsActive='Y' AND (q.ZC_DateClosed IS NULL OR q.ZC_DateClosed>=?) AND q.Created<? "
                + "ORDER BY q.ZC_DueDate NULLS LAST", rs -> {
                    LocalDate due = d(rs.getTimestamp(4));
                    openQueries.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), due == null ? "" : due.toString(),
                            rs.getString(5) == null ? "" : rs.getString(5), due != null && due.isBefore(periodEnd) ? "overdue" : ""});
                    queriesByRisk.merge(rs.getString(3) == null ? "-" : rs.getString(3), 1, Integer::sum);
                }, client, pE, pE);
    }

    private static String group(int id, Map<Integer, Integer> parent, Map<Integer, String> name, int level) {
        List<Integer> path = new ArrayList<>();
        Integer n = id;
        while (n != null && n != 0 && path.size() < 50) {
            path.add(0, n);
            n = parent.get(n);
        }
        int pick = path.size() >= level ? path.get(level - 1) : id;
        return name.getOrDefault(pick, String.valueOf(pick));
    }

    // ------------------------------------------------------------------ PDF

    /** Renders the pack. approval = null for a draft version. */
    public byte[] render(int version, String preparedBy) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 32, 40);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        String clientName = MClient.get(pack.getCtx(), client).getName();
        String footer = clientName + "  |  Board finance committee pack  |  " + periodName + "  |  version " + version
                + "  |  amounts in " + currency;
        w.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter writer, Document document) {
                PdfContentByte cb = writer.getDirectContent();
                ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(footer, NOTE), 36, 22, 0);
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), NOTE),
                        document.getPageSize().getWidth() - 36, 22, 0);
            }
        });
        doc.addTitle("Board pack " + periodName);
        doc.open();

        // cover
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(clientName, TITLE));
        c.addElement(new Paragraph("BOARD FINANCE COMMITTEE PACK", COVER));
        c.addElement(new Paragraph(periodName + "  (" + periodStart + " to " + periodEnd + "; year to date from " + yearStart + ")",
                COVER));
        c.addElement(new Paragraph("Version " + version + ", prepared by " + preparedBy + "  |  DRAFT until approved", COVER));
        c.setBackgroundColor(NAVY);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(CYAN);
        c.setBorderWidthBottom(4);
        c.setPadding(14);
        c.setPaddingTop(8);
        band.addCell(c);
        doc.add(band);

        PdfPTable tiles = new PdfPTable(4);
        tiles.setWidthPercentage(100);
        tiles.setSpacingBefore(12);
        tile(tiles, "Revenue, year to date", money(key.revenueYtd()), null);
        tile(tiles, "Expenditure, year to date", money(key.expenditureYtd()), null);
        tile(tiles, "Surplus / (deficit), year to date", money(key.surplusYtd()), key.surplusYtd().signum() < 0);
        tile(tiles, "Cash and bank", money(key.cash()), key.cash().signum() < 0);
        tile(tiles, "Debtors (overdue " + money(receivables.overdue()) + ")", money(key.receivables()), null);
        tile(tiles, "Creditors (overdue " + money(payables.overdue()) + ")", money(key.payables()), null);
        tile(tiles, "Debtor days", key.debtorDays() == null ? "-" : key.debtorDays().toString(), null);
        tile(tiles, "Budget absorption (expenditure)", key.budgetAbsorptionPct() == null ? "no budget"
                : key.budgetAbsorptionPct() + "%", null);
        doc.add(tiles);

        doc.add(h1("Contents"));
        String[] sections = {"1. Finance commentary", "2. Statement of financial performance", "3. Statement of financial position",
            "4. Business units", "5. Budget execution by cost centre", "6. Cash and bank", "7. Debtors", "8. Creditors",
            "9. Open audit queries", "Basis of preparation"};
        for (String s : sections) {
            doc.add(new Paragraph(s, BODY));
        }

        doc.add(h1("1. Finance commentary"));
        String commentary = pack.get_ValueAsString("ZC_Commentary");
        doc.add(new Paragraph(commentary.isBlank() ? "(No commentary entered.)" : commentary, BODY));

        doc.newPage();
        doc.add(h1("2. Statement of financial performance"));
        PdfPTable t = table(new float[] {3.4f, 1.3f, 1.4f, 1.4f, 1.3f, 1.4f}, "", "Month", "Year to date", "Budget to date",
                "Variance", "Prior year to date");
        for (Row r : performance.rows()) {
            if (r.kind() == Kind.HEADING) {
                heading(t, r.label(), 6);
                continue;
            }
            Font f = r.kind() == Kind.LINE ? TD : TDB;
            t.addCell(cell(r.label(), f, Element.ALIGN_LEFT, r.kind() == Kind.RESULT));
            t.addCell(num(r.month(), f, r.kind() == Kind.RESULT));
            t.addCell(num(r.ytd(), f, r.kind() == Kind.RESULT));
            t.addCell(num(r.budget(), f, r.kind() == Kind.RESULT));
            t.addCell(variance(r.variance(), r.kind() == Kind.RESULT));
            t.addCell(num(r.priorYtd(), f, r.kind() == Kind.RESULT));
        }
        doc.add(t);
        doc.add(note("Variance: positive is favourable (revenue above budget, expenditure below budget). Budget to date is the "
                + "approved annual budget x " + periodsElapsed + "/" + periodsInYear + ". Totals compare the budget only with the "
                + "lines it covers; lines without a budget show no variance."));

        doc.add(h1("3. Statement of financial position"));
        PdfPTable p = table(new float[] {4f, 1.6f, 1.6f}, "", "At " + periodEnd, "At " + yearStart.minusDays(1));
        for (Row r : position.rows()) {
            if (r.kind() == Kind.HEADING) {
                heading(p, r.label(), 3);
                continue;
            }
            Font f = r.kind() == Kind.LINE ? TD : TDB;
            p.addCell(cell(r.label(), f, Element.ALIGN_LEFT, r.kind() == Kind.RESULT));
            p.addCell(num(r.closing(), f, r.kind() == Kind.RESULT));
            p.addCell(num(r.priorClosing(), f, r.kind() == Kind.RESULT));
        }
        doc.add(p);
        if (!position.balances()) {
            doc.add(new Paragraph("The ledger does not balance at the period end: difference " + money(position.difference())
                    + ". Check unposted or one-sided entries before release.", WARN));
        }

        doc.newPage();
        doc.add(h1("4. Business units"));
        if (unitDimension == null) {
            doc.add(note("Business units are not set up: activate User List 1 in the accounting schema and assign a business unit "
                    + "to income and expenditure."));
        } else {
            PdfPTable u = table(new float[] {3f, 1.5f, 1.5f, 1.5f, 1f}, unitDimension, "Revenue", "Expenditure", "Surplus / (deficit)",
                    "Margin");
            BigDecimal tr = BigDecimal.ZERO, te = BigDecimal.ZERO;
            for (UnitResult r : units) {
                boolean na = BoardPack.NOT_ASSIGNED.equals(r.unit());
                u.addCell(cell(r.unit(), na ? NEG : TD, Element.ALIGN_LEFT, false));
                u.addCell(num(r.revenue(), TD, false));
                u.addCell(num(r.expenditure(), TD, false));
                u.addCell(num(r.surplus(), r.surplus().signum() < 0 ? NEG : TD, false));
                u.addCell(cell(r.marginPct() == null ? "" : r.marginPct() + "%", TD, Element.ALIGN_RIGHT, false));
                tr = tr.add(r.revenue());
                te = te.add(r.expenditure());
            }
            u.addCell(cell("Total", TDB, Element.ALIGN_LEFT, true));
            u.addCell(num(tr, TDB, true));
            u.addCell(num(te, TDB, true));
            u.addCell(num(tr.subtract(te), TDB, true));
            u.addCell(cell("", TDB, Element.ALIGN_RIGHT, true));
            doc.add(u);
            doc.add(note("Year to date. Income or expenditure posted without a business unit is shown as \"" + BoardPack.NOT_ASSIGNED
                    + "\"; switch on ZC_BUSINESS_UNIT_REQUIRED to prevent it."));
        }

        doc.add(h1("5. Budget execution by cost centre"));
        if (costCentres.isEmpty()) {
            doc.add(note("No approved budget for this financial year."));
        } else {
            PdfPTable b = table(new float[] {3f, 1.5f, 1.5f, 1.5f, 1.1f, 1.5f}, "Cost centre", "Annual budget", "Budget to date",
                    "Actual to date", "Absorption", "Remaining");
            for (String[] r : costCentres) {
                b.addCell(cell(r[0], TD, Element.ALIGN_LEFT, false));
                for (int i = 1; i < r.length; i++) {
                    b.addCell(cell(r[i], TD, Element.ALIGN_RIGHT, false));
                }
            }
            doc.add(b);
        }

        doc.add(h1("6. Cash and bank"));
        PdfPTable k = table(new float[] {3.2f, 1.5f, 1.3f, 1.5f, 1.2f}, "Account", "Ledger balance", "Last statement",
                "Statement balance", "Items not yet on a statement");
        for (Object[] r : banks) {
            k.addCell(cell((String) r[0], TD, Element.ALIGN_LEFT, false));
            k.addCell(num((BigDecimal) r[1], TD, false));
            k.addCell(cell(r[2] == null ? "none" : r[2].toString(), r[2] == null ? NEG : TD, Element.ALIGN_RIGHT, false));
            k.addCell(num((BigDecimal) r[3], TD, false));
            k.addCell(cell(String.valueOf(r[4]), TD, Element.ALIGN_RIGHT, false));
        }
        k.addCell(cell("Total", TDB, Element.ALIGN_LEFT, true));
        k.addCell(num(cash, TDB, true));
        k.addCell(cell("", TDB, Element.ALIGN_LEFT, true));
        k.addCell(cell("", TDB, Element.ALIGN_LEFT, true));
        k.addCell(cell("", TDB, Element.ALIGN_LEFT, true));
        doc.add(k);
        doc.add(note("Ledger balance: bank asset and in-transit accounts at the period end. The Bank Reconciliation Statement "
                + "gives the detail for each account."));

        doc.newPage();
        ageing(doc, "7. Debtors", receivables, "debtors");
        ageing(doc, "8. Creditors", payables, "creditors");

        doc.add(h1("9. Open audit queries"));
        if (openQueries.isEmpty()) {
            doc.add(new Paragraph("No audit queries open at the period end.", BODY));
        } else {
            StringBuilder sb = new StringBuilder(openQueries.size() + " open at the period end");
            queriesByRisk.forEach((r, n) -> sb.append("; ").append(r).append(' ').append(n));
            doc.add(new Paragraph(sb.toString(), BODY));
            PdfPTable q = table(new float[] {1.4f, 4f, 1f, 1.2f, 1.6f, 0.9f}, "Reference", "Query", "Risk", "Due", "Responsible", "");
            for (String[] r : openQueries) {
                for (int i = 0; i < r.length; i++) {
                    q.addCell(cell(r[i], "overdue".equals(r[5]) && (i == 3 || i == 5) ? NEG : TD, Element.ALIGN_LEFT, false));
                }
            }
            doc.add(q);
        }

        doc.add(h1("Basis of preparation"));
        doc.add(note("Prepared from the general ledger (actual postings, primary accounting schema) of ZenithCore Enterprise ERP. "
                + "Statements are grouped at level " + Math.max(1, pack.get_ValueAsInt("ZC_GroupLevel")) + " of the chart of accounts. "
                + "Net assets include the accumulated surplus of all years, so no year-end closing entry is needed. Budgets are the "
                + "approved ZenithCore budgets for the financial year, spread evenly over its periods. Debtors and creditors are open "
                + "invoices less allocations made by the period end, aged by days past their due date. Debtor days = debtors / revenue "
                + "to date x days elapsed. Unaudited management accounts."));
        doc.close();
        return out.toByteArray();
    }

    private void ageing(Document doc, String title, Ageing a, String what) throws Exception {
        doc.add(h1(title));
        PdfPTable t = table(new float[] {1.3f, 1.3f, 1.3f, 1.3f, 1.3f, 1.3f, 1.4f}, BoardPack.BUCKETS[0], BoardPack.BUCKETS[1],
                BoardPack.BUCKETS[2], BoardPack.BUCKETS[3], BoardPack.BUCKETS[4], BoardPack.BUCKETS[5], "Total");
        for (int i = 0; i < a.buckets().length; i++) {
            t.addCell(num(a.buckets()[i], i >= 4 && a.buckets()[i].signum() > 0 ? NEG : TD, false));
        }
        t.addCell(num(a.total(), TDB, false));
        doc.add(t);
        if (!a.largest().isEmpty()) {
            Paragraph p = new Paragraph("Largest " + what, TDH);
            p.setSpacingBefore(4);
            doc.add(p);
            PdfPTable l = table(new float[] {5f, 1.6f, 1f}, "", "Balance", "Share");
            for (Map.Entry<String, BigDecimal> e : a.largest()) {
                l.addCell(cell(e.getKey(), TD, Element.ALIGN_LEFT, false));
                l.addCell(num(e.getValue(), TD, false));
                l.addCell(cell(a.total().signum() == 0 ? "" : BoardPack.pct(e.getValue(), a.total()) + "%", TD, Element.ALIGN_RIGHT,
                        false));
            }
            doc.add(l);
        }
    }

    private static void tile(PdfPTable t, String label, String value, Boolean bad) {
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(label, TILE_L));
        c.addElement(new Paragraph(value, Boolean.TRUE.equals(bad) ? new Font(Font.HELVETICA, 13, Font.BOLD, RED) : TILE_V));
        c.setBackgroundColor(TILE);
        c.setBorder(Rectangle.LEFT);
        c.setBorderColor(CYAN);
        c.setBorderWidthLeft(3);
        c.setPadding(7);
        c.setPaddingTop(2);
        t.addCell(c);
    }

    private static Paragraph h1(String s) {
        Paragraph p = new Paragraph(s, H1);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        return p;
    }

    private static Paragraph note(String s) {
        Paragraph p = new Paragraph(s, NOTE);
        p.setSpacingBefore(3);
        return p;
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (int i = 0; i < headers.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(headers[i], TH));
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(CYAN);
            c.setBorderWidthBottom(1.5f);
            c.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            c.setPadding(3);
            t.addCell(c);
        }
        return t;
    }

    private static void heading(PdfPTable t, String label, int span) {
        PdfPCell c = new PdfPCell(new Phrase(label, TDH));
        c.setColspan(span);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(6);
        t.addCell(c);
    }

    private static PdfPCell cell(String text, Font f, int align, boolean shaded) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(SHADE);
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        if (shaded) {
            c.setBackgroundColor(SHADE);
        }
        return c;
    }

    private static PdfPCell num(BigDecimal v, Font f, boolean shaded) {
        Font use = v != null && v.signum() < 0 ? (f == TDB ? NEGB : NEG) : f;
        return cell(v == null ? "" : money(v), use, Element.ALIGN_RIGHT, shaded);
    }

    private static PdfPCell variance(BigDecimal v, boolean shaded) {
        return cell(v == null ? "" : money(v), v == null ? TD : v.signum() < 0 ? NEG : POS, Element.ALIGN_RIGHT, shaded);
    }

    /** Thousands separators; negatives in brackets, as boards expect. */
    public static String money(BigDecimal v) {
        if (v == null) {
            return "";
        }
        String s = String.format(Locale.ROOT, "%,.2f", v.abs());
        return v.signum() < 0 ? "(" + s + ")" : s;
    }

    /** The approval stamp drawn on every page of the approved copy. */
    public static byte[] stamp(byte[] pdf, String text) throws Exception {
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.pdf.PdfStamper st = new com.lowagie.text.pdf.PdfStamper(reader, out);
        Font f = new Font(Font.HELVETICA, 8, Font.BOLD, GREEN);
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            Rectangle r = reader.getPageSize(i);
            ColumnText.showTextAligned(st.getOverContent(i), Element.ALIGN_RIGHT, new Phrase(new Chunk(text, f)),
                    r.getWidth() - 36, r.getHeight() - 20, 0);
        }
        st.close();
        reader.close();
        return out.toByteArray();
    }

    public static String userName(int id) {
        return id <= 0 ? "" : MUser.getNameOfUser(id);
    }
}
