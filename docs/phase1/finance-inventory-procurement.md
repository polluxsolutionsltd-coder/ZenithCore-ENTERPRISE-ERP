# Phase 1: Finance, Inventory & Stores, Procurement (light)

What the proposal commits to (section 3), and where each item comes from: iDempiere as delivered
(configured, not coded), or a ZenithCore plug-in. Status as of 10 November 2026.

Legend: **iDempiere** = standard feature, needs configuration and testing only. **ZenithCore** = Pollux builds it.

## Controls built so far (`zenithcore-controls`, `zenithcore-idempiere-controls`)

| Proposal item | How | Status |
| --- | --- | --- |
| Second-person approval on every journal (and other documents by amount) | ZenithCore: Document Approval Rules + completion check | Built, verified (GL journal) |
| Pre-qualification gate on the supplier register; blacklist flag | ZenithCore: pre-qualification fields, window, gate on LPO, supplier invoice, supplier payment | Built, verified (supplier invoice) |
| Three-way match of LPO, goods received note and invoice | ZenithCore: check on supplier invoice completion, price tolerance per client | Built, verified |
| Audit-query register, tracked to closure with evidence | ZenithCore: register window, Close Audit Query with evidence and second person | Built, verified |
| Budgets by cost centre, budget check at requisition, LPO and payment voucher | ZenithCore: Budgets window with approval, check on completion, Budget Status Report | Built, verified (LPO, invoice) |
| Stores: named custodians, store-level access, reason-coded adjustments, write-off approval | ZenithCore: custodian and deputy per store, Stock Adjustment Reasons, checks on receipts, shipments, movements, counts and internal use | Built, verified (counts, receipt) |
| Blind counts on a tablet browser; variance report by item, store and cause | ZenithCore: Blind Stock Count window, Submit Count, Stock Count Variance Report | Built, verified |
| Goods received with inspection and rejection; quarantine locations | ZenithCore: locator use (normal, quarantine, rejected), Goods Inspection window, put-away movement, three-way match on accepted quantities | Built, verified |
| M-Pesa and bank statement import, matching and reconciliation | Import: iDempiere Import File Loader + Import Bank Statement, one loader format per bank or M-Pesa export (data). ZenithCore: Match Statement, statement matching rules, transaction reference on payments with duplicate check, completion check, Bank Reconciliation Statement | Built, verified (test layout); real loader formats wait for sample exports (CRIT-02) |
| Opening-balance clean-up workbench | ZenithCore: Opening Balances window (trial balance and sub-ledger CSV templates, mapping to the new chart, adjustments with reasons, audited figures, control accounts against sub-ledgers), reconciliation PDF, sign-off by a second person, posting of opening invoices, bank statements and journal through a clearing account | Built, verified; stock and assets are reconciled here and loaded with iDempiere's imports |
| VAT and withholding tax | VAT: iDempiere taxes (configure 16%, zero-rated, exempt). Withholding income tax and withholding VAT: ZenithCore dated rates, deduction lines on supplier invoices, register | Built, verified (resident professional fees, WHVAT); iTax layouts wait on CRIT-02 |

## Remaining Phase 1 work, in build order

| # | Item | How | Notes |
| --- | --- | --- | --- |
| 1 | Board pack generator, business-unit accounts | ZenithCore report on iDempiere financial reports | Business units as a reporting dimension (User List 1 or Activity) |
| 2 | Fixed asset tags and printed verification sheets | iDempiere assets + ZenithCore labels and sheets | |
| 3 | Kenyan chart of accounts and setup template (2Pack) | ZenithCore data | Loaded per institution |
| 4 | Expiry, slow-moving and wastage reports; batch, serial, expiry, QR labels | iDempiere + ZenithCore reports | |

## Standard iDempiere, configuration only

General ledger with dimensions, recurring and reversing journals, period locks, multi-currency (KES base);
accounts payable and receivable, credit limits, ageing, statements; cash, bank and petty cash; requisitions,
LPO/LSO with approval amounts per role; item master with classes, units, reorder levels; stores and bins;
transfers; weighted-average and FIFO costing posted to the ledger; trial balance and financial statements.
