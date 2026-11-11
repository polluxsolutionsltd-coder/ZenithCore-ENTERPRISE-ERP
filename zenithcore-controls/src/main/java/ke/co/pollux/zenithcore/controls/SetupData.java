package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Country setup data shipped with ZenithCore (setup/kenya in the controls plug-in): the chart of accounts
 * template in iDempiere's import format, VAT rates, charges and withholding rates. Parses the files and
 * checks them, so a broken chart or an undated rate is caught in CI rather than at an institution.
 */
public final class SetupData {

    private SetupData() {
    }

    /** iDempiere account types by the name used in the chart file. */
    static final Map<String, Character> TYPES = Map.of("Asset", 'A', "Liability", 'L', "Owner's Equity", 'O', "Revenue", 'R',
            "Expense", 'E', "Memo", 'M');

    /** Default accounts that documents post and that must therefore be control (document-controlled) accounts. */
    public static final Set<String> CONTROL_KEYS = Set.of("B_ASSET_ACCT", "B_INTRANSIT_ACCT", "B_UNALLOCATEDCASH_ACCT",
            "CB_ASSET_ACCT", "CB_CASHTRANSFER_ACCT", "C_RECEIVABLE_ACCT", "C_RECEIVABLE_SERVICES_ACCT", "V_PREPAYMENT_ACCT",
            "T_CREDIT_ACCT", "T_DUE_ACCT", "INTERCOMPANYDUEFROM_ACCT", "INTERCOMPANYDUETO_ACCT", "P_ASSET_ACCT", "V_LIABILITY_ACCT",
            "V_LIABILITY_SERVICES_ACCT", "NOTINVOICEDRECEIPTS_ACCT", "B_PAYMENTSELECT_ACCT", "C_PREPAYMENT_ACCT",
            "P_INVENTORYCLEARING_ACCT", "P_LANDEDCOSTCLEARING_ACCT", "PPVOFFSET_ACCT");

    public record Account(int line, String value, String name, String description, char type, boolean docControlled,
                          boolean summary, String key, String parent) {
    }

