package ke.co.pollux.zenithcore.controls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roles shipped with ZenithCore (setup/roles in the controls plug-in) and separation of duties.
 * <ul>
 * <li>roles.csv: each role, what it is for and what it must not do;</li>
 * <li>role-access.csv: windows (RW or R), forms and processes (run) and document actions per document base type
 * (for example "PR CO VO" on MMR), optionally narrowed to a sub-type for inventory and sales documents (MMI/IU
 * internal use, MMI/PI physical inventory, MMI/CA cost adjustment; SOO/xx sales order types);</li>
 * <li>role-conflicts.csv: pairs of roles one person must not hold, with the reason.</li>
 * </ul>
 * The files are checked here (in CI and before the roles are applied), and the conflict rule is used when a
 * role is given to a user and on the roles report.
 */
public final class RoleSetup {

    public static final String CONTROL = "Separation of duties";

    /** iDempiere document actions a role may be given (AD_Reference 135). */
    public static final Set<String> ACTIONS = Set.of("PR", "CO", "AP", "RJ", "VO", "CL", "RC", "RA", "RE", "IN", "XL", "PO");

    /** Base types whose document types are told apart by a sub-type: inventory (DocSubTypeInv) and sales orders (DocSubTypeSO). */
    public static final List<String> SUB_TYPED = List.of("MMI", "SOO");

    private RoleSetup() {
    }

    public record Role(int line, String name, String description, boolean showAccounting, String cannot) {
    }

    /** type: window, form, process or document; access: RW/R for windows, run for forms and processes, actions for documents. */
    public record Access(int line, String role, String type, String name, String access) {
        public List<String> actions() {
            return type.equals("document") ? List.of(access.trim().split("\\s+")) : List.of();
        }

        /** Document base type, e.g. MMI of MMI/IU. */
        public String baseType() {
            return name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
        }

        /** Document sub-type (DocSubTypeInv for MMI, DocSubTypeSO for SOO), or null for every type of the base type. */
        public String subType() {
            return name.contains("/") ? name.substring(name.indexOf('/') + 1) : null;
        }
    }

    public record Conflict(int line, String roleA, String roleB, String reason) {
        public boolean covers(String a, String b) {
            return roleA.equalsIgnoreCase(a) && roleB.equalsIgnoreCase(b) || roleA.equalsIgnoreCase(b) && roleB.equalsIgnoreCase(a);
        }
    }

    public record Definition(List<Role> roles, List<Access> access, List<Conflict> conflicts) {
        public List<Access> accessOf(String role) {
            return access.stream().filter(a -> a.role().equalsIgnoreCase(role)).toList();
        }
    }

    public static Definition parse(String roles, String access, String conflicts) {
        List<Role> r = new ArrayList<>();
        for (Map<String, String> m : SetupData.table(roles)) {
            r.add(new Role(line(m), m.get("role"), m.get("description"), "Y".equalsIgnoreCase(m.get("show_accounting")), m.get("cannot")));
        }
        List<Access> a = new ArrayList<>();
        for (Map<String, String> m : SetupData.table(access)) {
            a.add(new Access(line(m), m.get("role"), m.get("type").toLowerCase(), m.get("name"), m.get("access")));
        }
        List<Conflict> c = new ArrayList<>();
        for (Map<String, String> m : SetupData.table(conflicts)) {
            c.add(new Conflict(line(m), m.get("role_a"), m.get("role_b"), m.get("reason")));
        }
        return new Definition(r, a, c);
    }

    private static int line(Map<String, String> m) {
        return Integer.parseInt(m.get("_line"));
    }

