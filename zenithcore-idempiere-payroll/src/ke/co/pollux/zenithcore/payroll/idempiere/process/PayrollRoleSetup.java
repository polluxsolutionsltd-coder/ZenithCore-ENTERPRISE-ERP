package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.util.List;
import java.util.Map;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MRole;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Set Up Payroll Roles (menu: ZenithCore). Creates or refreshes three roles in the current client so
 * that no one person can run a payroll from start to finish:
 * <ul>
 *   <li><b>Payroll Officer</b>: maintains employee pay data, creates and calculates payroll runs, prints
 *       payslips and schedules. Cannot approve or post.</li>
 *   <li><b>HR Head</b>: maintains the establishment (approved positions), reviews pay data (read only),
 *       approves payroll runs and requests reversals. Cannot change pay data, calculate or post.</li>
 *   <li><b>Finance Head</b>: maintains the payroll accounts, completes (posts) approved payroll runs and
 *       reverses them when HR has requested it. Cannot change pay data, calculate or approve.</li>
 * </ul>
 * The roles are manual roles, so iDempiere does not add other access to them. Safe to run again: it
 * restores the intended access and removes any other ZenithCore payroll access from these roles.
 * Assign the roles to users in the User window. The processes also refuse a user who calculated a
 * payroll from approving or completing it, whatever their roles.
 */
@Process
public class PayrollRoleSetup extends SvrProcess {

    // ZenithCore dictionary
    static final int W_PAY_DATA = 1090000, W_RUN = 1090001, W_ACCOUNTS = 1090002, W_ESTABLISHMENT = 1090003;
    static final int P_PREVIEW = 1090000, P_CALC = 1090001, P_APPROVE = 1090002, P_COMPLETE = 1090003,
            P_PAYSLIPS = 1090004, P_SCHEDULES = 1090005, P_REQUEST_REVERSAL = 1090007, P_REVERSE = 1090008;
    // iDempiere core windows
    static final int W_BUSINESS_PARTNER = 123, W_GL_JOURNAL = 132;

    /** Role definition: windows (id to read-write) and processes. */
    record RoleSpec(String name, String description, Map<Integer, Boolean> windows, List<Integer> processes) {
    }

    static final List<RoleSpec> ROLES = List.of(
            new RoleSpec("Payroll Officer",
                    "ZenithCore payroll: maintains pay data, calculates payroll, prints payslips and schedules",
                    Map.of(W_PAY_DATA, true, W_RUN, true, W_BUSINESS_PARTNER, true, W_ESTABLISHMENT, false),
                    List.of(P_PREVIEW, P_CALC, P_PAYSLIPS, P_SCHEDULES)),
            new RoleSpec("HR Head",
                    "ZenithCore payroll: maintains the establishment, reviews pay data, approves payroll runs and requests reversals",
                    Map.of(W_PAY_DATA, false, W_RUN, true, W_BUSINESS_PARTNER, false, W_ESTABLISHMENT, true),
                    List.of(P_PREVIEW, P_APPROVE, P_PAYSLIPS, P_REQUEST_REVERSAL)),
            new RoleSpec("Finance Head",
                    "ZenithCore payroll: maintains payroll accounts, posts approved payroll runs and reverses them on request",
                    Map.of(W_PAY_DATA, false, W_RUN, true, W_ACCOUNTS, true, W_GL_JOURNAL, false, W_ESTABLISHMENT, false),
                    List.of(P_COMPLETE, P_PAYSLIPS, P_SCHEDULES, P_REVERSE)));

