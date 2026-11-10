package ke.co.pollux.zenithcore.controls.idempiere;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.compiere.model.MAccount;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MActivity;
import org.compiere.model.MCharge;
import org.compiere.model.MClientInfo;
import org.compiere.model.MConversionRate;
import org.compiere.model.MElementValue;
import org.compiere.model.MPeriod;
import org.compiere.model.MProduct;
import org.compiere.model.MYear;
import org.compiere.model.ProductCost;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.BudgetCheck;

/**
 * Reads budgets, spending and commitments from iDempiere for the budget check and the budget status
 * report. Budgets are client-wide, by financial year, cost centre (Activity) and account.
 * <ul>
 *   <li>Budget: lines of approved budgets for the year.</li>
 *   <li>Spent: actual postings in the primary accounting schema for the cost centre and account in the
 *       year (debits less credits).</li>
 *   <li>Committed: completed requisition lines not yet on an LPO, and completed LPO lines not yet
 *       invoiced, dated in the year.</li>
 * </ul>
 * Items kept in stock are left out: their purchases go to inventory, not to a budget line, and are
 * controlled by stores. Services, non-stocked items and charges are checked. The account of a line is
 * the account it will be posted to: the item's expense account or the charge's account.
 */
public final class BudgetLedger {

    /** One line of a document, mapped to cost centre and account (ids); account 0 = not budget-controlled. */
    public record Line(String label, int activityId, int accountId, BigDecimal amount) {
    }

    private final Properties ctx;
    private final String trx;
    private final MAcctSchema schema;
    private final Map<String, Integer> accountCache = new HashMap<>();

    public BudgetLedger(Properties ctx, int clientId, String trx) {
        this.ctx = ctx;
        this.trx = trx;
        this.schema = MClientInfo.get(ctx, clientId).getMAcctSchema1();
    }

    public MAcctSchema schema() {
        return schema;
    }

    /** Financial year of a date, or 0 if the date has no period. */
    public int yearOf(Timestamp date, int orgId) {
        MPeriod p = MPeriod.get(ctx, date, orgId, trx);
        return p == null ? 0 : p.getC_Year_ID();
    }

    /** Account a product or charge is posted to, or 0 for stocked items and lines with neither. */
    public int accountFor(int productId, int chargeId) {
        String key = productId + ":" + chargeId;
        return accountCache.computeIfAbsent(key, k -> {
            if (chargeId > 0) {
                MAccount a = MCharge.getAccount(chargeId, schema);
                return a == null ? 0 : a.getAccount_ID();
            }
            if (productId > 0) {
                MProduct p = MProduct.get(productId);
                if (p.isStocked()) {
                    return 0;
                }
                MAccount a = new ProductCost(ctx, productId, 0, trx).getAccount(ProductCost.ACCTTYPE_P_Expense, schema);
                return a == null ? 0 : a.getAccount_ID();
            }
            return 0;
        });
    }

    /** Amount in the accounting currency, or null if there is no exchange rate. */
    public BigDecimal toAcctCurrency(BigDecimal amount, int currencyId, Timestamp date, int conversionTypeId, int clientId,
            int orgId) {
        if (currencyId == 0 || currencyId == schema.getC_Currency_ID()) {
            return amount;
        }
        return MConversionRate.convert(ctx, amount, currencyId, schema.getC_Currency_ID(), date, conversionTypeId,
                clientId, orgId);
    }

    public static String costCentreLabel(int activityId) {
        if (activityId <= 0) {
            return null;
        }
        MActivity a = new MActivity(org.compiere.util.Env.getCtx(), activityId, null);
        return a.getValue() + " " + a.getName();
    }

    public String accountLabel(int accountId) {
        MElementValue ev = new MElementValue(ctx, accountId, trx);
        return ev.getValue() + " " + ev.getName();
    }

