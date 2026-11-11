package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.BudgetCheck.Demand;
import ke.co.pollux.zenithcore.controls.BudgetCheck.Key;
import ke.co.pollux.zenithcore.controls.BudgetCheck.Position;

class BudgetCheckTest {

    private static final Key REPAIRS = new Key("WS Workshop", "61200 Repairs");
    private static final Map<Key, Position> POS = Map.of(REPAIRS,
            new Position(new BigDecimal("500000"), new BigDecimal("320000"), new BigDecimal("150000")));

    private static Demand d(String line, String amount) {
        return new Demand(line, "WS Workshop", "61200 Repairs", new BigDecimal(amount));
    }

    @Test
    void exactly_the_available_amount_passes_and_one_cent_more_blocks() {
        assertTrue(BudgetCheck.check("LPO 1", List.of(d("line 10", "30000.00")), POS, BigDecimal.ZERO).allowed());
        ControlResult r = BudgetCheck.check("LPO 1", List.of(d("line 10", "30000.01")), POS, BigDecimal.ZERO);
        assertEquals(List.of("WS Workshop / 61200 Repairs: budget 500,000.00, spent 320,000.00, committed 150,000.00, "
                + "available 30,000.00; LPO 1 needs 30,000.01, over by 0.01"), r.reasons());
    }

    @Test
    void lines_on_the_same_budget_line_are_added_together() {
        ControlResult r = BudgetCheck.check("LPO 2", List.of(d("line 10", "20000"), d("line 20", "15000")), POS,
                BigDecimal.ZERO);
        assertFalse(r.allowed());
        assertTrue(r.message().contains("LPO 2 needs 35,000.00, over by 5,000.00"), r.message());
    }

    @Test
    void tolerance_extends_the_budget() {
        // 2% of 500,000 = 10,000 more: 40,000 available
        assertTrue(BudgetCheck.check("x", List.of(d("l", "40000")), POS, new BigDecimal("2")).allowed());
        ControlResult r = BudgetCheck.check("x", List.of(d("l", "40000.01")), POS, new BigDecimal("2"));
        assertTrue(r.message().contains("budget 500,000.00 (+2%)") && r.message().contains("available 40,000.00"),
                r.message());
    }

    @Test
    void no_budget_line_or_zero_budget_blocks() {
        Demand fuel = new Demand("line 10", "WS Workshop", "61500 Fuel", new BigDecimal("1000"));
        assertEquals(List.of("no approved budget for WS Workshop / 61500 Fuel (Req 7 needs 1,000.00)"),
                BudgetCheck.check("Req 7", List.of(fuel), POS, BigDecimal.ZERO).reasons());
        Map<Key, Position> zero = Map.of(REPAIRS, new Position(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertFalse(BudgetCheck.check("x", List.of(d("l", "1")), zero, BigDecimal.ZERO).allowed());
    }

    @Test
    void line_without_cost_centre_blocks() {
        Demand none = new Demand("line 30", null, "61200 Repairs", new BigDecimal("10"));
        assertEquals(List.of("line 30 has no cost centre"),
                BudgetCheck.check("x", List.of(none), POS, BigDecimal.ZERO).reasons());
    }

    @Test
    void credits_reduce_the_demand() {
        // a negative line (for example a discount) offsets a positive one on the same budget line
        assertTrue(BudgetCheck.check("x", List.of(d("l1", "35000"), d("l2", "-5000")), POS, BigDecimal.ZERO).allowed());
    }

    @Test
    void available_is_budget_less_spent_and_committed() {
        assertEquals(new BigDecimal("30000"), POS.get(REPAIRS).available());
    }
}
