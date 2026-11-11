package ke.co.pollux.zenithcore.controls.idempiere.process;

import static ke.co.pollux.zenithcore.controls.idempiere.ReportPdf.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MSysConfig;
import org.compiere.process.SvrProcess;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import ke.co.pollux.zenithcore.controls.RoleSetup;
import ke.co.pollux.zenithcore.controls.RoleSetup.Access;
import ke.co.pollux.zenithcore.controls.RoleSetup.Conflict;
import ke.co.pollux.zenithcore.controls.RoleSetup.Definition;
import ke.co.pollux.zenithcore.controls.RoleSetup.Role;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;
import ke.co.pollux.zenithcore.controls.idempiere.RoleFiles;

/**
 * Roles and Duties Report (menu): for auditors and the administrator. Users who hold roles that must be kept
 * apart; then each Phase 1 role with its users, what it must not do, and what it can actually do in this
 * tenant (read from the role, not the file), marking access that is not in the setup file. PDF.
 */
@Process
public class RoleReport extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        int client = getAD_Client_ID();
        String trx = get_TrxName();
        Definition d = RoleFiles.definition();
        // active users and their active roles
        Map<String, Set<String>> rolesByUser = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<String>> usersByRole = new LinkedHashMap<>();
        Set<String> support = new LinkedHashSet<>();
        OpeningBalanceData.query(trx, "SELECT u.Name, r.Name, u.AD_User_ID FROM AD_User_Roles ur JOIN AD_User u ON u.AD_User_ID=ur.AD_User_ID "
                + "JOIN AD_Role r ON r.AD_Role_ID=ur.AD_Role_ID WHERE ur.AD_Client_ID=? AND ur.IsActive='Y' AND u.IsActive='Y' AND r.IsActive='Y' "
                + "ORDER BY u.Name, r.Name", rs -> {
                    boolean sup = RoleFiles.SUPPORT_USERS.contains(rs.getInt(3));
                    if (sup) {
                        support.add(rs.getString(1));
                    } else {
                        rolesByUser.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    }
                    usersByRole.computeIfAbsent(rs.getString(2).toLowerCase(), k -> new ArrayList<>()).add(rs.getString(1) + (sup ? " (support login)" : ""));
                }, client);
        Map<String, List<Conflict>> conflicts = RoleSetup.userConflicts(rolesByUser, d.conflicts());
        String institution = MClient.get(getCtx()).getName();
        boolean on = MSysConfig.getBooleanValue("ZC_ROLE_CONFLICTS", false, client);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 26, 32);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        footer(w, institution, "Roles and duties as at " + LocalDate.now());
        doc.open();
        band(doc, institution, "ROLES AND DUTIES", "As at " + LocalDate.now() + "  |  Conflicting roles are " + (on ? "refused when given "
                + "to a user (ZC_ROLE_CONFLICTS = Y)" : "reported only (ZC_ROLE_CONFLICTS = N)"));
        PdfPTable sum = table(new float[] {1, 1, 1}, "Phase 1 roles", "Users holding them", "Users with conflicting roles");
        int holders = (int) rolesByUser.values().stream().filter(rs -> d.roles().stream().anyMatch(r -> rs.stream().anyMatch(x -> x.equalsIgnoreCase(r.name()))))
                .count();
        String[] figures = {String.valueOf(d.roles().size()), String.valueOf(holders), String.valueOf(conflicts.size())};
        for (int i = 0; i < 3; i++) {
            PdfPCell c = cell(figures[i], i == 2 && !conflicts.isEmpty() ? new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 11,
                    com.lowagie.text.Font.BOLD, RED) : BIG);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            sum.addCell(c);
        }
        sum.setSpacingBefore(8);
        doc.add(sum);

        heading(doc, "Users holding roles that must be kept apart");
        if (conflicts.isEmpty()) {
            doc.add(new Paragraph("None.", TD));
        } else {
            PdfPTable t = table(new float[] {1.4f, 2.6f, 4}, "User", "Roles", "Why they must be different people");
            conflicts.forEach((user, list) -> {
                for (Conflict c : list) {
                    t.addCell(cell(user, TDB));
                    t.addCell(cell(c.roleA() + " + " + c.roleB(), TDR));
                    t.addCell(cell(c.reason(), TD));
                }
            });
            doc.add(t);
            doc.add(new Paragraph("Remove one of the roles in the User window (Roles tab), or record why the risk is accepted and what "
                    + "compensating review is done.", NOTE));
        }
        if (!support.isEmpty()) {
            doc.add(new Paragraph("Support logins (" + String.join(", ", support) + ") are not counted above: iDempiere gives SuperUser every new "
                    + "role, and these logins already have full access. At go-live, lock them or keep their passwords with Pollux support only, and "
                    + "review their use in the change log.", NOTE));
        }

        int extras = 0;
        for (Role r : d.roles()) {
            heading(doc, r.name());
            doc.add(new Paragraph(r.description(), TD));
            doc.add(new Paragraph("Must not: " + r.cannot(), TD));
            List<String> users = usersByRole.getOrDefault(r.name().toLowerCase(), List.of());
            doc.add(new Paragraph("Users: " + (users.isEmpty() ? "none" : String.join(", ", users)), TDB));
            int roleId = org.compiere.util.DB.getSQLValueEx(trx, "SELECT MAX(AD_Role_ID) FROM AD_Role WHERE AD_Client_ID=? AND Name=?", client,
                    r.name());
            if (roleId <= 0) {
                doc.add(new Paragraph("Not set up in this tenant: run Set Up Phase 1 Roles.", TDR));
                continue;
            }
            Set<String> inFile = new LinkedHashSet<>();
            for (Access a : d.accessOf(r.name())) {
                inFile.add(a.type() + ":" + a.name());
            }
            PdfPTable t = table(new float[] {1.1f, 4.2f, 2.2f}, "Kind", "Window, form, process or document", "Access");
            int[] extra = {0};
            for (String[] k : new String[][] {{"window", "AD_Window"}, {"form", "AD_Form"}, {"process", "AD_Process"}}) {
                OpeningBalanceData.query(trx, "SELECT x.Name, a.IsReadWrite FROM " + k[1] + "_Access a JOIN " + k[1] + " x ON x." + k[1]
                        + "_ID=a." + k[1] + "_ID WHERE a.AD_Role_ID=? AND a.IsActive='Y' ORDER BY x.Name", rs -> {
                            boolean added = !inFile.contains(k[0] + ":" + rs.getString(1));
                            extra[0] += added ? 1 : 0;
                            t.addCell(cell(k[0], TD));
                            t.addCell(cell(rs.getString(1) + (added ? "  (added locally, not in the setup file)" : ""), added ? TDA : TD));
                            t.addCell(cell(k[0].equals("window") ? ("Y".equals(rs.getString(2)) ? "read and write" : "read only") : "run", TD));
                        }, roleId);
            }
            Map<String, List<String[]>> docs = new LinkedHashMap<>();
            OpeningBalanceData.query(trx, "SELECT dt.DocBaseType || ' ' || dt.Name, dt.DocBaseType, COALESCE(CASE dt.DocBaseType WHEN 'MMI' "
                    + "THEN dt.DocSubTypeInv WHEN 'SOO' THEN dt.DocSubTypeSO END,''), l.Value, l.Name FROM AD_Document_Action_Access a JOIN "
                    + "C_DocType dt ON dt.C_DocType_ID=a.C_DocType_ID JOIN AD_Ref_List l ON l.AD_Ref_List_ID=a.AD_Ref_List_ID WHERE "
                    + "a.AD_Role_ID=? AND a.IsActive='Y' ORDER BY 1, l.Name", rs -> docs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new String[] {rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}), roleId);
            List<Access> fileAccess = d.accessOf(r.name());
            docs.forEach((type, acts) -> {
                List<String> given = new ArrayList<>(), added = new ArrayList<>();
                for (String[] x : acts) {
                    (RoleFiles.fileGives(fileAccess, x[0], x[1], x[2]) ? given : added).add(x[3]);
                }
                extra[0] += added.size();
                t.addCell(cell("document", TD));
                t.addCell(cell(type + (added.isEmpty() ? "" : "  (" + String.join(", ", added) + " added locally, not in the setup file)"),
                        added.isEmpty() ? TD : TDA));
                List<String> all = new ArrayList<>(given);
                all.addAll(added);
                t.addCell(cell(String.join(", ", all), TD));
            });
            extras += extra[0];
            doc.add(t);
        }
        doc.close();
        File f = AssetPdf.file("roles-and-duties-" + LocalDate.now() + ".pdf", out.toByteArray());
        if (processUI != null) {
            processUI.download(f);
        }
        return conflicts.size() + " user(s) with conflicting roles; " + extras + " access record(s) added locally. " + f.getName();
    }

    private static void heading(Document doc, String text) throws Exception {
        Paragraph p = new Paragraph(text, H);
        p.setSpacingBefore(10);
        doc.add(p);
    }
}
