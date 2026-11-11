package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opening balances at go-live. The legacy trial balance is loaded, mapped to the new chart, adjusted (with a
 * reason for every adjustment) to the audited figures, and supported by sub-ledger detail (open customer
 * and supplier invoices, stock, assets) for every control account. It is posted once, after someone other
 * than the preparer signs it off. Bank and M-Pesa balances are sub-ledger items too, because their
 * accounts are control accounts posted by bank statements.
 * <p>
 * Amounts are signed: debit positive, credit negative. Sub-ledger item amounts are in their normal
 * direction (receivables, stock and assets positive as debits; payables positive as credits), so a
 * supplier's credit note is a negative payable.
 */
public final class OpeningBalance {

    public static final String CONTROL = "Opening balances";

    /** Kinds of sub-ledger item. Codes are those stored in the database. */
    public enum ItemType {
        RECEIVABLE("AR"), PAYABLE("AP"), STOCK("ST"), ASSET("FA"), BANK("BK");

        public final String code;

        ItemType(String code) {
            this.code = code;
        }

        public static ItemType of(String code) {
            for (ItemType t : values()) {
                if (t.code.equalsIgnoreCase(code == null ? "" : code.trim())) {
                    return t;
                }
            }
            return null;
        }

        /** What the party of an item of this type is, or null if it has none. */
        String party() {
            return switch (this) {
                case RECEIVABLE -> "customer";
                case PAYABLE -> "supplier";
                case BANK -> "bank account";
                default -> null;
            };
        }

        /** Debit-positive amount of an item of this type. */
        BigDecimal signed(BigDecimal amount) {
            return this == PAYABLE ? amount.negate() : amount;
        }
    }

    /** A trial balance line. accountId 0 = not yet mapped; audited null = no audited figure for this line. */
    public record TbLine(int lineNo, String legacyCode, String legacyName, int accountId, String account, BigDecimal legacy,
                         BigDecimal adjustment, String reason, BigDecimal audited) {

        public BigDecimal opening() {
            return legacy.add(adjustment == null ? BigDecimal.ZERO : adjustment);
        }
    }

    /** A sub-ledger item supporting a control account. */
    public record Item(int lineNo, ItemType type, String party, String reference, BigDecimal amount, int controlAccountId,
                       String controlAccount) {
    }

    /** A posting account in the new chart. */
    public record Account(int id, String value, boolean control) {
    }

    /** Control account, its trial balance figure and the sub-ledger total behind it. */
    public record ControlTotal(String account, BigDecimal trialBalance, BigDecimal subLedger) {
        public BigDecimal difference() {
            return trialBalance.subtract(subLedger);
        }
    }

    public record Result(List<String> problems, BigDecimal debits, BigDecimal credits, List<ControlTotal> controls) {
        public boolean ok() {
            return problems.isEmpty();
        }