    /** Minimal CSV: commas, double-quoted fields with "" escapes. Blank lines are skipped. */
    public static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            if (raw.isBlank()) {
                continue;
            }
            List<String> row = new ArrayList<>();
            StringBuilder f = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (q && c == '"' && i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    q = !q;
                } else if (c == ',' && !q) {
                    row.add(f.toString().trim());
                    f.setLength(0);
                } else {
                    f.append(c);
                }
            }
            row.add(f.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static String col(List<String> row, int i) {
        return i < row.size() ? row.get(i) : "";
    }

    /** Reads a chart in iDempiere's 19-column account import format; problems are added to the list. */
    public static List<Account> chart(String text, List<String> problems) {
        List<Account> out = new ArrayList<>();
        List<List<String>> rows = csv(text);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (col(r, 0).startsWith("[")) {
                continue;
            }
            int line = i + 1;
            if (r.size() < 9) {
                problems.add("line " + line + ": needs at least 9 columns");
                continue;
            }
            Character type = TYPES.get(col(r, 3));
            if (type == null) {
                problems.add("line " + line + " (" + col(r, 0) + "): account type " + col(r, 3) + " is not one of " + TYPES.keySet());
                continue;
            }
            out.add(new Account(line, col(r, 0), col(r, 1), col(r, 2), type, "Yes".equalsIgnoreCase(col(r, 5)),
                    "Yes".equalsIgnoreCase(col(r, 6)), col(r, 7), col(r, 8)));
        }
        return out;
    }

    /**
     * Checks a chart: unique codes and names, parents that exist and are summaries, the same account type
     * as the parent, each required default account exactly once on a posting account, and control accounts
     * flagged as such.
     */
    public static List<String> checkChart(List<Account> accounts, Set<String> requiredKeys) {
        List<String> p = new ArrayList<>();
        Map<String, Account> byValue = new HashMap<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (Account a : accounts) {
            if (a.value().isEmpty() || a.name().isEmpty()) {
                p.add("line " + a.line() + ": code and name are required");
            }
            if (byValue.put(a.value(), a) != null) {
                p.add("line " + a.line() + ": code " + a.value() + " is used twice");
            }
            if (!names.add(a.name().toLowerCase())) {
                p.add("line " + a.line() + ": name " + a.name() + " is used twice");
            }
            if (a.name().contains(",") || a.description().contains(",")) {
                p.add("line " + a.line() + " (" + a.value() + "): no commas in names or descriptions; iDempiere's setup turns them into spaces");
            }
            if (!a.key().isEmpty()) {
                keys.merge(a.key().toUpperCase(), 1, Integer::sum);
            }
        }
        for (Account a : accounts) {
            if (!a.parent().isEmpty()) {
                Account parent = byValue.get(a.parent());
                if (parent == null) {
                    p.add(a.value() + ": parent " + a.parent() + " does not exist");
                } else {
                    if (!parent.summary()) {
                        p.add(a.value() + ": parent " + a.parent() + " is not a summary account");
                    }
                    if (parent.type() != a.type()) {
                        p.add(a.value() + ": type " + a.type() + " differs from its parent " + a.parent() + " (" + parent.type() + ")");
                    }
                }
            } else if (!a.summary()) {
                p.add(a.value() + ": posting account without a parent");
            }
            if (a.summary() && !a.key().isEmpty()) {
                p.add(a.value() + ": default account " + a.key() + " is on a summary account");
            }
            if (CONTROL_KEYS.contains(a.key().toUpperCase()) && !a.docControlled()) {
                p.add(a.value() + ": " + a.key() + " is posted by documents and must be a control account");
            }
        }
        for (String k : requiredKeys) {
            int n = keys.getOrDefault(k, 0);
            if (n != 1) {
                p.add("default account " + k + (n == 0 ? " is missing" : " appears " + n + " times"));
            }
        }
        return p;
    }

    /** A dated rate row (VAT or withholding) for the common checks. */
    public record Rate(int line, String key, BigDecimal rate, String validFrom, String source) {
    }

    /** Every rate between 0 and 100, with a valid ISO date and a source; no two rows for the same key and date. */
    public static List<String> checkRates(List<Rate> rates) {
        List<String> p = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Rate r : rates) {
            String label = "line " + r.line() + " (" + r.key() + ")";
            if (r.rate() == null || r.rate().signum() < 0 || r.rate().compareTo(BigDecimal.valueOf(100)) > 0) {
                p.add(label + ": rate must be between 0 and 100");
            }
            try {
                LocalDate.parse(r.validFrom());
            } catch (DateTimeParseException e) {
                p.add(label + ": valid_from " + r.validFrom() + " is not a date (yyyy-mm-dd)");
            }
            if (r.source() == null || r.source().isBlank()) {
                p.add(label + ": every rate needs a source");
            }
            if (!seen.add(r.key() + "|" + r.validFrom())) {
                p.add(label + ": two rows for the same date");
            }
        }
        return p;
    }

    /** Rows of a headed CSV as maps by column name. */
    public static List<Map<String, String>> table(String text) {
        List<List<String>> rows = csv(text);
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        List<String> head = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < head.size(); c++) {
                m.put(head.get(c), col(rows.get(i), c));
            }
            m.put("_line", String.valueOf(i + 1));
            out.add(m);
        }
        return out;
    }

    /** Rate rows of taxes.csv (key = name) or withholding.csv (key = type + residence). */
    public static List<Rate> rates(List<Map<String, String>> rows, boolean withholding) {
        List<Rate> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String key = withholding ? r.get("type") + ("Y".equals(r.get("non_resident")) ? " non-resident" : " resident") : r.get("name");
            BigDecimal rate;
            try {
                rate = new BigDecimal(r.get("rate"));
            } catch (RuntimeException e) {
                rate = null;
            }
            out.add(new Rate(Integer.parseInt(r.get("_line")), key, rate, r.get("valid_from"), r.get("source")));
        }
        return out;
    }
}
