package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.BoardPack.Ageing;
import ke.co.pollux.zenithcore.controls.BoardPack.Balance;
import ke.co.pollux.zenithcore.controls.BoardPack.KeyFigures;
import ke.co.pollux.zenithcore.controls.BoardPack.Kind;
import ke.co.pollux.zenithcore.controls.BoardPack.OpenItem;
import ke.co.pollux.zenithcore.controls.BoardPack.Row;
import ke.co.pollux.zenithcore.controls.BoardPack.Statement;
import ke.co.pollux.zenithcore.controls.BoardPack.Type;
import ke.co.pollux.zenithcore.controls.BoardPack.UnitBalance;
import ke.co.pollux.zenithcore.controls.BoardPack.UnitLine;
import ke.co.pollux.zenithcore.controls.BoardPack.UnitResult;

class BoardPackTest {

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    /** group, type, month, ytd, budget ytd, prior ytd, closing, prior closing (ledger debit positive). */
    private static Balance b(String group, Type t, String m, String ytd, String budget, String prior, String close, String priorClose) {
        return new Balance(group, group + " account", t, d(m), d(ytd), d(budget), d(prior), d(close), d(priorClose));
    }

    // a small institution: rent income, staff costs, utilities; cash, debtors, creditors, capital fund
    private static final List<Balance> LEDGER = List.of(
            b("Rental income", Type.R, "-100000", "-1100000", "1000000", "-900000", "-3100000", "-2000000"),
            b("Staff costs", Type.E, "60000", "640000", "660000", "600000", "1840000", "1200000"),
            b("Utilities", Type.E, "5000", "60000", null, "50000", "160000", "100000"),
            b("Cash", Type.A, "0", "0", null, "0", "1500000", "1100000"),
            b("Debtors", Type.A, "0", "0", null, "0", "400000", "200000"),
            b("Creditors", Type.L, "0", "0", null, "0", "-200000", "-100000"),
            b("Capital fund", Type.O, "0", "0", null, "0", "-600000", "-500000"));

