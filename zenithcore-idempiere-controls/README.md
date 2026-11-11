# ZenithCore Controls for iDempiere

OSGi plug-in with the Phase 1 finance and procurement controls. The rules themselves are in
`zenithcore-controls` (plain Java, tested in CI); this plug-in applies them when documents are completed.

## What it does today

All checks run when a document is completed. A blocked document keeps its status (iDempiere shows it as
Invalid) and the user sees every reason at once. Reversals are not checked.

- **Document Approval Rules** window (menu: ZenithCore → Finance and Procurement Controls). One rule per
  kind of document (GL journal, supplier invoice, supplier payment, LPO, requisition, stock count, stock
  movement, bank statement) with a "from amount". The person who created such a document cannot complete it
  from that amount upwards. Payroll journals are exempt because the payroll run already needs three people.
- **Supplier pre-qualification gate** (client setting `ZC_SUPPLIER_GATE` = Y). LPOs, supplier invoices and
  supplier payments are blocked for a supplier who is not pre-qualified on the document date, whose
  pre-qualification has lapsed, whose KRA tax compliance certificate is missing or expired, or who is
  blacklisted. Employees are not checked.
  - **Supplier Pre-qualification (Kenya)** window: status (Pending, Pre-qualified, Suspended, Blacklisted),
    pre-qualified-until date, tax compliance certificate number and expiry, blacklist reason. Attach the
    supporting documents to the record. The same fields show read-only on the Business Partner window's
    Vendor tab, so only people with this window can change them.
- **Three-way match** (client setting `ZC_THREE_WAY_MATCH` = Y, tolerance `ZC_THREE_WAY_PRICE_TOLERANCE_PCT`).
  Every supplier invoice line must come from an LPO line; the quantity invoiced, with earlier invoices on the
  same LPO line, cannot exceed the quantity on completed goods received notes; and the price cannot exceed
  the LPO price plus the tolerance. Service items need the LPO line and price check only.
- **Budget check** (client setting `ZC_BUDGET_CONTROL` = Y, tolerance `ZC_BUDGET_TOLERANCE_PCT`). Requisitions,
  LPOs, supplier invoice lines that are not from an LPO, and direct payments against a charge are blocked if,
  for any cost centre and account they charge, the approved budget for the financial year no longer covers
  what has been spent, what is committed and this document. Every line needs a cost centre (iDempiere's
  Activity; requisitions get a Cost centre field on the header).
  - Spent: actual postings in the year on the cost centre and account. Committed: completed requisition lines
    not yet on an LPO, and completed LPO lines not yet invoiced. When an LPO is invoiced its amount moves from
    committed to spent, so nothing is counted twice.
  - The account of a line is where it will post: the charge's account, or the expense account of a service or
    non-stocked item. Items kept in stock are not budget-controlled (they go to inventory; stores controls apply).
  - **Budgets (Kenya)** window: a budget per financial year with lines by cost centre and account. **Approve
    Budget** needs a person other than the preparer and locks the budget; only approved budgets count. A
    supplementary budget is another approved budget for the same year; the lines add up.
  - **Budget Status Report** (menu): budget, spent, committed, available and percentage used by cost centre and
    account for a year, calculated exactly as the check calculates them. PDF. Sample:
    [docs/images/budget-status-report.png](../docs/images/budget-status-report.png).
- **Withholding** (client setting `ZC_WITHHOLDING` = Y; `ZC_WHVAT_AGENT` = Y if the institution is an appointed
  withholding VAT agent). When a supplier invoice is prepared, ZenithCore adds deduction lines for withholding
  income tax by type of payment and for withholding VAT on standard-rated lines, so the supplier's payable is the
  net amount and the tax sits on its payable account until remitted. Preparing again recalculates the lines.
  - **Withholding Tax Rates (Kenya)** window: types (code, type of payment, income tax or VAT, payable charge) and
    their dated resident and non-resident rates, with a monthly threshold for residents and the legal basis. Rates
    are data: a change in law is a new dated rate.
  - The type of a line is its own Withholding type, else its product's, else its charge's. Supplier flags (on
    Supplier Pre-qualification): non-resident, VAT-registered, withholding tax exemption, withholding VAT exemption.
  - Resident monthly threshold: no withholding while the supplier's total of that type in the month, this invoice
    included, is at or below the threshold. When an invoice takes the month over it, the working says to check
    earlier invoices that month.
  - Three-way match and the budget check ignore deduction lines.
  - **Withholding Tax Register** (menu): every deduction on completed supplier invoices in a month with supplier
    PIN, base, rate, amount and payment date, totals by type. CSV. Withholding is due within five working days
    after payment. This is a working register; the iTax upload layouts wait on CRIT-02.
  - Set the accounting schema not to allow negative postings, so deduction lines post as credits to the payable
    accounts rather than negative debits.
