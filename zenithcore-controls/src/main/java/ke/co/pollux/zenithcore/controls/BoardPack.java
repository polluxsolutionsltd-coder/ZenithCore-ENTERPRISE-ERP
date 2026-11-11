package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Figures for the board finance committee pack, worked out from ledger balances: statement of financial
 * performance (month, year to date, budget, variance, prior year), statement of financial position,
 * business-unit results, debtor and creditor ageing, and key figures. Also the business-unit rule:
 * income and expenditure must carry a business unit, or the business-unit accounts do not add up.
 * <p>
 * Ledger amounts come in debit positive. Statements present revenue, liabilities and net assets as
 * positive figures. Variances are positive when favourable.
 */
public final class BoardPack {

    public static final String CONTROL = "Business unit";

    /** Account types as in iDempiere: Asset, Liability, Owner's equity, Revenue, Expense. */
    public enum Type {
        A, L, O, R, E;

        /** +1 if the type is shown debit positive, -1 if credit positive. */
        int presentation() {
            return this == A || this == E ? 1 : -1;
        }
    }

    /**
     * One posting account's ledger balances (debit positive). Budget is the year-to-date budget in the
     * presentation sign (positive revenue, positive expense), or null if the account has none.
     */
    public record Balance(String group, String account, Type type, BigDecimal month, BigDecimal ytd, BigDecimal budgetYtd,
                          BigDecimal priorYtd, BigDecimal closing, BigDecimal priorClosing) {
    }

    public enum Kind {
        HEADING, LINE, TOTAL, RESULT
    }

    /**
     * A statement row. For the financial position, closing and priorClosing are used; else the others.
     * budgetedYtd is the year-to-date actual of the lines that have a budget: totals compare a budget only
     * with the spending it covers, so unbudgeted lines do not distort the variance.
     */
    public record Row(Kind kind, String label, BigDecimal month, BigDecimal ytd, BigDecimal budget, BigDecimal variance,
                      BigDecimal priorYtd, BigDecimal closing, BigDecimal priorClosing, BigDecimal budgetedYtd) {

        Row(Kind kind, String label, BigDecimal month, BigDecimal ytd, BigDecimal budget, BigDecimal variance,
            BigDecimal priorYtd, BigDecimal closing, BigDecimal priorClosing) {
            this(kind, label, month, ytd, budget, variance, priorYtd, closing, priorClosing, null);
        }
    }

    public record Statement(List<Row> rows, BigDecimal difference) {
        /** For the financial position: assets equal liabilities plus net assets. */
        public boolean balances() {
            return difference.signum() == 0;
        }
    }

    private BoardPack() {
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal show(Type t, BigDecimal v) {
        return t.presentation() > 0 ? z(v) : z(v).negate();
    }

    /** Statement of financial performance, grouped, with totals and the surplus or deficit. */
    public static Statement performance(List<Balance> balances) {
        List<Row> rows = new ArrayList<>();
        BigDecimal[] rev = section(rows, balances, Type.R, "Revenue", "Total revenue");
        BigDecimal[] exp = section(rows, balances, Type.E, "Expenditure", "Total expenditure");
        BigDecimal budget = rev[2] == null && exp[2] == null ? null : z(rev[2]).subtract(z(exp[2]));
        BigDecimal ytd = rev[1].subtract(exp[1]);
        // favourable variances add up: revenue over budget plus expenditure under budget
        BigDecimal var = budget == null ? null : z(variance(Type.R, rev[4], rev[2])).add(z(variance(Type.E, exp[4], exp[2])));
        rows.add(new Row(Kind.RESULT, "Surplus / (deficit)", rev[0].subtract(exp[0]), ytd, budget, var,
                rev[3].subtract(exp[3]), null, null, null));
        return new Statement(rows, BigDecimal.ZERO);
    }

    /** @return month, ytd, budget (null if no line has one), prior ytd, actual of budgeted lines */
    private static BigDecimal[] section(List<Row> rows, List<Balance> balances, Type type, String heading, String total) {
        rows.add(new Row(Kind.HEADING, heading, null, null, null, null, null, null, null));
        Map<String, BigDecimal[]> groups = new LinkedHashMap<>();
        Map<String, Boolean> hasBudget = new LinkedHashMap<>();
        balances.stream().filter(b -> b.type() == type).sorted(Comparator.comparing(Balance::group)).forEach(b -> {
            BigDecimal[] g = groups.computeIfAbsent(b.group(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO});
            g[0] = g[0].add(show(type, b.month()));
            g[1] = g[1].add(show(type, b.ytd()));
            g[2] = g[2].add(z(b.budgetYtd()));
            g[3] = g[3].add(show(type, b.priorYtd()));
            hasBudget.merge(b.group(), b.budgetYtd() != null, Boolean::logicalOr);
        });
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map.Entry<String, BigDecimal[]> e : groups.entrySet()) {
            BigDecimal[] g = e.getValue();
            if (g[0].signum() == 0 && g[1].signum() == 0 && g[2].signum() == 0 && g[3].signum() == 0) {
                continue;
            }
            BigDecimal budget = hasBudget.get(e.getKey()) ? g[2] : null;
            rows.add(new Row(Kind.LINE, e.getKey(), g[0], g[1], budget, variance(type, g[1], budget), g[3], null, null));
            t[0] = t[0].add(g[0]);
            t[1] = t[1].add(g[1]);
            if (budget != null) {
                t[2] = z(t[2]).add(budget);
                t[4] = t[4].add(g[1]);
            }
            t[3] = t[3].add(g[3]);
        }
        rows.add(new Row(Kind.TOTAL, total, t[0], t[1], t[2], variance(type, t[4], t[2]), t[3], null, null, t[4]));
        return t;
    }

