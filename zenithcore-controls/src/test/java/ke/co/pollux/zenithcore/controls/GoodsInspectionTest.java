package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.GoodsInspection.InspectedLine;
import ke.co.pollux.zenithcore.controls.GoodsInspection.LocationUse;
import ke.co.pollux.zenithcore.controls.GoodsInspection.StockLine;

class GoodsInspectionTest {

    private static final StoresControl.Store MAIN = new StoresControl.Store("Main Store", 10, 11);
    private static final int RECEIVER = 10, INSPECTOR = 30;

    private static InspectedLine line(String rec, String acc, String rej, String reason) {
        return new InspectedLine(10, "Cement 50kg", new BigDecimal(rec), new BigDecimal(acc), new BigDecimal(rej), reason);
    }

    @Test
    void receipts_must_go_to_quarantine() {
        assertTrue(GoodsInspection.receive(List.of(new StockLine(10, "Cement", "Q-Main", LocationUse.QUARANTINE))).allowed());
        assertEquals(List.of("line 20 (Paint) must be received into a quarantine location, not Shelf A"),
                GoodsInspection.receive(List.of(new StockLine(20, "Paint", "Shelf A", LocationUse.NORMAL))).reasons());
    }

    @Test
    void quarantine_and_rejected_stock_cannot_be_used() {
        List<String> r = GoodsInspection.use(List.of(new StockLine(10, "Cement", "Q-Main", LocationUse.QUARANTINE),
                new StockLine(20, "Paint", "R-Main", LocationUse.REJECTED), new StockLine(30, "Nails", "Shelf A",
                        LocationUse.NORMAL)), "issued").reasons();
        assertEquals(2, r.size());
        assertTrue(r.get(0).contains("is not yet inspected and cannot be issued"));
        assertTrue(r.get(1).contains("was rejected and cannot be issued; return it to the supplier"));
    }

    @Test
    void every_unit_accepted_or_rejected_by_an_independent_inspector() {
        assertTrue(GoodsInspection.complete("Inspection 1", List.of(line("100", "95", "5", "DMG")), INSPECTOR, RECEIVER, MAIN)
                .allowed());
        assertEquals(List.of("line 10 (Cement 50kg): accepted 95 + rejected 4 must equal received 100"),
                GoodsInspection.complete("x", List.of(line("100", "95", "4", "DMG")), INSPECTOR, RECEIVER, MAIN).reasons());
        assertEquals(List.of("line 10 (Cement 50kg): accepted 101 + rejected 0 must equal received 100"),
                GoodsInspection.complete("x", List.of(line("100", "101", "0", null)), INSPECTOR, RECEIVER, MAIN).reasons(),
                "one over");
    }

    @Test
    void rejection_needs_a_reason() {
        assertEquals(List.of("line 10 (Cement 50kg): give the reason for rejecting 5"),
                GoodsInspection.complete("x", List.of(line("100", "95", "5", " ")), INSPECTOR, RECEIVER, MAIN).reasons());
    }

    @Test
    void receiver_and_custodians_cannot_inspect() {
        assertTrue(GoodsInspection.complete("x", List.of(line("1", "1", "0", null)), 50, 50, MAIN).message()
                .contains("you recorded the goods received note"));
        assertTrue(GoodsInspection.complete("x", List.of(line("1", "1", "0", null)), 11, RECEIVER, MAIN).message()
                .contains("custodians of Main Store cannot inspect"));
    }

    @Test
    void negative_quantities_and_empty_inspections_block() {
        assertTrue(GoodsInspection.complete("x", List.of(line("1", "2", "-1", "DMG")), INSPECTOR, RECEIVER, MAIN).message()
                .contains("cannot be negative"));
        assertTrue(GoodsInspection.complete("Inspection 9", List.of(), INSPECTOR, RECEIVER, MAIN).message()
                .contains("Inspection 9 has no lines"));
    }
}
