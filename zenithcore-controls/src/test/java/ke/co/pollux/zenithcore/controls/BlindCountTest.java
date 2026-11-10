package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.BlindCount.CountLine;
import ke.co.pollux.zenithcore.controls.BlindCount.Totals;
import ke.co.pollux.zenithcore.controls.BlindCount.VarianceLine;

class BlindCountTest {

    private static final StoresControl.Store MAIN = new StoresControl.Store("Main Store", 10, 11);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void all_lines_counted_by_independent_counters_can_be_submitted() {
        assertTrue(BlindCount.submit("Count 1", MAIN, List.of(new CountLine(10, "A", d("5"), 30),
                new CountLine(20, "B", BigDecimal.ZERO, 31)), true).allowed(), "zero is a count");
    }

    @Test
    void uncounted_lines_block_and_are_listed() {
        ControlResult r = BlindCount.submit("Count 1", MAIN, List.of(new CountLine(10, "A", null, 0),
                new CountLine(20, "B", d("1"), 30), new CountLine(30, "C", null, 0)), true);
        assertEquals(List.of("not counted yet: lines 10, 30 (enter 0 for an item not found)"), r.reasons());
    }

    @Test
    void custodian_counting_own_store_blocks_only_when_counts_must_be_independent() {
        List<CountLine> lines = List.of(new CountLine(10, "A", d("5"), 10), new CountLine(20, "B", d("5"), 11));
        assertEquals(List.of("counted by a custodian of Main Store: lines 10, 20; a different person must count them"),
                BlindCount.submit("Count 1", MAIN, lines, true).reasons());
        assertTrue(BlindCount.submit("Count 1", MAIN, lines, false).allowed());
    }

    @Test
    void empty_sheet_blocks() {
        assertFalse(BlindCount.submit("Count 1", MAIN, List.of(), true).allowed());
    }

    @Test
    void only_submitted_counts_can_be_posted() {
        assertFalse(BlindCount.complete("Count 1", false).allowed());
        assertTrue(BlindCount.complete("Count 1", true).allowed());
    }

    @Test
    void variance_value_and_totals_by_cause_and_store() {
        List<VarianceLine> lines = List.of(
                new VarianceLine("Main", "Cement", d("20"), d("18"), d("850"), "Damaged"),      // -1,700.00
                new VarianceLine("Main", "Nails", d("100"), d("103"), d("2.50"), "Count variance"), // +7.50
                new VarianceLine("Annex", "Paint", d("10"), d("9"), d("1200.005"), "Damaged"),   // -1,200.01
                new VarianceLine("Annex", "Brush", d("5"), d("5"), d("300"), null));            // no difference
        assertEquals(d("-1200.01"), lines.get(2).value(), "rounded half up");
        Map<String, Totals> cause = BlindCount.totals(lines, true);
        assertEquals(2, cause.size());
        assertEquals(new Totals(2, d("0.00"), d("-2900.01")), cause.get("Damaged"));
        assertEquals(d("7.50"), cause.get("Count variance").net());
        Map<String, Totals> store = BlindCount.totals(lines, false);
        assertEquals(d("-1692.50"), store.get("Main").net());
        assertEquals(1, store.get("Annex").lines());
    }

    @Test
    void difference_without_reason_is_grouped_as_no_reason() {
        Map<String, Totals> m = BlindCount.totals(List.of(new VarianceLine("Main", "A", d("1"), d("0"), d("10"), " ")), true);
        assertTrue(m.containsKey("No reason given"));
    }
}