    private static Row row(Statement s, String label) {
        return s.rows().stream().filter(r -> r.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void performance_presents_revenue_positive_with_favourable_variances() {
        Statement s = BoardPack.performance(LEDGER);
        Row rent = row(s, "Rental income");
        assertEquals(d("100000"), rent.month());
        assertEquals(d("1100000"), rent.ytd());
        assertEquals(d("100000"), rent.variance());          // above budget: favourable
        assertEquals(d("20000"), row(s, "Staff costs").variance()); // below budget: favourable
        assertNull(row(s, "Utilities").budget());
        Row totalExp = row(s, "Total expenditure");
        assertEquals(d("700000"), totalExp.ytd());
        assertEquals(d("660000"), totalExp.budget());        // only budgeted lines carry a budget
        // the unbudgeted utilities (60,000) are not set against the staff-cost budget
        assertEquals(d("640000"), totalExp.budgetedYtd());
        assertEquals(d("20000"), totalExp.variance());
        Row result = row(s, "Surplus / (deficit)");
        assertEquals(Kind.RESULT, result.kind());
        assertEquals(d("400000"), result.ytd());
        assertEquals(d("340000"), result.budget());
        assertEquals(d("120000"), result.variance());        // revenue +100,000 and staff costs +20,000
        assertEquals(d("250000"), result.priorYtd());
    }

    @Test
    void groups_with_nothing_in_any_column_are_left_out() {
        Statement s = BoardPack.performance(List.of(b("Rental income", Type.R, "0", "0", null, "0", "0", "0"),
                b("Fees", Type.R, "-1", "-1", null, "0", "-1", "0")));
        assertTrue(s.rows().stream().noneMatch(r -> r.label().equals("Rental income")));
        assertNull(row(s, "Surplus / (deficit)").budget());
    }

    @Test
    void position_balances_through_the_accumulated_surplus() {
        Statement s = BoardPack.position(LEDGER);
        assertEquals(d("1900000"), row(s, "Total assets").closing());
        assertEquals(d("1300000"), row(s, "Total assets").priorClosing());
        // accumulated surplus = revenue 3,100,000 less expenditure 2,000,000
        assertEquals(d("1100000"), row(s, "Accumulated surplus / (deficit)").closing());
        assertEquals(d("1700000"), row(s, "Total net assets").closing());
        assertTrue(s.balances());
        assertEquals(d("1300000"), row(s, "Total liabilities and net assets").priorClosing());
    }

    @Test
    void position_shows_a_difference_when_the_ledger_does_not_balance() {
        List<Balance> l = new java.util.ArrayList<>(LEDGER);
        l.add(b("Suspense", Type.A, "0", "0", null, "0", "0.01", "0"));
        Statement s = BoardPack.position(l);
        assertFalse(s.balances());
        assertEquals(d("0.01"), s.difference());
    }

    @Test
    void business_units_with_unassigned_last() {
        List<UnitResult> u = BoardPack.units(List.of(new UnitBalance("Workshops", Type.R, d("-200000")),
                new UnitBalance("Workshops", Type.E, d("150000")), new UnitBalance("Rentals", Type.R, d("-900000")),
                new UnitBalance("Rentals", Type.E, d("300000")), new UnitBalance(null, Type.E, d("250000")),
                new UnitBalance("Rentals", Type.A, d("5"))));
        assertEquals(List.of("Rentals", "Workshops", BoardPack.NOT_ASSIGNED), u.stream().map(UnitResult::unit).toList());
        assertEquals(d("600000"), u.get(0).surplus());
        assertEquals(d("66.7"), u.get(0).marginPct());
        assertEquals(d("25.0"), u.get(1).marginPct());
        assertNull(u.get(2).marginPct());
        assertEquals(d("-250000"), u.get(2).surplus());
    }

    @Test
    void ageing_bucket_boundaries() {
        LocalDate at = LocalDate.of(2026, 11, 30);
        Ageing a = BoardPack.ageing(List.of(new OpenItem("A", at, d("1")), new OpenItem("B", at.minusDays(1), d("2")),
                new OpenItem("C", at.minusDays(30), d("4")), new OpenItem("D", at.minusDays(31), d("8")),
                new OpenItem("E", at.minusDays(90), d("16")), new OpenItem("F", at.minusDays(91), d("32")),
                new OpenItem("G", at.minusDays(180), d("64")), new OpenItem("H", at.minusDays(181), d("128")),
                new OpenItem("H", at.plusDays(10), d("-28"))), at, 3);
        assertArrayEquals(new BigDecimal[] {d("-27"), d("6"), d("8"), d("16"), d("96"), d("128")}, a.buckets());
        assertEquals(d("227"), a.total());
        assertEquals(d("254"), a.overdue());
        assertEquals(List.of("H", "G", "F"), a.largest().stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(d("100"), a.largest().get(0).getValue()); // credit note netted
    }

    @Test
    void key_figures() {
        Statement p = BoardPack.performance(LEDGER);
        LocalDate at = LocalDate.of(2026, 11, 30);
        Ageing ar = BoardPack.ageing(List.of(new OpenItem("A", at, d("400000"))), at, 5);
        Ageing ap = BoardPack.ageing(List.of(new OpenItem("S", at, d("200000"))), at, 5);
        KeyFigures k = BoardPack.keyFigures(p, d("1500000"), ar, ap, 334);
        assertEquals(d("1100000"), k.revenueYtd());
        assertEquals(d("400000"), k.surplusYtd());
        assertEquals(121L, k.debtorDays()); // 400,000 / 1,100,000 x 334 = 121.45
        assertEquals(d("97.0"), k.budgetAbsorptionPct()); // budgeted staff costs 640,000 / 660,000
        KeyFigures none = BoardPack.keyFigures(BoardPack.performance(List.of()), d("0"), ar, ap, 334);
        assertNull(none.debtorDays());
        assertNull(none.budgetAbsorptionPct());
    }

    @Test
    void budget_to_date_is_pro_rata() {
        assertEquals(d("916666.67"), BoardPack.budgetToDate(d("1000000"), 11, 12));
        assertEquals(d("1000000.00"), BoardPack.budgetToDate(d("1000000"), 12, 12));
    }

    @Test
    void income_and_expenditure_lines_need_a_business_unit() {
        assertTrue(BoardPack.requireUnit("GL Journal 1009", List.of(new UnitLine(10, true, true),
                new UnitLine(20, false, false))).allowed());
        ControlResult r = BoardPack.requireUnit("GL Journal 1009", List.of(new UnitLine(10, true, false)));
        assertEquals(List.of("GL Journal 1009 line 10: set the business unit"), r.reasons());
    }
}