- **Stores controls** (client setting `ZC_STORES_CONTROL` = Y; write-off approval from `ZC_WRITEOFF_APPROVAL_FROM`).
  - Each store (warehouse) has a **Custodian** and a **Deputy custodian** (Warehouse window). Only they can complete
    a goods received note or shipment for the store, move stock out of it, or prepare its stock counts and
    adjustments. A store without a custodian cannot be received into, issued from or adjusted.
  - **Stock Adjustment Reasons** window: codes such as count variance, damaged, expired, theft or loss, obsolete
    (write-offs) and issue to cost centre, data correction (not write-offs). Every count difference and every
    internal-use line needs a reason (Reason field on the count and internal-use lines).
  - Write-offs (stock removed for a write-off reason, at current cost) at or above the set value must be completed
    by someone who is neither the preparer nor a custodian of the store. Cost adjustment documents are not affected.
- **Blind stock counts** (client setting `ZC_BLIND_COUNT` = Y).
  - **Blind Stock Count** window (works in a tablet browser): open counts with their lines showing locator, item,
    attribute and unit, and a **Counted** field. Book quantities are not on the screen, and count sheets start
    empty. Each count records who counted it and when. Items found that are not on the sheet can be added.
    Screenshot: [docs/images/blind-count-sheet.png](../docs/images/blind-count-sheet.png).
  - **Submit Count** needs every line counted (0 for an item not there) and, with blind counting on, no line counted
    by the store's custodian or deputy. It takes the book quantity at that moment and the counted quantities into
    the count, and locks the lines; counts cannot be changed afterwards.
  - The custodian then gives a reason for each difference (Physical Inventory window) and the count is posted under
    the stores controls (write-off approval). A count that was not submitted cannot be posted.
  - **Stock Count Variance Report** (menu): every difference in submitted and posted counts in a period, by item,
    with book, counted, difference, value at current cost, reason and counter, then totals by store and by cause.
    PDF. It shows book quantities, so it is for custodians and Finance, not counters. Sample:
    [docs/images/count-variance-report.png](../docs/images/count-variance-report.png).
  - Counters should have the Blind Stock Count window only, not Physical Inventory (which shows book quantities).
