package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.Withholding.Deduction;
import ke.co.pollux.zenithcore.controls.Withholding.Kind;
import ke.co.pollux.zenithcore.controls.Withholding.Line;
import ke.co.pollux.zenithcore.controls.Withholding.Rate;
import ke.co.pollux.zenithcore.controls.Withholding.Result;
import ke.co.pollux.zenithcore.controls.Withholding.Supplier;

/**
 * Rates as in KRA's withholding income tax guide (November 2023) and withholding VAT overview (December
 * 2024); test data only, the rates themselves live in the database.
 */
class WithholdingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final BigDecimal T24K = new BigDecimal("24000");
    private static final List<Rate> RATES = List.of(
            new Rate("PROF", "Professional fees", Kind.INCOME_TAX, false, new BigDecimal("5"), T24K, LocalDate.of(2020, 1, 1), null),
            new Rate("PROF", "Professional fees", Kind.INCOME_TAX, true, new BigDecimal("20"), null, LocalDate.of(2020, 1, 1), null),
            new Rate("CONT", "Contractual fees", Kind.INCOME_TAX, false, new BigDecimal("3"), T24K, LocalDate.of(2020, 1, 1), null),
            new Rate("WHVAT", "Withholding VAT", Kind.VAT, false, new BigDecimal("2"), null, LocalDate.of(2019, 11, 7), null));
    private static final Supplier LOCAL = new Supplier("Acme Consultants", false, false, true, false);

    private static Line line(int no, String net, String vat, String type) {
        return new Line(no, "line " + no, new BigDecimal(net), new BigDecimal(vat), type);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v).setScale(2);
    }

    @Test
    void professional_fees_with_vat_and_whvat_agent() {
        // Fee 100,000 + VAT 16%: WHT 5% = 5,000; WHVAT 2% of 100,000 = 2,000
        Result r = Withholding.calculate(List.of(line(10, "100000", "16", "PROF")), LOCAL, DAY, RATES, Map.of(), true, "WHVAT");
        assertEquals(2, r.deductions().size(), r.trace().toString());
        assertEquals(d("5000"), r.deductions().get(0).amount());
        assertEquals(d("2000"), r.deductions().get(1).amount());
        assertEquals(Kind.VAT, r.deductions().get(1).kind());
        assertEquals(d("7000"), r.total());
        assertTrue(r.trace().contains("Professional fees: 5% of 100,000.00 = 5,000.00"), r.trace().toString());
    }

    @Test
    void monthly_threshold_exactly_24000_is_exempt_one_cent_over_is_not() {
        assertTrue(Withholding.calculate(List.of(line(10, "24000.00", "0", "CONT")), LOCAL, DAY, RATES, Map.of(), false, "WHVAT")
                .deductions().isEmpty());
        Result r = Withholding.calculate(List.of(line(10, "24000.01", "0", "CONT")), LOCAL, DAY, RATES, Map.of(), false, "WHVAT");
        assertEquals(d("720"), r.deductions().get(0).amount(), "3% of 24,000.01 = 720.0003");
    }

    @Test
    void earlier_invoices_in_the_month_count_toward_the_threshold() {
        Result r = Withholding.calculate(List.of(line(10, "10000", "0", "PROF")), LOCAL, DAY, RATES,
                Map.of("PROF", new BigDecimal("20000")), false, "WHVAT");
        assertEquals(d("500"), r.deductions().get(0).amount());
        assertTrue(r.trace().get(0).contains("check withholding on earlier invoices this month (20,000.00)"), r.trace().toString());
    }

    @Test
    void non_resident_rate_has_no_threshold_and_no_whvat() {
        Supplier foreign = new Supplier("Foreign Advisers LLP", true, false, false, false);
        Result r = Withholding.calculate(List.of(line(10, "10000", "16", "PROF")), foreign, DAY, RATES, Map.of(), true, "WHVAT");
        assertEquals(1, r.deductions().size());
        assertEquals(d("2000"), r.deductions().get(0).amount(), "20%");
        assertTrue(r.trace().contains("Withholding VAT: not applied to a non-resident supplier"));
    }

    @Test
    void lines_of_one_type_are_added_and_rounded_once() {
        // 3% of (10,000.10 + 20,000.15) = 900.0075 -> 900.01
        Result r = Withholding.calculate(List.of(line(10, "10000.10", "0", "CONT"), line(20, "20000.15", "0", "CONT")),
                LOCAL, DAY, RATES, Map.of(), false, "WHVAT");
        Deduction x = r.deductions().get(0);
        assertEquals(d("30000.25"), x.base());
        assertEquals(d("900.01"), x.amount());
    }

    @Test
    void whvat_only_on_standard_rated_lines_and_only_for_agents() {
        List<Line> lines = List.of(line(10, "50000", "16", null), line(20, "30000", "0", null));
        Result agent = Withholding.calculate(lines, LOCAL, DAY, RATES, Map.of(), true, "WHVAT");
        assertEquals(d("1000"), agent.deductions().get(0).amount(), "2% of 50,000 only");
        assertTrue(Withholding.calculate(lines, LOCAL, DAY, RATES, Map.of(), false, "WHVAT").deductions().isEmpty());
    }

    @Test
    void exemptions_and_unregistered_suppliers() {
        Supplier exempt = new Supplier("Exempt Co", false, true, true, true);
        Result r = Withholding.calculate(List.of(line(10, "100000", "16", "PROF")), exempt, DAY, RATES, Map.of(), true, "WHVAT");
        assertTrue(r.deductions().isEmpty(), r.trace().toString());
        Supplier unregistered = new Supplier("Small Co", false, false, false, false);
        assertEquals(1, Withholding.calculate(List.of(line(10, "100000", "16", "PROF")), unregistered, DAY, RATES, Map.of(), true,
                "WHVAT").deductions().size(), "WHT only");
    }

    @Test
    void missing_rate_is_a_problem_and_rates_are_dated() {
        Result r = Withholding.calculate(List.of(line(10, "100000", "0", "RENT")), LOCAL, DAY, RATES, Map.of(), false, "WHVAT");
        assertEquals(List.of("no resident withholding tax rate for RENT on 2026-11-10"), r.problems());
        Rate old = new Rate("X", "Old", Kind.INCOME_TAX, false, BigDecimal.TEN, null, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 6, 30));
        assertTrue(Withholding.find(List.of(old), "X", false, LocalDate.of(2026, 6, 30)).isPresent(), "last day");
        assertTrue(Withholding.find(List.of(old), "X", false, LocalDate.of(2026, 7, 1)).isEmpty(), "day after");
    }
}
