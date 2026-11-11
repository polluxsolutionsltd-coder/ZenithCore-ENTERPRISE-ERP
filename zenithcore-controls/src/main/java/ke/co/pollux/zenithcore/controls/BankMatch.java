package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matches bank and M-Pesa statement lines to the payments and receipts already in the books, to bank
 * charges, and to customers who paid without a receipt being recorded. Matching runs in passes, so a
 * line with an exact reference is never taken by a looser rule first:
 * <ol>
 * <li>Reference: the line's transaction reference (M-Pesa receipt code, cheque or EFT reference) equals a
 * payment's reference or document number, with the same amount.</li>
 * <li>Charge: the line's description or payer contains the text of a statement rule (bank charges, M-Pesa
 * tariffs, interest).</li>
 * <li>Amount and date: exactly one unmatched payment with the same amount within the date window (and for
 * the customer named by the account reference, if there is one). Two or more is left for review.</li>
 * <li>New receipt (money in only): the account reference the payer typed (paybill account number) is an
 * open customer invoice number, or a customer's code. A receipt is proposed for that invoice or customer.</li>
 * </ol>
 * Anything else is left unmatched with the reason, for the cashier. Every decision is explained in words.
 */
public final class BankMatch {

    public static final String CONTROL = "Bank statement";

    /** A statement line. Amount is signed: money in positive, money out negative. */
    public record StatementLine(int lineNo, LocalDate date, BigDecimal amount, String reference, String accountRef,
                                String payer, String description) {
    }

    /** A completed payment or receipt not yet reconciled. Amount is signed like statement lines. */
    public record Payment(int id, String documentNo, String reference, int bpartnerId, String bpartner, LocalDate date,
                          BigDecimal amount) {
    }

    /** An open customer invoice. */
    public record OpenInvoice(int id, String documentNo, int bpartnerId, String bpartner, BigDecimal open) {
    }

    /** A customer and the code payers may quote as the account reference. */
    public record Customer(int bpartnerId, String name, String code) {
    }

    /** Statement rule: lines whose description or payer contains the text go to the charge. */
    public record ChargeRule(String text, int chargeId, String chargeName) {
    }

    public enum Kind {
        REFERENCE, CHARGE, AMOUNT_DATE, NEW_RECEIPT, REVIEW, UNMATCHED;

        /** Code stored on the statement line. */
        public String code() {
            return switch (this) {
                case REFERENCE -> "REF";
                case CHARGE -> "CHG";
                case AMOUNT_DATE -> "AMT";
                case NEW_RECEIPT -> "NEW";
                case REVIEW -> "REV";
                case UNMATCHED -> "NON";
            };
        }
    }

    /**
     * Outcome for one line. For NEW_RECEIPT, invoiceId is 0 when the receipt is on the customer's account
     * rather than against an invoice.
     */
    public record Match(int lineNo, Kind kind, Payment payment, ChargeRule charge, int bpartnerId, int invoiceId,
                        String note) {
    }

    private BankMatch() {
    }

