package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MRole;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.CacheMgt;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.RoleSetup.Access;
import ke.co.pollux.zenithcore.controls.RoleSetup.Definition;
import ke.co.pollux.zenithcore.controls.RoleSetup.Role;
import ke.co.pollux.zenithcore.controls.idempiere.RoleFiles;

/**
 * Set Up Phase 1 Roles (menu, run by the institution's administrator). Creates or updates the roles in
 * setup/roles/roles.csv as manual roles (iDempiere adds nothing to them by itself) with the windows, forms,
 * processes and document actions in role-access.csv.
 * <ul>
 * <li>Every name is looked up first; if any window, form or process is missing or ambiguous nothing is
 * changed.</li>
 * <li>Document actions are given on every active document type of the base type in this tenant; base types the
 * tenant does not use are reported.</li>
 * <li>Safe to run again: it restores the access in the file. Access added to these roles by hand is kept and
 * listed, so the Roles and Duties Report can show it to auditors.</li>
 * </ul>
 * Users are not assigned here: assign the roles in the User window, one duty per person (see the conflicts
 * in role-conflicts.csv and ZC_ROLE_CONFLICTS).
 */
@Process
public class Phase1RoleSetup extends SvrProcess {

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
        Definition d = RoleFiles.definition();
        // resolve every name before changing anything
        List<String> problems = new ArrayList<>();
        Map<String, Integer> ids = new TreeMap<>();
        Set<String> unusedTypes = new LinkedHashSet<>();
        for (Access a : d.access()) {
            String key = a.type() + ":" + a.name();
            if (ids.containsKey(key)) {
                continue;
            }
            switch (a.type()) {
                case "window" -> ids.put(key, one("AD_Window", a.name(), problems, trx));
                case "form" -> ids.put(key, one("AD_Form", a.name(), problems, trx));
                case "process" -> ids.put(key, one("AD_Process", a.name(), problems, trx));
                default -> {
                    ids.put(key, 0);
                    for (String act : a.actions()) {
                        if (refList(act, trx) <= 0) {
                            problems.add("document action " + act + " is not in iDempiere");
                        }
                    }
                    String sub = a.subType() == null ? "" : a.subType();
                    if (DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM (" + RoleFiles.DOC_TYPES + ") t", client, a.baseType(), sub, sub) == 0) {
                        unusedTypes.add(a.name());
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AdempiereUserError("Nothing changed: " + String.join("; ", new LinkedHashSet<>(problems)));
        }
        int created = 0, grants = 0;
        for (Role spec : d.roles()) {
            MRole role = new Query(getCtx(), MRole.Table_Name, "Name=? AND AD_Client_ID=?", trx).setParameters(spec.name(), client).first();
            boolean isNew = role == null;
            if (isNew) {
                role = new MRole(getCtx(), 0, trx);
                role.setName(spec.name());
                role.setUserLevel(MRole.USERLEVEL_ClientPlusOrganization);
                role.setIsManual(true);
                role.setIsAccessAllOrgs(true);
                created++;
            } else if (!role.isManual()) {
                throw new AdempiereUserError("Role " + spec.name() + " exists and is not a manual role. Rename it, or make it manual, then run this again.");
            }
            String desc = spec.description();
            role.setDescription(desc.length() > 255 ? desc.substring(0, 255) : desc);
            role.setIsShowAcct(spec.showAccounting());
            role.setIsActive(true);
            role.saveEx();
            int roleId = role.getAD_Role_ID();
            for (Access a : d.accessOf(spec.name())) {
                int id = ids.get(a.type() + ":" + a.name());
                switch (a.type()) {
                    case "window" -> grants += grant("AD_Window_Access", "AD_Window_ID", id, roleId, a.access().equals("RW"), trx);
                    case "form" -> grants += grant("AD_Form_Access", "AD_Form_ID", id, roleId, true, trx);
                    case "process" -> grants += grant("AD_Process_Access", "AD_Process_ID", id, roleId, true, trx);
                    default -> grants += documentActions(roleId, a, client, trx);
                }
            }
            List<String> extra = extras(d, spec.name(), roleId, trx);
            addLog(spec.name() + (isNew ? " created" : " updated") + (extra.isEmpty() ? "" : "; also has, not in the file (kept): "
                    + String.join(", ", extra)));
        }
        for (String t : unusedTypes) {
            addLog("No document types " + t + " in this tenant; its actions were not given");
        }
        CacheMgt.get().reset(MRole.Table_Name);
        return d.roles().size() + " roles ready (" + created + " created), " + grants + " access records added or restored. "
                + "Assign them in the User window; see the Roles and Duties Report.";
    }

    /** The active record with this name, or a problem if there is none or more than one. */
    private static int one(String table, String name, List<String> problems, String trx) {
        int n = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM " + table + " WHERE Name=? AND IsActive='Y'", name);
        if (n != 1) {
            problems.add((n == 0 ? "no active " : n + " active records in ") + table.substring(3).toLowerCase() + (n == 0 ? " called " : " called ")
                    + name);
            return 0;
        }
        return DB.getSQLValueEx(trx, "SELECT " + table + "_ID FROM " + table + " WHERE Name=? AND IsActive='Y'", name);
    }

    private static int refList(String action, String trx) {
        return DB.getSQLValueEx(trx, "SELECT MAX(AD_Ref_List_ID) FROM AD_Ref_List WHERE AD_Reference_ID=135 AND Value=?", action);
    }

    /** Adds or restores one access record; returns 1 when something changed. */
    private int grant(String table, String keyCol, int id, int roleId, boolean readWrite, String trx) {
        String rw = readWrite ? "Y" : "N";
        int same = DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM " + table + " WHERE AD_Role_ID=? AND " + keyCol + "=? AND IsActive='Y' AND IsReadWrite=?",
                roleId, id, rw);
        if (same > 0) {
            return 0;
        }
        int n = DB.executeUpdateEx("UPDATE " + table + " SET IsActive='Y', IsReadWrite=?, Updated=now(), UpdatedBy=? WHERE AD_Role_ID=? AND "
                + keyCol + "=?", new Object[] {rw, getAD_User_ID(), roleId, id}, trx);
        if (n == 0) {
            DB.executeUpdateEx("INSERT INTO " + table + " (" + keyCol + ",AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,"
                    + "UpdatedBy,IsReadWrite," + table + "_UU) VALUES (?,?,?,0,'Y',now(),?,now(),?,?,?::uuid)",
                    new Object[] {id, roleId, getAD_Client_ID(), getAD_User_ID(), getAD_User_ID(), rw, UUID.randomUUID().toString()}, trx);
        }
        return 1;
    }

    private int documentActions(int roleId, Access a, int client, String trx) {
        int changed = 0;
        List<Integer> types = new ArrayList<>();
        String sub = a.subType() == null ? "" : a.subType();
        ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData.query(trx, RoleFiles.DOC_TYPES, rs -> types.add(rs.getInt(1)), client,
                a.baseType(), sub, sub);
        for (int type : types) {
            for (String act : a.actions()) {
                int ref = refList(act, trx);
                int n = DB.executeUpdateEx("UPDATE AD_Document_Action_Access SET IsActive='Y', Updated=now(), UpdatedBy=? WHERE AD_Role_ID=? "
                        + "AND C_DocType_ID=? AND AD_Ref_List_ID=? AND IsActive='N'", new Object[] {getAD_User_ID(), roleId, type, ref}, trx);
                if (n == 0 && DB.getSQLValueEx(trx, "SELECT COUNT(*) FROM AD_Document_Action_Access WHERE AD_Role_ID=? AND C_DocType_ID=? "
                        + "AND AD_Ref_List_ID=?", roleId, type, ref) == 0) {
                    DB.executeUpdateEx("INSERT INTO AD_Document_Action_Access (AD_Role_ID,C_DocType_ID,AD_Ref_List_ID,AD_Client_ID,AD_Org_ID,"
                            + "IsActive,Created,CreatedBy,Updated,UpdatedBy,AD_Document_Action_Access_UU) VALUES (?,?,?,?,0,'Y',now(),?,now(),?,?::uuid)",
                            new Object[] {roleId, type, ref, client, getAD_User_ID(), getAD_User_ID(), UUID.randomUUID().toString()}, trx);
                    n = 1;
                }
                changed += n;
            }
        }
        return changed;
    }

    /** Windows, forms and processes the role has that the file does not give it. */
    private static List<String> extras(Definition d, String role, int roleId, String trx) {
        Set<String> inFile = new LinkedHashSet<>();
        for (Access a : d.accessOf(role)) {
            inFile.add(a.type() + ":" + a.name());
        }
        List<String> out = new ArrayList<>();
        for (String[] t : new String[][] {{"window", "AD_Window"}, {"form", "AD_Form"}, {"process", "AD_Process"}}) {
            ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData.query(trx, "SELECT x.Name FROM " + t[1] + "_Access a JOIN " + t[1]
                    + " x ON x." + t[1] + "_ID=a." + t[1] + "_ID WHERE a.AD_Role_ID=? AND a.IsActive='Y' ORDER BY x.Name", rs -> {
                        if (!inFile.contains(t[0] + ":" + rs.getString(1))) {
                            out.add(t[0] + " " + rs.getString(1));
                        }
                    }, roleId);
        }
        ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData.query(trx, "SELECT dt.DocBaseType, COALESCE(CASE dt.DocBaseType WHEN "
                + "'MMI' THEN dt.DocSubTypeInv WHEN 'SOO' THEN dt.DocSubTypeSO END,''), dt.Name, l.Value, l.Name FROM AD_Document_Action_Access a "
                + "JOIN C_DocType dt ON dt.C_DocType_ID=a.C_DocType_ID JOIN AD_Ref_List l ON l.AD_Ref_List_ID=a.AD_Ref_List_ID "
                + "WHERE a.AD_Role_ID=? AND a.IsActive='Y' ORDER BY dt.Name, l.Name", rs -> {
                    if (!RoleFiles.fileGives(d.accessOf(role), rs.getString(1), rs.getString(2), rs.getString(4))) {
                        out.add("document " + rs.getString(3) + ": " + rs.getString(5));
                    }
                }, roleId);
        return out;
    }
}
