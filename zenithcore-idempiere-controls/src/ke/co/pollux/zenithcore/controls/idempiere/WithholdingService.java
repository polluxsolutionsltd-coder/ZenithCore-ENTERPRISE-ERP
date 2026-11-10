package ke.co.pollux.zenithcore.controls.idempiere;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.compiere.model.MBPartner;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTax;
import org.compiere.util.DB;
import org.compiere.util.Env;

import ke.co.pollux.zenithcore.controls.Withholding;

/**
 * Adds withholding deduction lines to a supplier invoice when it is prepared (client setting
 * ZC_WITHHOLDING = Y). Earlier deduction lines are removed and recalculated, so editing an invoice and
 * preparing it again gives the right figures. Each deduction is a negative line on the withholding
 * type's payable charge, without VAT, so the supplier's payable is the net amount and the tax withheld
 * sits on the payable account until it is remitted to KRA.
 */
public final class WithholdingService {

    public static final String LINE_FLAG = "ZC_IsWHTLine";

    private WithholdingService() {
    }

    /** True for lines ZenithCore added for tax withheld. */
    public static boolean isDeductionLine(MInvoiceLine l) {
        return l.get_ValueAsBoolean(LINE_FLAG);
    }

    /** @return problems that stop the invoice, empty if none */
    public static List<String> apply(MInvoice inv) {
        int client = inv.getAD_Client_ID();
        String trx = inv.get_TrxName();
        for (MInvoiceLine l : inv.getLines(true)) {
            if (isDeductionLine(l)) {
                l.deleteEx(true);
            }
        }
        List<Withholding.Line> lines = new ArrayList<>();
        for (MInvoiceLine l : inv.getLines(true)) {
            BigDecimal vat = l.getC_Tax_ID() > 0 ? MTax.get(l.getC_Tax_ID()).getRate() : BigDecimal.ZERO;
            lines.add(new Withholding.Line(l.getLine(), l.getDescription(), l.getLineNetAmt(), vat, typeOf(l, trx)));
        }
        MBPartner bp = MBPartner.get(inv.getCtx(), inv.getC_BPartner_ID());
        Withholding.Supplier s = new Withholding.Supplier(bp.getName(), bp.get_ValueAsBoolean("ZC_IsNonResident"),
                bp.get_ValueAsBoolean("ZC_IsWHTExempt"), bp.get_ValueAsBoolean("ZC_IsVATRegistered"),
                bp.get_ValueAsBoolean("ZC_IsWHVATExempt"));
        LocalDate date = inv.getDateInvoiced().toLocalDateTime().toLocalDate();
        Map<String, Integer> charges = new HashMap<>();
        List<Withholding.Rate> rates = rates(client, trx, charges);
        Withholding.Result r = Withholding.calculate(lines, s, date, rates, monthToDate(inv),
                MSysConfig.getBooleanValue("ZC_WHVAT_AGENT", false, client),
                MSysConfig.getValue("ZC_WHVAT_TYPE", "WHVAT", client));
        if (!r.problems().isEmpty()) {
            return r.problems();
        }
        if (r.deductions().isEmpty()) {
            return List.of();
        }
        int exemptTax = DB.getSQLValueEx(trx, "SELECT MIN(C_Tax_ID) FROM C_Tax WHERE AD_Client_ID=? AND IsTaxExempt='Y' "
                + "AND IsActive='Y' AND IsSummary='N'", client);
        if (exemptTax <= 0) {
            return List.of("set up a tax-exempt tax rate for withholding deduction lines");
        }
        int lineNo = DB.getSQLValueEx(trx, "SELECT COALESCE(MAX(Line),0) FROM C_InvoiceLine WHERE C_Invoice_ID=?",
                inv.getC_Invoice_ID());
        for (Withholding.Deduction d : r.deductions()) {
            MInvoiceLine l = new MInvoiceLine(inv);
            l.setLine(lineNo += 10);
            l.setC_Charge_ID(charges.get(d.type()));
            l.setQty(Env.ONE);
            l.setPrice(d.amount().negate());
            l.setC_Tax_ID(exemptTax);
            l.setDescription(d.name() + " " + d.ratePct().stripTrailingZeros().toPlainString() + "% of "
                    + String.format(java.util.Locale.ROOT, "%,.2f", d.base()));
            l.set_ValueNoCheck(LINE_FLAG, Boolean.TRUE);
            l.set_ValueNoCheck("ZC_WHTBase", d.base());
            l.set_ValueNoCheck("ZC_WHTType_ID", DB.getSQLValueEx(trx,
                    "SELECT ZC_WHTType_ID FROM ZC_WHTType WHERE AD_Client_ID=? AND Value=?", client, d.type()));
            l.saveEx();
        }
        return List.of();
    }

