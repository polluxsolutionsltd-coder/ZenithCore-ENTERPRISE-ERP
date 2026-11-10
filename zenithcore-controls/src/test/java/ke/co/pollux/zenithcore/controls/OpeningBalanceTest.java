package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.OpeningBalance.Account;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Item;
import ke.co.pollux.zenithcore.controls.OpeningBalance.ItemType;
import ke.co.pollux.zenithcore.controls.OpeningBalance.JournalLine;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Result;
import ke.co.pollux.zenithcore.controls.OpeningBalance.TbLine;

class OpeningBalanceTest {

    // new chart: bank 1, receivables 2 (control), payables 3 (control), capital 4, clearing 9
    private static final Map<Integer, Account> CHART = Map.of(1, new Account(1, "11100", false),
            2, new Account(2, "12110", true), 3, new Account(3, "21100", true), 4, new Account(4, "31000", false),
            9, new Account(9, "19990", false));

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static TbLine tb(int no, String code, int acct, String legacy, String adj, String reason, String audited) {
        return new TbLine(no, code, "Legacy " + code, acct, acct == 0 ? "" : CHART.get(acct).value(), d(legacy),
                adj == null ? null : d(adj), reason, audited == null ? null : d(audited));
    }

    private static List<TbLine> balanced() {
        return List.of(tb(10, "1000", 1, "500000.00", null, null, "500000.00"),
                tb(20, "1100", 2, "120000.00", "-20000.00", "Bad debt written off per audit adjustment 4", "100000.00"),
                tb(30, "2000", 3, "-80000.00", null, null, null),
                tb(40, "3000", 4, "-540000.00", "20000.00", "Audit adjustment 4", "-520000.00"));
    }

    private static List<Item> items() {
        return List.of(new Item(1, ItemType.RECEIVABLE, "Kamau Fabricators", "INV-0091", d("60000.00"), 2, "12110"),
                new Item(2, ItemType.RECEIVABLE, "Wanjiru Foods", "INV-0102", d("40000.00"), 2, "12110"),
                new Item(3, ItemType.PAYABLE, "Mwangi Hardware", "MH/77", d("85000.00"), 3, "21100"),
                new Item(4, ItemType.PAYABLE, "Mwangi Hardware", "CN/12", d("-5000.00"), 3, "21100"));
    }

    @Test
    void a_clean_workbench_passes() {
        Result r = OpeningBalance.check(balanced(), items(), CHART);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(d("600000.00"), r.debits());
        assertEquals(d("600000.00"), r.credits());
        assertEquals(2, r.controls().size());
    }

    @Test
    void out_of_balance_by_one_cent() {
        List<TbLine> l = new ArrayList<>(balanced());
        l.set(0, tb(10, "1000", 1, "500000.01", null, null, null));
        Result r = OpeningBalance.check(l, items(), CHART);
        assertEquals(List.of("the trial balance does not balance: debits 600,000.01, credits 600,000.00, difference 0.01"),
                r.problems());
    }

    @Test
    void audited_figure_must_agree_to_the_cent() {
        List<TbLine> l = new ArrayList<>(balanced());
        l.set(0, tb(10, "1000", 1, "500000.00", null, null, "500000.01"));
        assertEquals(List.of("line 10 (1000 Legacy 1000): opening balance 500,000.00 differs from the audited 500,000.01 by -0.01"),
                OpeningBalance.check(l, items(), CHART).problems());
    }

    @Test
    void unmapped_lines_non_posting_accounts_duplicates_and_adjustments_without_reason() {
        List<TbLine> l = List.of(tb(10, "1000", 0, "100", null, null, null), tb(20, "1000", 1, "-50", "-50", " ", null),
                new TbLine(30, "1300", "Old suspense", 77, "19000 (summary)", d("0"), null, null, null));
        List<String> p = OpeningBalance.check(l, List.of(), CHART).problems();
        assertTrue(p.contains("line 10 (1000 Legacy 1000): not mapped to an account in the new chart"));
        assertTrue(p.contains("line 20 (1000 Legacy 1000): legacy account 1000 appears more than once"));
        assertTrue(p.contains("line 20 (1000 Legacy 1000): adjustment of -50.00 has no reason"));
        assertTrue(p.contains("line 30 (1300 Old suspense): 19000 (summary) is not an active posting account"));
    }