    /** All ZenithCore payroll windows and processes: access to any not granted is removed from these roles. */
    static final List<Integer> ZC_WINDOWS = List.of(W_PAY_DATA, W_RUN, W_ACCOUNTS, W_ESTABLISHMENT);
    static final List<Integer> ZC_PROCESSES = List.of(P_PREVIEW, P_CALC, P_APPROVE, P_COMPLETE, P_PAYSLIPS, P_SCHEDULES,
            P_REQUEST_REVERSAL, P_REVERSE);

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        int clientId = Env.getAD_Client_ID(getCtx());
        if (clientId == 0) {
            throw new AdempiereUserError("Run this in the institution's client, not System");
        }
        StringBuilder msg = new StringBuilder();
        for (RoleSpec spec : ROLES) {
            MRole role = new Query(getCtx(), MRole.Table_Name, "Name=? AND AD_Client_ID=?", get_TrxName())
                    .setParameters(spec.name(), clientId).first();
            boolean created = role == null;
            if (created) {
                role = new MRole(getCtx(), 0, get_TrxName());
                role.setName(spec.name());
                role.setUserLevel(MRole.USERLEVEL_ClientPlusOrganization);
                role.setIsManual(true);
                role.setIsAccessAllOrgs(true);
            } else if (!role.isManual()) {
                throw new AdempiereUserError("Role " + spec.name()
                        + " exists and is not a manual role. Rename it, or make it manual, then run this again.");
            }
            role.setDescription(spec.description());
            role.setIsActive(true);
            role.saveEx();

            int roleId = role.getAD_Role_ID();
            for (int w : ZC_WINDOWS) {
                if (!spec.windows().containsKey(w)) {
                    DB.executeUpdateEx("DELETE FROM AD_Window_Access WHERE AD_Role_ID=? AND AD_Window_ID=?",
                            new Object[] {roleId, w}, get_TrxName());
                }
            }
            for (int p : ZC_PROCESSES) {
                if (!spec.processes().contains(p)) {
                    DB.executeUpdateEx("DELETE FROM AD_Process_Access WHERE AD_Role_ID=? AND AD_Process_ID=?",
                            new Object[] {roleId, p}, get_TrxName());
                }
            }
            for (Map.Entry<Integer, Boolean> w : spec.windows().entrySet()) {
                String rw = w.getValue() ? "Y" : "N";
                int n = DB.executeUpdateEx("UPDATE AD_Window_Access SET IsActive='Y', IsReadWrite=?, Updated=now(), UpdatedBy=? "
                        + "WHERE AD_Role_ID=? AND AD_Window_ID=?", new Object[] {rw, getAD_User_ID(), roleId, w.getKey()},
                        get_TrxName());
                if (n == 0) {
                    DB.executeUpdateEx("INSERT INTO AD_Window_Access (AD_Window_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,"
                            + "Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Window_Access_UU) "
                            + "VALUES (?,?,?,0,'Y',now(),?,now(),?,?,?::uuid)", new Object[] {w.getKey(), roleId, clientId,
                                    getAD_User_ID(), getAD_User_ID(), rw, java.util.UUID.randomUUID().toString()}, get_TrxName());
                }
            }
            for (int p : spec.processes()) {
                int n = DB.executeUpdateEx("UPDATE AD_Process_Access SET IsActive='Y', IsReadWrite='Y', Updated=now(), UpdatedBy=? "
                        + "WHERE AD_Role_ID=? AND AD_Process_ID=?", new Object[] {getAD_User_ID(), roleId, p}, get_TrxName());
                if (n == 0) {
                    DB.executeUpdateEx("INSERT INTO AD_Process_Access (AD_Process_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,"
                            + "Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Process_Access_UU) "
                            + "VALUES (?,?,?,0,'Y',now(),?,now(),?,'Y',?::uuid)", new Object[] {p, roleId, clientId,
                                    getAD_User_ID(), getAD_User_ID(), java.util.UUID.randomUUID().toString()}, get_TrxName());
                }
            }
            addLog(spec.name() + (created ? " created" : " updated") + ": " + spec.description());
            msg.append(msg.length() > 0 ? ", " : "").append(spec.name());
        }
        return "Payroll roles ready: " + msg + ". Assign them to users in the User window; each user should hold only one of them.";
    }
}
