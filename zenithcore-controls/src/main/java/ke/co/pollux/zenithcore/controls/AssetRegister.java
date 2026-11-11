package ke.co.pollux.zenithcore.controls;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed asset tags and physical verification.
 * <p>
 * Tags: prefix, a zero-padded number and a Luhn check digit (JKIP-000123-6), so a mistyped or misread
 * tag is caught when it is entered.
 * <p>
 * Verification: every asset at the location is looked for and recorded as found, found elsewhere, or
 * not found, with its condition; assets found that are not in the register are recorded too. The person
 * verifying is not the custodian of the assets they verify. Exceptions go to Finance and the board of
 * survey.
 */
public final class AssetRegister {

    public static final String CONTROL = "Asset verification";

    private AssetRegister() {
    }

    // ---------------------------------------------------------------- tags

    /** Luhn check digit of a string of digits. */
    static int luhn(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** Tag for a number: prefix + number padded to the digits + "-" + check digit. */
    public static String tag(String prefix, long number, int digits) {
        if (number <= 0) {
            throw new IllegalArgumentException("tag numbers start at 1");
        }
        String n = String.format("%0" + digits + "d", number);
        if (n.length() > digits) {
            throw new IllegalArgumentException("tag number " + number + " does not fit in " + digits + " digits");
        }
        return (prefix == null ? "" : prefix) + n + "-" + luhn(n);
    }

    /** The number in a tag, or -1 if the tag is not in the format or its check digit is wrong. */
    public static long number(String prefix, String tag) {
        if (tag == null) {
            return -1;
        }
        String t = tag.trim().toUpperCase();
        String p = prefix == null ? "" : prefix.toUpperCase();
        if (!t.startsWith(p) || t.length() < p.length() + 3 || t.charAt(t.length() - 2) != '-') {
            return -1;
        }
        String n = t.substring(p.length(), t.length() - 2);
        char check = t.charAt(t.length() - 1);
        if (n.isEmpty() || !n.chars().allMatch(Character::isDigit) || !Character.isDigit(check)) {
            return -1;
        }
        return luhn(n) == check - '0' ? Long.parseLong(n) : -1;
    }

    public static boolean valid(String prefix, String tag) {
        return number(prefix, tag) > 0;
    }

    /** Next free number after the tags already issued with this prefix. */
    public static long next(String prefix, List<String> issued) {
        long max = 0;
        for (String t : issued) {
            max = Math.max(max, number(prefix, t));
        }
        return max + 1;
    }

    // ---------------------------------------------------------- verification

    public enum Result {
        FOUND("F"), FOUND_ELSEWHERE("M"), NOT_FOUND("N"), NOT_IN_REGISTER("U");

        public final String code;

        Result(String code) {
            this.code = code;
        }

        public static Result of(String code) {
            for (Result r : values()) {
                if (r.code.equals(code)) {
                    return r;
                }
            }
            return null;
        }
    }

    public enum Condition {
        GOOD("G"), FAIR("F"), POOR("P"), UNSERVICEABLE("U");

        public final String code;

        Condition(String code) {
            this.code = code;
        }

        public static Condition of(String code) {
            for (Condition c : values()) {
                if (c.code.equals(code)) {
                    return c;
                }
            }
            return null;
        }
    }

    /**
     * A verification line. asset = register label (tag and name), null for an asset not in the register;
     * expected and found are location names; custodian = user id of the asset's custodian (0 if none).
     */
    public record Line(int lineNo, String asset, String tag, Result result, String expected, String found, Condition condition,
                       String remarks, int custodian) {
    }

    /** Checks before a verification is completed. tagPrefix is used to check tags typed for unrecorded assets. */
    public static ControlResult complete(List<Line> lines, int verifier, String tagPrefix) {
        List<String> p = new ArrayList<>();
        if (lines.isEmpty()) {
            p.add("no assets to verify; load the assets first");
        }
        for (Line l : lines) {
            String label = "line " + l.lineNo() + " (" + (l.asset() != null ? l.asset() : l.tag() == null ? "" : l.tag()) + ")";
            if (l.result() == null) {
                p.add(label + ": record whether the asset was found");
                continue;
            }
            boolean seen = l.result() != Result.NOT_FOUND;
            if (seen && l.condition() == null) {
                p.add(label + ": record the condition");
            }
            if (l.result() == Result.FOUND_ELSEWHERE) {
                if (l.found() == null || l.found().isBlank()) {
                    p.add(label + ": record where the asset was found");
                } else if (l.found().equals(l.expected())) {
                    p.add(label + ": found at its own location " + l.found() + "; record it as found");
                }
            }
            if ((l.result() == Result.NOT_FOUND || l.result() == Result.NOT_IN_REGISTER) && blank(l.remarks())) {
                p.add(label + ": " + (l.result() == Result.NOT_FOUND ? "explain what was done to find it"
                        : "describe the asset (make, model, serial number)"));
            }
            if (l.result() == Result.NOT_IN_REGISTER) {
                if (l.asset() != null) {
                    p.add(label + ": the asset is in the register; record it as found or found elsewhere");
                }
                if (!blank(l.tag()) && !valid(tagPrefix, l.tag())) {
                    p.add(label + ": tag " + l.tag() + " is not a valid tag (mistyped, or another institution's)");
                }
            } else if (l.asset() == null) {
                p.add(label + ": choose the asset, or record the line as not in the register");
            }
            if (l.custodian() > 0 && l.custodian() == verifier) {
                p.add(label + ": the person verifying is the asset's custodian, so someone else must verify it");
            }
        }
        return p.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, p);
    }

    public record Summary(Map<Result, Integer> byResult, List<Line> notFound, List<Line> elsewhere, List<Line> forSurvey,
                          List<Line> unrecorded) {
        public int exceptions() {
            return notFound.size() + elsewhere.size() + forSurvey.size() + unrecorded.size();
        }
    }

    /** Counts and exception lists: not found, found elsewhere, poor or unserviceable (for the board of survey), unrecorded. */
    public static Summary summary(List<Line> lines) {
        Map<Result, Integer> by = new EnumMap<>(Result.class);
        for (Result r : Result.values()) {
            by.put(r, 0);
        }
        List<Line> nf = new ArrayList<>(), el = new ArrayList<>(), sv = new ArrayList<>(), un = new ArrayList<>();
        for (Line l : lines) {
            if (l.result() == null) {
                continue;
            }
            by.merge(l.result(), 1, Integer::sum);
            switch (l.result()) {
                case NOT_FOUND -> nf.add(l);
                case FOUND_ELSEWHERE -> el.add(l);
                case NOT_IN_REGISTER -> un.add(l);
                default -> {
                    // found where expected
                }
            }
            if (l.result() != Result.NOT_FOUND && (l.condition() == Condition.POOR || l.condition() == Condition.UNSERVICEABLE)) {
                sv.add(l);
            }
        }
        return new Summary(by, nf, el, sv, un);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
