package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.BankMatch.ChargeRule;
import ke.co.pollux.zenithcore.controls.BankMatch.Customer;
import ke.co.pollux.zenithcore.controls.BankMatch.Kind;
import ke.co.pollux.zenithcore.controls.BankMatch.LineState;
import ke.co.pollux.zenithcore.controls.BankMatch.Match;
import ke.co.pollux.zenithcore.controls.BankMatch.OpenInvoice;
import ke.co.pollux.zenithcore.controls.BankMatch.Outstanding;
import ke.co.pollux.zenithcore.controls.BankMatch.Payment;
import ke.co.pollux.zenithcore.controls.BankMatch.StatementLine;

class BankMatchTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final List<ChargeRule> RULES = List.of(new ChargeRule("Pay Bill Charge", 500, "M-Pesa charges"),
            new ChargeRule("Ledger fee", 501, "Bank charges"));
    private static final List<Customer> CUSTOMERS = List.of(new Customer(7, "Kamau Fabricators", "TEN-007"),
            new Customer(8, "Wanjiru Foods", "TEN-008"));
    private static final List<OpenInvoice> INVOICES = List.of(new OpenInvoice(900, "INV-1001", 7, "Kamau Fabricators",
            new BigDecimal("25000.00")));

    private static StatementLine line(int no, LocalDate d, String amt, String ref, String acc, String desc) {
        return new StatementLine(no, d, new BigDecimal(amt), ref, acc, "", desc);
    }

    private static Payment pay(int id, String doc, String ref, int bp, LocalDate d, String amt) {
        return new Payment(id, doc, ref, bp, bp == 7 ? "Kamau Fabricators" : "Wanjiru Foods", d, new BigDecimal(amt));
    }

    private static List<Match> run(List<StatementLine> lines, List<Payment> pays) {
        return BankMatch.match(lines, pays, INVOICES, CUSTOMERS, RULES, 3, true);
    }

    @Test
    void reference_match_ignores_case_and_punctuation() {
        List<Match> m = run(List.of(line(10, D, "1500", "qk12-abc3de", null, "Paybill")),
                List.of(pay(1, "RC-1", "QK12ABC3DE", 7, D.minusDays(20), "1500")));
        assertEquals(Kind.REFERENCE, m.get(0).kind());
        assertEquals(1, m.get(0).payment().id());
    }

    @Test
    void reference_with_a_different_amount_goes_to_review() {
        Match m = run(List.of(line(10, D, "1500", "QK12ABC3DE", null, "")),
                List.of(pay(1, "RC-1", "QK12ABC3DE", 7, D, "1500.01"))).get(0);
        assertEquals(Kind.REVIEW, m.kind());
        assertEquals("reference QK12ABC3DE is on RC-1 but for 1,500.01, not 1,500.00", m.note());
    }

    @Test
    void exact_reference_wins_over_an_earlier_amount_match() {
        // line 10 has no reference and would take payment 2 by amount if matching were line by line
        List<Match> m = run(List.of(line(10, D, "2000", null, null, ""), line(20, D, "2000", "EFT-889", null, "")),
                List.of(pay(2, "RC-2", "EFT889", 7, D, "2000"), pay(3, "RC-3", null, 8, D, "2000")));
        assertEquals(Kind.REFERENCE, m.get(1).kind());
        assertEquals(2, m.get(1).payment().id());
        assertEquals(Kind.AMOUNT_DATE, m.get(0).kind());
        assertEquals(3, m.get(0).payment().id());
    }

    @Test
    void charges_by_description() {
        Match m = run(List.of(line(10, D, "-33", "QK99", null, "Pay Bill Charge")), List.of()).get(0);
        assertEquals(Kind.CHARGE, m.kind());
        assertEquals(500, m.charge().chargeId());
    }

    @Test
    void amount_and_date_window_boundary() {
        Payment p = pay(4, "PV-4", null, 8, D, "-12000");
        assertEquals(Kind.AMOUNT_DATE, run(List.of(line(10, D.plusDays(3), "-12000", null, null, "")), List.of(p)).get(0).kind());
        assertEquals(Kind.UNMATCHED, run(List.of(line(10, D.plusDays(4), "-12000", null, null, "")), List.of(p)).get(0).kind());
        assertEquals(Kind.UNMATCHED, run(List.of(line(10, D, "-12000.01", null, null, "")), List.of(p)).get(0).kind());
    }

    @Test
    void two_payments_with_the_same_amount_go_to_review() {
        Match m = run(List.of(line(10, D, "5000", null, null, "")),
                List.of(pay(5, "RC-5", null, 7, D, "5000"), pay(6, "RC-6", null, 8, D, "5000"))).get(0);
        assertEquals(Kind.REVIEW, m.kind());
        assertEquals("2 payments have this amount within 3 days: RC-5, RC-6", m.note());
    }

    @Test
    void account_reference_narrows_an_amount_match_to_the_customer() {
        Match m = run(List.of(line(10, D, "5000", null, "TEN-008", "")),
                List.of(pay(5, "RC-5", null, 7, D, "5000"), pay(6, "RC-6", null, 8, D, "5000"))).get(0);
        assertEquals(Kind.AMOUNT_DATE, m.kind());
        assertEquals(6, m.payment().id());
    }

    @Test
    void new_receipt_for_an_invoice_up_to_its_open_amount() {
        List<Match> m = run(List.of(line(10, D, "20000", "QA1", "inv 1001", ""), line(20, D, "5000", "QA2", "INV-1001", ""),
                line(30, D, "0.01", "QA3", "INV-1001", "")), List.of());
        assertEquals(Kind.NEW_RECEIPT, m.get(0).kind());
        assertEquals(900, m.get(0).invoiceId());
        assertEquals(900, m.get(1).invoiceId());   // exactly the 5,000 left
        assertEquals(Kind.NEW_RECEIPT, m.get(2).kind());
        assertEquals(0, m.get(2).invoiceId());     // nothing left open: on account
        assertEquals(7, m.get(2).bpartnerId());
        assertEquals("receipt from Kamau Fabricators on account: 0.01 is more than the 0.00 open on invoice INV-1001",
                m.get(2).note());
    }

    @Test
    void new_receipt_on_account_by_customer_code() {
        Match m = run(List.of(line(10, D, "3000", "QB1", "ten008", "")), List.of()).get(0);
        assertEquals(Kind.NEW_RECEIPT, m.kind());
        assertEquals(8, m.bpartnerId());
        assertEquals(0, m.invoiceId());
    }

    @Test
    void no_receipts_proposed_for_money_out_unknown_references_or_when_switched_off() {
        assertEquals(Kind.UNMATCHED, run(List.of(line(10, D, "-3000", "QB1", "TEN-008", "")), List.of()).get(0).kind());
        Match m = run(List.of(line(10, D, "3000", "QB1", "TEN-999", "")), List.of()).get(0);
        assertEquals(Kind.UNMATCHED, m.kind());
        assertEquals("no receipt with this reference or amount, and account reference TEN-999 is not an open invoice or customer code",
                m.note());
        assertEquals(Kind.UNMATCHED, BankMatch.match(List.of(line(10, D, "3000", "QB1", "TEN-008", "")), List.of(), INVOICES,
                CUSTOMERS, RULES, 3, false).get(0).kind());
    }

    @Test
    void a_payment_is_matched_once() {
        List<Match> m = run(List.of(line(10, D, "700", null, null, ""), line(20, D, "700", null, null, "")),
                List.of(pay(9, "RC-9", null, 7, D, "700")));
        assertEquals(Kind.AMOUNT_DATE, m.get(0).kind());
        assertEquals(Kind.UNMATCHED, m.get(1).kind());
    }

    @Test
    void completion_needs_every_transaction_matched() {
        assertTrue(BankMatch.complete(List.of(new LineState(10, new BigDecimal("1500"), true, "QK1"),
                new LineState(20, BigDecimal.ZERO, false, "charge"))).allowed());
        ControlResult r = BankMatch.complete(List.of(new LineState(30, new BigDecimal("-0.01"), false, "EFT9")));
        assertFalse(r.allowed());
        assertEquals("line 30 (-0.01, EFT9) is not matched to a payment or receipt; run Match Statement, record it, or set a charge",
                r.reasons().get(0));
    }

    @Test
    void a_reference_is_receipted_once() {
        assertTrue(BankMatch.uniqueReference("Receipt RC-7", "QK12ABC3DE", null).allowed());
        assertTrue(BankMatch.uniqueReference("Receipt RC-7", null, "RC-1").allowed());
        assertEquals("Receipt RC-7: transaction reference QK12ABC3DE is already recorded on RC-1",
                BankMatch.uniqueReference("Receipt RC-7", "QK12ABC3DE", "RC-1").reasons().get(0));
    }

    @Test
    void reconciliation_statement() {
        BankMatch.Reconciliation r = BankMatch.reconcile(new BigDecimal("100000.00"),
                List.of(new Outstanding(D, "PV-1", "Supplier", new BigDecimal("30000.00"))),
                List.of(new Outstanding(D, "RC-1", "Tenant", new BigDecimal("5000.00"))), new BigDecimal("75000.00"));
        assertEquals(new BigDecimal("75000.00"), r.adjustedBank());
        assertTrue(r.balanced());
        assertEquals(new BigDecimal("0.01"), BankMatch.reconcile(new BigDecimal("100000.00"), List.of(), List.of(),
                new BigDecimal("100000.01")).difference());
    }
}
