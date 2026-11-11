package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.StoresControl.AdjustmentLine;
import ke.co.pollux.zenithcore.controls.StoresControl.Store;

class StoresControlTest {

    private static final int KEEPER = 10, DEPUTY = 11, PREPARER_OTHER = 12, AUDITOR = 20;
    private static final Store MAIN = new Store("Main Store", KEEPER, DEPUTY);

    private static AdjustmentLine line(int no, String change, String cost, String reason, boolean writeOff) {
        return new AdjustmentLine(no, "Cement 50kg", new BigDecimal(change), new BigDecimal(cost), reason, writeOff);
    }

    @Test
    void custodian_and_deputy_may_receive_others_may_not() {
        assertTrue(StoresControl.custodian(MAIN, KEEPER, "receive into").allowed());
        assertTrue(StoresControl.custodian(MAIN, DEPUTY, "receive into").allowed());
        assertEquals(List.of("only the custodian of Main Store or their deputy can receive into this store"),
                StoresControl.custodian(MAIN, AUDITOR, "receive into").reasons());
    }

    @Test
    void store_without_custodian_blocks() {
        Store none = new Store("Old Store", 0, 0);
        assertFalse(StoresControl.custodian(none, KEEPER, "issued from").allowed());
        assertTrue(StoresControl.adjustment("Count 1", none, List.of(), KEEPER, AUDITOR, BigDecimal.ZERO).message()
                .contains("Old Store has no custodian"));
    }

    @Test
    void every_change_needs_a_reason_unchanged_lines_do_not() {
        ControlResult r = StoresControl.adjustment("Count 1", MAIN,
                List.of(line(10, "-2", "850", null, false), line(20, "0", "850", null, false), line(30, "3", "850", "CNT", false)),
                KEEPER, AUDITOR, BigDecimal.ZERO);
        assertEquals(List.of("line 10 (Cement 50kg) changes stock by -2 without a reason"), r.reasons());
    }

    @Test
    void write_off_value_counts_only_removals_with_write_off_reasons() {
        assertEquals(new BigDecimal("1700.00"), StoresControl.writeOffValue(List.of(line(10, "-2", "850", "DMG", true),
                line(20, "5", "850", "CNT", true), line(30, "-4", "100", "ISS", false))));
    }

    @Test
    void custodian_cannot_approve_own_store_write_off_but_auditor_can() {
        List<AdjustmentLine> damaged = List.of(line(10, "-2", "850", "DMG", true));
        ControlResult self = StoresControl.adjustment("Count 7", MAIN, damaged, KEEPER, KEEPER, BigDecimal.ZERO);
        assertTrue(self.message().contains("Count 7 writes off 1,700.00; it must be approved by someone other than the "
                + "preparer and the custodians of Main Store"), self.message());
        assertFalse(StoresControl.adjustment("x", MAIN, damaged, KEEPER, DEPUTY, BigDecimal.ZERO).allowed(), "deputy");
        assertTrue(StoresControl.adjustment("x", MAIN, damaged, KEEPER, AUDITOR, BigDecimal.ZERO).allowed());
    }

    @Test
    void write_off_threshold_boundary() {
        List<AdjustmentLine> damaged = List.of(line(10, "-2", "850", "DMG", true)); // 1,700.00
        assertFalse(StoresControl.adjustment("x", MAIN, damaged, KEEPER, KEEPER, new BigDecimal("1700.00")).allowed(),
                "at the threshold");
        assertTrue(StoresControl.adjustment("x", MAIN, damaged, KEEPER, KEEPER, new BigDecimal("1700.01")).allowed(),
                "one cent below the threshold");
    }

    @Test
    void adjustment_must_be_prepared_by_a_custodian() {
        ControlResult r = StoresControl.adjustment("Issue 3", MAIN, List.of(line(10, "-1", "100", "ISS", false)),
                PREPARER_OTHER, AUDITOR, BigDecimal.ZERO);
        assertEquals(List.of("Issue 3 was not prepared by the custodian of Main Store or their deputy"), r.reasons());
    }
}
