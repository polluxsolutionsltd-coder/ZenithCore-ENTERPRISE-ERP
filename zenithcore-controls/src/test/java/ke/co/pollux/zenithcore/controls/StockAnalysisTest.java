package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.StockAnalysis.Classified;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryBucket;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryReport;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Issue;
import ke.co.pollux.zenithcore.controls.StockAnalysis.IssueKind;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Item;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Lot;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Loss;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Movement;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Policy;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Wastage;

class StockAnalysisTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final Policy P = new Policy(90, 365, new BigDecimal("6"));

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    @Test
    void expiry_bucket_boundaries() {
        assertEquals(ExpiryBucket.D30, StockAnalysis.bucket(D, D));                 // usable on its expiry date
        assertEquals(ExpiryBucket.EXPIRED, StockAnalysis.bucket(D.minusDays(1), D));
        assertEquals(ExpiryBucket.D30, StockAnalysis.bucket(D.plusDays(30), D));
        assertEquals(ExpiryBucket.D90, StockAnalysis.bucket(D.plusDays(31), D));
        assertEquals(ExpiryBucket.D90, StockAnalysis.bucket(D.plusDays(90), D));
        assertEquals(ExpiryBucket.D180, StockAnalysis.bucket(D.plusDays(91), D));
        assertEquals(ExpiryBucket.D180, StockAnalysis.bucket(D.plusDays(180), D));
        assertEquals(ExpiryBucket.LATER, StockAnalysis.bucket(D.plusDays(181), D));
        assertEquals(ExpiryBucket.NO_DATE, StockAnalysis.bucket(null, D));
    }

    @Test
    void expiry_report_orders_soonest_first_and_values_by_bucket() {
        ExpiryReport r = StockAnalysis.expiry(List.of(
                new Lot("Main", "Fertilizer", "L3", D.plusDays(200), d("10"), d("12.50")),
                new Lot("Main", "Fertilizer", "L1", D.minusDays(5), d("4"), d("12.50")),
                new Lot("Main", "Seed", null, null, d("7"), d("3")),
                new Lot("Main", "Fertilizer", "L2", D.plusDays(20), d("2"), d("12.505")),
                new Lot("Main", "Fertilizer", "L0", D.minusDays(50), d("0"), d("12.50"))), D);
        assertEquals(java.util.Arrays.asList("L1", "L2", "L3", null), r.lots().stream().map(Lot::lot).toList()); // zero stock left out
        assertEquals(d("50.00"), r.valueByBucket().get(ExpiryBucket.EXPIRED));
        assertEquals(d("25.01"), r.valueByBucket().get(ExpiryBucket.D30));                    // 2 x 12.505, half up
        assertEquals(d("125.00"), r.valueByBucket().get(ExpiryBucket.LATER));
        assertEquals(d("21.00"), r.valueByBucket().get(ExpiryBucket.NO_DATE));
        assertEquals(1, r.lotsByBucket().get(ExpiryBucket.EXPIRED));
    }

    private static Item item(String product, String onHand, LocalDate last, String issued) {
        return new Item("Main", product, d(onHand), d("10"), last, d(issued));
    }

    @Test
    void slow_and_non_moving_boundaries() {
        assertEquals(Movement.NON_MOVING, StockAnalysis.classify(item("A", "5", null, "0"), D, P).movement());
        assertEquals(Movement.NON_MOVING, StockAnalysis.classify(item("A", "5", D.minusDays(365), "1"), D, P).movement());
        assertEquals(Movement.SLOW, StockAnalysis.classify(item("A", "5", D.minusDays(364), "1"), D, P).movement());
        assertEquals(Movement.SLOW, StockAnalysis.classify(item("A", "5", D.minusDays(90), "100"), D, P).movement());
        assertEquals(Movement.NORMAL, StockAnalysis.classify(item("A", "5", D.minusDays(89), "100"), D, P).movement());
        // cover: 60 on hand, 120 issued a year = 10 a month -> 6.0 months: at the limit, normal; 61 -> 6.1, slow
        Classified at = StockAnalysis.classify(item("A", "60", D.minusDays(3), "120"), D, P);
        assertEquals(Movement.NORMAL, at.movement());
        assertEquals(d("6.0"), at.coverMonths());
        Classified over = StockAnalysis.classify(item("A", "61", D.minusDays(3), "120"), D, P);
        assertEquals(Movement.SLOW, over.movement());
        assertEquals("6.1 months of cover", over.reason());
        assertNull(StockAnalysis.classify(item("A", "5", D.minusDays(3), "0"), D, P).coverMonths());
    }

    @Test
    void slow_moving_report_lists_non_moving_first_then_by_value() {
        List<Classified> r = StockAnalysis.slowMoving(List.of(item("Normal", "1", D.minusDays(1), "50"),
                item("Slow big", "100", D.minusDays(120), "5"), item("Dead", "2", null, "0"), item("Slow small", "1", D.minusDays(100), "1"),
                item("Gone", "0", null, "0")), D, P);
        assertEquals(List.of("Dead", "Slow big", "Slow small"), r.stream().map(c -> c.item().product()).toList());
        assertEquals(d("1000.00"), r.get(1).item().value());
    }

    @Test
    void wastage_by_reason_store_and_item() {
        Wastage w = StockAnalysis.wastage(List.of(new Loss(D, "Main", "Fertilizer", "Expired", "Write-off", d("4"), d("50")),
                new Loss(D, "Main", "Seed", "Damaged", "Write-off", d("1"), d("3")),
                new Loss(D, "Annex", "Fertilizer", "Expired", "Write-off", d("2"), d("25.005")),
                new Loss(D, "Main", "Holly Bush", "Rejected on receipt: damaged", "Receipt", d("2"), d("36"))), 2);
        assertEquals(d("114.01"), w.total());
        assertEquals(List.of("Expired", "Rejected on receipt: damaged", "Damaged"), List.copyOf(w.byReason().keySet()));
        assertEquals(d("75.01"), w.byReason().get("Expired"));
        assertEquals(List.of("Main", "Annex"), List.copyOf(w.byStore().keySet()));
        assertEquals(List.of("Fertilizer", "Holly Bush"), w.topItems().stream().map(java.util.Map.Entry::getKey).toList());
    }

    @Test
    void expired_stock_is_only_written_off_or_set_aside() {
        LocalDate exp = D.minusDays(1);
        List<Issue> lines = List.of(new Issue(10, "Fertilizer", "L1", exp, IssueKind.SHIPMENT, false, false),
                new Issue(20, "Fertilizer", "L1", exp, IssueKind.INTERNAL_USE, false, false),
                new Issue(30, "Fertilizer", "L1", exp, IssueKind.INTERNAL_USE, true, false),
                new Issue(40, "Fertilizer", "L1", exp, IssueKind.MOVE_TO_NORMAL, false, false),
                new Issue(50, "Fertilizer", "L1", exp, IssueKind.MOVE_TO_HOLD, false, false),
                new Issue(60, "Fertilizer", "L2", D, IssueKind.SHIPMENT, false, false),            // expires today: still usable
                new Issue(70, "Seed", null, null, IssueKind.SHIPMENT, false, false));
        ControlResult r = StockAnalysis.checkExpired("Shipment 500", lines, D);
        assertEquals(List.of("Shipment 500 line 10: Fertilizer lot L1 expired on 2026-11-09; it cannot be supplied",
                "Shipment 500 line 20: Fertilizer lot L1 expired on 2026-11-09; it can only be written off (use the Expired reason)",
                "Shipment 500 line 40: Fertilizer lot L1 expired on 2026-11-09; move it to a quarantine or rejected location, not a normal one"),
                r.reasons());
        assertTrue(StockAnalysis.checkExpired("x", lines.subList(4, 7), D).allowed());
        assertEquals(List.of("x line 80: Fertilizer has expiry dates; choose the lot (first to expire first)"),
                StockAnalysis.checkExpired("x", List.of(new Issue(80, "Fertilizer", null, null, IssueKind.SHIPMENT, false, true),
                        new Issue(90, "Fertilizer", null, null, IssueKind.MOVE_TO_HOLD, false, true)), D).reasons());
    }
}