    /**
     * Positions for the given cost-centre/account pairs (keys "activity:account") in a year, leaving out
     * the commitments of one document (the one being checked).
     */
    public Map<String, BudgetCheck.Position> positions(int clientId, int yearId, Set<String> keys, String excludeTable,
            int excludeId) {
        Map<String, BigDecimal> budget = new HashMap<>(), spent = new HashMap<>(), committed = new HashMap<>();
        Timestamp[] range = yearRange(yearId);
        Set<Integer> activities = new TreeSet<>();
        for (String k : keys) {
            activities.add(Integer.parseInt(k.split(":")[0]));
        }
        String inActivities = activities.isEmpty() ? "(-1)" : activities.toString().replace('[', '(').replace(']', ')');

        query("SELECT bl.C_Activity_ID, bl.Account_ID, SUM(bl.ZC_Amount) FROM ZC_BudgetLine bl "
                + "JOIN ZC_Budget b ON b.ZC_Budget_ID=bl.ZC_Budget_ID WHERE b.AD_Client_ID=? AND b.C_Year_ID=? "
                + "AND b.Processed='Y' AND b.IsActive='Y' AND bl.IsActive='Y' AND bl.C_Activity_ID IN " + inActivities
                + " GROUP BY bl.C_Activity_ID, bl.Account_ID", budget, clientId, yearId);
        query("SELECT f.C_Activity_ID, f.Account_ID, SUM(f.AmtAcctDr-f.AmtAcctCr) FROM Fact_Acct f WHERE f.AD_Client_ID=? "
                + "AND f.C_AcctSchema_ID=? AND f.PostingType='A' AND f.DateAcct BETWEEN ? AND ? AND f.C_Activity_ID IN "
                + inActivities + " GROUP BY f.C_Activity_ID, f.Account_ID", spent, clientId, schema.getC_AcctSchema_ID(),
                range[0], range[1]);

        // commitments: requisition lines not yet ordered
        String reqSql = "SELECT r.C_Activity_ID, l.M_Product_ID, l.C_Charge_ID, l.LineNetAmt, r.M_Requisition_ID "
                + "FROM M_RequisitionLine l JOIN M_Requisition r ON r.M_Requisition_ID=l.M_Requisition_ID "
                + "WHERE r.AD_Client_ID=? AND r.DocStatus IN ('CO') AND l.C_OrderLine_ID IS NULL AND r.DateDoc BETWEEN ? AND ? "
                + "AND r.C_Activity_ID IN " + inActivities;
        commitments(reqSql, "M_Requisition", excludeTable, excludeId, committed, clientId, range[0], range[1]);
        // commitments: LPO lines not yet invoiced (in accounting currency at the order date)
        String poSql = "SELECT COALESCE(l.C_Activity_ID, o.C_Activity_ID), l.M_Product_ID, l.C_Charge_ID, "
                + "currencyBase((l.QtyOrdered-l.QtyInvoiced)*l.PriceActual, o.C_Currency_ID, o.DateAcct, o.AD_Client_ID, o.AD_Org_ID), "
                + "o.C_Order_ID FROM C_OrderLine l JOIN C_Order o ON o.C_Order_ID=l.C_Order_ID "
                + "WHERE o.AD_Client_ID=? AND o.IsSOTrx='N' AND o.DocStatus='CO' AND l.QtyOrdered<>l.QtyInvoiced "
                + "AND o.DateOrdered BETWEEN ? AND ? AND COALESCE(l.C_Activity_ID, o.C_Activity_ID) IN " + inActivities;
        commitments(poSql, "C_Order", excludeTable, excludeId, committed, clientId, range[0], range[1]);

        Map<String, BudgetCheck.Position> out = new LinkedHashMap<>();
        Set<String> all = new TreeSet<>(keys);
        for (String k : all) {
            out.put(k, new BudgetCheck.Position(nz(budget.get(k)), nz(spent.get(k)), nz(committed.get(k))));
        }
        return out;
    }

    /** All budget lines of a year (keys "activity:account"), optionally one cost centre, for the report. */
    public Set<String> budgetKeys(int clientId, int yearId, int activityId) {
        Set<String> keys = new TreeSet<>();
        String sql = "SELECT DISTINCT bl.C_Activity_ID, bl.Account_ID FROM ZC_BudgetLine bl JOIN ZC_Budget b "
                + "ON b.ZC_Budget_ID=bl.ZC_Budget_ID WHERE b.AD_Client_ID=? AND b.C_Year_ID=? AND b.Processed='Y'"
                + (activityId > 0 ? " AND bl.C_Activity_ID=" + activityId : "");
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, clientId);
            ps.setInt(2, yearId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getInt(1) + ":" + rs.getInt(2));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return keys;
    }

    private void commitments(String sql, String table, String excludeTable, int excludeId, Map<String, BigDecimal> into,
            int clientId, Timestamp from, Timestamp to) {
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, clientId);
            ps.setTimestamp(2, from);
            ps.setTimestamp(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (table.equals(excludeTable) && rs.getInt(5) == excludeId) {
                        continue;
                    }
                    int account = accountFor(rs.getInt(2), rs.getInt(3));
                    if (account > 0 && rs.getBigDecimal(4) != null) {
                        into.merge(rs.getInt(1) + ":" + account, rs.getBigDecimal(4), BigDecimal::add);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void query(String sql, Map<String, BigDecimal> into, Object... params) {
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.merge(rs.getInt(1) + ":" + rs.getInt(2), rs.getBigDecimal(3), BigDecimal::add);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Timestamp[] yearRange(int yearId) {
        List<Object> r = DB.getSQLValueObjectsEx(trx, "SELECT MIN(StartDate), MAX(EndDate) FROM C_Period WHERE C_Year_ID=?",
                yearId);
        return new Timestamp[] {(Timestamp) r.get(0), (Timestamp) r.get(1)};
    }

    public String yearName(int yearId) {
        return new MYear(ctx, yearId, trx).getFiscalYear();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Groups document lines into rule demands and positions, and runs the budget check. */
    public ke.co.pollux.zenithcore.controls.ControlResult check(String documentLabel, int clientId, int yearId,
            List<Line> lines, String excludeTable, int excludeId, BigDecimal tolerancePct) {
        List<BudgetCheck.Demand> demands = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (Line l : lines) {
            if (l.accountId() <= 0) {
                continue; // stocked item or no account: not budget-controlled
            }
            String acct = accountLabel(l.accountId());
            if (l.activityId() <= 0) {
                demands.add(new BudgetCheck.Demand(l.label(), null, acct, l.amount()));
                continue;
            }
            keys.add(l.activityId() + ":" + l.accountId());
            demands.add(new BudgetCheck.Demand(l.label(), costCentreLabel(l.activityId()), acct, l.amount()));
        }
        if (demands.isEmpty()) {
            return ke.co.pollux.zenithcore.controls.ControlResult.pass(BudgetCheck.CONTROL);
        }
        Map<BudgetCheck.Key, BudgetCheck.Position> positions = new HashMap<>();
        for (Map.Entry<String, BudgetCheck.Position> e : positions(clientId, yearId, keys, excludeTable, excludeId).entrySet()) {
            String[] k = e.getKey().split(":");
            positions.put(new BudgetCheck.Key(costCentreLabel(Integer.parseInt(k[0])), accountLabel(Integer.parseInt(k[1]))),
                    e.getValue());
        }
        return BudgetCheck.check(documentLabel + " (" + yearName(yearId) + ")", demands, positions, tolerancePct);
    }
}
