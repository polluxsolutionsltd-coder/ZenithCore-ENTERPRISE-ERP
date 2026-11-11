package ke.co.pollux.zenithcore.controls.idempiere.process;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import org.adempiere.base.annotation.Process;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MBPartner;
import org.compiere.model.MBankAccount;
import org.compiere.model.MBankStatement;
import org.compiere.model.MBankStatementLine;
import org.compiere.model.MConversionType;
import org.compiere.model.MDocType;
import org.compiere.model.MGLCategory;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MJournal;
import org.compiere.model.MJournalLine;
import org.compiere.model.MPriceList;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.DocAction;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;

import ke.co.pollux.zenithcore.controls.OpeningBalance;
import ke.co.pollux.zenithcore.controls.OpeningBalance.Item;
import ke.co.pollux.zenithcore.controls.OpeningBalance.ItemType;
import ke.co.pollux.zenithcore.controls.OpeningBalance.JournalLine;
import ke.co.pollux.zenithcore.controls.idempiere.ControlsEventHandler;
import ke.co.pollux.zenithcore.controls.idempiere.OpeningBalanceData;

/**
 * Post Opening Balances (button on Opening Balances), after sign-off and only once:
 * <ol>
 * <li>an opening invoice (or credit memo) per customer and supplier item, dated at its original date and
 * posted on the opening balance date, with one line on the clearing charge, so the open item can be aged,
 * paid and allocated as usual;</li>
 * <li>an opening bank statement per bank or M-Pesa item, with one line on the clearing charge, so the
 * bank account's statement balance starts at the opening figure;</li>
 * <li>the opening journal: one line per account, with control accounts that have sub-ledger items going to
 * the clearing account instead.</li>
 * </ol>
 * Stock and fixed asset items are reconciled here but loaded with iDempiere's own stock count and asset
 * imports, posting against the same clearing account. Everything is created in one transaction: if any
 * document fails, nothing is posted.
 */
