package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MSysConfig;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;

import ke.co.pollux.zenithcore.controls.AssetRegister;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Assign Asset Tags (menu). Gives the next tag number, with its check digit, to every active asset that
 * has none, by group, location and asset number. Prefix and digits come from ZC_ASSET_TAG_PREFIX and
 * ZC_ASSET_TAG_DIGITS. Tags are never changed or reused once given.
 */
@Process
public class AssetAssignTags extends SvrProcess {

    @Parameter(name = "A_Asset_Group_ID")
    private int group;
    @Parameter(name = "ZC_AssetLocation_ID")
    private int location;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        int client = getAD_Client_ID();
        String prefix = MSysConfig.getValue("ZC_ASSET_TAG_PREFIX", "ZC-", client);
        int digits = MSysConfig.getIntValue("ZC_ASSET_TAG_DIGITS", 6, client);
        List<String> issued = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT ZC_TagNo FROM A_Asset WHERE AD_Client_ID=? AND ZC_TagNo IS NOT NULL",
                rs -> issued.add(rs.getString(1)), client);
        long next = AssetRegister.next(prefix, issued);
        List<Integer> assets = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT a.A_Asset_ID FROM A_Asset a LEFT JOIN A_Asset_Group g ON g.A_Asset_Group_ID=a.A_Asset_Group_ID "
                + "LEFT JOIN ZC_AssetLocation l ON l.ZC_AssetLocation_ID=a.ZC_AssetLocation_ID WHERE a.AD_Client_ID=? AND a.IsActive='Y' "
                + "AND a.IsDisposed='N' AND a.ZC_TagNo IS NULL AND (?=0 OR a.A_Asset_Group_ID=?) AND (?=0 OR a.ZC_AssetLocation_ID=?) "
                + "ORDER BY g.Name, l.Name, a.Value", rs -> assets.add(rs.getInt(1)), client, group, group, location, location);
        if (assets.isEmpty()) {
            return "Every matching asset already has a tag";
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String first = null, last = null;
        for (int id : assets) {
            String tag = AssetRegister.tag(prefix, next++, digits);
            DB.executeUpdateEx("UPDATE A_Asset SET ZC_TagNo=?, ZC_DateTagged=?, Updated=now(), UpdatedBy=? WHERE A_Asset_ID=? "
                    + "AND ZC_TagNo IS NULL", new Object[] {tag, now, getAD_User_ID(), id}, trx);
            first = first == null ? tag : first;
            last = tag;
        }
        return assets.size() + " assets tagged, " + first + " to " + last + ". Print the labels with Print Asset Labels.";
    }
}