        public ControlResult asControl() {
            return ok() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, problems);
        }
    }

    /** A line of the opening journal. */
    public record JournalLine(int accountId, String account, BigDecimal amount) {
    }

    private OpeningBalance() {
    }

    /**
     * Checks a workbench before sign-off.
     *
     * @param accounts posting accounts of the new chart by id; a mapped account missing here is not a
     *                 posting account (summary or inactive)
     */
    public static Result check(List<TbLine> lines, List<Item> items, Map<Integer, Account> accounts) {
        List<String> p = new ArrayList<>();
        if (lines.isEmpty()) {
            p.add("no trial balance lines");
        }
        Set<String> codes = new HashSet<>();
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        Map<Integer, BigDecimal> tbByAccount = new LinkedHashMap<>();
        for (TbLine l : lines) {
            String code = l.legacyCode() == null ? "" : l.legacyCode().trim();
            String label = "line " + l.lineNo() + " (" + code + " " + (l.legacyName() == null ? "" : l.legacyName()) + ")";
            if (!code.isEmpty() && !codes.add(code.toUpperCase())) {
                p.add(label + ": legacy account " + code + " appears more than once");
            }
            if (l.accountId() <= 0) {
                p.add(label + ": not mapped to an account in the new chart");
            } else if (!accounts.containsKey(l.accountId())) {
                p.add(label + ": " + l.account() + " is not an active posting account");
            }
            BigDecimal adj = l.adjustment() == null ? BigDecimal.ZERO : l.adjustment();
            if (adj.signum() != 0 && (l.reason() == null || l.reason().isBlank())) {
                p.add(label + ": adjustment of " + Amounts.fmt(adj) + " has no reason");
            }
            if (l.audited() != null && l.opening().compareTo(l.audited()) != 0) {
                p.add(label + ": opening balance " + Amounts.fmt(l.opening()) + " differs from the audited "
                        + Amounts.fmt(l.audited()) + " by " + Amounts.fmt(l.opening().subtract(l.audited())));
            }
            if (l.opening().signum() > 0) {
                dr = dr.add(l.opening());
            } else {
                cr = cr.add(l.opening().negate());
            }
            if (l.accountId() > 0) {
                tbByAccount.merge(l.accountId(), l.opening(), BigDecimal::add);
            }
        }
        if (dr.compareTo(cr) != 0) {
            p.add("the trial balance does not balance: debits " + Amounts.fmt(dr) + ", credits " + Amounts.fmt(cr)
                    + ", difference " + Amounts.fmt(dr.subtract(cr)));
        }

        Map<Integer, BigDecimal> subByAccount = new LinkedHashMap<>();
        Map<Integer, String> names = new HashMap<>();
        Set<String> refs = new HashSet<>();
        for (Item i : items) {
            String label = "item " + i.lineNo() + " (" + (i.reference() == null ? "" : i.reference()) + ")";
            if (i.type() == null) {
                p.add(label + ": type must be AR, AP, ST, FA or BK");
                continue;
            }
            if (i.amount() == null || i.amount().signum() == 0) {
                p.add(label + ": amount is zero");
            }
            if (i.type().party() != null && (i.party() == null || i.party().isBlank())) {
                p.add(label + ": " + i.type().party() + " is not found");
            }
            if (i.reference() == null || i.reference().isBlank()) {
                p.add("item " + i.lineNo() + ": reference (legacy invoice, item or asset number) is missing");
            } else if (!refs.add(i.type().code + "|" + (i.party() == null ? "" : i.party()) + "|" + i.reference().trim().toUpperCase())) {
                p.add(label + ": the same " + i.type().code + " reference appears twice for " + i.party());
            }
            if (i.controlAccountId() <= 0) {
                p.add(label + ": no control account");
                continue;
            }
            if (i.amount() != null) {
                subByAccount.merge(i.controlAccountId(), i.type().signed(i.amount()), BigDecimal::add);
            }
            names.put(i.controlAccountId(), i.controlAccount());
        }

        List<ControlTotal> controls = new ArrayList<>();
        Set<Integer> controlIds = new java.util.LinkedHashSet<>(subByAccount.keySet());
        for (Map.Entry<Integer, BigDecimal> e : tbByAccount.entrySet()) {
            Account a = accounts.get(e.getKey());
            if (a != null && a.control() && e.getValue().signum() != 0) {
                controlIds.add(e.getKey());
            }
        }
        for (int id : controlIds) {
            Account a = accounts.get(id);
            String name = a != null ? a.value() : names.getOrDefault(id, String.valueOf(id));
            BigDecimal tb = tbByAccount.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal sub = subByAccount.getOrDefault(id, BigDecimal.ZERO);
            ControlTotal ct = new ControlTotal(name, tb, sub);
            controls.add(ct);
            if (ct.difference().signum() != 0) {
                p.add(subByAccount.containsKey(id)
                        ? "control account " + name + ": trial balance " + Amounts.fmt(tb) + " but sub-ledger items "
                                + Amounts.fmt(sub) + ", difference " + Amounts.fmt(ct.difference())
                        : "control account " + name + " has a balance of " + Amounts.fmt(tb) + " but no sub-ledger items");
            }
        }
        return new Result(p, dr.setScale(2, RoundingMode.HALF_UP), cr.setScale(2, RoundingMode.HALF_UP), controls);
    }

    /**
     * Sign-off: the checks must pass, and the person signing off is not the person who prepared the
     * workbench.
     */
    public static ControlResult signOff(Result check, int preparer, int signer) {
        List<String> reasons = new ArrayList<>(check.problems());
        if (preparer == signer) {
            reasons.add("you prepared these opening balances, so someone else must sign them off");
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /**
     * The opening journal: one line per account. Accounts with sub-ledger items are posted to the clearing
     * account instead, because their documents (opening invoices, stock count, asset register) post the
     * control account against clearing. Once every sub-ledger is loaded the clearing account is nil.
     */
    public static List<JournalLine> journal(List<TbLine> lines, List<Item> items, int clearingAccountId, String clearing) {
        Set<Integer> fromDocuments = new HashSet<>();
        for (Item i : items) {
            fromDocuments.add(i.controlAccountId());
        }
        Map<Integer, BigDecimal> amounts = new LinkedHashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (TbLine l : lines) {
            int id = fromDocuments.contains(l.accountId()) ? clearingAccountId : l.accountId();
            amounts.merge(id, l.opening(), BigDecimal::add);
            names.put(id, id == clearingAccountId ? clearing : l.account());
        }
        List<JournalLine> out = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : amounts.entrySet()) {
            if (e.getValue().signum() != 0) {
                out.add(new JournalLine(e.getKey(), names.get(e.getKey()), e.getValue()));
            }
        }
        return out;
    }
}
