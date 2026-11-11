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
import org.compiere.util.AdempiereUserError;

import ke.co.pollux.zenithcore.controls.StockAnalysis;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Loss;
import ke.co.pollux.zenithcore.controls.idempiere.AssetPdf;
import ke.co.pollux.zenithcore.controls.idempiere.BoardPackReport;
import ke.co.pollux.zenithcore.controls.idempiere.StockReports;

/** Stock Wastage Report (menu): write-offs by reason, store and item, and rejections on receipt. PDF. */
@Process
public class WastageReport extends SvrProcess {

    @Parameter(name = "DateFrom")
    private Timestamp from;
    @Parameter(name = "DateTo")
    private Timestamp to;
    @Parameter(name = "M_Warehouse_ID")
    private int warehouse;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter fields
    }

    @Override
    protected String doIt() throws Exception {
        LocalDate f0 = from.toLocalDateTime().toLocalDate();
        LocalDate t0 = to == null ? LocalDate.now() : to.toLocalDateTime().toLocalDate();
        if (t0.isBefore(f0)) {
            throw new AdempiereUserError("The period ends before it starts");
        }
        List<Loss> w = StockReports.writeOffs(getAD_Client_ID(), warehouse, f0, t0, get_TrxName());
        List<Loss> r = StockReports.rejections(getAD_Client_ID(), warehouse, f0, t0, get_TrxName());
        String store = warehouse > 0 ? MWarehouse.get(warehouse).getName() : "All stores";
        File f = AssetPdf.file("stock-wastage-" + f0 + "-" + t0 + ".pdf",
                StockReports.wastagePdf(MClient.get(getCtx()).getName(), store, f0, t0, w, r));
        if (processUI != null) {
            processUI.download(f);
        }
        return "Written off " + BoardPackReport.money(StockAnalysis.wastage(w, 0).total()) + " (" + w.size() + " line(s)); rejected on receipt "
                + BoardPackReport.money(StockAnalysis.wastage(r, 0).total()) + ". " + f.getName();
    }
}
