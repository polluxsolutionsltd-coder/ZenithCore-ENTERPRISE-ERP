package ke.co.pollux.zenithcore.controls.idempiere;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.MBPartner;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.osgi.service.event.Event;

import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.SecondPersonApproval;
import ke.co.pollux.zenithcore.controls.SupplierGate;
import ke.co.pollux.zenithcore.controls.ThreeWayMatch;

/**
 * Enforces the ZenithCore Phase 1 controls when a document is completed:
 * <ul>
 *   <li>second-person approval, for the kinds of document listed in Document Approval Rules;</li>
 *   <li>supplier pre-qualification gate on LPOs, supplier invoices and supplier payments (client switch
 *       ZC_SUPPLIER_GATE);</li>
 *   <li>three-way match on supplier invoices (client switch ZC_THREE_WAY_MATCH, tolerance
 *       ZC_THREE_WAY_PRICE_TOLERANCE_PCT).</li>
 * </ul>
 * A blocked document stays in its current status and the user sees every reason at once. Reversal
 * documents are not checked (the reversal is an action on an already-approved document). A document
 * whose approval was given in another ZenithCore process (for example a payroll journal approved and
 * posted through the payroll run) carries the attribute {@link #APPROVED_ELSEWHERE} and skips the
 * second-person check only.
 */
public class ControlsEventHandler extends AbstractEventHandler {

    /** PO attribute set by ZenithCore processes that have already enforced their own approvals. */
    public static final String APPROVED_ELSEWHERE = "ZC_ApprovedElsewhere";

    private static final CLogger log = CLogger.getCLogger(ControlsEventHandler.class);

    /** Documents checked, with the column holding their amount for approval thresholds. */
    static final Map<String, String> DOCUMENTS = Map.of("GL_Journal", "TotalDr", "C_Invoice", "GrandTotal",
            "C_Payment", "PayAmt", "C_Order", "GrandTotal", "M_Requisition", "TotalLines", "M_Inventory", "",
            "M_Movement", "", "C_BankStatement", "StatementDifference");

    @Override
    protected void initialize() {
        for (String table : DOCUMENTS.keySet()) {
            registerTableEvent(IEventTopics.DOC_BEFORE_COMPLETE, table);
        }
    }

    @Override
    protected void doHandleEvent(Event event) {
        PO po = getPO(event);
        try {
            List<String> problems = new ArrayList<>();
            for (ControlResult r : check(po)) {
                if (!r.allowed()) {
                    problems.add(r.message());
                }
            }
            if (!problems.isEmpty()) {
                addErrorMessage(event, String.join(" | ", problems));
            }
        } catch (RuntimeException e) {
            log.severe("ZenithCore controls failed on " + po.get_TableName() + " " + po.get_ID() + ": " + e);
            addErrorMessage(event, "ZenithCore controls could not check this document: " + e.getMessage());
        }
    }

    List<ControlResult> check(PO po) {
        List<ControlResult> results = new ArrayList<>();
        if (po.get_ColumnIndex("Reversal_ID") >= 0 && po.get_ValueAsInt("Reversal_ID") > 0) {
            return results;
        }
        int clientId = po.getAD_Client_ID();
        String table = po.get_TableName();
        MDocType dt = docType(po);
        String base = dt == null ? null : dt.getDocBaseType();
        String label = (dt == null ? table : dt.getName()) + " " + po.get_ValueAsString("DocumentNo");

        // 1. Second-person approval
        if (base != null && po.get_Attribute(APPROVED_ELSEWHERE) == null) {
            PO rule = new Query(po.getCtx(), "ZC_DocControl", "DocBaseType=? AND AD_Client_ID=?", po.get_TrxName())
                    .setOnlyActiveRecords(true).setParameters(base, clientId).first();
            if (rule != null) {
                String amountColumn = DOCUMENTS.get(table);
                BigDecimal amount = amountColumn == null || amountColumn.isEmpty() ? BigDecimal.ZERO
                        : (BigDecimal) po.get_Value(amountColumn);
                results.add(SecondPersonApproval.check(label, po.getCreatedBy(), Env.getAD_User_ID(po.getCtx()), amount,
                        (BigDecimal) rule.get_Value("ZC_FromAmount")));
            }
        }

        // 2. Supplier pre-qualification gate
        boolean supplierDoc = ("C_Order".equals(table) && !po.get_ValueAsBoolean("IsSOTrx"))
                || ("C_Invoice".equals(table) && MDocType.DOCBASETYPE_APInvoice.equals(base))
                || ("C_Payment".equals(table) && !po.get_ValueAsBoolean("IsReceipt"));
        if (supplierDoc && MSysConfig.getBooleanValue("ZC_SUPPLIER_GATE", false, clientId)) {
            MBPartner bp = MBPartner.get(po.getCtx(), po.get_ValueAsInt("C_BPartner_ID"));
            if (bp != null && bp.isVendor() && !bp.isEmployee()) {
                results.add(SupplierGate.check(supplier(bp), documentDate(po)));
            }
        }

        // 3. Three-way match
        if ("C_Invoice".equals(table) && MDocType.DOCBASETYPE_APInvoice.equals(base)
                && MSysConfig.getBooleanValue("ZC_THREE_WAY_MATCH", false, clientId)) {
            BigDecimal tolerance = new BigDecimal(MSysConfig.getValue("ZC_THREE_WAY_PRICE_TOLERANCE_PCT", "0", clientId).trim());
            results.add(ThreeWayMatch.check(matchLines((MInvoice) po), tolerance));
        }
        return results;
    }