    /** Favourable positive: revenue above budget, expenditure below budget. */
    static BigDecimal variance(Type type, BigDecimal actual, BigDecimal budget) {
        if (budget == null) {
            return null;
        }
        return type == Type.R ? actual.subtract(budget) : budget.subtract(actual);
    }

    /**
     * Statement of financial position at the period end and the previous year end. Net assets are the
     * equity accounts plus the accumulated surplus (all revenue and expenditure to date), so the statement
     * balances without a year-end closing entry.
     */
    public static Statement position(List<Balance> balances) {
        List<Row> rows = new ArrayList<>();
        BigDecimal[] a = positionSection(rows, balances, Type.A, "Assets", "Total assets", null);
        BigDecimal[] l = positionSection(rows, balances, Type.L, "Liabilities", "Total liabilities", null);
        BigDecimal surplus = BigDecimal.ZERO, priorSurplus = BigDecimal.ZERO;
        for (Balance b : balances) {
            if (b.type() == Type.R || b.type() == Type.E) {
                surplus = surplus.subtract(z(b.closing()));
                priorSurplus = priorSurplus.subtract(z(b.priorClosing()));
            }
        }
        BigDecimal[] o = positionSection(rows, balances, Type.O, "Net assets", "Total net assets",
                new BigDecimal[] {surplus, priorSurplus});
        BigDecimal le = l[0].add(o[0]);
        rows.add(new Row(Kind.RESULT, "Total liabilities and net assets", null, null, null, null, null, le, l[1].add(o[1])));
        return new Statement(rows, a[0].subtract(le));
    }

