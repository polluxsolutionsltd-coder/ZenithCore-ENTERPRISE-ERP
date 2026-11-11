package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.RoleSetup.Conflict;
import ke.co.pollux.zenithcore.controls.RoleSetup.Definition;

/** Checks the role files shipped in the controls plug-in, and the separation-of-duties rule. */
class RoleSetupTest {

    private static final Path ROLES = Path.of("..", "zenithcore-idempiere-controls", "setup", "roles");
    /** Roles created by the payroll plug-in's Set Up Payroll Roles. */
    static final Set<String> PAYROLL_ROLES = Set.of("Payroll Officer", "HR Head", "Finance Head");

    private static String read(String f) throws Exception {
        return Files.readString(ROLES.resolve(f), StandardCharsets.UTF_8);
    }

    private static Definition shipped() throws Exception {
        return RoleSetup.parse(read("roles.csv"), read("role-access.csv"), read("role-conflicts.csv"));
    }

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(List.of(s));
    }

    @Test
    void shipped_roles_are_consistent() throws Exception {
        Definition d = shipped();
        assertEquals(List.of(), RoleSetup.check(d, PAYROLL_ROLES));
        assertEquals(9, d.roles().size());
        // every role can do something, and every role is in at least one conflict
        for (RoleSetup.Role r : d.roles()) {
            assertTrue(!d.accessOf(r.name()).isEmpty(), r.name());
            assertTrue(d.conflicts().stream().anyMatch(c -> c.roleA().equals(r.name()) || c.roleB().equals(r.name())), r.name());
        }
    }

    /** The duties the controls separate must not sit in one shipped role. */
    @Test
    void shipped_roles_keep_duties_apart() throws Exception {
        Definition d = shipped();
        Set<String> counters = holders(d, "process", "Submit Count");
        Set<String> custodians = holders(d, "window", "Material Receipt", "RW");
        assertTrue(counters.stream().noneMatch(custodians::contains), "counters are not custodians");
        assertTrue(holders(d, "process", "Complete Inspection").stream().noneMatch(custodians::contains), "inspectors are not custodians");
        assertEquals(Set.of("Finance Manager"), holders(d, "process", "Approve Budget"));
        // store keepers complete counts and internal use, never cost adjustments (MMI/CA)
        assertEquals(List.of("MMI/IU", "MMI/PI"), d.accessOf("Store Keeper").stream().filter(a -> a.type().equals("document")
                && a.baseType().equals("MMI")).map(RoleSetup.Access::name).sorted().toList());
        assertEquals(Set.of("Finance Manager"), holders(d, "process", "Sign Off"));
        assertEquals(Set.of("Board Pack Approver"), holders(d, "process", "Approve Board Pack"));
        assertEquals(Set.of("Asset Verifier"), holders(d, "process", "Complete Verification"));
        assertEquals(Set.of("Cashier"), holders(d, "document", "ARR", null).stream()
                .filter(r -> d.accessOf(r).stream().anyMatch(a -> a.name().equals("ARR") && a.actions().contains("CO"))).collect(java.util.stream.Collectors.toSet()));
        // nobody who generates the board pack approves it; nobody who verifies assets keeps the register
        for (Conflict c : List.of(conflict(d, "Finance Officer", "Board Pack Approver"), conflict(d, "Finance Officer", "Asset Verifier"))) {
            assertTrue(c != null);
        }
    }

    private static Conflict conflict(Definition d, String a, String b) {
        return d.conflicts().stream().filter(c -> c.covers(a, b)).findFirst().orElse(null);
    }

    private static Set<String> holders(Definition d, String type, String name) {
        return holders(d, type, name, null);
    }

    private static Set<String> holders(Definition d, String type, String name, String access) {
        Set<String> out = new LinkedHashSet<>();
        d.access().stream().filter(a -> a.type().equals(type) && a.name().equals(name) && (access == null || a.access().equals(access)))
                .forEach(a -> out.add(a.role()));
        return out;
    }

    @Test
    void assignment_is_blocked_when_roles_conflict() throws Exception {
        List<Conflict> rules = shipped().conflicts();
        ControlResult r = RoleSetup.checkAssignment("Jane", set("Store Keeper", "Cashier"), "Stock Counter", rules);
        assertEquals(List.of("Jane already holds Store Keeper, so cannot also be Stock Counter: a custodian would count their own store"),
                r.reasons());
        assertTrue(RoleSetup.checkAssignment("Jane", set("Store Keeper"), "Cashier", rules).allowed());
        assertTrue(RoleSetup.checkAssignment("Jane", set("Store Keeper"), "Store Keeper", rules).allowed());     // same role again
        assertTrue(RoleSetup.checkAssignment("Jane", set("finance officer"), "Finance Manager", rules).reasons().get(0)
                .endsWith("journals, invoices and payments need a preparer and a different approver"));            // case does not matter
        assertEquals(2, RoleSetup.checkAssignment("Joe", set("Payroll Officer", "HR Head"), "Finance Head", rules).reasons().size());
        assertTrue(RoleSetup.checkAssignment("Joe", set(), "Finance Head", rules).allowed());
    }

    @Test
    void users_with_conflicting_roles_are_listed() throws Exception {
        Map<String, Set<String>> users = new LinkedHashMap<>();
        users.put("Ann", set("Store Keeper", "Procurement Officer", "Inspection Committee"));
        users.put("Ben", set("Cashier", "Store Keeper"));
        users.put("Cy", set("Finance Manager"));
        Map<String, List<Conflict>> c = RoleSetup.userConflicts(users, shipped().conflicts());
        assertEquals(List.of("Ann"), List.copyOf(c.keySet()));
        assertEquals(3, c.get("Ann").size());   // store/procurement, store/inspection, procurement/inspection
    }

    @Test
    void checks_catch_mistakes() {
        Definition d = RoleSetup.parse("role,description,show_accounting,cannot\nA,Does a,N,\nA,Again,N,\nB,Does b,N,\n,No name,N,\n",
                "role,type,name,access\nA,window,W1,RW\nA,window,W1,R\nA,window,W2,Read\nC,process,P1,run\nA,process,P2,yes\n"
                        + "A,document,GL,CO\nA,document,API,CO DELETE\nA,report,X,run\nA,document,MMR,\nA,document,GLJ/XX,CO\nA,document,MMI/IU,CO\n",
                "role_a,role_b,reason\nA,B,x\nB,A,y\nA,A,z\nA,Z,\n");
        List<String> p = RoleSetup.check(d, Set.of("Payroll Officer"));
        List<String> expected = List.of("roles line 3: A is defined twice", "roles line 5: name and description are required",
                "access line 3 (A, W1): listed twice for this role", "access line 4 (A, W2): window access must be RW or R",
                "access line 5 (C, P1): role C is not in roles.csv", "access line 6 (A, P2): process access must be run",
                "access line 7 (A, GL): document must be a base type of three capital letters, e.g. GLJ, or base type/sub-type, e.g. MMI/IU",
                "access line 11 (A, GLJ/XX): only MMI and SOO documents have sub-types",
                "access line 8 (A, API): unknown document action DELETE (use AP CL CO IN PO PR RA RC RE RJ VO XL)",
                "access line 9 (A, X): type must be window, form, process or document", "access line 10 (A, MMR): no document actions",
                "conflicts line 3: B and A are listed twice", "conflicts line 4: a role cannot conflict with itself",
"conflicts line 5: role Z is not known", "conflicts line 5: every conflict needs a reason");
        for (String e : expected) {
            assertTrue(p.contains(e), e + " in " + p);
        }
        assertEquals(expected.size(), p.size(), p.toString());
    }
}