    /** Withholding type code of a line: its own, else its product's, else its charge's. */
    static String typeOf(MInvoiceLine l, String trx) {
        return DB.getSQLValueStringEx(trx, "SELECT t.Value FROM ZC_WHTType t WHERE t.ZC_WHTType_ID=COALESCE(NULLIF(?,0), "
                + "(SELECT ZC_WHTType_ID FROM M_Product WHERE M_Product_ID=?), (SELECT ZC_WHTType_ID FROM C_Charge WHERE C_Charge_ID=?))",
                l.get_ValueAsInt("ZC_WHTType_ID"), l.getM_Product_ID(), l.getC_Charge_ID());
    }

    static List<Withholding.Rate> rates(int client, String trx, Map<String, Integer> chargeByType) {
        List<Withholding.Rate> out = new ArrayList<>();
        String sql = "SELECT t.Value, t.Name, t.ZC_TaxKind, r.ZC_IsNonResident, r.ZC_Rate, r.ZC_MonthlyThreshold, r.ValidFrom, "
                + "r.ValidTo, t.C_Charge_ID FROM ZC_WHTRate r JOIN ZC_WHTType t ON t.ZC_WHTType_ID=r.ZC_WHTType_ID "
                + "WHERE t.AD_Client_ID=? AND t.IsActive='Y' AND r.IsActive='Y' ORDER BY r.ValidFrom DESC";
        try (PreparedStatement ps = DB.prepareStatement(sql, trx)) {
            ps.setInt(1, client);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Withholding.Rate(rs.getString(1), rs.getString(2), Withholding.Kind.of(rs.getString(3)),
                            "Y".equals(rs.getString(4)), rs.getBigDecimal(5), rs.getBigDecimal(6), date(rs.getTimestamp(7)),
                            date(rs.getTimestamp(8))));
                    chargeByType.put(rs.getString(1), rs.getInt(9));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** The supplier's earlier amounts this month by withholding type, from completed supplier invoices. */
    static Map<String, BigDecimal> monthToDate(MInvoice inv) {
        Map<String, BigDecimal> m = new HashMap<>();
        String sql = "SELECT t.Value, SUM(il.LineNetAmt) FROM C_InvoiceLine il JOIN C_Invoice i ON i.C_Invoice_ID=il.C_Invoice_ID "
                + "LEFT JOIN M_Product p ON p.M_Product_ID=il.M_Product_ID LEFT JOIN C_Charge c ON c.C_Charge_ID=il.C_Charge_ID "
                + "JOIN ZC_WHTType t ON t.ZC_WHTType_ID=COALESCE(il.ZC_WHTType_ID, p.ZC_WHTType_ID, c.ZC_WHTType_ID) "
                + "WHERE i.AD_Client_ID=? AND i.C_BPartner_ID=? AND i.C_Invoice_ID<>? AND i.IsSOTrx='N' AND i.DocStatus IN ('CO','CL') "
                + "AND il.ZC_IsWHTLine='N' AND i.DateInvoiced>=date_trunc('month', ?::timestamp) "
                + "AND i.DateInvoiced<date_trunc('month', ?::timestamp)+interval '1 month' GROUP BY t.Value";
        try (PreparedStatement ps = DB.prepareStatement(sql, inv.get_TrxName())) {
            ps.setInt(1, inv.getAD_Client_ID());
            ps.setInt(2, inv.getC_BPartner_ID());
            ps.setInt(3, inv.getC_Invoice_ID());
            ps.setTimestamp(4, inv.getDateInvoiced());
            ps.setTimestamp(5, inv.getDateInvoiced());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getBigDecimal(2));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    private static LocalDate date(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toLocalDate();
    }
}
