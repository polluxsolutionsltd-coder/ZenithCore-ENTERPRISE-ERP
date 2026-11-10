package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.SetupData.Account;
import ke.co.pollux.zenithcore.controls.SetupData.Rate;

/** Checks the Kenya setup data shipped in the controls plug-in, and the checks themselves. */
class SetupDataTest {

    private static final Path KENYA = Path.of("..", "zenithcore-idempiere-controls", "setup", "kenya");

    /** The 55 default accounts iDempiere's Initial Tenant Setup looks for (AccountingUS.csv, iDempiere 13). */
    static final Set<String> IDEMPIERE_DEFAULTS = Set.of("B_ASSET_ACCT", "B_INTERESTEXP_ACCT", "B_INTERESTREV_ACCT",
            "B_INTRANSIT_ACCT", "B_PAYMENTSELECT_ACCT", "B_UNALLOCATEDCASH_ACCT", "CB_ASSET_ACCT", "CB_CASHTRANSFER_ACCT",
            "CB_DIFFERENCES_ACCT", "CB_EXPENSE_ACCT", "CB_RECEIPT_ACCT", "CH_EXPENSE_ACCT", "COMMITMENTOFFSETSALES_ACCT",
            "COMMITMENTOFFSET_ACCT", "CURRENCYBALANCING_ACCT", "C_PREPAYMENT_ACCT", "C_RECEIVABLE_ACCT", "C_RECEIVABLE_SERVICES_ACCT",
            "DEFAULT_ACCT", "INTERCOMPANYDUEFROM_ACCT", "INTERCOMPANYDUETO_ACCT", "NOTINVOICEDRECEIPTS_ACCT", "PAYDISCOUNT_EXP_ACCT",
            "PAYDISCOUNT_REV_ACCT", "PJ_ASSET_ACCT", "PJ_WIP_ACCT", "PPVOFFSET_ACCT", "P_ASSET_ACCT", "P_AVERAGECOSTVARIANCE_ACCT",
            "P_COGS_ACCT", "P_COSTADJUSTMENT_ACCT", "P_EXPENSE_ACCT", "P_INVENTORYCLEARING_ACCT", "P_INVOICEPRICEVARIANCE_ACCT",
            "P_LANDEDCOSTCLEARING_ACCT", "P_PURCHASEPRICEVARIANCE_ACCT", "P_RATEVARIANCE_ACCT", "P_REVENUE_ACCT",
            "P_TRADEDISCOUNTGRANT_ACCT", "P_TRADEDISCOUNTREC_ACCT", "REALIZEDGAIN_ACCT", "REALIZEDLOSS_ACCT", "SUSPENSEBALANCING_ACCT",
            "T_CREDIT_ACCT", "T_DUE_ACCT", "T_EXPENSE_ACCT", "UNEARNEDREVENUE_ACCT", "UNREALIZEDGAIN_ACCT", "UNREALIZEDLOSS_ACCT",
            "V_LIABILITY_ACCT", "V_LIABILITY_SERVICES_ACCT", "V_PREPAYMENT_ACCT", "WRITEOFF_ACCT", "W_DIFFERENCES_ACCT");

