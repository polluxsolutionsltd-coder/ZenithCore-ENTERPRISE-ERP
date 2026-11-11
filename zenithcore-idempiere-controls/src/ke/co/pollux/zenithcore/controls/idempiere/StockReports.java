package ke.co.pollux.zenithcore.controls.idempiere;

import static ke.co.pollux.zenithcore.controls.idempiere.ReportPdf.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.compiere.model.MCost;
import org.compiere.model.MProduct;
import org.compiere.model.MSysConfig;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import ke.co.pollux.zenithcore.controls.StockAnalysis;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Classified;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryBucket;
import ke.co.pollux.zenithcore.controls.StockAnalysis.ExpiryReport;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Item;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Lot;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Loss;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Movement;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Policy;
import ke.co.pollux.zenithcore.controls.StockAnalysis.Wastage;

/**
 * Data and PDFs for the stock expiry, slow-moving and wastage reports. Stock is valued at the product's
 * current cost in the primary accounting schema (the costing method of the schema), as on the stock
 * valuation report.
 */
public final class StockReports {


    private StockReports() {
    }

    // -------------------------------------------------------------------- data

    /** Current unit cost by product and attribute set instance, cached for one report. */
    private static final class Costs {
        private final Map<String, BigDecimal> cache = new HashMap<>();
        private final String trx;

        Costs(String trx) {
            this.trx = trx;
        }

        BigDecimal of(int product, int asi) {
            return cache.computeIfAbsent(product + "/" + asi, k -> {
                BigDecimal c = MCost.getCurrentCost(MProduct.get(product), asi, trx);
                return c == null ? BigDecimal.ZERO : c;
            });
        }
    }

