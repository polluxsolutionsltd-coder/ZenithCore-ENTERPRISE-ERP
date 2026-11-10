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
`testdata/garden-world-withholding.sql` (payable charges, types and rates, an LSO and its invoice).

## Notes for developers

- The completion checks are one event handler, `ControlsEventHandler` (OSGi component listening to
  `DOC_BEFORE_COMPLETE`). Add new document controls there; put the rule in `zenithcore-controls` with tests.
- A ZenithCore process that has enforced its own approvals sets the PO attribute `ZC_ApprovedElsewhere` on the
  document before completing it (the payroll journal does).
- Windows on iDempiere tables must include the AD_Client_ID and AD_Org_ID fields (hidden is fine), or the
  records are read-only.
- Dictionary IDs 1091500–1091999.