    /** Upper case, letters and digits only, so "inv-1001 " matches "INV1001". */
    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static List<Match> match(List<StatementLine> lines, List<Payment> payments, List<OpenInvoice> invoices,
            List<Customer> customers, List<ChargeRule> rules, int dateWindowDays, boolean proposeReceipts) {
        Map<Integer, Match> out = new HashMap<>();
        Set<Integer> used = new HashSet<>();

        // 1 reference
        for (StatementLine l : lines) {
            String ref = norm(l.reference());
            if (l.amount().signum() == 0) {
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.UNMATCHED, null, null, 0, 0, "zero amount"));
                continue;
            }
            if (ref.isEmpty()) {
                continue;
            }
            for (Payment p : payments) {
                if (used.contains(p.id()) || !(ref.equals(norm(p.reference())) || ref.equals(norm(p.documentNo())))) {
                    continue;
                }
                if (p.amount().compareTo(l.amount()) == 0) {
                    used.add(p.id());
                    out.put(l.lineNo(), new Match(l.lineNo(), Kind.REFERENCE, p, null, p.bpartnerId(), 0,
                            "reference " + l.reference() + " is on " + p.documentNo() + " (" + p.bpartner() + ")"));
                } else {
                    out.put(l.lineNo(), new Match(l.lineNo(), Kind.REVIEW, null, null, 0, 0, "reference " + l.reference()
                            + " is on " + p.documentNo() + " but for " + Amounts.fmt(p.amount()) + ", not "
                            + Amounts.fmt(l.amount())));
                }
                break;
            }
        }

        // 2 charges
        for (StatementLine l : lines) {
            if (out.containsKey(l.lineNo())) {
                continue;
            }
            String text = ((l.description() == null ? "" : l.description()) + " " + (l.payer() == null ? "" : l.payer()))
                    .toUpperCase(Locale.ROOT);
            for (ChargeRule r : rules) {
                if (!r.text().isBlank() && text.contains(r.text().toUpperCase(Locale.ROOT))) {
                    out.put(l.lineNo(), new Match(l.lineNo(), Kind.CHARGE, null, r, 0, 0,
                            "\"" + r.text() + "\" in the description: " + r.chargeName()));
                    break;
                }
            }
        }

        // the customer the account reference points to, if any
        Map<Integer, Integer> lineCustomer = new HashMap<>();
        Map<Integer, OpenInvoice> lineInvoice = new HashMap<>();
        Map<Integer, String> lineProblem = new HashMap<>();
        for (StatementLine l : lines) {
            String acc = norm(l.accountRef());
            if (acc.isEmpty()) {
                continue;
            }
            List<OpenInvoice> inv = invoices.stream().filter(i -> acc.equals(norm(i.documentNo()))).toList();
            List<Customer> cus = customers.stream().filter(c -> acc.equals(norm(c.code()))).toList();
            if (inv.size() == 1) {
                lineInvoice.put(l.lineNo(), inv.get(0));
                lineCustomer.put(l.lineNo(), inv.get(0).bpartnerId());
            } else if (inv.size() > 1) {
                lineProblem.put(l.lineNo(), "account reference " + l.accountRef() + " matches " + inv.size() + " open invoices");
            } else if (cus.size() == 1) {
                lineCustomer.put(l.lineNo(), cus.get(0).bpartnerId());
            } else if (cus.size() > 1) {
                lineProblem.put(l.lineNo(), "account reference " + l.accountRef() + " matches " + cus.size() + " customers");
            }
        }

        // 3 amount and date
        for (StatementLine l : lines) {
            if (out.containsKey(l.lineNo())) {
                continue;
            }
            Integer bp = lineCustomer.get(l.lineNo());
            List<Payment> c = payments.stream()
                    .filter(p -> !used.contains(p.id()) && p.amount().compareTo(l.amount()) == 0
                            && Math.abs(ChronoUnit.DAYS.between(p.date(), l.date())) <= dateWindowDays
                            && (bp == null || bp == p.bpartnerId()))
                    .toList();
            if (c.size() == 1) {
                Payment p = c.get(0);
                used.add(p.id());
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.AMOUNT_DATE, p, null, p.bpartnerId(), 0,
                        "only " + p.documentNo() + " (" + p.bpartner() + ", " + p.date() + ") has this amount within "
                                + dateWindowDays + " days"));
            } else if (c.size() > 1) {
                StringBuilder sb = new StringBuilder(c.size() + " payments have this amount within " + dateWindowDays
                        + " days: ");
                for (int i = 0; i < c.size() && i < 5; i++) {
                    sb.append(i == 0 ? "" : ", ").append(c.get(i).documentNo());
                }
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.REVIEW, null, null, 0, 0, sb.append(c.size() > 5 ? ", ..." : "")
                        .toString()));
            }
        }

        // 4 new receipts from the account reference
        Map<Integer, BigDecimal> openLeft = new HashMap<>();
        for (StatementLine l : lines) {
            if (out.containsKey(l.lineNo())) {
                continue;
            }
            String problem = lineProblem.get(l.lineNo());
            if (problem != null) {
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.REVIEW, null, null, 0, 0, problem));
                continue;
            }
            Integer bp = lineCustomer.get(l.lineNo());
            if (bp == null || l.amount().signum() < 0 || !proposeReceipts) {
                String why = l.amount().signum() < 0 ? "no payment with this reference or amount"
                        : (l.accountRef() == null || l.accountRef().isBlank()) ? "no receipt with this reference or amount, and no account reference"
                        : bp == null ? "no receipt with this reference or amount, and account reference " + l.accountRef()
                                + " is not an open invoice or customer code"
                        : "no receipt with this reference or amount; record the receipt";
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.UNMATCHED, null, null, 0, 0, why));
                continue;
            }
            OpenInvoice inv = lineInvoice.get(l.lineNo());
            if (inv != null) {
                BigDecimal left = openLeft.getOrDefault(inv.id(), inv.open());
                if (l.amount().compareTo(left) <= 0) {
                    openLeft.put(inv.id(), left.subtract(l.amount()));
                    out.put(l.lineNo(), new Match(l.lineNo(), Kind.NEW_RECEIPT, null, null, bp, inv.id(),
                            "receipt from " + inv.bpartner() + " for invoice " + inv.documentNo() + " (open "
                                    + Amounts.fmt(left) + ")"));
                } else {
                    out.put(l.lineNo(), new Match(l.lineNo(), Kind.NEW_RECEIPT, null, null, bp, 0,
                            "receipt from " + inv.bpartner() + " on account: " + Amounts.fmt(l.amount())
                                    + " is more than the " + Amounts.fmt(left) + " open on invoice " + inv.documentNo()));
                }
            } else {
                String name = customers.stream().filter(c -> c.bpartnerId() == bp).map(Customer::name).findFirst().orElse("");
                out.put(l.lineNo(), new Match(l.lineNo(), Kind.NEW_RECEIPT, null, null, bp, 0,
                        "receipt from " + name + " on account (customer code " + l.accountRef() + ")"));
            }
        }

        List<Match> result = new ArrayList<>();
        for (StatementLine l : lines) {
            result.add(out.get(l.lineNo()));
        }
        return result;
    }

    /** A statement line as it stands when the statement is completed. */
    public record LineState(int lineNo, BigDecimal trxAmt, boolean hasPayment, String reference) {
    }

    /** Completing a statement: every line carrying a transaction amount must be matched to a payment. */
    public static ControlResult complete(List<LineState> lines) {
        List<String> reasons = new ArrayList<>();
        for (LineState l : lines) {
            if (l.trxAmt().signum() != 0 && !l.hasPayment()) {
                reasons.add("line " + l.lineNo() + " (" + Amounts.fmt(l.trxAmt())
                        + (l.reference() == null || l.reference().isBlank() ? "" : ", " + l.reference())
                        + ") is not matched to a payment or receipt; run Match Statement, record it, or set a charge");
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /**
     * Completing a payment or receipt: its transaction reference must not already be on another completed
     * payment or receipt of the same account (the same M-Pesa code receipted twice).
     *
     * @param existing document number of the other payment with the same reference, or null
     */
    public static ControlResult uniqueReference(String label, String reference, String existing) {
        if (reference == null || reference.isBlank() || existing == null) {
            return ControlResult.pass(CONTROL);
        }
        return ControlResult.block(CONTROL, List.of(label + ": transaction reference " + reference
                + " is already recorded on " + existing));
    }

    /** An item in transit: a payment not yet on the bank statement. Amount is positive. */
    public record Outstanding(LocalDate date, String documentNo, String party, BigDecimal amount) {
    }

    /** Bank reconciliation statement as at a date. */
    public record Reconciliation(BigDecimal bankBalance, BigDecimal unpresented, BigDecimal uncredited,
                                 BigDecimal adjustedBank, BigDecimal cashBook, BigDecimal difference) {
        public boolean balanced() {
            return difference.signum() == 0;
        }
    }

    /**
     * Balance per bank statement, less payments not yet presented, plus receipts not yet credited, gives the
     * adjusted bank balance, which should equal the cash book.
     */
    public static Reconciliation reconcile(BigDecimal bankBalance, List<Outstanding> unpresented,
            List<Outstanding> uncredited, BigDecimal cashBook) {
        BigDecimal up = unpresented.stream().map(Outstanding::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal uc = uncredited.stream().map(Outstanding::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adjusted = bankBalance.subtract(up).add(uc);
        return new Reconciliation(bankBalance, up, uc, adjusted, cashBook, cashBook.subtract(adjusted));
    }
}
