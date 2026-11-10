package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MBankAccount;
import org.compiere.model.MBankStatement;
import org.compiere.model.MBankStatementLine;
import org.compiere.model.MPayment;
import org.compiere.model.MSysConfig;
import org.compiere.process.DocAction;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.BankMatch;
import ke.co.pollux.zenithcore.controls.BankMatch.Kind;

/**
 * Match Statement (button on Bank Statement). Runs the ZenithCore matching rule over the draft statement's
 * lines that have no payment, deposit batch or charge yet, and records the outcome on each line (Matched and
 * Match note). With ZC_BANK_CREATE_RECEIPTS = Y it also records and completes a receipt for money in whose
 * account reference is an open invoice number or a customer code.
 * <p>
 * Statement line fields used: transaction reference = EFT transaction ID, else reference no.; account
 * reference = EFT reference; payer = EFT payee; description = description, else memo. Set the bank's or
 * M-Pesa's import loader format to fill these.
 */
@Process
public class MatchStatement extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current statement
    }

    @Override
    protected String doIt() throws Exception {
        MBankStatement st = new MBankStatement(getCtx(), getRecord_ID(), get_TrxName());
        if (st.get_ID() <= 0) {
            throw new AdempiereUserError("Open a bank statement first");
        }
        if (st.isProcessed()) {
            throw new AdempiereUserError("This statement is already completed");
        }
        int client = st.getAD_Client_ID();
        int account = st.getC_BankAccount_ID();
        String trx = get_TrxName();

        List<BankMatch.StatementLine> lines = new ArrayList<>();
        Set<String> accountRefs = new LinkedHashSet<>();
        String sql = "SELECT Line, StatementLineDate, StmtAmt, COALESCE(NULLIF(EftTrxID,''), ReferenceNo), EftReference, EftPayee, "
                + "COALESCE(NULLIF(Description,''), Memo, EftMemo), C_BankStatementLine_ID FROM C_BankStatementLine "
                + "WHERE C_BankStatement_ID=? AND IsActive='Y' AND COALESCE(C_Payment_ID,0)=0 AND COALESCE(C_DepositBatch_ID,0)=0 "
                + "AND COALESCE(C_Charge_ID,0)=0 ORDER BY Line";
        Map<Integer, Integer> lineIds = new java.util.HashMap<>();
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, st.get_ID());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new BankMatch.StatementLine(rs.getInt(1), date(rs.getTimestamp(2)), rs.getBigDecimal(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                    lineIds.put(rs.getInt(1), rs.getInt(8));
                    if (rs.getString(5) != null && !rs.getString(5).isBlank()) {
                        accountRefs.add(norm(rs.getString(5)));
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            return "Every line of this statement is already matched";
        }

        List<BankMatch.Payment> payments = new ArrayList<>();
        sql = "SELECT p.C_Payment_ID, p.DocumentNo, COALESCE(NULLIF(p.ZC_TrxReference,''), NULLIF(p.CheckNo,'')), p.C_BPartner_ID, bp.Name, p.DateTrx, "
                + "CASE WHEN p.IsReceipt='Y' THEN p.PayAmt ELSE -p.PayAmt END FROM C_Payment p "
                + "JOIN C_BPartner bp ON bp.C_BPartner_ID=p.C_BPartner_ID WHERE p.C_BankAccount_ID=? "
                + "AND p.DocStatus IN ('CO','CL') AND p.IsReconciled='N' AND COALESCE(p.C_DepositBatch_ID,0)=0 "
                + "AND NOT EXISTS (SELECT 1 FROM C_BankStatementLine bl JOIN C_BankStatement b ON b.C_BankStatement_ID=bl.C_BankStatement_ID "
                + "WHERE bl.C_Payment_ID=p.C_Payment_ID AND b.DocStatus NOT IN ('VO','RE')) ORDER BY p.DateTrx, p.C_Payment_ID";
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new BankMatch.Payment(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getString(5), date(rs.getTimestamp(6)), rs.getBigDecimal(7)));
                }
            }
        }

        List<BankMatch.OpenInvoice> invoices = new ArrayList<>();
        List<BankMatch.Customer> customers = new ArrayList<>();
        if (!accountRefs.isEmpty()) {
            String in = String.join(",", accountRefs.stream().map(r -> "?").toList());
            sql = "SELECT i.C_Invoice_ID, i.DocumentNo, i.C_BPartner_ID, bp.Name, invoiceOpen(i.C_Invoice_ID,0) FROM C_Invoice i "
                    + "JOIN C_BPartner bp ON bp.C_BPartner_ID=i.C_BPartner_ID WHERE i.AD_Client_ID=? AND i.IsSOTrx='Y' "
                    + "AND i.DocStatus IN ('CO','CL') AND i.IsPaid='N' "
                    + "AND UPPER(regexp_replace(i.DocumentNo,'[^A-Za-z0-9]','','g')) IN (" + in + ")";
            try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
                bind(ps, client, accountRefs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        invoices.add(new BankMatch.OpenInvoice(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                                rs.getBigDecimal(5)));
                    }
                }
            }
            sql = "SELECT C_BPartner_ID, Name, Value FROM C_BPartner WHERE AD_Client_ID=? AND IsCustomer='Y' AND IsActive='Y' "
                    + "AND UPPER(regexp_replace(Value,'[^A-Za-z0-9]','','g')) IN (" + in + ")";
            try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
                bind(ps, client, accountRefs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        customers.add(new BankMatch.Customer(rs.getInt(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
        }

        List<BankMatch.ChargeRule> rules = new ArrayList<>();
        sql = "SELECT r.ZC_MatchText, r.C_Charge_ID, c.Name FROM ZC_StatementRule r JOIN C_Charge c ON c.C_Charge_ID=r.C_Charge_ID "
                + "WHERE r.AD_Client_ID=? AND r.IsActive='Y' AND (r.C_BankAccount_ID IS NULL OR r.C_BankAccount_ID=?) "
                + "ORDER BY r.C_BankAccount_ID NULLS LAST, LENGTH(r.ZC_MatchText) DESC";
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, client);
            ps.setInt(2, account);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(new BankMatch.ChargeRule(rs.getString(1), rs.getInt(2), rs.getString(3)));
                }
            }
        }

        boolean createReceipts = MSysConfig.getBooleanValue("ZC_BANK_CREATE_RECEIPTS", false, client);
        int days = MSysConfig.getIntValue("ZC_BANK_MATCH_DAYS", 3, client);
        List<BankMatch.Match> matches = BankMatch.match(lines, payments, invoices, customers, rules, days, createReceipts);

        Map<Kind, Integer> count = new EnumMap<>(Kind.class);
        MBankAccount bank = MBankAccount.get(account);
        for (int i = 0; i < lines.size(); i++) {
            BankMatch.StatementLine sl = lines.get(i);
            BankMatch.Match m = matches.get(i);
            MBankStatementLine line = new MBankStatementLine(getCtx(), lineIds.get(sl.lineNo()), trx);
            Kind kind = m.kind();
            String note = m.note();
            switch (kind) {
                case REFERENCE, AMOUNT_DATE -> {
                    line.setC_Payment_ID(m.payment().id());
                    line.setC_BPartner_ID(m.payment().bpartnerId());
                    line.setTrxAmt(line.getStmtAmt());
                }
                case CHARGE -> {
                    line.setTrxAmt(BigDecimal.ZERO);
                    line.setC_Charge_ID(m.charge().chargeId());
                }
                case NEW_RECEIPT -> {
                    MPayment p = new MPayment(getCtx(), 0, trx);
                    p.setAD_Org_ID(st.getAD_Org_ID());
                    p.setC_BankAccount_ID(account);
                    p.setIsReceipt(true);
                    p.setC_DocType_ID(true);
                    p.setDateTrx(line.getStatementLineDate());
                    p.setDateAcct(line.getStatementLineDate());
                    p.setC_BPartner_ID(m.bpartnerId());
                    p.setTenderType(MPayment.TENDERTYPE_DirectDeposit);
                    p.setC_Currency_ID(bank.getC_Currency_ID());
                    p.setPayAmt(sl.amount());
                    if (m.invoiceId() > 0) {
                        p.setC_Invoice_ID(m.invoiceId());
                        BigDecimal open = DB.getSQLValueBDEx(trx, "SELECT invoiceOpen(?,0)", m.invoiceId());
                        p.setOverUnderAmt(open.subtract(sl.amount()));
                    }
                    if (sl.reference() != null) {
                        p.set_ValueNoCheck("ZC_TrxReference",
                                sl.reference().length() > 40 ? sl.reference().substring(0, 40) : sl.reference());
                    }
                    p.setDescription("From statement " + st.getName() + " line " + sl.lineNo()
                            + (sl.payer() == null || sl.payer().isBlank() ? "" : ", paid by " + sl.payer()));
                    p.saveEx();
                    if (p.processIt(DocAction.ACTION_Complete)) {
                        p.saveEx();
                        line.setC_Payment_ID(p.getC_Payment_ID());
                        line.setC_BPartner_ID(m.bpartnerId());
                        line.setTrxAmt(line.getStmtAmt());
                        note = note + "; receipt " + p.getDocumentNo();
                    } else {
                        String why = p.getProcessMsg();
                        p.deleteEx(true);
                        kind = Kind.REVIEW;
                        note = "receipt could not be completed: " + why;
                    }
                }
                default -> {
                    // left for the cashier
                }
            }
            line.set_ValueNoCheck("ZC_MatchType", kind.code());
            line.set_ValueNoCheck("ZC_MatchNote", note.length() > 255 ? note.substring(0, 252) + "..." : note);
            line.saveEx();
            count.merge(kind, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(lines.size() + " lines: ");
        String[][] labels = {{"REFERENCE", "by reference"}, {"AMOUNT_DATE", "by amount and date"},
                {"NEW_RECEIPT", "new receipts"}, {"CHARGE", "by charge rule"}, {"REVIEW", "to review"}, {"UNMATCHED", "not matched"}};
        boolean first = true;
        for (String[] l : labels) {
            Integer n = count.get(Kind.valueOf(l[0]));
            if (n != null) {
                sb.append(first ? "" : ", ").append(n).append(' ').append(l[1]);
                first = false;
            }
        }
        return sb.toString();
    }

    private static void bind(PreparedStatement ps, int client, Set<String> refs) throws java.sql.SQLException {
        ps.setInt(1, client);
        int i = 2;
        for (String r : refs) {
            ps.setString(i++, r);
        }
    }

    private static String norm(String s) {
        return s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static LocalDate date(Timestamp t) {
        return t.toLocalDateTime().toLocalDate();
    }
}