- **Goods received inspection** (client setting `ZC_GRN_INSPECTION` = Y).
  - Each locator has a **Locator use**: normal, quarantine or rejected (Warehouse window, Locator tab). Every
    stocked line of a supplier goods received note must go to a quarantine locator.
  - Stock in quarantine or rejected locators cannot be shipped to customers, used internally or moved by an ordinary
    movement. Only the put-away movement of an inspection moves it.
  - **Goods Inspection** window: pick a completed goods received note, **Load Receipt Lines**, then enter accepted
    and rejected quantities and a reason for every rejection (damaged, not to specification, expired, short or
    excess, other). **Complete Inspection** needs accepted + rejected = received on every line, and an inspector
    who neither recorded the goods received note nor is a custodian of the store. It creates a draft put-away
    movement (accepted to the store's default locator, rejected to its rejected locator) for the custodian to
    complete, and locks the inspection. Screenshot: [docs/images/goods-inspection.png](../docs/images/goods-inspection.png).
  - With the setting on, the three-way match counts accepted quantities from completed inspections, not received
    quantities, so a supplier is paid only for what was accepted. Return rejected stock with an iDempiere vendor return.
- **M-Pesa and bank statements** (client settings `ZC_BANK_MATCHING`, `ZC_BANK_MATCH_DAYS`, `ZC_BANK_CREATE_RECEIPTS`).
  - Each M-Pesa paybill or till and each bank account is an iDempiere bank account (bank marked own bank) with its
    own bank asset and in-transit GL accounts.
  - Import: iDempiere **Import File Loader** with one **Import Loader Format** per bank or M-Pesa export, then
    **Import Bank Statement**. The format is data: map the transaction reference (M-Pesa receipt code) to EFT Trx ID,
    the account number the payer typed to EFT Reference, the payer to EFT Payee, the narrative to Line Description,
    and the amount to both Statement Amount and Transaction Amount. Real formats wait for sample exports (CRIT-02);
    GardenWorld has a test layout only. The import refuses a transaction ID it has already imported.
  - Payments and receipts have a **Transaction reference** (M-Pesa code, EFT or RTGS reference; iDempiere's Check No
    holds digits only). With matching on, the same reference cannot be completed twice on one account.
  - **Match Statement** (button on Bank Statement) works on lines without a payment or charge, in passes: same
    reference and amount; **Statement Matching Rules** (text in the narrative, for example Pay Bill Charge, to a
    charge); the only payment with the same amount within the window (narrowed to the customer named in the account
    reference); then, with `ZC_BANK_CREATE_RECEIPTS` = Y, a completed receipt for money in whose account reference is
    an open invoice number (up to its open amount) or a customer code (on account). Each line records how it matched
    and why (Matched, Match note), and lines for review say what to check. Screenshot:
    [docs/images/statement-matching.png](../docs/images/statement-matching.png).
  - A statement cannot be completed while any line with a transaction amount has no payment (`ZC_BANK_MATCHING` = Y).
    Unidentified money in is set to a suspense charge by the cashier.
  - **Bank Reconciliation Statement** (menu): balance per last completed statement, less payments not yet presented,
    plus receipts not yet credited, against the cash book (GL balance of the account's asset and in-transit
    accounts), with the unexplained difference and signature blocks. Warns if the GL accounts are shared with another
    bank account or documents are unposted. Sample: [docs/images/bank-reconciliation.png](../docs/images/bank-reconciliation.png).
- **Audit Query Register** window: reference, title, raised by (external or internal audit, board, KRA),
  financial year, risk, finding, recommendation, responsible person, due date, management response, status.
  **Close Audit Query** needs the management response, at least one evidence file attached, and a closer who
  is not the responsible person. Closing locks the record.

Verified on 10 November 2026 in iDempiere 13 (GardenWorld), through the web UI:
- GL journal ZC-T4, prepared by GardenAdmin: GardenAdmin's completion was refused ("you prepared GL Journal
  ZC-T4, so a different person must complete it"); SuperUser completed it.
- Supplier invoice ZC-T3, Seed Farm (pending, no certificate), line not on an LPO: refused with all three
  reasons.
- Supplier invoice ZC-T2, Patio Fun, Patio Chair at 40.00 against an LPO price of 33.75 (2% tolerance):
  refused on price. ZC-T1 (three lines of LPO 800002, quantities received, LPO prices) completed. ZC-T2 then
  also failed on quantity ("60 invoiced (including 30 on earlier invoices) but only 30 received").
  Screenshot: [docs/images/three-way-match-blocked.png](../docs/images/three-way-match-blocked.png).
- Audit query ML 2024/25-07: closing without response or evidence was refused; with both, GardenAdmin (the
  responsible person) was refused; SuperUser closed it, record locked.
- A payroll posted by the Finance Head still completed its GL journal with the journal rule on.
- Budget check, 10 November 2026: LPO ZC-T5 (Workshop, Freight 30,000) was refused while the budget was
  unapproved ("no approved budget for WS Workshop / 54000 Freight in"). GardenAdmin could not approve the budget
  they prepared; SuperUser approved it (60,000.00). ZC-T5 then completed. ZC-T6 (Freight 25,000) was refused:
  "budget 50,000.00, spent 0.00, committed 30,000.00, available 20,000.00; Purchase Order ZC-T6 (2026) needs
  25,000.00, over by 5,000.00". ZC-T7 was refused for a line without a cost centre and ZC-T8 for a cost centre
  and account with no budget. After supplier invoice ZC-T9 for ZC-T5 was completed and posted, the report and
  the check showed the 30,000 as spent instead of committed, and ZC-T6 was still refused by 5,000.
- Withholding, 10 November 2026: LSO ZC-T10 (Patio Fun, Commissions 100,000, marked professional fees) and its
  invoice ZC-T11 (GST 7% standing in for VAT 16%). Preparing the invoice added "Professional fees 5% of 100,000.00"
  (-5,000.00) and "Withholding VAT 2% of 100,000.00" (-2,000.00): lines 93,000.00, tax 7,000.00, payable
  100,000.00. Posted: expense 100,000, input tax 7,000, payable 100,000, withholding tax 5,000 and withholding VAT
  2,000 on their accounts. The Withholding Tax Register for Nov-26 listed both with totals. Thresholds, non-resident
  rates, exemptions and unregistered suppliers are covered by the rule tests, not yet in the UI.
- Stores, 10 November 2026 (HQ Warehouse, custodian GardenUser, write-off approval from 100.00): count ZC-T12 with a
  gain and no reason was refused; count ZC-T13 (2 Oak Trees damaged, 104.19 at average cost) was refused for
  GardenUser ("writes off 104.19 ... must be approved by someone other than the preparer and the custodians") and
  completed by GardenAdmin; count ZC-T14 prepared by GardenAdmin was refused (not prepared by the custodian);
  receipt ZC-T15 was refused for GardenAdmin and completed by GardenUser. Movements and deputies are covered by the
  rule tests, not yet in the UI.
- Blind counts, 10 November 2026: count sheet ZC-T16 (HQ Warehouse, four items, prepared by GardenUser, the
  custodian). GardenUser entered two counts and Submit Count was refused (two lines not counted; two counted by a
  custodian). GardenAdmin counted Oak Tree 17, Elm Tree 15, Holly Bush 9; submit was refused while Azalea Bush was
  uncounted, then accepted with Azalea Bush 6: book quantities 19, 15, 5, 10 taken at submission. Posting ZC-T17,
  never submitted, was refused. With reasons given, GardenAdmin posted ZC-T16. The variance report for November
  showed the differences at cost by store and cause (net -222.63, including the earlier ZC-T13).
- Goods inspection, 10 November 2026 (HQ Warehouse with HQ Quarantine and HQ Rejected locators): receipt ZC-T18
  into the normal locator was refused ("must be received into a quarantine location"). LPO ZC-T20 (10 Holly Bush
  from Patio Fun at 18.00) and its receipt ZC-T19 into quarantine were completed by GardenUser (custodian). Invoice
  ZC-T21 for 10 was refused ("10 invoiced but only 0 received"), and internal use ZC-T22 from quarantine was
  refused ("not yet inspected"). Inspection ZC-I1 loaded the line; with 8 accepted and 2 rejected and no reason,
  completion was refused; with reason Damaged, GardenAdmin (who recorded the receipt) was refused; SuperUser
  completed it, creating put-away movement 10000000 (8 to Default HQ Locator, 2 to HQ Rejected), which GardenUser
  completed. ZC-T21 for 10 was then refused ("only 8 received") and completed for 8.
- Bank matching, 10 November 2026 (M-Pesa paybill test account with its own GL accounts): receipts ZC-R1 (1,500.00,
  QK12ABC3DE), ZC-R2 (2,000.00, no reference), ZC-R3 (700.00) and payment ZC-P1 (12,000.00) completed. A six-line test
  CSV was loaded with the Import File Loader and imported as statement ZC-S1. Completing it was refused (line 10 not
  matched). Match Statement: line 10 by reference to ZC-R1, line 20 (Pay Bill Charge, -33.00) to Bank Charge by rule,
  line 30 by amount and date to ZC-R2, line 40 (account 200002) a receipt of 100.00 against invoice 200002 (open
  161.12), line 50 (account joeblock) a receipt of 3,000.00 on Joe Block's account, line 60 (account XYZ123) not
  matched. Completion was refused for line 60; with line 60 set to the unidentified-receipts charge it completed.
  Receipt ZC-R4 repeating QK12ABC3DE in lower case was refused. Bank Reconciliation Statement at 10 Nov: bank
  7,017.00, unpresented 12,000.00 (ZC-P1), uncredited 700.00 (ZC-R3), adjusted -4,283.00, cash book -4,283.00,
  reconciled. For bank account 1234 it warned that the GL accounts are shared.
- Not yet exercised in the UI: blacklisted supplier, supplier payments (gate and budget), LPO completion under
  the gate, requisitions and supplier invoices not from an LPO under the budget check (same code paths, covered
  by the rule tests).

## Build and deploy

```bash
cd zenithcore-controls && mvn -B install
cd ../zenithcore-idempiere-controls && mvn -B verify -Didempiere.home=/path/to/idempiere-server
```

Deploy like the payroll plug-in (both jars into `plugins/`, `bundles.info`, clear the OSGi cache), then apply
`migration/postgresql/202611100800_ZC-0004_Controls.sql` (generated by `tools/gen_dictionary.py`; never edit the
SQL by hand). On GardenWorld only: `migration/postgresql/demo-garden-world-access.sql` and
`testdata/garden-world-controls.sql` (rules, switches, sample suppliers, draft documents, an audit query) and
`testdata/garden-world-budget.sql` (cost centres, a budget to approve, draft LPOs and an invoice) and
`testdata/garden-world-withholding.sql` (payable charges, types and rates, an LSO and its invoice) and
`testdata/garden-world-stores.sql` (custodian, reasons, three counts and a receipt) and
`testdata/garden-world-blind-count.sql` (blind counting on, two count sheets) and
`testdata/garden-world-inspection.sql` (inspection on, quarantine and rejected locators, an LPO, two receipts, an
invoice, an internal use and an inspection) and `testdata/garden-world-bank.sql` (matching on, an M-Pesa test account
with its own GL accounts, a statement rule, a test loader format, receipts and a payment; load
`testdata/garden-world-mpesa-statement.csv` with the Import File Loader).

## Notes for developers

- The completion checks are one event handler, `ControlsEventHandler` (OSGi component listening to
  `DOC_BEFORE_COMPLETE`). Add new document controls there; put the rule in `zenithcore-controls` with tests.
- A ZenithCore process that has enforced its own approvals sets the PO attribute `ZC_ApprovedElsewhere` on the
  document before completing it (the payroll journal does).
- Windows on iDempiere tables must include the AD_Client_ID and AD_Org_ID fields (hidden is fine), or the
  records are read-only.
- Dictionary IDs 1091500–1091999.
