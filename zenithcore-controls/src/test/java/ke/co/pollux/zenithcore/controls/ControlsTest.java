package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.SupplierGate.Status;
import ke.co.pollux.zenithcore.controls.SupplierGate.Supplier;
import ke.co.pollux.zenithcore.controls.ThreeWayMatch.Line;

class ControlsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Nested
    class SecondPerson {
        @Test
        void same_person_is_blocked_when_rule_applies_always() {
            ControlResult r = SecondPersonApproval.check("GL journal 1007", 100, 100, d("10"), BigDecimal.ZERO);
            assertFalse(r.allowed());
            assertTrue(r.message().contains("you prepared GL journal 1007"), r.message());
        }

        @Test
        void different_person_passes() {
            assertTrue(SecondPersonApproval.check("x", 100, 101, d("1000000"), null).allowed());
        }

        @Test
        void threshold_boundary_exact_amount_needs_second_person_one_cent_below_does_not() {
            assertFalse(SecondPersonApproval.check("x", 1, 1, d("50000.00"), d("50000")).allowed());
            assertTrue(SecondPersonApproval.check("x", 1, 1, d("49999.99"), d("50000")).allowed());
        }

        @Test
        void negative_amounts_use_absolute_value() {
            assertFalse(SecondPersonApproval.check("credit note", 1, 1, d("-60000"), d("50000")).allowed());
        }
    }

    @Nested
    class Supplier_gate {
        private final LocalDate today = LocalDate.of(2026, 11, 10);

        private Supplier s(Status st, LocalDate until, LocalDate tcc) {
            return new Supplier("Acme Ltd", st, until, tcc, null);
        }

        @Test
        void qualified_with_valid_tcc_passes() {
            assertTrue(SupplierGate.check(s(Status.QUALIFIED, today.plusMonths(6), today.plusMonths(3)), today).allowed());
        }

        @Test
        void last_valid_day_passes_and_day_after_blocks() {
            assertTrue(SupplierGate.check(s(Status.QUALIFIED, today, today), today).allowed());
            ControlResult r = SupplierGate.check(s(Status.QUALIFIED, today, today), today.plusDays(1));
            assertEquals(2, r.reasons().size(), r.message());
            assertTrue(r.message().contains("pre-qualification ended on 2026-11-10"), r.message());
            assertTrue(r.message().contains("tax compliance certificate expired on 2026-11-10"), r.message());
        }

        @Test
        void pending_or_suspended_blocks() {
            assertTrue(SupplierGate.check(s(Status.PENDING, today, today), today).message().contains("pending, not pre-qualified"));
            assertFalse(SupplierGate.check(s(Status.SUSPENDED, today, today), today).allowed());
        }

        @Test
        void missing_tcc_or_end_date_blocks() {
            ControlResult r = SupplierGate.check(s(Status.QUALIFIED, null, null), today);
            assertEquals(List.of("Acme Ltd has no pre-qualification end date",
                    "no KRA tax compliance certificate on file for Acme Ltd"), r.reasons());
        }

        @Test
        void blacklisted_blocks_with_reason_and_nothing_else() {
            ControlResult r = SupplierGate.check(new Supplier("Acme Ltd", Status.BLACKLISTED, today.plusYears(1),
                    today.plusYears(1), "Fake delivery notes, board minute 12/2026"), today);
            assertEquals(List.of("Acme Ltd is blacklisted (Fake delivery notes, board minute 12/2026)"), r.reasons());
        }

        @Test
        void unknown_status_code_is_pending() {
            assertEquals(Status.PENDING, Status.of(null));
            assertEquals(Status.BLACKLISTED, Status.of("B"));
        }
    }

    @Nested
    class Three_way_match {
        private Line line(String inv, String rec, String before, String invPrice, String poPrice) {
            return new Line(10, "Cement 50kg", true, true, d(inv), d(rec), d(before), d(invPrice), d(poPrice));
        }

        @Test
        void fully_received_at_lpo_price_passes() {
            assertTrue(ThreeWayMatch.check(List.of(line("100", "100", "0", "850", "850")), BigDecimal.ZERO).allowed());
        }

        @Test
        void invoicing_more_than_received_blocks_including_earlier_invoices() {
            ControlResult r = ThreeWayMatch.check(List.of(line("40", "100", "70", "850", "850")), BigDecimal.ZERO);
            assertEquals(List.of("line 10 (Cement 50kg): 110 invoiced (including 70 on earlier invoices) but only 100 received"),
                    r.reasons());
            assertTrue(ThreeWayMatch.check(List.of(line("30", "100", "70", "850", "850")), BigDecimal.ZERO).allowed());
        }

        @Test
        void price_tolerance_boundary() {
            // 2% over 850.00 is 867.00: allowed; 867.01 is not
            assertTrue(ThreeWayMatch.check(List.of(line("1", "1", "0", "867.00", "850")), d("2")).allowed());
            ControlResult r = ThreeWayMatch.check(List.of(line("1", "1", "0", "867.01", "850")), d("2"));
            assertEquals(List.of("line 10 (Cement 50kg): invoice price 867.01 is above the LPO price 850.00 plus 2%"),
                    r.reasons());
            assertFalse(ThreeWayMatch.check(List.of(line("1", "1", "0", "850.01", "850")), BigDecimal.ZERO).allowed());
        }

        @Test
        void line_without_lpo_blocks() {
            Line l = new Line(20, "Transport", false, false, d("1"), d("0"), d("0"), d("5000"), d("0"));
            assertEquals(List.of("line 20 (Transport) is not on an LPO"), ThreeWayMatch.check(List.of(l), null).reasons());
        }

        @Test
        void service_line_needs_no_goods_received_note() {
            Line l = new Line(30, "Cleaning services", true, false, d("1"), d("0"), d("0"), d("20000"), d("20000"));
            assertTrue(ThreeWayMatch.check(List.of(l), BigDecimal.ZERO).allowed());
        }
    }

    @Nested
    class Audit_query {
        @Test
        void complete_query_closed_by_another_person_passes() {
            assertTrue(AuditQueryClosure.check("AQ-2025-014", "Reconciled and adjusted", 1, 100, 101).allowed());
        }

        @Test
        void missing_response_evidence_and_self_closure_all_reported() {
            ControlResult r = AuditQueryClosure.check("AQ-2025-014", " ", 0, 100, 100);
            assertEquals(3, r.reasons().size(), r.message());
        }

        @Test
        void unassigned_query_can_be_closed_by_anyone() {
            assertTrue(AuditQueryClosure.check("AQ-1", "Done", 2, 0, 100).allowed());
        }
    }
}