    @Test
    void control_accounts_must_agree_with_their_sub_ledgers() {
        List<Item> i = new ArrayList<>(items());
        i.remove(3); // supplier credit note left out: payables sub-ledger 85,000 against 80,000 in the trial balance
        Result r = OpeningBalance.check(balanced(), i, CHART);
        assertEquals(List.of("control account 21100: trial balance -80,000.00 but sub-ledger items -85,000.00, difference 5,000.00"),
                r.problems());
    }

    @Test
    void a_control_account_with_a_balance_needs_items() {
        Result r = OpeningBalance.check(balanced(), items().subList(2, 4), CHART);
        assertEquals(List.of("control account 12110 has a balance of 100,000.00 but no sub-ledger items"), r.problems());
    }

    @Test
    void item_checks() {
        List<Item> i = List.of(new Item(1, null, "X", "R1", d("1"), 2, "12110"),
                new Item(2, ItemType.RECEIVABLE, null, "R2", d("0"), 2, "12110"),
                new Item(3, ItemType.STOCK, "", " ", d("5"), 0, null),
                new Item(4, ItemType.PAYABLE, "S", "r9", d("1"), 3, "21100"),
                new Item(5, ItemType.PAYABLE, "S", "R9 ", d("1"), 3, "21100"));
        List<String> p = OpeningBalance.check(List.of(), i, CHART).problems();
        assertTrue(p.contains("item 1 (R1): type must be AR, AP, ST, FA or BK"));
        assertTrue(p.contains("item 2 (R2): amount is zero"));
        assertTrue(p.contains("item 2 (R2): customer is not found"));
        assertTrue(p.contains("item 3: reference (legacy invoice, item or asset number) is missing"));
        assertTrue(p.contains("item 3 ( ): no control account"));
        assertTrue(p.contains("item 5 (R9 ): the same AP reference appears twice for S"));
    }

    @Test
    void bank_balances_are_sub_ledger_items_and_go_to_clearing() {
        Map<Integer, Account> chart = new java.util.HashMap<>(CHART);
        chart.put(1, new Account(1, "11100", true)); // bank asset accounts are control accounts in iDempiere
        List<Item> withBank = new ArrayList<>(items());
        assertEquals(List.of("control account 11100 has a balance of 500,000.00 but no sub-ledger items"),
                OpeningBalance.check(balanced(), withBank, chart).problems());
        withBank.add(new Item(5, ItemType.BANK, "KCB 1100223344", "Statement 31 Oct", d("450000.00"), 1, "11100"));
        withBank.add(new Item(6, ItemType.BANK, "M-Pesa paybill", "Statement 31 Oct", d("50000.00"), 1, "11100"));
        assertTrue(OpeningBalance.check(balanced(), withBank, chart).ok());
        withBank.add(new Item(7, ItemType.BANK, null, "Old account", d("1.00"), 1, "11100"));
        assertTrue(OpeningBalance.check(balanced(), withBank, chart).problems().contains("item 7 (Old account): bank account is not found"));
        List<JournalLine> j = OpeningBalance.journal(balanced(), withBank.subList(0, 6), 9, "19990");
        assertEquals(List.of(new JournalLine(9, "19990", d("520000.00")), new JournalLine(4, "31000", d("-520000.00"))), j);
    }

    @Test
    void sign_off_by_someone_other_than_the_preparer() {
        Result ok = OpeningBalance.check(balanced(), items(), CHART);
        assertTrue(OpeningBalance.signOff(ok, 10, 11).allowed());
        assertEquals(List.of("you prepared these opening balances, so someone else must sign them off"),
                OpeningBalance.signOff(ok, 10, 10).reasons());
        Result bad = OpeningBalance.check(balanced(), List.of(), CHART);
        assertFalse(OpeningBalance.signOff(bad, 10, 11).allowed());
    }

    @Test
    void journal_posts_control_accounts_to_clearing_and_balances() {
        List<JournalLine> j = OpeningBalance.journal(balanced(), items(), 9, "19990");
        assertEquals(List.of(new JournalLine(1, "11100", d("500000.00")), new JournalLine(9, "19990", d("20000.00")),
                new JournalLine(4, "31000", d("-520000.00"))), j);
        assertEquals(0, j.stream().map(JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum());
    }
}