    private static String read(String file) throws Exception {
        return Files.readString(KENYA.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void kenya_chart_is_complete_and_consistent() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Account> chart = SetupData.chart(read("AccountingKE.csv"), problems);
        problems.addAll(SetupData.checkChart(chart, IDEMPIERE_DEFAULTS));
        assertEquals(List.of(), problems);
        assertEquals(54, IDEMPIERE_DEFAULTS.size());
        // Kenyan accounts the controls and payroll rely on
        Set<String> names = chart.stream().map(Account::name).collect(Collectors.toSet());
        for (String n : List.of("M-Pesa paybill", "VAT output tax", "VAT input tax", "Withholding income tax payable",
                "Withholding VAT payable", "PAYE payable", "NSSF payable", "SHIF payable", "Affordable Housing Levy payable",
                "NITA levy payable", "HELB deductions payable", "Opening balance clearing", "Unidentified receipts (suspense)")) {
            assertTrue(names.contains(n), n);
        }
        // payroll deductions are posted by the payroll journal, so they are not control accounts
        assertTrue(chart.stream().filter(a -> a.value().startsWith("217") && !a.summary()).noneMatch(Account::docControlled));
    }

    @Test
    void kenya_rates_are_dated_and_sourced() throws Exception {
        List<Map<String, String>> taxes = SetupData.table(read("taxes.csv"));
        List<Map<String, String>> wht = SetupData.table(read("withholding.csv"));
        assertEquals(List.of(), SetupData.checkRates(SetupData.rates(taxes, false)));
        assertEquals(List.of(), SetupData.checkRates(SetupData.rates(wht, true)));
        assertEquals("16", taxes.stream().filter(t -> "Y".equals(t.get("default"))).findFirst().orElseThrow().get("rate"));
        assertEquals(1, taxes.stream().filter(t -> "Y".equals(t.get("exempt"))).count());
        // withholding charges and chart accounts of charges exist
        List<Map<String, String>> charges = SetupData.table(read("charges.csv"));
        Set<String> chargeNames = charges.stream().map(c -> c.get("name")).collect(Collectors.toSet());
        assertTrue(wht.stream().allMatch(w -> chargeNames.contains(w.get("charge"))));
        Set<String> codes = SetupData.chart(read("AccountingKE.csv"), new ArrayList<>()).stream().filter(a -> !a.summary())
                .map(Account::value).collect(Collectors.toSet());
        assertTrue(charges.stream().allMatch(c -> codes.contains(c.get("account"))), "charge accounts exist in the chart");
        Set<String> categories = taxes.stream().map(t -> t.get("category")).collect(Collectors.toSet());
        assertTrue(charges.stream().allMatch(c -> categories.contains(c.get("tax_category"))));
    }

    @Test
    void chart_checks_catch_mistakes() {
        String csv = "[Account_Value],[Account_Name],[Account_Description],[Account_Type],[Account_Sign],[Account_Document],"
                + "[Account_Summary],[Default_Account],[Account_Parent]\n"
                + "1,\"Assets\",,\"Asset\",,,\"Yes\",,\n"
                + "11100,\"Bank\",,\"Asset\",,\"No\",\"No\",\"B_ASSET_ACCT\",1\n"
                + "11200,\"Bank\",,\"Asset\",,\"No\",\"No\",\"T_DUE_ACCT\",1\n"
                + "21000,\"Payables, trade\",,\"Liability\",,\"No\",\"No\",,1\n"
                + "30000,\"Fund\",,\"Owner's Equity\",,\"No\",\"No\",,9\n"
                + "40000,\"Sales\",,\"Revenue\",,\"No\",\"No\",,11100\n"
                + "50000,\"Odd\",,\"Expenses\",,\"No\",\"No\",,1\n"
                + "60000,\"Loose\",,\"Expense\",,\"No\",\"No\",,\n";
        List<String> p = new ArrayList<>();
        List<Account> chart = SetupData.chart(csv, p);
        p.addAll(SetupData.checkChart(chart, Set.of("B_ASSET_ACCT", "T_DUE_ACCT", "T_CREDIT_ACCT")));
        assertTrue(p.contains("line 8 (50000): account type Expenses is not one of " + SetupData.TYPES.keySet()), p.toString());
        assertTrue(p.contains("line 4: name Bank is used twice"));
        assertTrue(p.contains("line 5 (21000): no commas in names or descriptions; iDempiere's setup turns them into spaces"));
        assertTrue(p.contains("21000: type L differs from its parent 1 (A)"));
        assertTrue(p.contains("30000: parent 9 does not exist"));
        assertTrue(p.contains("40000: parent 11100 is not a summary account"));
        assertTrue(p.contains("60000: posting account without a parent"));
        assertTrue(p.contains("11100: B_ASSET_ACCT is posted by documents and must be a control account"));
        assertTrue(p.contains("default account T_CREDIT_ACCT is missing"));
    }

    @Test
    void rate_checks_catch_mistakes() {
        List<String> p = SetupData.checkRates(List.of(new Rate(2, "VAT 16%", new BigDecimal("16"), "2013-09-02", "VAT Act"),
                new Rate(3, "VAT 16%", new BigDecimal("16"), "2013-09-02", "VAT Act"),
                new Rate(4, "X", new BigDecimal("100.01"), "2026-13-01", " "),
                new Rate(5, "Y", null, "2026-01-01", "s")));
        assertEquals(List.of("line 3 (VAT 16%): two rows for the same date", "line 4 (X): rate must be between 0 and 100",
                "line 4 (X): valid_from 2026-13-01 is not a date (yyyy-mm-dd)", "line 4 (X): every rate needs a source",
                "line 5 (Y): rate must be between 0 and 100"), p);
        assertEquals(List.of(), SetupData.checkRates(List.of(new Rate(2, "Z", BigDecimal.ZERO, "2026-01-01", "s"),
                new Rate(3, "Z", BigDecimal.valueOf(100), "2026-07-01", "s"))));
    }
}