    /**
     * Stock on hand by locator and lot for products whose attribute set records expiry dates (or whose
     * lot has one). The store shown is "warehouse / locator" so set-aside stock can be told from usable stock.
     */
    public static List<Lot> lots(int client, int warehouse, String trx) {
        Costs costs = new Costs(trx);
        List<Lot> out = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT w.Name || ' / ' || l.Value, p.Value || ' ' || p.Name, COALESCE(asi.Lot, asi.SerNo), "
                + "asi.GuaranteeDate, SUM(s.QtyOnHand), p.M_Product_ID, s.M_AttributeSetInstance_ID FROM M_StorageOnHand s "
                + "JOIN M_Locator l ON l.M_Locator_ID=s.M_Locator_ID JOIN M_Warehouse w ON w.M_Warehouse_ID=l.M_Warehouse_ID "
                + "JOIN M_Product p ON p.M_Product_ID=s.M_Product_ID LEFT JOIN M_AttributeSet ats ON ats.M_AttributeSet_ID=p.M_AttributeSet_ID "
                + "LEFT JOIN M_AttributeSetInstance asi ON asi.M_AttributeSetInstance_ID=s.M_AttributeSetInstance_ID "
                + "AND s.M_AttributeSetInstance_ID>0 WHERE s.AD_Client_ID=? AND (?=0 OR w.M_Warehouse_ID=?) "
                + "AND (ats.IsGuaranteeDate='Y' OR asi.GuaranteeDate IS NOT NULL) "
                + "GROUP BY w.Name, l.Value, p.Value, p.Name, asi.Lot, asi.SerNo, asi.GuaranteeDate, p.M_Product_ID, "
                + "s.M_AttributeSetInstance_ID HAVING SUM(s.QtyOnHand)>0", rs -> {
                    Timestamp exp = rs.getTimestamp(4);
                    out.add(new Lot(rs.getString(1), rs.getString(2), rs.getString(3),
                            exp == null ? null : exp.toLocalDateTime().toLocalDate(), rs.getBigDecimal(5), costs.of(rs.getInt(6), rs.getInt(7))));
                }, client, warehouse, warehouse);
        return out;
    }

    /** SQL condition on M_Transaction t: consumption (sales, issues, production), not write-offs. */
    private static final String ISSUE = "(t.MovementType IN ('C-','P-') OR (t.MovementType='I-' AND il.QtyInternalUse>0 "
            + "AND COALESCE(r.ZC_IsWriteOff,'N')='N'))";

    /** On hand by store and product, with the last issue and the quantity issued in the 12 months to the date. */
    public static List<Item> items(int client, int warehouse, LocalDate asAt, String trx) {
        Costs costs = new Costs(trx);
        Timestamp to = Timestamp.valueOf(asAt.plusDays(1).atStartOfDay());
        Timestamp yearAgo = Timestamp.valueOf(asAt.minusMonths(12).plusDays(1).atStartOfDay());
        List<Item> out = new ArrayList<>();
        OpeningBalanceData.query(trx, "WITH oh AS (SELECT l.M_Warehouse_ID, s.M_Product_ID, SUM(s.QtyOnHand) qty FROM M_StorageOnHand s "
                + "JOIN M_Locator l ON l.M_Locator_ID=s.M_Locator_ID WHERE s.AD_Client_ID=? AND (?=0 OR l.M_Warehouse_ID=?) "
                + "GROUP BY l.M_Warehouse_ID, s.M_Product_ID HAVING SUM(s.QtyOnHand)>0), "
                + "iss AS (SELECT l.M_Warehouse_ID, t.M_Product_ID, MAX(t.MovementDate) last, "
                + "SUM(CASE WHEN t.MovementDate>=? THEN -t.MovementQty ELSE 0 END) qty FROM M_Transaction t "
                + "JOIN M_Locator l ON l.M_Locator_ID=t.M_Locator_ID LEFT JOIN M_InventoryLine il ON il.M_InventoryLine_ID=t.M_InventoryLine_ID "
                + "LEFT JOIN ZC_AdjustmentReason r ON r.ZC_AdjustmentReason_ID=il.ZC_AdjustmentReason_ID "
                + "WHERE t.AD_Client_ID=? AND t.MovementDate<? AND " + ISSUE + " GROUP BY l.M_Warehouse_ID, t.M_Product_ID) "
                + "SELECT w.Name, p.Value || ' ' || p.Name, oh.qty, iss.last, COALESCE(iss.qty,0), p.M_Product_ID FROM oh "
                + "JOIN M_Warehouse w ON w.M_Warehouse_ID=oh.M_Warehouse_ID JOIN M_Product p ON p.M_Product_ID=oh.M_Product_ID "
                + "LEFT JOIN iss ON iss.M_Warehouse_ID=oh.M_Warehouse_ID AND iss.M_Product_ID=oh.M_Product_ID WHERE p.IsStocked='Y'", rs -> {
                    Timestamp last = rs.getTimestamp(4);
                    out.add(new Item(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), costs.of(rs.getInt(6), 0),
                            last == null ? null : last.toLocalDateTime().toLocalDate(), rs.getBigDecimal(5)));
                }, client, warehouse, warehouse, yearAgo, client, to);
        return out;
    }

    /** Written-off stock (internal use and count lines with a write-off reason) in the period. */
    public static List<Loss> writeOffs(int client, int warehouse, LocalDate from, LocalDate to, String trx) {
        Costs costs = new Costs(trx);
        List<Loss> out = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT i.MovementDate, w.Name, p.Value || ' ' || p.Name, r.Name, "
                + "CASE WHEN il.QtyInternalUse<>0 THEN 'Internal use ' ELSE 'Count ' END || i.DocumentNo, "
                + "CASE WHEN il.QtyInternalUse<>0 THEN il.QtyInternalUse ELSE il.QtyBook-il.QtyCount END, il.M_Product_ID, "
                + "il.M_AttributeSetInstance_ID FROM M_InventoryLine il JOIN M_Inventory i ON i.M_Inventory_ID=il.M_Inventory_ID "
                + "JOIN ZC_AdjustmentReason r ON r.ZC_AdjustmentReason_ID=il.ZC_AdjustmentReason_ID AND r.ZC_IsWriteOff='Y' "
                + "JOIN M_Warehouse w ON w.M_Warehouse_ID=i.M_Warehouse_ID JOIN M_Product p ON p.M_Product_ID=il.M_Product_ID "
                + "WHERE i.AD_Client_ID=? AND i.DocStatus IN ('CO','CL') AND i.MovementDate>=? AND i.MovementDate<? AND (?=0 OR i.M_Warehouse_ID=?) "
                + "AND il.IsActive='Y' AND (il.QtyInternalUse>0 OR (il.QtyInternalUse=0 AND il.QtyBook>il.QtyCount)) ORDER BY i.MovementDate",
                rs -> {
                    BigDecimal qty = rs.getBigDecimal(6);
                    out.add(new Loss(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), qty, qty.multiply(costs.of(rs.getInt(7), rs.getInt(8)))));
                }, client, start(from), start(to.plusDays(1)), warehouse, warehouse);
        return out;
    }

    /**
     * Goods rejected on inspection in the period, valued at the order price (or current cost without an
     * order). These go back to the supplier, so they are shown apart from write-offs.
     */
    public static List<Loss> rejections(int client, int warehouse, LocalDate from, LocalDate to, String trx) {
        Costs costs = new Costs(trx);
        List<Loss> out = new ArrayList<>();
        OpeningBalanceData.query(trx, "SELECT x.ZC_DateInspected, w.Name, p.Value || ' ' || p.Name, "
                + "'Rejected on receipt' || COALESCE(': ' || COALESCE(rl.Name, NULLIF(TRIM(l.ZC_RejectReason),'')), ''), 'Receipt ' || io.DocumentNo, "
                + "l.ZC_QtyRejected, ol.PriceActual, l.M_Product_ID FROM ZC_InspectionLine l JOIN ZC_Inspection x ON x.ZC_Inspection_ID=l.ZC_Inspection_ID "
                + "JOIN M_InOut io ON io.M_InOut_ID=x.M_InOut_ID JOIN M_Warehouse w ON w.M_Warehouse_ID=io.M_Warehouse_ID "
                + "JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID LEFT JOIN M_InOutLine iol ON iol.M_InOutLine_ID=l.M_InOutLine_ID "
                + "LEFT JOIN C_OrderLine ol ON ol.C_OrderLine_ID=iol.C_OrderLine_ID LEFT JOIN AD_Ref_List rl ON rl.Value=l.ZC_RejectReason "
                + "AND rl.AD_Reference_ID=(SELECT c.AD_Reference_Value_ID FROM AD_Column c JOIN AD_Table t ON t.AD_Table_ID=c.AD_Table_ID "
                + "WHERE t.TableName='ZC_InspectionLine' AND c.ColumnName='ZC_RejectReason') WHERE x.AD_Client_ID=? AND x.ZC_InspectionStatus='CO' "
                + "AND x.ZC_DateInspected>=? AND x.ZC_DateInspected<? AND (?=0 OR io.M_Warehouse_ID=?) AND l.ZC_QtyRejected>0 "
                + "ORDER BY x.ZC_DateInspected", rs -> {
                    BigDecimal qty = rs.getBigDecimal(6);
                    BigDecimal price = rs.getBigDecimal(7) != null ? rs.getBigDecimal(7) : costs.of(rs.getInt(8), 0);
                    out.add(new Loss(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), qty, qty.multiply(price)));
                }, client, start(from), start(to.plusDays(1)), warehouse, warehouse);
        return out;
    }

    private static String qty(BigDecimal q) {
        return q == null ? "" : q.stripTrailingZeros().toPlainString();
    }

    public static Policy policy(int client) {
        return new Policy(MSysConfig.getIntValue("ZC_SLOW_MOVING_DAYS", 90, client), MSysConfig.getIntValue("ZC_NON_MOVING_DAYS", 365, client),
                new BigDecimal(MSysConfig.getValue("ZC_SLOW_COVER_MONTHS", "6", client).trim()));
    }

    private static Timestamp start(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay());
    }

    // ------------------------------------------------------------------- PDFs

    /** Expiry report: summary by bucket, then every lot soonest first. */
    public static byte[] expiryPdf(String institution, String store, LocalDate asAt, ExpiryReport r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 30);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        footer(w, institution, "Stock expiry report as at " + asAt);
        doc.open();
        band(doc, institution, "STOCK EXPIRY REPORT  |  " + store, "As at " + asAt + "  |  Expired: stock past its expiry date. "
                + "A lot is usable up to and including its expiry date.");
        PdfPTable sum = table(new float[] {1, 1, 1, 1, 1, 1});
        for (ExpiryBucket b : ExpiryBucket.values()) {
            sum.addCell(head(b.label));
        }
        for (ExpiryBucket b : ExpiryBucket.values()) {
            PdfPCell c = cell(BoardPackReport.money(r.valueByBucket().get(b)) + "\n" + r.lotsByBucket().get(b) + " lot(s)",
                    b == ExpiryBucket.EXPIRED && r.lotsByBucket().get(b) > 0 ? new Font(Font.HELVETICA, 11, Font.BOLD, RED) : BIG);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            sum.addCell(c);
        }
        sum.setSpacingBefore(8);
        doc.add(sum);
        Paragraph how = new Paragraph("Issue the lot that expires first. Move expired lots to a quarantine or rejected location and "
                + "write them off with the Expired reason once the board of survey approves disposal.", NOTE);
        how.setSpacingBefore(4);
        doc.add(how);
        Paragraph h = new Paragraph("Lots, soonest expiry first", H);
        h.setSpacingBefore(10);
        doc.add(h);
        PdfPTable t = table(new float[] {2.4f, 3.6f, 1.6f, 1.2f, 0.9f, 1.7f, 1, 1.1f, 1.3f}, "Store / location", "Product", "Lot / serial",
                "Expires", "Days left", "Status", "Quantity", "Unit cost", "Value");
        for (Lot l : r.lots()) {
            ExpiryBucket b = StockAnalysis.bucket(l.expiry(), asAt);
            Font f = b == ExpiryBucket.EXPIRED ? TDR : b == ExpiryBucket.D30 ? TDA : TD;
            t.addCell(cell(l.store(), TD));
            t.addCell(cell(l.product(), TD));
            t.addCell(cell(l.lot(), TDB));
            t.addCell(cell(l.expiry() == null ? "" : l.expiry().toString(), f));
            t.addCell(num(l.expiry() == null ? "" : String.valueOf(java.time.temporal.ChronoUnit.DAYS.between(asAt, l.expiry())), f));
            t.addCell(cell(b.label, f));
            t.addCell(num(qty(l.qty()), TD));
            t.addCell(num(BoardPackReport.money(l.unitCost()), TD));
            t.addCell(num(BoardPackReport.money(l.value()), TDB));
        }
        if (r.lots().isEmpty()) {
            doc.add(new Paragraph("No stock of products with expiry dates.", TD));
        } else {
            doc.add(t);
        }
        doc.close();
        return out.toByteArray();
    }

    /** Slow and non-moving stock: totals, then items (non-moving first, largest value first). */
    public static byte[] slowPdf(String institution, String store, LocalDate asAt, Policy p, List<Classified> items) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 30);
        PdfWriter w = PdfWriter.getInstance(doc, out);
        footer(w, institution, "Slow and non-moving stock as at " + asAt);
        doc.open();
        band(doc, institution, "SLOW AND NON-MOVING STOCK  |  " + store, "As at " + asAt + "  |  Non-moving: never issued or no issue for "
                + p.nonMovingDays() + " days. Slow: no issue for " + p.slowDays() + " days, or more than "
                + p.maxCoverMonths().stripTrailingZeros().toPlainString() + " months of cover.");
        BigDecimal non = BigDecimal.ZERO, slow = BigDecimal.ZERO;
        for (Classified c : items) {
            if (c.movement() == Movement.NON_MOVING) {
                non = non.add(c.item().value());
            } else {
                slow = slow.add(c.item().value());
            }
        }
        PdfPTable sum = table(new float[] {1, 1, 1}, "Non-moving", "Slow-moving", "Items listed");
        for (String s : new String[] {BoardPackReport.money(non), BoardPackReport.money(slow), String.valueOf(items.size())}) {
            PdfPCell c = cell(s, BIG);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            sum.addCell(c);
        }
        sum.setSpacingBefore(8);
        doc.add(sum);
        Paragraph how = new Paragraph("Issues are sales, issues to users and production. Write-offs and transfers between stores are not "
                + "issues. Cover = quantity on hand / average monthly issues over the last 12 months. Review with user departments: "
                + "redeploy, stop reordering, or put to the board of survey.", NOTE);
        how.setSpacingBefore(4);
        doc.add(how);
        PdfPTable t = table(new float[] {1.8f, 3.8f, 1.3f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.3f, 2}, "Store", "Product", "Class",
                "On hand", "Last issue", "Days", "Issued 12m", "Cover (months)", "Value", "Reason");
        for (Classified c : items) {
            Item i = c.item();
            t.addCell(cell(i.store(), TD));
            t.addCell(cell(i.product(), TD));
            t.addCell(cell(c.movement().label, c.movement() == Movement.NON_MOVING ? TDR : TDA));
            t.addCell(num(qty(i.onHand()), TD));
            t.addCell(cell(i.lastIssue() == null ? "never" : i.lastIssue().toString(), TD));
            t.addCell(num(c.daysSinceIssue() == null ? "" : String.valueOf(c.daysSinceIssue()), TD));
            t.addCell(num(qty(i.issued12m()), TD));
            t.addCell(num(c.coverMonths() == null ? "" : c.coverMonths().toPlainString(), TD));
            t.addCell(num(BoardPackReport.money(i.value()), TDB));
            t.addCell(cell(c.reason(), TD));
        }
        if (items.isEmpty()) {
            doc.add(new Paragraph("No slow or non-moving stock.", TD));
        } else {
            doc.add(t);
        }
        doc.close();
        return out.toByteArray();
    }

    /** Wastage: write-offs by reason, store and item with every line; rejections on receipt apart. */
    public static byte[] wastagePdf(String institution, String store, LocalDate from, LocalDate to, List<Loss> writeOffs,
                                    List<Loss> rejections) throws Exception {
        Wastage w = StockAnalysis.wastage(writeOffs, 10);
        Wastage rej = StockAnalysis.wastage(rejections, 10);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 30, 30, 26, 32);
        PdfWriter wr = PdfWriter.getInstance(doc, out);
        footer(wr, institution, "Stock wastage " + from + " to " + to);
        doc.open();
        band(doc, institution, "STOCK WASTAGE REPORT  |  " + store, from + " to " + to + "  |  Write-offs valued at current cost; "
                + "rejections at the order price.");
        PdfPTable sum = table(new float[] {1, 1, 1}, "Written off", "Write-off lines", "Rejected on receipt (returned to supplier)");
        for (String s : new String[] {BoardPackReport.money(w.total()), String.valueOf(writeOffs.size()), BoardPackReport.money(rej.total())}) {
            PdfPCell c = cell(s, BIG);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            sum.addCell(c);
        }
        sum.setSpacingBefore(8);
        doc.add(sum);
        totals(doc, "Written off by reason", w.byReason());
        totals(doc, "Written off by store", w.byStore());
        Map<String, BigDecimal> top = new java.util.LinkedHashMap<>();
        w.topItems().forEach(e -> top.put(e.getKey(), e.getValue()));
        totals(doc, "Items with the largest write-offs", top);
        lines(doc, "Write-off lines", writeOffs);
        totals(doc, "Rejected on receipt by reason", rej.byReason());
        lines(doc, "Rejection lines", rejections);
        doc.close();
        return out.toByteArray();
    }

    private static void totals(Document doc, String title, Map<String, BigDecimal> m) throws Exception {
        Paragraph p = new Paragraph(title, H);
        p.setSpacingBefore(10);
        doc.add(p);
        if (m.isEmpty()) {
            doc.add(new Paragraph("None.", TD));
            return;
        }
        PdfPTable t = table(new float[] {5, 1.5f});
        for (Map.Entry<String, BigDecimal> e : m.entrySet()) {
            t.addCell(cell(e.getKey(), TD));
            t.addCell(num(BoardPackReport.money(e.getValue()), TDB));
        }
        doc.add(t);
    }

    private static void lines(Document doc, String title, List<Loss> losses) throws Exception {
        Paragraph p = new Paragraph(title, H);
        p.setSpacingBefore(10);
        doc.add(p);
        if (losses.isEmpty()) {
            doc.add(new Paragraph("None.", TD));
            return;
        }
        PdfPTable t = table(new float[] {1.2f, 1.6f, 3, 2.2f, 1.8f, 1, 1.3f}, "Date", "Store", "Product", "Reason", "Document", "Qty", "Value");
        for (Loss l : losses) {
            t.addCell(cell(l.date().toString(), TD));
            t.addCell(cell(l.store(), TD));
            t.addCell(cell(l.product(), TD));
            t.addCell(cell(l.reason(), TD));
            t.addCell(cell(l.source(), TD));
            t.addCell(num(qty(l.qty()), TD));
            t.addCell(num(BoardPackReport.money(l.value().setScale(2, java.math.RoundingMode.HALF_UP)), TDB));
        }
        doc.add(t);
    }

}
