package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAccount;
import org.compiere.model.MCharge;
import org.compiere.model.MClientInfo;
import org.compiere.model.MTable;
import org.compiere.model.MTax;
import org.compiere.model.MTaxCategory;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.SetupData;

/**
 * Apply Kenya Setup (menu, run in the institution's tenant after Initial Tenant Setup and Import Account
 * with setup/kenya/AccountingKE.csv). Loads the Kenya setup data shipped in this plug-in:
 * <ul>
 * <li>VAT tax categories and rates (taxes.csv); their accounts come from the chart's defaults (VAT output
 * and input tax);</li>
 * <li>charges ZenithCore uses, on the chart's accounts (charges.csv);</li>
 * <li>withholding income tax and withholding VAT types and dated rates (withholding.csv).</li>
 * </ul>
 * Safe to run again: existing records are kept, a rate that differs from the file is reported (a change in
 * law is a new dated row in the file, never an edit), and only what is missing is added.
 */
@Process
public class ApplyKenyaSetup extends SvrProcess {

    private final List<String> added = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Override
    protected void prepare() {
        // no parameters: applies to the tenant of the session
    }

    @Override
    protected String doIt() throws Exception {
        int client = getAD_Client_ID();
        if (client == 0) {
            throw new AdempiereUserError("Log in to the institution's tenant, not System");
        }
        String trx = get_TrxName();
        int schema = MClientInfo.get(getCtx(), client).getC_AcctSchema1_ID();
        verifyChart(schema, trx);
        taxes(SetupData.table(read("taxes.csv")), client, trx);
        charges(SetupData.table(read("charges.csv")), client, schema, trx);
        withholding(SetupData.table(read("withholding.csv")), client, trx);
        for (String w : warnings) {
            addLog(w);
        }
        for (String a : added) {
            addLog("Added: " + a);
        }
        return added.size() + " added, " + warnings.size() + " warning(s)" + (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings));
    }

    private String read(String file) throws Exception {
        try (InputStream in = ApplyKenyaSetup.class.getResourceAsStream("/setup/kenya/" + file)) {
            if (in == null) {
                throw new AdempiereUserError("setup/kenya/" + file + " is missing from the plug-in");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> problems = file.equals("taxes.csv") ? SetupData.checkRates(SetupData.rates(SetupData.table(text), false))
                    : file.equals("withholding.csv") ? SetupData.checkRates(SetupData.rates(SetupData.table(text), true)) : List.of();
            if (!problems.isEmpty()) {
                throw new AdempiereUserError(file + ": " + String.join("; ", problems));
            }
            return text;
        }
    }

    /** The accounts the charges need must exist: the chart must have been imported first. */
    private void verifyChart(int schema, String trx) {
        int missing = 0;
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> c : SetupData.table(readQuiet("charges.csv"))) {
            if (account(schema, c.get("account"), trx) <= 0) {
                missing++;
                sb.append(sb.length() == 0 ? "" : ", ").append(c.get("account"));
            }
        }
        if (missing > 0) {
            throw new AdempiereUserError("Accounts " + sb + " are not in the chart. Import setup/kenya/AccountingKE.csv with "
                    + "Import File Loader (Accounting - Accounts) and Import Account first.");
        }
    }

    private String readQuiet(String file) {
        try {
            return read(file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int account(int schema, String value, String trx) {
        return DB.getSQLValueEx(trx, "SELECT MAX(ev.C_ElementValue_ID) FROM C_ElementValue ev JOIN C_AcctSchema_Element ase ON "
                + "ase.C_Element_ID=ev.C_Element_ID AND ase.ElementType='AC' WHERE ase.C_AcctSchema_ID=? AND ev.Value=? AND ev.IsSummary='N'",
                schema, value);
    }

    private static Timestamp date(String iso) {
        return Timestamp.valueOf(LocalDate.parse(iso).atStartOfDay());
    }

    private int category(String name, boolean isDefault, int client, String trx) {
        int id = DB.getSQLValueEx(trx, "SELECT MAX(C_TaxCategory_ID) FROM C_TaxCategory WHERE AD_Client_ID=? AND Name=?", client, name);
        if (id > 0) {
            return id;
        }
        MTaxCategory c = new MTaxCategory(getCtx(), 0, trx);
        c.setAD_Org_ID(0);
        c.setName(name);
        c.setIsDefault(isDefault && DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM C_TaxCategory WHERE AD_Client_ID=? AND IsDefault='Y'",
                client) == 0);
        c.saveEx();
        added.add("tax category " + name);
        return c.getC_TaxCategory_ID();
    }

    private void taxes(List<Map<String, String>> rows, int client, String trx) {
        for (Map<String, String> r : rows) {
            boolean def = "Y".equals(r.get("default"));
            int cat = category(r.get("category"), def, client, trx);
            BigDecimal rate = new BigDecimal(r.get("rate"));
            Timestamp from = date(r.get("valid_from"));
            int id = DB.getSQLValueEx(trx, "SELECT MAX(C_Tax_ID) FROM C_Tax WHERE AD_Client_ID=? AND Name=? AND ValidFrom=?",
                    client, r.get("name"), from);
            if (id > 0) {
                BigDecimal have = DB.getSQLValueBDEx(trx, "SELECT Rate FROM C_Tax WHERE C_Tax_ID=?", id);
                if (have.compareTo(rate) != 0) {
                    warnings.add("tax " + r.get("name") + " from " + r.get("valid_from") + " is " + have.stripTrailingZeros().toPlainString()
                            + "% here but " + r.get("rate") + "% in the file; check which is right");
                }
                continue;
            }
            MTax t = new MTax(getCtx(), 0, trx);
            t.setAD_Org_ID(0);
            t.setName(r.get("name"));
            t.setC_TaxCategory_ID(cat);
            t.setRate(rate);
            t.setValidFrom(from);
            t.setSOPOType(r.get("sopo"));
            t.setIsTaxExempt("Y".equals(r.get("exempt")));
            t.setIsDefault(def);
            t.setDescription(r.get("source").length() > 255 ? r.get("source").substring(0, 255) : r.get("source"));
            t.saveEx();
            added.add("tax " + r.get("name") + " from " + r.get("valid_from"));
        }
    }

    private void charges(List<Map<String, String>> rows, int client, int schema, String trx) {
        for (Map<String, String> r : rows) {
            int id = DB.getSQLValueEx(trx, "SELECT MAX(C_Charge_ID) FROM C_Charge WHERE AD_Client_ID=? AND Name=?", client, r.get("name"));
            int acct = account(schema, r.get("account"), trx);
            MAccount combo = MAccount.get(getCtx(), client, 0, schema, acct, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, trx);
            if (id > 0) {
                int have = DB.getSQLValueEx(trx, "SELECT vc.Account_ID FROM C_Charge_Acct ca JOIN C_ValidCombination vc ON "
                        + "vc.C_ValidCombination_ID=ca.Ch_Expense_Acct WHERE ca.C_Charge_ID=? AND ca.C_AcctSchema_ID=?", id, schema);
                if (have != acct) {
                    warnings.add("charge " + r.get("name") + " posts to another account than " + r.get("account") + "; left as it is");
                }
                continue;
            }
            MCharge c = new MCharge(getCtx(), 0, trx);
            c.setAD_Org_ID(0);
            c.setName(r.get("name"));
            c.setDescription(r.get("description"));
            c.setChargeAmt(BigDecimal.ZERO);
            c.setC_TaxCategory_ID(category(r.get("tax_category"), false, client, trx));
            c.saveEx();
            DB.executeUpdateEx("UPDATE C_Charge_Acct SET Ch_Expense_Acct=? WHERE C_Charge_ID=? AND C_AcctSchema_ID=?",
                    new Object[] {combo.getC_ValidCombination_ID(), c.getC_Charge_ID(), schema}, trx);
            added.add("charge " + r.get("name") + " on " + r.get("account"));
        }
    }

    private void withholding(List<Map<String, String>> rows, int client, String trx) {
        for (Map<String, String> r : rows) {
            int type = DB.getSQLValueEx(trx, "SELECT MAX(ZC_WHTType_ID) FROM ZC_WHTType WHERE AD_Client_ID=? AND Value=?", client,
                    r.get("type"));
            if (type <= 0) {
                PO t = MTable.get(getCtx(), "ZC_WHTType").getPO(0, trx);
                t.setAD_Org_ID(0);
                t.set_ValueNoCheck("Value", r.get("type"));
                t.set_ValueNoCheck("Name", r.get("name"));
                t.set_ValueNoCheck("ZC_TaxKind", r.get("kind"));
                t.set_ValueNoCheck("C_Charge_ID", DB.getSQLValueEx(trx, "SELECT MAX(C_Charge_ID) FROM C_Charge WHERE AD_Client_ID=? "
                        + "AND Name=?", client, r.get("charge")));
                t.saveEx();
                type = t.get_ID();
                added.add("withholding type " + r.get("type"));
            }
            boolean nonRes = "Y".equals(r.get("non_resident"));
            Timestamp from = date(r.get("valid_from"));
            int rate = DB.getSQLValueEx(trx, "SELECT MAX(ZC_WHTRate_ID) FROM ZC_WHTRate WHERE ZC_WHTType_ID=? AND ZC_IsNonResident=? "
                    + "AND ValidFrom=?", type, nonRes ? "Y" : "N", from);
            if (rate > 0) {
                BigDecimal have = DB.getSQLValueBDEx(trx, "SELECT ZC_Rate FROM ZC_WHTRate WHERE ZC_WHTRate_ID=?", rate);
                if (have.compareTo(new BigDecimal(r.get("rate"))) != 0) {
                    warnings.add("withholding " + r.get("type") + (nonRes ? " non-resident" : " resident") + " from " + r.get("valid_from")
                            + " is " + have.stripTrailingZeros().toPlainString() + "% here but " + r.get("rate") + "% in the file");
                }
                continue;
            }
            PO w = MTable.get(getCtx(), "ZC_WHTRate").getPO(0, trx);
            w.setAD_Org_ID(0);
            w.set_ValueNoCheck("ZC_WHTType_ID", type);
            w.set_ValueNoCheck("ZC_IsNonResident", nonRes);
            w.set_ValueNoCheck("ValidFrom", from);
            w.set_ValueNoCheck("ZC_Rate", new BigDecimal(r.get("rate")));
            if (!r.get("monthly_threshold").isEmpty()) {
                w.set_ValueNoCheck("ZC_MonthlyThreshold", new BigDecimal(r.get("monthly_threshold")));
            }
            w.set_ValueNoCheck("Description", r.get("source").length() > 255 ? r.get("source").substring(0, 255) : r.get("source"));
            w.saveEx();
            added.add("withholding rate " + r.get("type") + (nonRes ? " non-resident " : " resident ") + r.get("rate") + "% from "
                    + r.get("valid_from"));
        }
    }
}
