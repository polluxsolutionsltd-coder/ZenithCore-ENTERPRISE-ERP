package ke.co.pollux.zenithcore.controls.idempiere;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.RoleSetup;
import ke.co.pollux.zenithcore.controls.RoleSetup.Definition;

/** The role files shipped in this plug-in (setup/roles), read once and checked. */
public final class RoleFiles {

    /** Roles created by the payroll plug-in, named in the conflicts file. */
    public static final Set<String> PAYROLL_ROLES = Set.of("Payroll Officer", "HR Head", "Finance Head");

    /** iDempiere's support logins (System, SuperUser): iDempiere gives SuperUser every new role. */
    public static final Set<Integer> SUPPORT_USERS = Set.of(0, 100);

    /** SQL on C_DocType: active types of the client for a base type and optional sub-type; parameters client, base, sub, sub. */
    public static final String DOC_TYPES = "SELECT C_DocType_ID FROM C_DocType WHERE AD_Client_ID=? AND DocBaseType=? AND IsActive='Y' "
            + "AND (?='' OR COALESCE(CASE DocBaseType WHEN 'MMI' THEN DocSubTypeInv WHEN 'SOO' THEN DocSubTypeSO END,'')=?)";

    /** Is this document action on this type given by the file? (base type, sub-type of the type, action code) */
    public static boolean fileGives(java.util.List<ke.co.pollux.zenithcore.controls.RoleSetup.Access> access, String base, String sub, String action) {
        return access.stream().anyMatch(a -> a.type().equals("document") && a.baseType().equals(base)
                && (a.subType() == null || a.subType().equals(sub)) && a.actions().contains(action));
    }

    private static volatile Definition cached;

    private RoleFiles() {
    }

    public static Definition definition() {
        Definition d = cached;
        if (d == null) {
            d = RoleSetup.parse(read("roles.csv"), read("role-access.csv"), read("role-conflicts.csv"));
            List<String> problems = RoleSetup.check(d, PAYROLL_ROLES);
            if (!problems.isEmpty()) {
                throw new AdempiereUserError("setup/roles: " + String.join("; ", problems));
            }
            cached = d;
        }
        return d;
    }

    private static String read(String file) {
        try (InputStream in = RoleFiles.class.getResourceAsStream("/setup/roles/" + file)) {
            if (in == null) {
                throw new AdempiereUserError("setup/roles/" + file + " is missing from the plug-in");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