@Process
public class OpeningBalancePost extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        String trx = get_TrxName();
        PO h = MTable.get(getCtx(), "ZC_OpeningBalance").getPO(getRecord_ID(), trx);
        if (h == null || h.get_ID() <= 0) {
            throw new AdempiereUserError("Open an opening balance first");
        }
        if ("PO".equals(h.get_ValueAsString("ZC_OBStatus")) || h.get_ValueAsInt("GL_Journal_ID") > 0) {
            throw new AdempiereUserError("These opening balances are already posted");
        }
        if (!"SO".equals(h.get_ValueAsString("ZC_OBStatus"))) {
            throw new AdempiereUserError("Opening balances must be signed off before they are posted");
        }
        OpeningBalanceData data = new OpeningBalanceData(h, trx);
        OpeningBalance.Result r = data.check();
        if (!r.ok()) {
            throw new AdempiereUserError("The signed-off figures no longer pass the checks: " + String.join("; ", r.problems()));
        }
        Timestamp cutover = (Timestamp) h.get_Value("ZC_CutoverDate");
        String name = h.get_ValueAsString("Name");
        int charge = h.get_ValueAsInt("C_Charge_ID");
        int exemptTax = DB.getSQLValueEx(trx, "SELECT MIN(C_Tax_ID) FROM C_Tax WHERE AD_Client_ID=? AND IsTaxExempt='Y' "
                + "AND IsActive='Y' AND IsSummary='N'", h.getAD_Client_ID());
        if (exemptTax <= 0) {
            throw new AdempiereUserError("Set up a tax-exempt tax rate for opening invoices");
        }

        int invoices = 0;
        BigDecimal ar = BigDecimal.ZERO, ap = BigDecimal.ZERO;
        for (Item i : data.items) {
            if (i.type() != ItemType.RECEIVABLE && i.type() != ItemType.PAYABLE) {
                continue;
            }
            boolean so = i.type() == ItemType.RECEIVABLE;
            boolean credit = i.amount().signum() < 0;
            String base = so ? (credit ? MDocType.DOCBASETYPE_ARCreditMemo : MDocType.DOCBASETYPE_ARInvoice)
                    : (credit ? MDocType.DOCBASETYPE_APCreditMemo : MDocType.DOCBASETYPE_APInvoice);
            MInvoice inv = new MInvoice(getCtx(), 0, trx);
            inv.setAD_Org_ID(h.getAD_Org_ID());
            inv.setIsSOTrx(so);
            inv.setC_DocTypeTarget_ID(base);
            inv.setBPartner(MBPartner.get(getCtx(), data.itemPartners.get(i.lineNo())));
            int currency = MAcctSchema.get(data.schema).getC_Currency_ID();
            if (inv.getM_PriceList_ID() <= 0 || MPriceList.get(inv.getM_PriceList_ID()).getC_Currency_ID() != currency) {
                // the price list only sets the currency of a charge-only invoice: take the default, else any in the base currency
                int pl = DB.getSQLValueEx(trx, "SELECT M_PriceList_ID FROM M_PriceList WHERE AD_Client_ID=? AND IsActive='Y' "
                        + "AND IsSOPriceList=? AND C_Currency_ID=? ORDER BY IsDefault DESC, M_PriceList_ID FETCH FIRST 1 ROWS ONLY",
                        h.getAD_Client_ID(), so ? "Y" : "N", currency);
                if (pl <= 0) {
                    throw new AdempiereUserError("Set up a " + (so ? "sales" : "purchase") + " price list in the accounting currency");
                }
                inv.setM_PriceList_ID(pl);
            }
            Timestamp date = DB.getSQLValueTSEx(trx, "SELECT DateDoc FROM ZC_OpeningItem WHERE ZC_OpeningItem_ID=?",
                    data.itemIds.get(i.lineNo()));
            inv.setDateInvoiced(date != null ? date : cutover);
            inv.setDateAcct(cutover);
            inv.setPOReference(i.reference().length() > 20 ? i.reference().substring(0, 20) : i.reference());
            inv.setDescription("Opening balance " + name + ": " + i.reference());
            inv.saveEx();
            MInvoiceLine l = new MInvoiceLine(inv);
            l.setC_Charge_ID(charge);
            l.setQty(Env.ONE);
            l.setPrice(i.amount().abs());
            l.setC_Tax_ID(exemptTax);
            l.setDescription("Opening balance: " + i.reference());
            l.saveEx();
            inv.set_Attribute(ControlsEventHandler.OPENING_BALANCE, name);
            if (!inv.processIt(DocAction.ACTION_Complete)) {
                throw new AdempiereUserError("Opening invoice for item " + i.lineNo() + " (" + i.reference() + ") could not be "
                        + "completed: " + inv.getProcessMsg());
            }
            inv.saveEx();
            DB.executeUpdateEx("UPDATE ZC_OpeningItem SET C_Invoice_ID=? WHERE ZC_OpeningItem_ID=?",
                    new Object[] {inv.getC_Invoice_ID(), data.itemIds.get(i.lineNo())}, trx);
            invoices++;
            if (so) {
                ar = ar.add(i.amount());
            } else {
                ap = ap.add(i.amount());
            }
        }

        int statements = 0;
        for (Item i : data.items) {
            if (i.type() != ItemType.BANK) {
                continue;
            }
            MBankAccount bank = MBankAccount.get(data.itemBanks.get(i.lineNo()));
            MBankStatement st = new MBankStatement(bank);
            st.setAD_Org_ID(h.getAD_Org_ID());
            st.setName("Opening balance " + name);
            st.setStatementDate(cutover);
            st.setDateAcct(cutover);
            st.setDescription(i.reference());
            st.saveEx();
            MBankStatementLine sl = new MBankStatementLine(st);
            sl.setC_Currency_ID(bank.getC_Currency_ID());
            sl.setStatementLineDate(cutover);
            sl.setValutaDate(cutover);
            sl.setDateAcct(cutover);
            sl.setStmtAmt(i.amount());
            sl.setTrxAmt(BigDecimal.ZERO);
            sl.setC_Charge_ID(charge);
            sl.setChargeAmt(i.amount());
            sl.setDescription("Opening balance: " + i.reference());
            sl.saveEx();
            st.set_Attribute(ControlsEventHandler.OPENING_BALANCE, name);
            if (!st.processIt(DocAction.ACTION_Complete)) {
                throw new AdempiereUserError("Opening statement for item " + i.lineNo() + " (" + i.party() + ") could not be "
                        + "completed: " + st.getProcessMsg());
            }
            st.saveEx();
            DB.executeUpdateEx("UPDATE ZC_OpeningItem SET C_BankStatement_ID=? WHERE ZC_OpeningItem_ID=?",
                    new Object[] {st.getC_BankStatement_ID(), data.itemIds.get(i.lineNo())}, trx);
            statements++;
        }

        int clearing = data.clearingAccount(trx);
        List<JournalLine> lines = OpeningBalance.journal(data.lines, data.items, clearing, "clearing");
        MAcctSchema schema = MAcctSchema.get(data.schema);
        MJournal j = new MJournal(getCtx(), 0, trx);
        j.setAD_Org_ID(h.getAD_Org_ID());
        j.setC_AcctSchema_ID(data.schema);
        j.setC_Currency_ID(schema.getC_Currency_ID());
        j.setC_ConversionType_ID(MConversionType.getDefault(h.getAD_Client_ID()));
        MDocType[] dts = MDocType.getOfDocBaseType(getCtx(), MDocType.DOCBASETYPE_GLJournal);
        if (dts.length == 0) {
            throw new AdempiereUserError("No GL Journal document type is set up for this client");
        }
        j.setC_DocType_ID(dts[0].getC_DocType_ID());
        j.setGL_Category_ID(MGLCategory.getDefault(getCtx(), MGLCategory.CATEGORYTYPE_Manual).getGL_Category_ID());
        j.setDateDoc(cutover);
        j.setDateAcct(cutover);
        j.setPostingType(MJournal.POSTINGTYPE_Actual);
        j.setDescription("Opening balances " + name);
        j.saveEx();
        int no = 0;
        for (JournalLine jl : lines) {
            MJournalLine l = new MJournalLine(j);
            l.setLine(no += 10);
            l.setAD_Org_ID(h.getAD_Org_ID());
            l.setAccount_ID(jl.accountId());
            l.setAmtSourceDr(jl.amount().signum() > 0 ? jl.amount() : BigDecimal.ZERO);
            l.setAmtSourceCr(jl.amount().signum() < 0 ? jl.amount().negate() : BigDecimal.ZERO);
            l.setDescription(jl.accountId() == clearing ? "Control accounts posted by sub-ledger documents" : "Opening balance");
            l.saveEx();
        }
        j.set_Attribute(ControlsEventHandler.OPENING_BALANCE, name);
        j.set_Attribute(ControlsEventHandler.APPROVED_ELSEWHERE, "Opening balances " + name);
        if (!j.processIt(DocAction.ACTION_Complete)) {
            throw new AdempiereUserError("Opening journal could not be completed: " + j.getProcessMsg());
        }
        j.saveEx();

        h.set_ValueNoCheck("GL_Journal_ID", j.getGL_Journal_ID());
        h.set_ValueNoCheck("ZC_OBStatus", "PO");
        h.saveEx();
        return "Posted: journal " + j.getDocumentNo() + " (" + lines.size() + " lines), " + statements + " opening bank statements and "
                + invoices + " opening invoices "
                + "(receivables " + OpeningBalanceCheck.money(ar) + ", payables " + OpeningBalanceCheck.money(ap) + "). Load stock "
                + "and assets against the clearing account; Check and Reconcile shows its balance.";
    }
}
