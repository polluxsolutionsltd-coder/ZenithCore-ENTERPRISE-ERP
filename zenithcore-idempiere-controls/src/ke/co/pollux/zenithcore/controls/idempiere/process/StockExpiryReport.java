package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.io.File;
import java.sql.Timestamp;
import java.time.LocalDate;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.model.MClient;
import org.compiere.model.MWarehouse;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.controls.StockAnalysis;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryBucket;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryReport;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.BoardPackReport;
import ke.co.pollux.zenithcore.controls.idempiere.StockReports;

/** Stock Expiry Report (menu): stock by lot and days to expiry, soonest first. PDF. */
@Process
public class StockExpiryReport extends SvrProcess {

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
        ExpiryReport r = StockAnalysis.expiry(StockReports.lots(getAD_Client_ID(), warehouse, get_TrxName()), d);
        String store = warehouse > 0 ? MWarehouse.get(warehouse).getName() : "All stores";
        File f = AssetPdf.file("stock-expiry-" + d + ".pdf", StockReports.expiryPdf(MClient.get(getCtx()).getName(), store, d, r));
        if (processUI != null) {
            processUI.download(f);
        }
        return r.lots().size() + " lot(s); expired " + r.lotsByBucket().get(ExpiryBucket.EXPIRED) + " worth "
                + BoardPackReport.money(r.valueByBucket().get(ExpiryBucket.EXPIRED)) + ", within 30 days "
                + r.lotsByBucket().get(ExpiryBucket.D30) + " worth " + BoardPackReport.money(r.valueByBucket().get(ExpiryBucket.D30)) + ". "
                + f.getName();
    }
}