    /**
     * Checks the files: roles named once and described; access only for those roles, with a known type and
     * access value and no duplicates; document actions known; conflicts between known roles (the shipped roles
     * or other roles named, such as the payroll roles), never a role with itself, each pair once, with a reason.
     */
    public static List<String> check(Definition d, Set<String> otherRoles) {
        List<String> p = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Role r : d.roles()) {
            if (r.name() == null || r.name().isBlank() || r.description() == null || r.description().isBlank()) {
                p.add("roles line " + r.line() + ": name and description are required");
            } else if (!names.add(r.name().toLowerCase())) {
                p.add("roles line " + r.line() + ": " + r.name() + " is defined twice");
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Access a : d.access()) {
            String at = "access line " + a.line() + " (" + a.role() + ", " + a.name() + ")";
            if (!names.contains(a.role().toLowerCase())) {
                p.add(at + ": role " + a.role() + " is not in roles.csv");
            }
            if (a.name() == null || a.name().isBlank()) {
                p.add(at + ": name is required");
            }
            switch (a.type()) {
                case "window" -> {
                    if (!a.access().equals("RW") && !a.access().equals("R")) {
                        p.add(at + ": window access must be RW or R");
                    }
                }
                case "form", "process" -> {
                    if (!a.access().equals("run")) {
                        p.add(at + ": " + a.type() + " access must be run");
                    }
                }
                case "document" -> {
                    if (!a.name().matches("[A-Z]{3}(/[A-Z]{2})?")) {
                        p.add(at + ": document must be a base type of three capital letters, e.g. GLJ, or base type/sub-type, e.g. MMI/IU");
                    } else if (a.subType() != null && !SUB_TYPED.contains(a.baseType())) {
                        p.add(at + ": only " + String.join(" and ", SUB_TYPED) + " documents have sub-types");
                    }
                    if (a.access() == null || a.access().isBlank()) {
                        p.add(at + ": no document actions");
                    } else {
                        for (String x : a.actions()) {
                            if (!ACTIONS.contains(x)) {
                                p.add(at + ": unknown document action " + x + " (use " + String.join(" ", ACTIONS.stream().sorted().toList()) + ")");
                            }
                        }
                    }
                }
                default -> p.add(at + ": type must be window, form, process or document");
            }
            if (!seen.add((a.role() + "|" + a.type() + "|" + a.name()).toLowerCase())) {
                p.add(at + ": listed twice for this role");
            }
        }
        Set<String> known = new LinkedHashSet<>(names);
        otherRoles.forEach(o -> known.add(o.toLowerCase()));
        Set<String> pairs = new LinkedHashSet<>();
        for (Conflict c : d.conflicts()) {
            String at = "conflicts line " + c.line();
            for (String r : List.of(c.roleA(), c.roleB())) {
                if (!known.contains(r.toLowerCase())) {
                    p.add(at + ": role " + r + " is not known");
                }
            }
            if (c.roleA().equalsIgnoreCase(c.roleB())) {
                p.add(at + ": a role cannot conflict with itself");
            }
            String key = c.roleA().compareToIgnoreCase(c.roleB()) < 0 ? c.roleA() + "|" + c.roleB() : c.roleB() + "|" + c.roleA();
            if (!pairs.add(key.toLowerCase())) {
                p.add(at + ": " + c.roleA() + " and " + c.roleB() + " are listed twice");
            }
            if (c.reason() == null || c.reason().isBlank()) {
                p.add(at + ": every conflict needs a reason");
            }
        }
        return p;
    }

    /** Conflicts among the roles one person holds, each pair once, in file order. */
    public static List<Conflict> conflicts(List<Conflict> rules, Set<String> heldRoles) {
        List<Conflict> out = new ArrayList<>();
        List<String> held = new ArrayList<>(heldRoles);
        for (Conflict c : rules) {
            for (int i = 0; i < held.size(); i++) {
                for (int j = i + 1; j < held.size(); j++) {
                    if (c.covers(held.get(i), held.get(j))) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    /** Giving a user one more role: blocked if it conflicts with a role they already hold. */
    public static ControlResult checkAssignment(String user, Set<String> heldRoles, String newRole, List<Conflict> rules) {
        List<String> reasons = new ArrayList<>();
        for (String h : heldRoles) {
            if (h.equalsIgnoreCase(newRole)) {
                continue;
            }
            for (Conflict c : rules) {
                if (c.covers(h, newRole)) {
                    reasons.add(user + " already holds " + h + ", so cannot also be " + newRole + ": " + c.reason());
                }
            }
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }

    /** Users holding conflicting roles: user name to the conflicts, for the roles report. */
    public static Map<String, List<Conflict>> userConflicts(Map<String, Set<String>> rolesByUser, List<Conflict> rules) {
        Map<String, List<Conflict>> out = new LinkedHashMap<>();
        rolesByUser.forEach((u, roles) -> {
            List<Conflict> c = conflicts(rules, roles);
            if (!c.isEmpty()) {
                out.put(u, c);
            }
        });
        return out;
    }
}