    private static MDocType docType(PO po) {
        int id = po.get_ColumnIndex("C_DocType_ID") >= 0 ? po.get_ValueAsInt("C_DocType_ID") : 0;
        if (id <= 0 && po.get_ColumnIndex("C_DocTypeTarget_ID") >= 0) {
            id = po.get_ValueAsInt("C_DocTypeTarget_ID");
        }
        return id > 0 ? MDocType.get(id) : null;
    }

    static SupplierGate.Supplier supplier(MBPartner bp) {
        return new SupplierGate.Supplier(bp.getName(), SupplierGate.Status.of(bp.get_ValueAsString("ZC_PrequalStatus")),
                date(bp.get_Value("ZC_PrequalValidTo")), date(bp.get_Value("ZC_TCCExpiry")),
                bp.get_ValueAsString("ZC_BlacklistReason"));
    }

    private static LocalDate documentDate(PO po) {
        for (String col : new String[] {"DateAcct", "DateOrdered", "DateInvoiced", "DateTrx"}) {
            if (po.get_ColumnIndex(col) >= 0 && po.get_Value(col) != null) {
                return date(po.get_Value(col));
            }
        }
        return LocalDate.now();
    }

    private static LocalDate date(Object v) {
        return v instanceof Timestamp ts ? ts.toLocalDateTime().toLocalDate() : null;
    }

    private static List<ThreeWayMatch.Line> matchLines(MInvoice invoice) {
        List<ThreeWayMatch.Line> lines = new ArrayList<>();
        for (MInvoiceLine il : invoice.getLines(true)) {
            if (il.getM_Product_ID() <= 0 && il.getC_Charge_ID() <= 0 && il.getLineNetAmt().signum() == 0) {
                continue; // description line
            }
            String item = il.getM_Product_ID() > 0 ? MProduct.get(il.getM_Product_ID()).getName()
                    : il.getDescription() != null ? il.getDescription() : "charge";
            int ol = il.getC_OrderLine_ID();
            boolean stocked = il.getM_Product_ID() > 0 && MProduct.get(il.getM_Product_ID()).isStocked();
            BigDecimal received = BigDecimal.ZERO, before = BigDecimal.ZERO, orderPrice = BigDecimal.ZERO;
            if (ol > 0) {
                received = nz(DB.getSQLValueBDEx(invoice.get_TrxName(),
                        "SELECT SUM(l.MovementQty) FROM M_InOutLine l JOIN M_InOut io ON io.M_InOut_ID=l.M_InOut_ID "
                                + "WHERE l.C_OrderLine_ID=? AND io.DocStatus IN ('CO','CL')", ol));
                before = nz(DB.getSQLValueBDEx(invoice.get_TrxName(),
                        "SELECT SUM(l.QtyInvoiced) FROM C_InvoiceLine l JOIN C_Invoice i ON i.C_Invoice_ID=l.C_Invoice_ID "
                                + "WHERE l.C_OrderLine_ID=? AND i.DocStatus IN ('CO','CL') AND i.C_Invoice_ID<>?",
                        ol, invoice.getC_Invoice_ID()));
                orderPrice = new MOrderLine(invoice.getCtx(), ol, invoice.get_TrxName()).getPriceActual();
            }
            lines.add(new ThreeWayMatch.Line(il.getLine(), item, ol > 0, stocked, il.getQtyInvoiced(), received, before,
                    il.getPriceActual(), orderPrice));
        }
        return lines;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