    private static BigDecimal[] positionSection(List<Row> rows, List<Balance> balances, Type type, String heading,
            String total, BigDecimal[] accumulatedSurplus) {
        rows.add(new Row(Kind.HEADING, heading, null, null, null, null, null, null, null));
        Map<String, BigDecimal[]> groups = new LinkedHashMap<>();
        balances.stream().filter(b -> b.type() == type).sorted(Comparator.comparing(Balance::group)).forEach(b -> {
            BigDecimal[] g = groups.computeIfAbsent(b.group(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            g[0] = g[0].add(show(type, b.closing()));
            g[1] = g[1].add(show(type, b.priorClosing()));
        });
        if (accumulatedSurplus != null) {
            groups.put("Accumulated surplus / (deficit)", accumulatedSurplus);
        }
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map.Entry<String, BigDecimal[]> e : groups.entrySet()) {
            BigDecimal[] g = e.getValue();
            if (g[0].signum() == 0 && g[1].signum() == 0) {
                continue;
            }
            rows.add(new Row(Kind.LINE, e.getKey(), null, null, null, null, null, g[0], g[1]));
            t[0] = t[0].add(g[0]);
            t[1] = t[1].add(g[1]);
        }
        rows.add(new Row(Kind.TOTAL, total, null, null, null, null, null, t[0], t[1]));
        return t;
    }

    /** Revenue or expenditure of a business unit for the year to date (debit positive). Unit null = not assigned. */
    public record UnitBalance(String unit, Type type, BigDecimal ytd) {
    }

    public record UnitResult(String unit, BigDecimal revenue, BigDecimal expenditure, BigDecimal surplus, BigDecimal marginPct) {
    }

    public static final String NOT_ASSIGNED = "Not assigned";

    /** Business-unit results, largest revenue first, with income or expenditure not assigned to a unit last. */
    public static List<UnitResult> units(List<UnitBalance> balances) {
        Map<String, BigDecimal[]> m = new LinkedHashMap<>();
        for (UnitBalance b : balances) {
            if (b.type() != Type.R && b.type() != Type.E) {
                continue;
            }
            BigDecimal[] v = m.computeIfAbsent(b.unit() == null ? NOT_ASSIGNED : b.unit(),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            v[b.type() == Type.R ? 0 : 1] = v[b.type() == Type.R ? 0 : 1].add(show(b.type(), b.ytd()));
        }
        List<UnitResult> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : m.entrySet()) {
            BigDecimal rev = e.getValue()[0], exp = e.getValue()[1];
            if (rev.signum() == 0 && exp.signum() == 0) {
                continue;
            }
            BigDecimal s = rev.subtract(exp);
            out.add(new UnitResult(e.getKey(), rev, exp, s, rev.signum() == 0 ? null : pct(s, rev)));
        }
        out.sort(Comparator.comparing((UnitResult u) -> NOT_ASSIGNED.equals(u.unit())).thenComparing(UnitResult::revenue,
                Comparator.reverseOrder()));
        return out;
    }

    /** a / b as a percentage to one decimal. */
    public static BigDecimal pct(BigDecimal a, BigDecimal b) {
        return a.multiply(BigDecimal.valueOf(100)).divide(b, 1, RoundingMode.HALF_UP);
    }

    /** An open invoice or credit note: amount still open (credit notes negative) and its due date. */
    public record OpenItem(String party, LocalDate due, BigDecimal open) {
    }

    public static final String[] BUCKETS = {"Not yet due", "1-30 days", "31-60 days", "61-90 days", "91-180 days",
        "Over 180 days"};

    public record Ageing(BigDecimal[] buckets, BigDecimal total, List<Map.Entry<String, BigDecimal>> largest) {
        public BigDecimal overdue() {
            return total.subtract(buckets[0]);
        }
    }

    /** Ages open items by days past due at the date, and lists the largest balances by party. */
    public static Ageing ageing(List<OpenItem> items, LocalDate asAt, int top) {
        BigDecimal[] b = new BigDecimal[BUCKETS.length];
        java.util.Arrays.fill(b, BigDecimal.ZERO);
        Map<String, BigDecimal> byParty = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OpenItem i : items) {
            long days = ChronoUnit.DAYS.between(i.due(), asAt);
            int k = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : days <= 180 ? 4 : 5;
            b[k] = b[k].add(i.open());
            total = total.add(i.open());
            byParty.merge(i.party(), i.open(), BigDecimal::add);
        }
        List<Map.Entry<String, BigDecimal>> largest = byParty.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).limit(top).toList();
        return new Ageing(b, total, largest);
    }

    /** Headline figures for the cover page. */
    public record KeyFigures(BigDecimal revenueYtd, BigDecimal expenditureYtd, BigDecimal surplusYtd, BigDecimal cash,
                             BigDecimal receivables, BigDecimal payables, Long debtorDays, BigDecimal budgetAbsorptionPct) {
    }

    /**
     * Debtor days = receivables / revenue to date x days elapsed in the year. Budget absorption =
     * expenditure on budgeted lines to date / their budget to date (null when there is no budget).
     */
    public static KeyFigures keyFigures(Statement performance, BigDecimal cash, Ageing receivables, Ageing payables,
            long daysElapsed) {
        Row rev = find(performance, "Total revenue"), exp = find(performance, "Total expenditure");
        Row res = find(performance, "Surplus / (deficit)");
        Long debtorDays = rev.ytd().signum() <= 0 ? null
                : receivables.total().multiply(BigDecimal.valueOf(daysElapsed)).divide(rev.ytd(), 0, RoundingMode.HALF_UP)
                        .longValue();
        BigDecimal absorption = exp.budget() == null || exp.budget().signum() == 0 ? null : pct(exp.budgetedYtd(), exp.budget());
        return new KeyFigures(rev.ytd(), exp.ytd(), res.ytd(), cash, receivables.total(), payables.total(), debtorDays,
                absorption);
    }

    private static Row find(Statement s, String label) {
        return s.rows().stream().filter(r -> r.label().equals(label)).findFirst().orElseThrow();
    }

    /** A document line for the business-unit rule. */
    public record UnitLine(int lineNo, boolean incomeOrExpense, boolean hasUnit) {
    }

    /** Income and expenditure lines must carry a business unit. */
    public static ControlResult requireUnit(String label, List<UnitLine> lines) {
        List<String> reasons = new ArrayList<>();
        for (UnitLine l : lines) {
            if (l.incomeOrExpense() && !l.hasUnit()) {
                reasons.add(label + " line " + l.lineNo() + ": set the business unit");
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /** Pro-rata budget to date: annual x periods elapsed / periods in the year. */
    public static BigDecimal budgetToDate(BigDecimal annual, int periodsElapsed, int periodsInYear) {
        Objects.requireNonNull(annual);
        return annual.multiply(BigDecimal.valueOf(periodsElapsed)).divide(BigDecimal.valueOf(periodsInYear), 2,
                RoundingMode.HALF_UP);
    }
}
