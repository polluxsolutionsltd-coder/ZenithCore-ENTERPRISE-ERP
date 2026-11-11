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
import org.compiere.model.MBankStatement;
import org.compiere.model.MBankStatementLine;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MCost;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MInventory;
import org.compiere.model.MInventoryLine;
import org.compiere.model.MLocator;
import org.compiere.model.MMovement;
import org.compiere.model.MMovementLine;
import org.compiere.model.MWarehouse;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MPayment;
import org.compiere.model.MPriceList;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.model.MProduct;
import org.compiere.model.MSysConfig;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.osgi.service.event.Event;

import ke.co.pollux.zenithcore.controls.BlindCount;
import ke.co.pollux.zenithcore.controls.BankMatch;
import ke.co.pollux.zenithcore.controls.BoardPack;
import ke.co.pollux.zenithcore.controls.BudgetCheck;
import ke.co.pollux.zenithcore.controls.ControlResult;
import ke.co.pollux.zenithcore.controls.GoodsInspection;
import ke.co.pollux.zenithcore.controls.SecondPersonApproval;
import ke.co.pollux.zenithcore.controls.StoresControl;
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

    /**
     * PO attribute on opening-balance invoices and journals created by Post Opening Balances: historical,
     * audited and signed off, so no procurement, withholding, budget or approval control applies to them.
     */
    public static final String OPENING_BALANCE = "ZC_OpeningBalance";

    private static final CLogger log = CLogger.getCLogger(ControlsEventHandler.class);

    /** Documents checked, with the column holding their amount for approval thresholds. */
    static final Map<String, String> DOCUMENTS = Map.of("GL_Journal", "TotalDr", "C_Invoice", "GrandTotal",
            "C_Payment", "PayAmt", "C_Order", "GrandTotal", "M_Requisition", "TotalLines", "M_Inventory", "",
            "M_Movement", "", "C_BankStatement", "StatementDifference", "M_InOut", "");

    @Override
    protected void initialize() {
        for (String table : DOCUMENTS.keySet()) {
            registerTableEvent(IEventTopics.DOC_BEFORE_COMPLETE, table);
        }
        registerTableEvent(IEventTopics.DOC_BEFORE_PREPARE, "C_Invoice");
        registerTableEvent(IEventTopics.PO_BEFORE_NEW, "M_InventoryLine");
        registerTableEvent(IEventTopics.PO_BEFORE_CHANGE, "M_InventoryLine");
    }

    @Override
    protected void doHandleEvent(Event event) {
        PO po = getPO(event);
        if ("M_InventoryLine".equals(po.get_TableName())) {
            countLineChanged(event, po);
            return;
        }
        if (po.get_Attribute(OPENING_BALANCE) != null) {
            return;
        }
        if (IEventTopics.DOC_BEFORE_PREPARE.equals(event.getTopic())) {
            prepareInvoice(event, po);
            return;
        }
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

    /** Blind counts: record who counted a line and when; no changes to counts once submitted. */
    private void countLineChanged(Event event, PO po) {
        boolean isNew = IEventTopics.PO_BEFORE_NEW.equals(event.getTopic());
        boolean counted = isNew ? po.get_Value("ZC_QtyCounted") != null : po.is_ValueChanged("ZC_QtyCounted");
        if (!counted) {
            return;
        }
        MInventory inv = new MInventory(po.getCtx(), po.get_ValueAsInt("M_Inventory_ID"), po.get_TrxName());
        if ("S".equals(inv.get_ValueAsString("ZC_CountStatus")) || inv.isProcessed()) {
            addErrorMessage(event, "Blind count: count " + inv.getDocumentNo() + " is already submitted; counts can no longer be changed");
            return;
        }
        po.set_ValueNoCheck("ZC_CountedUser_ID", Env.getAD_User_ID(po.getCtx()));
        po.set_ValueNoCheck("ZC_DateCounted", new Timestamp(System.currentTimeMillis()));
    }

    /** Withholding deduction lines on supplier invoices (client setting ZC_WITHHOLDING). */
    private void prepareInvoice(Event event, PO po) {
        try {
            MInvoice inv = (MInvoice) po;
            MDocType dt = MDocType.get(inv.getC_DocTypeTarget_ID() > 0 ? inv.getC_DocTypeTarget_ID() : inv.getC_DocType_ID());
            if (dt == null || !MDocType.DOCBASETYPE_APInvoice.equals(dt.getDocBaseType()) || inv.getReversal_ID() > 0
                    || !MSysConfig.getBooleanValue("ZC_WITHHOLDING", false, inv.getAD_Client_ID())) {
                return;
            }
            List<String> problems = WithholdingService.apply(inv);
            if (!problems.isEmpty()) {
                addErrorMessage(event, "Withholding: " + String.join("; ", problems));
            }
        } catch (RuntimeException e) {
            log.severe("ZenithCore withholding failed on invoice " + po.get_ID() + ": " + e);
            addErrorMessage(event, "ZenithCore withholding could not be calculated: " + e.getMessage());
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

        // 4. Stores controls
        if (MSysConfig.getBooleanValue("ZC_STORES_CONTROL", false, clientId)) {
            results.addAll(storesChecks(po, label, base, dt));
        }

        // 5. Goods inspection and quarantine
        if (MSysConfig.getBooleanValue("ZC_GRN_INSPECTION", false, clientId)) {
            ControlResult r = inspectionCheck(po, dt);
            if (r != null) {
                results.add(r);
            }
        }

        // 6. Bank and M-Pesa statements: every transaction matched
        if ("C_BankStatement".equals(table) && MSysConfig.getBooleanValue("ZC_BANK_MATCHING", false, clientId)) {
            List<BankMatch.LineState> lines = new ArrayList<>();
            for (MBankStatementLine l : ((MBankStatement) po).getLines(true)) {
                lines.add(new BankMatch.LineState(l.getLine(), l.getTrxAmt(),
                        l.getC_Payment_ID() > 0 || l.getC_DepositBatch_ID() > 0,
                        l.getEftTrxID() != null && !l.getEftTrxID().isBlank() ? l.getEftTrxID() : l.getReferenceNo()));
            }
            results.add(BankMatch.complete(lines));
        }
        if ("C_Payment".equals(table) && MSysConfig.getBooleanValue("ZC_BANK_MATCHING", false, clientId)) {
            String ref = po.get_ValueAsString("ZC_TrxReference");
            String other = ref.isBlank() ? null : DB.getSQLValueStringEx(po.get_TrxName(), "SELECT MIN(DocumentNo) FROM C_Payment "
                    + "WHERE C_BankAccount_ID=? AND C_Payment_ID<>? AND DocStatus IN ('CO','CL') "
                    + "AND UPPER(ZC_TrxReference)=UPPER(?)", po.get_ValueAsInt("C_BankAccount_ID"), po.get_ID(), ref.trim());
            results.add(BankMatch.uniqueReference(label, ref.trim(), other));
        }

        // 7. Business unit on income and expenditure
        if (MSysConfig.getBooleanValue("ZC_BUSINESS_UNIT_REQUIRED", false, clientId)) {
            String sql = switch (table) {
                case "GL_Journal" -> "SELECT l.Line, ev.AccountType IN ('R','E'), COALESCE(l.User1_ID, vc.User1_ID, 0)>0 "
                        + "FROM GL_JournalLine l JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID=l.C_ValidCombination_ID "
                        + "JOIN C_ElementValue ev ON ev.C_ElementValue_ID=vc.Account_ID WHERE l.GL_Journal_ID=? AND l.IsActive='Y'";
                // revenue on customer invoices; charges and services (not stocked items) on supplier invoices
                case "C_Invoice" -> "SELECT l.Line, i.IsSOTrx='Y' OR l.C_Charge_ID>0 OR COALESCE(p.IsStocked,'N')='N', "
                        + "COALESCE(l.User1_ID, i.User1_ID, 0)>0 FROM C_InvoiceLine l JOIN C_Invoice i ON i.C_Invoice_ID=l.C_Invoice_ID "
                        + "LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID WHERE l.C_Invoice_ID=? AND l.IsActive='Y' "
                        + "AND l.ZC_IsWHTLine='N' AND (l.C_Charge_ID>0 OR l.M_Product_ID>0)";
                case "C_Payment" -> "SELECT 10, C_Charge_ID>0, COALESCE(User1_ID,0)>0 FROM C_Payment WHERE C_Payment_ID=?";
                default -> null;
            };
            if (sql != null) {
                List<BoardPack.UnitLine> lines = new ArrayList<>();
                try (java.sql.PreparedStatement ps = DB.prepareStatement(sql, po.get_TrxName())) {
                    ps.setInt(1, po.get_ID());
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            lines.add(new BoardPack.UnitLine(rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3)));
                        }
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
                results.add(BoardPack.requireUnit(label, lines));
            }
        }

        // 8. Budget check
        boolean budgetDoc = "M_Requisition".equals(table) || ("C_Order".equals(table) && !po.get_ValueAsBoolean("IsSOTrx"))
                || ("C_Invoice".equals(table) && MDocType.DOCBASETYPE_APInvoice.equals(base))
                || ("C_Payment".equals(table) && !po.get_ValueAsBoolean("IsReceipt") && po.get_ValueAsInt("C_Charge_ID") > 0
                        && po.get_ValueAsInt("C_Invoice_ID") <= 0);
        if (budgetDoc && MSysConfig.getBooleanValue("ZC_BUDGET_CONTROL", false, clientId)) {
            BigDecimal tolerance = new BigDecimal(MSysConfig.getValue("ZC_BUDGET_TOLERANCE_PCT", "0", clientId).trim());
            BudgetLedger ledger = new BudgetLedger(po.getCtx(), clientId, po.get_TrxName());
            Timestamp date = documentTimestamp(po);
            int year = ledger.yearOf(date, po.getAD_Org_ID());
            if (year <= 0) {
                results.add(ControlResult.block(BudgetCheck.CONTROL, List.of("no financial year covers " + date)));
            } else {
                results.add(ledger.check(label, clientId, year, budgetLines(po, ledger, date), table, po.get_ID(), tolerance));
            }
        }
        return results;
    }

    private static List<ControlResult> storesChecks(PO po, String label, String base, MDocType dt) {
        List<ControlResult> out = new ArrayList<>();
        int user = Env.getAD_User_ID(po.getCtx());
        switch (po.get_TableName()) {
            case "M_InOut" -> out.add(StoresControl.custodian(store(po.get_ValueAsInt("M_Warehouse_ID"), po.get_TrxName()), user,
                    po.get_ValueAsBoolean("IsSOTrx") ? "issue from" : "receive into"));
            case "M_Movement" -> {
                java.util.Set<Integer> from = new java.util.TreeSet<>();
                for (MMovementLine l : ((MMovement) po).getLines(true)) {
                    from.add(MLocator.get(l.getM_Locator_ID()).getM_Warehouse_ID());
                }
                for (int w : from) {
                    out.add(StoresControl.custodian(store(w, po.get_TrxName()), user, "move stock out of"));
                }
            }
            case "M_Inventory" -> {
                String sub = dt == null ? null : dt.getDocSubTypeInv();
                if (!MDocType.DOCSUBTYPEINV_PhysicalInventory.equals(sub) && !MDocType.DOCSUBTYPEINV_InternalUseInventory.equals(sub)) {
                    break; // cost adjustments are a finance document
                }
                boolean count = MDocType.DOCSUBTYPEINV_PhysicalInventory.equals(sub);
                if (count && MSysConfig.getBooleanValue("ZC_BLIND_COUNT", false, po.getAD_Client_ID())) {
                    out.add(BlindCount.complete(label, "S".equals(po.get_ValueAsString("ZC_CountStatus"))));
                }
                List<StoresControl.AdjustmentLine> lines = new ArrayList<>();
                for (MInventoryLine l : ((MInventory) po).getLines(true)) {
                    BigDecimal change = count ? l.getQtyCount().subtract(l.getQtyBook()) : l.getQtyInternalUse().negate();
                    int reasonId = l.get_ValueAsInt("ZC_AdjustmentReason_ID");
                    String code = null;
                    boolean writeOff = false;
                    if (reasonId > 0) {
                        PO r = org.compiere.model.MTable.get(po.getCtx(), "ZC_AdjustmentReason").getPO(reasonId, po.get_TrxName());
                        code = r.get_ValueAsString("Value");
                        writeOff = r.get_ValueAsBoolean("ZC_IsWriteOff");
                    }
                    BigDecimal cost = change.signum() == 0 ? BigDecimal.ZERO
                            : nz(MCost.getCurrentCost(MProduct.get(l.getM_Product_ID()), l.getM_AttributeSetInstance_ID(), po.get_TrxName()));
                    lines.add(new StoresControl.AdjustmentLine(l.getLine(), MProduct.get(l.getM_Product_ID()).getName(), change,
                            cost, code, writeOff));
                }
                BigDecimal from = new BigDecimal(MSysConfig.getValue("ZC_WRITEOFF_APPROVAL_FROM", "0", po.getAD_Client_ID()).trim());
                out.add(StoresControl.adjustment(label, store(po.get_ValueAsInt("M_Warehouse_ID"), po.get_TrxName()), lines,
                        po.getCreatedBy(), user, from));
            }
            default -> {
                // not a stores document
            }
        }
        return out;
    }

    /** Receipts into quarantine; no issue, movement or use of quarantined or rejected stock. */
    private static ControlResult inspectionCheck(PO po, MDocType dt) {
        List<GoodsInspection.StockLine> lines = new ArrayList<>();
        switch (po.get_TableName()) {
            case "M_InOut" -> {
                MInOut io = (MInOut) po;
                String type = io.getMovementType();
                boolean receipt = MInOut.MOVEMENTTYPE_VendorReceipts.equals(type);
                boolean shipment = MInOut.MOVEMENTTYPE_CustomerShipment.equals(type);
                if (!receipt && !shipment) {
                    return null; // returns to suppliers and from customers
                }
                for (MInOutLine l : io.getLines(true)) {
                    if (l.getM_Product_ID() > 0 && MProduct.get(l.getM_Product_ID()).isStocked()) {
                        lines.add(stockLine(l.getLine(), l.getM_Product_ID(), l.getM_Locator_ID()));
                    }
                }
                return receipt ? GoodsInspection.receive(lines) : GoodsInspection.use(lines, "issued");
            }
            case "M_Movement" -> {
                if (po.get_ValueAsInt("ZC_Inspection_ID") > 0) {
                    return null; // put-away after inspection
                }
                for (MMovementLine l : ((MMovement) po).getLines(true)) {
                    lines.add(stockLine(l.getLine(), l.getM_Product_ID(), l.getM_Locator_ID()));
                }
                return GoodsInspection.use(lines, "moved");
            }
            case "M_Inventory" -> {
                if (dt == null || !MDocType.DOCSUBTYPEINV_InternalUseInventory.equals(dt.getDocSubTypeInv())) {
                    return null;
                }
                for (MInventoryLine l : ((MInventory) po).getLines(true)) {
                    lines.add(stockLine(l.getLine(), l.getM_Product_ID(), l.getM_Locator_ID()));
                }
                return GoodsInspection.use(lines, "used");
            }
            default -> {
                return null;
            }
        }
    }

    private static GoodsInspection.StockLine stockLine(int line, int productId, int locatorId) {
        MLocator loc = MLocator.get(locatorId);
        return new GoodsInspection.StockLine(line, MProduct.get(productId).getName(), loc.getValue(),
                GoodsInspection.LocationUse.of(loc.get_ValueAsString("ZC_LocatorUse")));
    }

    private static StoresControl.Store store(int warehouseId, String trx) {
        MWarehouse w = MWarehouse.get(warehouseId);
        return new StoresControl.Store(w.getName(), w.get_ValueAsInt("ZC_CustodianUser_ID"),
                w.get_ValueAsInt("ZC_DeputyCustodianUser_ID"));
    }

    private static Timestamp documentTimestamp(PO po) {
        for (String col : new String[] {"DateAcct", "DateOrdered", "DateInvoiced", "DateDoc", "DateTrx"}) {
            if (po.get_ColumnIndex(col) >= 0 && po.get_Value(col) != null) {
                return (Timestamp) po.get_Value(col);
            }
        }
        return new Timestamp(System.currentTimeMillis());
    }

    /** The document's lines as budget lines (cost centre, account, amount in accounting currency). */
    private static List<BudgetLedger.Line> budgetLines(PO po, BudgetLedger ledger, Timestamp date) {
        List<BudgetLedger.Line> out = new ArrayList<>();
        int client = po.getAD_Client_ID(), org = po.getAD_Org_ID();
        int headerActivity = po.get_ColumnIndex("C_Activity_ID") >= 0 ? po.get_ValueAsInt("C_Activity_ID") : 0;
        switch (po.get_TableName()) {
            case "M_Requisition" -> {
                MRequisition r = (MRequisition) po;
                int currency = r.getM_PriceList_ID() > 0 ? MPriceList.get(r.getM_PriceList_ID()).getC_Currency_ID() : 0;
                for (MRequisitionLine l : r.getLines()) {
                    out.add(new BudgetLedger.Line("line " + l.getLine(), headerActivity,
                            ledger.accountFor(l.getM_Product_ID(), l.getC_Charge_ID()),
                            convert(ledger, l.getLineNetAmt(), currency, date, 0, client, org)));
                }
            }
            case "C_Order" -> {
                MOrder o = (MOrder) po;
                for (MOrderLine l : o.getLines(true, null)) {
                    out.add(new BudgetLedger.Line("line " + l.getLine(), l.getC_Activity_ID() > 0 ? l.getC_Activity_ID() : headerActivity,
                            ledger.accountFor(l.getM_Product_ID(), l.getC_Charge_ID()),
                            convert(ledger, l.getLineNetAmt(), o.getC_Currency_ID(), date, o.getC_ConversionType_ID(), client, org)));
                }
            }
            case "C_Invoice" -> {
                MInvoice i = (MInvoice) po;
                for (MInvoiceLine l : i.getLines(true)) {
                    if (l.getC_OrderLine_ID() > 0 || WithholdingService.isDeductionLine(l)) {
                        continue; // already committed on its LPO, or tax withheld
                    }
                    out.add(new BudgetLedger.Line("line " + l.getLine(), l.getC_Activity_ID() > 0 ? l.getC_Activity_ID() : headerActivity,
                            ledger.accountFor(l.getM_Product_ID(), l.getC_Charge_ID()),
                            convert(ledger, l.getLineNetAmt(), i.getC_Currency_ID(), date, i.getC_ConversionType_ID(), client, org)));
                }
            }
            case "C_Payment" -> {
                MPayment p = (MPayment) po;
                out.add(new BudgetLedger.Line("payment", headerActivity, ledger.accountFor(0, p.getC_Charge_ID()),
                        convert(ledger, p.getPayAmt(), p.getC_Currency_ID(), date, p.getC_ConversionType_ID(), client, org)));
            }
            default -> {
                // not budget-controlled
            }
        }
        return out;
    }

    private static BigDecimal convert(BudgetLedger ledger, BigDecimal amount, int currency, Timestamp date, int convType,
            int client, int org) {
        BigDecimal v = ledger.toAcctCurrency(amount, currency, date, convType, client, org);
        if (v == null) {
            throw new IllegalStateException("no exchange rate to the accounting currency on " + date);
        }
        return v;
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
            if (WithholdingService.isDeductionLine(il)) {
                continue; // tax withheld, not a purchase
            }
            String item = il.getM_Product_ID() > 0 ? MProduct.get(il.getM_Product_ID()).getName()
                    : il.getDescription() != null ? il.getDescription() : "charge";
            int ol = il.getC_OrderLine_ID();
            boolean stocked = il.getM_Product_ID() > 0 && MProduct.get(il.getM_Product_ID()).isStocked();
            BigDecimal received = BigDecimal.ZERO, before = BigDecimal.ZERO, orderPrice = BigDecimal.ZERO;
            if (ol > 0) {
                boolean inspected = stocked && MSysConfig.getBooleanValue("ZC_GRN_INSPECTION", false, invoice.getAD_Client_ID());
                received = nz(DB.getSQLValueBDEx(invoice.get_TrxName(), inspected
                        // only what an inspection accepted counts as received
                        ? "SELECT SUM(il.ZC_QtyAccepted) FROM ZC_InspectionLine il JOIN ZC_Inspection i ON i.ZC_Inspection_ID=il.ZC_Inspection_ID "
                                + "JOIN M_InOutLine l ON l.M_InOutLine_ID=il.M_InOutLine_ID WHERE l.C_OrderLine_ID=? AND i.Processed='Y'"
                        : "SELECT SUM(l.MovementQty) FROM M_InOutLine l JOIN M_InOut io ON io.M_InOut_ID=l.M_InOut_ID "
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
