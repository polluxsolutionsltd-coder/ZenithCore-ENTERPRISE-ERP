package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MWarehouse;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.controls.StockAnalysis;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Classified;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Policy;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.StockReports;

/** Slow and Non-moving Stock (menu). Thresholds from ZC_SLOW_MOVING_DAYS, ZC_NON_MOVING_DAYS, ZC_SLOW_COVER_MONTHS. PDF. */
@Process
public class SlowMovingReport extends SvrProcess {

    @Parameter(name = "M_Warehouse_ID")
    private int warehouse;
    @Parameter(name = "DateTo")
    private Timestamp asAt;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        LocalDate d = asAt == null ? LocalDate.now() : asAt.toLocalDateTime().toLocalDate();
        Policy p = StockReports.policy(getAD_Client_ID());
        List<Classified> items = StockAnalysis.slowMoving(StockReports.items(getAD_Client_ID(), warehouse, d, get_TrxName()), d, p);
        String store = warehouse > 0 ? MWarehouse.get(warehouse).getName() : "All stores";
        File f = AssetPdf.file("slow-moving-stock-" + d + ".pdf", StockReports.slowPdf(MClient.get(getCtx()).getName(), store, d, p, items));
        if (processUI != null) {
            processUI.download(f);
        }
        return items.size() + " slow or non-moving item(s). " + f.getName();
    }
}
