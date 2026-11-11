package ke.co.pollux.zenithcore.payroll.ke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.payroll.ke.EstablishmentChecks.Position;
import ke.co.pollux.zenithcore.payroll.ke.EstablishmentSummary.Holder;
import ke.co.pollux.zenithcore.payroll.ke.EstablishmentSummary.Summary;

class EstablishmentSummaryTest {

    private static final LocalDate AS_AT = LocalDate.of(2026, 12, 31);

    private static Position pos(String code, int hc, LocalDate to) {
        return new Position(code, code + " title", 1, hc, null, LocalDate.of(2026, 1, 1), to, true);
    }

    private static Holder h(String id, String pos, String basic, String fixed) {
        return new Holder(id, "N" + id, pos, new BigDecimal(basic), new BigDecimal(fixed));
    }

    @Test
    void counts_vacant_over_and_costs() {
        Summary s = EstablishmentSummary.of(
                List.of(pos("WS", 2, null), pos("CC", 1, null), pos("DRV", 3, null)),
                List.of(h("E1", "WS", "62000", "78000"), h("E4", "WS", "18000", "18000"),
                        h("E6", "CC", "82000", "102000"), h("E7", "CC", "40000", "40000"), h("E9", null, "1", "1")),
                AS_AT);
        assertEquals(6, s.approved());
        assertEquals(4, s.filled());
        assertEquals(3, s.vacant(), "DRV all vacant");
        assertEquals(1, s.over(), "CC one over");
        assertEquals(new BigDecimal("202000.00"), s.basicCost());
        assertEquals(new BigDecimal("238000.00"), s.fixedCost());
        assertEquals(List.of("E9 NE9"), s.withoutPosition());
        assertEquals(List.of("E6 NE6", "E7 NE7"), s.rows().get(1).holders());
        assertEquals(0, s.rows().get(0).vacant());
    }

    @Test
    void position_ended_before_date_is_left_out_and_its_holders_listed_without_position() {
        Summary s = EstablishmentSummary.of(List.of(pos("OLD", 1, AS_AT.minusDays(1)), pos("NEW", 1, AS_AT)),
                List.of(h("E1", "OLD", "100", "100"), h("E2", "NEW", "200", "200")), AS_AT);
        assertEquals(1, s.rows().size(), "NEW is in force on its last day");
        assertEquals(1, s.approved());
        assertEquals(List.of("E1 NE1"), s.withoutPosition());
    }
}
