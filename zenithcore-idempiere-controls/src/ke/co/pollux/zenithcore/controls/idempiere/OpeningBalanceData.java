package ke.co.pollux.zenithcore.controls.idempiere;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.compiere.model.MClientInfo;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.OpeningBalance;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Account;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Item;
import ke.co.pollux.zenithcore.controls.OpeningBalance.ItemType;
import ke.co.pollux.zenithcore.controls.OpeningBalance.TbLine;

/** Reads an opening-balance workbench into the rule's records and runs the checks. */
public final class OpeningBalanceData {

    public final PO header;
    public final int schema;
    public final List<TbLine> lines = new ArrayList<>();
    public final Map<Integer, Integer> lineIds = new HashMap<>();
    public final List<Item> items = new ArrayList<>();
    public final Map<Integer, Integer> itemIds = new HashMap<>();
    public final Map<Integer, Integer> itemPartners = new HashMap<>();
    public final Map<Integer, Integer> itemBanks = new HashMap<>();
    public final Map<Integer, Account> accounts = new HashMap<>();
    /** Problems found against iDempiere data, on top of the rule's checks. */
    public final List<String> extra = new ArrayList<>();

    public OpeningBalanceData(PO header, String trx) {
        this.header = header;
        int client = header.getAD_Client_ID();
        this.schema = MClientInfo.get(header.getCtx(), client).getC_AcctSchema1_ID();
        int id = header.get_ID();
        query(trx, "SELECT ev.C_ElementValue_ID, ev.Value || ' ' || ev.Name, ev.IsDocControlled FROM C_ElementValue ev "
                + "JOIN C_AcctSchema_Element ase ON ase.C_Element_ID=ev.C_Element_ID AND ase.ElementType='AC' "
                + "WHERE ase.C_AcctSchema_ID=? AND ev.IsSummary='N' AND ev.IsActive='Y'", rs -> accounts.put(rs.getInt(1),
                new Account(rs.getInt(1), rs.getString(2), "Y".equals(rs.getString(3)))), schema);
        query(trx, "SELECT l.Line, l.ZC_LegacyCode, l.ZC_LegacyName, COALESCE(l.Account_ID,0), ev.Value || ' ' || ev.Name, "
                + "l.ZC_LegacyAmt, l.ZC_AdjustmentAmt, l.ZC_AdjustmentNote, l.ZC_AuditedAmt, l.ZC_OpeningBalanceLine_ID "
                + "FROM ZC_OpeningBalanceLine l LEFT JOIN C_ElementValue ev ON ev.C_ElementValue_ID=l.Account_ID "
                + "WHERE l.ZC_OpeningBalance_ID=? AND l.IsActive='Y' ORDER BY l.Line", rs -> {
                    lines.add(new TbLine(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), rs.getBigDecimal(9)));
                    lineIds.put(rs.getInt(1), rs.getInt(10));
                }, id);
        query(trx, "SELECT i.Line, i.ZC_OpeningItemType, CASE WHEN i.ZC_OpeningItemType='BK' THEN ba.Name || ' ' || ba.AccountNo "
                + "ELSE bp.Name END, i.ZC_LegacyRef, i.ZC_ItemAmt, COALESCE(i.Account_ID,0), "
                + "ev.Value || ' ' || ev.Name, i.ZC_OpeningItem_ID, COALESCE(i.C_BPartner_ID,0), ev.Value, COALESCE(i.C_BankAccount_ID,0) "
                + "FROM ZC_OpeningItem i LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID=i.C_BPartner_ID "
                + "LEFT JOIN C_BankAccount ba ON ba.C_BankAccount_ID=i.C_BankAccount_ID "
                + "LEFT JOIN C_ElementValue ev ON ev.C_ElementValue_ID=i.Account_ID "
                + "WHERE i.ZC_OpeningBalance_ID=? AND i.IsActive='Y' ORDER BY i.Line", rs -> {
                    ItemType type = ItemType.of(rs.getString(2));
                    items.add(new Item(rs.getInt(1), type, rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6),
                            rs.getString(7)));
                    itemIds.put(rs.getInt(1), rs.getInt(8));
                    itemPartners.put(rs.getInt(1), rs.getInt(9));
                    itemBanks.put(rs.getInt(1), rs.getInt(11));
                    // the opening document posts to the partner's or bank account's own account
                    int party = type == ItemType.BANK ? rs.getInt(11) : rs.getInt(9);
                    if ((type == ItemType.RECEIVABLE || type == ItemType.PAYABLE || type == ItemType.BANK) && party > 0
                            && rs.getInt(6) > 0) {
                        int own = partnerAccount(party, type, trx);
                        if (own > 0 && own != rs.getInt(6)) {
                            extra.add("item " + rs.getInt(1) + " (" + rs.getString(4) + "): " + rs.getString(3) + " posts to "
                                    + DB.getSQLValueStringEx(trx, "SELECT Value FROM C_ElementValue WHERE C_ElementValue_ID=?", own)
                                    + ", not " + rs.getString(10));
                        }
                    }
                }, id);
        int clearing = clearingAccount(trx);
        if (clearing <= 0) {
            extra.add("the clearing charge has no expense account in the primary accounting schema");
        }
    }

    public OpeningBalance.Result check() {
        OpeningBalance.Result r = OpeningBalance.check(lines, items, accounts);
        if (extra.isEmpty()) {
            return r;
        }
        List<String> all = new ArrayList<>(r.problems());
        all.addAll(extra);
        return new OpeningBalance.Result(all, r.debits(), r.credits(), r.controls());
    }

    /** Account behind the clearing charge (its expense account) in the primary schema. */
    public int clearingAccount(String trx) {
        return DB.getSQLValueEx(trx, "SELECT vc.Account_ID FROM C_Charge_Acct ca JOIN C_ValidCombination vc "
                + "ON vc.C_ValidCombination_ID=ca.Ch_Expense_Acct WHERE ca.C_Charge_ID=? AND ca.C_AcctSchema_ID=?",
                header.get_ValueAsInt("C_Charge_ID"), schema);
    }

    /**
     * The partner's receivable (customer) or liability (supplier) account, or the bank account's asset
     * account (bank items, id = bank account), in the primary schema.
     */
    public int partnerAccount(int bpartner, ItemType type, String trx) {
        if (type == ItemType.BANK) {
            return DB.getSQLValueEx(trx, "SELECT vc.Account_ID FROM C_BankAccount_Acct a JOIN C_ValidCombination vc "
                    + "ON vc.C_ValidCombination_ID=a.B_Asset_Acct WHERE a.C_BankAccount_ID=? AND a.C_AcctSchema_ID=?", bpartner, schema);
        }
        String sql = type == ItemType.RECEIVABLE
                ? "SELECT vc.Account_ID FROM C_BP_Customer_Acct a JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID=a.C_Receivable_Acct "
                        + "WHERE a.C_BPartner_ID=? AND a.C_AcctSchema_ID=?"
                : "SELECT vc.Account_ID FROM C_BP_Vendor_Acct a JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID=a.V_Liability_Acct "
                        + "WHERE a.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
        return DB.getSQLValueEx(trx, sql, bpartner, schema);
    }

    /** Writes the check results: opening amounts and a note on every line and item; totals on the header. */
    public void writeResults(OpeningBalance.Result r, String trx) {
        for (TbLine l : lines) {
            PO po = MTable.get(header.getCtx(), "ZC_OpeningBalanceLine").getPO(lineIds.get(l.lineNo()), trx);
            po.set_ValueNoCheck("ZC_OpeningAmt", l.opening());
            po.set_ValueNoCheck("ZC_CheckNote", note(r.problems(), "line " + l.lineNo() + " "));
            po.saveEx();
        }
        for (Item i : items) {
            PO po = MTable.get(header.getCtx(), "ZC_OpeningItem").getPO(itemIds.get(i.lineNo()), trx);
            po.set_ValueNoCheck("ZC_CheckNote", note(r.problems(), "item " + i.lineNo() + " "));
            po.saveEx();
        }
        header.set_ValueNoCheck("ZC_TotalDebit", r.debits());
        header.set_ValueNoCheck("ZC_TotalCredit", r.credits());
    }

    private static String note(List<String> problems, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String p : problems) {
            if (p.startsWith(prefix) || p.startsWith(prefix.trim() + ":")) {
                String s = p.substring(p.indexOf(':') + 1).trim();
                sb.append(sb.length() == 0 ? "" : "; ").append(s);
            }
        }
        String s = sb.length() == 0 ? "OK" : sb.toString();
        return s.length() > 255 ? s.substring(0, 252) + "..." : s;
    }

    /** Refuses changes once signed off. */
    public static void requireOpen(PO header) {
        String st = header.get_ValueAsString("ZC_OBStatus");
        if ("SO".equals(st) || "PO".equals(st)) {
            throw new AdempiereUserError("These opening balances are signed off and can no longer be changed");
        }
    }

    interface Row {
        void read(ResultSet rs) throws Exception;
    }

    static void query(String trx, String sql, Row row, Object... params) {
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            DB.setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    row.read(rs);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
