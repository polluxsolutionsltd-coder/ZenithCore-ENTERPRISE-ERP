package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/** Print Asset Labels (menu): labels for tagged assets, by group, location and tagging date. PDF. */
@Process
public class AssetLabels extends SvrProcess {

    @Parameter(name = "A_Asset_Group_ID")
    private int group;
    @Parameter(name = "ZC_AssetLocation_ID")
    private int location;
    @Parameter(name = "DateFrom")
    private Timestamp from;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        List<AssetPdf.Label> labels = new ArrayList<>();
        OpeningBalanceData.query(get_TrxName(), "SELECT a.ZC_TagNo, a.Name, COALESCE(g.Name,'') || COALESCE('  |  ' || l.Name, '') "
                + "FROM A_Asset a LEFT JOIN A_Asset_Group g ON g.A_Asset_Group_ID=a.A_Asset_Group_ID LEFT JOIN ZC_AssetLocation l ON "
                + "l.ZC_AssetLocation_ID=a.ZC_AssetLocation_ID WHERE a.AD_Client_ID=? AND a.IsActive='Y' AND a.IsDisposed='N' "
                + "AND a.ZC_TagNo IS NOT NULL AND (?=0 OR a.A_Asset_Group_ID=?) AND (?=0 OR a.ZC_AssetLocation_ID=?) "
                + "AND (?::timestamp IS NULL OR a.ZC_DateTagged>=?) ORDER BY a.ZC_TagNo",
                rs -> labels.add(new AssetPdf.Label(rs.getString(1), rs.getString(2), rs.getString(3))),
                getAD_Client_ID(), group, group, location, location, from, from);
        byte[] pdf = AssetPdf.labels(MClient.get(getCtx()).getName(), AssetPdf.labelText(getAD_Client_ID()), labels);
        File f = AssetPdf.file("asset-labels.pdf", pdf);
        if (processUI != null) {
            processUI.download(f);
        }
        return labels.size() + " labels on " + Math.max(1, (labels.size() + 23) / 24) + " sheet(s). " + f.getName();
    }
}
