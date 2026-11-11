package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.OpeningBalance.ItemType;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Load Attached Files (button on Opening Balances). Reads the CSV files attached to the record, in the
 * ZenithCore templates (docs/phase1/opening-balance-templates/):
 * <ul>
 * <li>trial balance: account_code, account_name, debit, credit, audited_debit, audited_credit,
 * new_account, adjustment, adjustment_reason (only the first four are required);</li>
 * <li>sub-ledger items: type (AR, AP, ST, FA, BK), partner_code, reference, date (yyyy-mm-dd), amount,
 * control_account, description. For BK (bank or M-Pesa balance) partner_code is the bank account
 * number.</li>
 * </ul>
 * A file is recognised by its header row. Loading replaces the earlier lines of that kind. Legacy codes are
 * mapped to the new chart from new_account, else an account with the same code, else the mapping used in
 * an earlier opening balance. Customer and supplier items take the partner's own receivable or payable
 * account when no control account is given.
 */
@Process
public class OpeningBalanceLoad extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters: runs on the current opening balance
    }

    @Override
    protected String doIt() throws Exception {
        PO h = MTable.get(getCtx(), "ZC_OpeningBalance").getPO(getRecord_ID(), get_TrxName());
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Save the opening balance first");
        }
        OpeningBalanceData.requireOpen(h);
        MAttachment att = MAttachment.get(getCtx(), h.get_Table_ID(), h.get_ID(), get_TrxName());
        if (att == null || att.getEntryCount() == 0) {
            throw new AdempiereUserError("Attach the trial balance and sub-ledger CSV files to this record first");
        }
        List<String> done = new ArrayList<>();
        for (MAttachmentEntry e : att.getEntries()) {
            if (!e.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                continue;
            }
            List<List<String>> rows = csv(new String(e.getData(), StandardCharsets.UTF_8));
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Integer> col = header(rows.get(0));
            if (col.containsKey("account_code")) {
                done.add(loadTrialBalance(h, rows, col) + " trial balance lines from " + e.getName());
            } else if (col.containsKey("type") && col.containsKey("reference")) {
                done.add(loadItems(h, rows, col) + " sub-ledger items from " + e.getName());
            } else {
                done.add(e.getName() + " skipped: header row not recognised");
            }
        }
        if (done.isEmpty()) {
            throw new AdempiereUserError("No CSV files are attached");
        }
        h.set_ValueNoCheck("ZC_OBStatus", "DR");
        h.saveEx();
        return String.join("; ", done) + ". Press Check and Reconcile.";
    }

    private int loadTrialBalance(PO h, List<List<String>> rows, Map<String, Integer> col) {
        String trx = get_TrxName();
        DB.executeUpdateEx("DELETE FROM ZC_OpeningBalanceLine WHERE ZC_OpeningBalance_ID=?", new Object[] {h.get_ID()}, trx);
        int schema = new OpeningBalanceData(h, trx).schema;
        int n = 0;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String code = get(row, col, "account_code");
            if (code.isEmpty()) {
                continue;
            }
            PO l = MTable.get(getCtx(), "ZC_OpeningBalanceLine").getPO(0, trx);
            l.setAD_Org_ID(h.getAD_Org_ID());
            l.set_ValueNoCheck("ZC_OpeningBalance_ID", h.get_ID());
            l.set_ValueNoCheck("Line", ++n * 10);
            l.set_ValueNoCheck("ZC_LegacyCode", code);
            l.set_ValueNoCheck("ZC_LegacyName", get(row, col, "account_name"));
            l.set_ValueNoCheck("ZC_LegacyAmt", amt(get(row, col, "debit"), r).subtract(amt(get(row, col, "credit"), r)));
            String ad = get(row, col, "audited_debit"), ac = get(row, col, "audited_credit");
            if (!ad.isEmpty() || !ac.isEmpty()) {
                l.set_ValueNoCheck("ZC_AuditedAmt", amt(ad, r).subtract(amt(ac, r)));
            }
            String adj = get(row, col, "adjustment");
            if (!adj.isEmpty()) {
                l.set_ValueNoCheck("ZC_AdjustmentAmt", amt(adj, r));
                l.set_ValueNoCheck("ZC_AdjustmentNote", get(row, col, "adjustment_reason"));
            }
            int acct = account(get(row, col, "new_account"), schema, trx);
            if (acct <= 0) {
                acct = account(code, schema, trx);
            }
            if (acct <= 0) {
                acct = DB.getSQLValueEx(trx, "SELECT MAX(Account_ID) FROM ZC_OpeningBalanceLine WHERE AD_Client_ID=? "
                        + "AND UPPER(ZC_LegacyCode)=UPPER(?) AND Account_ID IS NOT NULL", h.getAD_Client_ID(), code);
            }
            if (acct > 0) {
                l.set_ValueNoCheck("Account_ID", acct);
            }
            l.saveEx();
        }
        return n;
    }

    private int loadItems(PO h, List<List<String>> rows, Map<String, Integer> col) {
        String trx = get_TrxName();
        DB.executeUpdateEx("DELETE FROM ZC_OpeningItem WHERE ZC_OpeningBalance_ID=?", new Object[] {h.get_ID()}, trx);
        OpeningBalanceData data = new OpeningBalanceData(h, trx);
        int n = 0;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String type = get(row, col, "type").toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            PO i = MTable.get(getCtx(), "ZC_OpeningItem").getPO(0, trx);
            i.setAD_Org_ID(h.getAD_Org_ID());
            i.set_ValueNoCheck("ZC_OpeningBalance_ID", h.get_ID());
            i.set_ValueNoCheck("Line", ++n * 10);
            ItemType t = ItemType.of(type);
            if (t == null) {
                throw new AdempiereUserError("Row " + (r + 1) + ": type " + type + " is not AR, AP, ST, FA or BK");
            }
            i.set_ValueNoCheck("ZC_OpeningItemType", t.code);
            String partner = get(row, col, "partner_code");
            i.set_ValueNoCheck("ZC_PartnerCode", partner);
            int bp = 0;
            if (t == ItemType.BANK) {
                bp = partner.isEmpty() ? 0 : DB.getSQLValueEx(trx, "SELECT MAX(C_BankAccount_ID) FROM C_BankAccount WHERE "
                        + "AD_Client_ID=? AND (UPPER(AccountNo)=UPPER(?) OR UPPER(Value)=UPPER(?))", h.getAD_Client_ID(), partner, partner);
                if (bp > 0) {
                    i.set_ValueNoCheck("C_BankAccount_ID", bp);
                }
            } else {
                bp = partner.isEmpty() ? 0 : DB.getSQLValueEx(trx, "SELECT MAX(C_BPartner_ID) FROM C_BPartner WHERE AD_Client_ID=? "
                        + "AND UPPER(Value)=UPPER(?)", h.getAD_Client_ID(), partner);
                if (bp > 0) {
                    i.set_ValueNoCheck("C_BPartner_ID", bp);
                }
            }
            i.set_ValueNoCheck("ZC_LegacyRef", get(row, col, "reference"));
            String date = get(row, col, "date");
            if (!date.isEmpty()) {
                try {
                    i.set_ValueNoCheck("DateDoc", Timestamp.valueOf(LocalDate.parse(date).atStartOfDay()));
                } catch (RuntimeException ex) {
                    throw new AdempiereUserError("Row " + (r + 1) + ": date " + date + " is not yyyy-mm-dd");
                }
            }
            i.set_ValueNoCheck("Description", get(row, col, "description"));
            i.set_ValueNoCheck("ZC_ItemAmt", amt(get(row, col, "amount"), r));
            int acct = account(get(row, col, "control_account"), data.schema, trx);
            if (acct <= 0 && bp > 0 && (t == ItemType.RECEIVABLE || t == ItemType.PAYABLE || t == ItemType.BANK)) {
                acct = data.partnerAccount(bp, t, trx);
            }
            if (acct > 0) {
                i.set_ValueNoCheck("Account_ID", acct);
            }
            i.saveEx();
        }
        return n;
    }

    private static int account(String value, int schema, String trx) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return DB.getSQLValueEx(trx, "SELECT MAX(ev.C_ElementValue_ID) FROM C_ElementValue ev JOIN C_AcctSchema_Element ase "
                + "ON ase.C_Element_ID=ev.C_Element_ID AND ase.ElementType='AC' WHERE ase.C_AcctSchema_ID=? AND ev.Value=?",
                schema, value.trim());
    }

    private static BigDecimal amt(String s, int row) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        String v = s.replace(",", "").replace(" ", "").trim();
        boolean neg = v.startsWith("(") && v.endsWith(")");
        try {
            BigDecimal b = new BigDecimal(neg ? v.substring(1, v.length() - 1) : v);
            return neg ? b.negate() : b;
        } catch (NumberFormatException e) {
            throw new AdempiereUserError("Row " + (row + 1) + ": " + s + " is not an amount");
        }
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i).trim();
    }

    private static Map<String, Integer> header(List<String> row) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < row.size(); i++) {
            m.put(row.get(i).trim().toLowerCase(Locale.ROOT).replace('﻿', ' ').trim().replace(' ', '_'), i);
        }
        return m;
    }

    /** Minimal CSV: commas, double-quoted fields with "" escapes, CR LF or LF line ends. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    q = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(f.toString());
                f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }
}
