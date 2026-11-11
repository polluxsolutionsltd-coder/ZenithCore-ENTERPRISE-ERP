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
- **Opening Balances** window (go-live).
  - Header: name, opening balance date, source and audit reference, and a **clearing charge** whose account must
    end at nil. Attach the legacy trial balance and sub-ledger CSV files (ZenithCore templates in
    `docs/phase1/opening-balance-templates/`) and press **Load Attached Files**: lines are mapped to the new chart
    from the file, the same code or an earlier mapping; customer, supplier and bank items take their own control
    account.
  - **Trial Balance** tab: legacy balance, adjustment and its reason, opening balance, audited balance, check result.
    **Sub-ledger Items** tab: customer invoices (AR), supplier invoices and credit notes (AP), bank and M-Pesa
    balances (BK), stock (ST), fixed assets (FA).
  - **Check and Reconcile**: every line mapped to a posting account, every adjustment with a reason, opening equal to
    audited to the cent, debits equal to credits, every control account with a balance equal to its sub-ledger items,
    items with a partner and a unique reference. Notes each problem on its line and prints the reconciliation PDF
    (sample: [docs/images/opening-balances.png](../docs/images/opening-balances.png)).
  - **Sign Off** by someone other than the preparer, only when the checks pass; locks everything.
  - **Post Opening Balances**, once: an opening invoice or credit memo per AR/AP item (original date for ageing,
    posted on the opening date, one line on the clearing charge, no ZenithCore procurement or withholding controls),
    an opening bank statement per BK item, and the opening journal (control accounts with items go to the clearing
    account). One transaction: if any document fails, nothing is posted. Stock and assets are loaded with iDempiere's
    own imports against the clearing account; after posting, Check and Reconcile shows the clearing balance.
- **Business units** (client setting `ZC_BUSINESS_UNIT_REQUIRED`). Business units are iDempiere's accounting dimension
  User List 1: an element of type User with one value per unit, added to the accounting schema as User List 1
  (configuration, no code). With the setting on, these must carry a business unit (on the line or its account
  combination):
  - GL journal lines on income and expenditure accounts;
  - customer invoice lines;
  - supplier invoice lines for charges and non-stocked items;
  - payments with a charge.
- **Board Packs** window: period, statement detail level (level of the chart tree to group by) and finance
  commentary.
  - **Generate Board Pack** builds the PDF from the ledger and attaches it as a new version. It contains:
    - cover with key figures;
    - commentary;
    - statement of financial performance (month, year to date, budget to date, favourable-positive variance,
      prior year);
    - statement of financial position (balanced through the accumulated surplus);
    - business-unit results, with income or expenditure not assigned to a unit shown separately;
    - budget execution by cost centre;
    - cash and bank (ledger balance, last statement, items not yet on a statement);
    - debtor and creditor ageing at the period end, with the largest balances;
    - audit queries open at the period end;
    - basis of preparation.
  - Budget totals compare a budget only with the lines it covers.
  - **Approve Board Pack**: someone other than the person who generated the version. It stamps that exact version
    "APPROVED FOR RELEASE" on every page, attaches it and locks the pack.
  - Samples: [cover](../docs/images/board-pack-cover.png), [statements](../docs/images/board-pack-statements.png),
    [business units](../docs/images/board-pack-units.png).
- **Fixed asset tags and verification** (on iDempiere's fixed asset register).
  - **Asset Locations** window: buildings and rooms, each with a responsible person. The Asset window gains tag
    number, date tagged, location, custodian, condition and last verified.
  - **Assign Asset Tags** (menu) gives each untagged active asset the next number with a Luhn check digit
    (`ZC_ASSET_TAG_PREFIX`, `ZC_ASSET_TAG_DIGITS`; for example JKIP-000123-0), so a mistyped tag is caught. Tags are
    never changed or reused.
  - **Print Asset Labels** (menu): 24 labels per A4 sheet (63.5 x 33.9 mm), each with a QR code of the tag, the
    institution, the asset, the tag in large type and the owner text (`ZC_ASSET_LABEL_TEXT`). Sample:
    [docs/images/asset-labels.png](../docs/images/asset-labels.png).
  - **Asset Verification** window: location (or all), group, verifier, board of survey reference.
    - **Load Assets** adds the assets with tag, expected location and custodian.
    - **Print Verification Sheets** gives one field sheet per location, with boxes for found here, elsewhere or not
      found and the condition, rows for assets not in the register, and signatures
      ([sample](../docs/images/asset-verification-sheet.png)).
    - Results go on the Assets tab.
    - **Complete Verification** checks every line: a result; a condition for assets seen; where an asset found
      elsewhere was found; the search done for assets not found; a description and a valid tag for assets not in the
      register; and a verifier who is not the custodian. It then records condition and date on each asset seen, moves
      assets found elsewhere in the register, locks the verification and prints the exceptions.
    - **Print Exceptions** gives four lists: not found, found elsewhere, poor or unserviceable for the board of
      survey, and not in the register ([sample](../docs/images/asset-verification-exceptions.png)).
- **Kenya setup for a new institution** (data in [setup/kenya](setup/kenya/README.md), checked by `SetupDataTest`):
  1. As System: **Initial Tenant Setup** with `setup/kenya/AccountingKE.csv` as the chart of accounts file,
     currency KES, country Kenya, and Activity Accounting ticked (cost centres for budgets). This creates the 54
     default accounts.
  2. As the tenant administrator:
     - **Import File Loader** with `AccountingKE.csv` and the format *Accounting - Accounts*;
     - then **Import Accounts** (Tenant and Element set, Update Default Accounts off) for the whole chart and its
       tree.
  3. **Apply Kenya Setup** (menu) adds:
     - VAT 16%, zero rated and exempt, on VAT output tax 21610 and VAT input tax 12610;
     - the charges ZenithCore uses (bank and M-Pesa charges, unidentified receipts, opening balance clearing, the
       withholding payables);
     - withholding types with dated rates.
     It is safe to run again, and it reports any rate that differs from the file.
  4. Open the periods (Period Control), and deactivate the "Standard" tax that Initial Tenant Setup creates if it is
     not used.
  Shipped as data files plus a process rather than a 2Pack, so rates are reviewable, dated and re-runnable. A 2Pack
  can still be exported from a configured tenant to clone it.
- **Expired stock and stock reports** (lots, serial numbers and expiry dates are iDempiere attribute sets: give
  the product an attribute set with Lot and Guarantee Date, or Serial No; the expiry date is the guarantee date of
  the lot):
  - **Expired stock control** (client setting `ZC_BLOCK_EXPIRED_ISSUE` = Y). A lot is usable up to and including
    its expiry date. Expired lots cannot be supplied on customer shipments, used on internal use (except written
    off with a write-off reason such as Expired) or moved into a normal location; moving them to a quarantine or
    rejected location is allowed. Shipment, internal use and movement lines for a product with expiry dates must
    name the lot, so the store issues the first to expire rather than letting iDempiere pick one.
  - **Stock Expiry Report** (menu, store and date): stock by lot, soonest expiry first, with days left and value,
    and totals for expired, within 30 days, 31-90, 91-180, over 180 days and no date
    ([sample](../docs/images/stock-expiry-report.png)).
  - **Slow and Non-moving Stock** (menu): items with no issue for `ZC_NON_MOVING_DAYS` (365, non-moving) or
    `ZC_SLOW_MOVING_DAYS` (90, slow), or with more than `ZC_SLOW_COVER_MONTHS` (6) months of cover. Issues are
    sales, issues to users and production; write-offs and transfers are not.
  - **Stock Wastage Report** (menu, period and store): write-offs (internal use and counts with a write-off
    reason) by reason, store and item at current cost, with every line; goods rejected on inspection shown
    apart, at the order price, since they go back to the supplier.
  - **Print Stock Labels** (menu, a goods received note or a store): one label per lot or serial number, 24 per
    A4 sheet like the asset labels, with the product, lot or serial number, expiry date and a QR code holding
    `P:<product code>|L:<lot>|S:<serial>|E:<yyyy-mm-dd>` (parts without a value are left out)
    ([sample](../docs/images/stock-labels.png)).
  Stock is valued at the current cost of the primary accounting schema, as on iDempiere's stock valuation.
- **Phase 1 roles and separation of duties** (data in [setup/roles](setup/roles/README.md), checked by `RoleSetupTest`):
  - **Set Up Phase 1 Roles** (menu, run by the institution's administrator): creates or updates nine manual roles
    (Store Keeper, Stock Counter, Inspection Committee, Procurement Officer, Cashier, Finance Officer, Finance
    Manager, Board Pack Approver, Asset Verifier). Each role gets the windows, forms, processes and document
    actions in the file. If any name is not found, nothing changes. It is safe to run again; access added by hand
    is kept and listed.
  - **Separation of duties** (client setting `ZC_ROLE_CONFLICTS` = Y): a role cannot be given to a user who holds a
    role it must be kept apart from, for example Store Keeper and Stock Counter. The refusal gives the reason.
  - **Roles and Duties Report** (menu): users holding conflicting roles, then each role with its users, what it
    must not do, and what it can do in this tenant, read from the role itself, with access added locally marked
    ([sample](../docs/images/roles-and-duties-report.png)). iDempiere gives its SuperUser support login every new
    role, so support logins are listed apart, with a go-live note.
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
- Opening balances, 10 November 2026 ("GardenWorld go-live (test)" at 1 Nov 2026, prepared by GardenAdmin): the two
  test CSV files were attached in the window and loaded (9 trial balance lines, 5 items). Check and Reconcile found
  the three planted problems: adjustment without a reason (1101), legacy account 1999 not mapped, payables
  sub-ledger 325,000.00 against 310,000.00 in the trial balance. With the reason entered, 1999 mapped to 15300 and the
  missing credit note CN/12 added, it reconciled (debits and credits 2,442,500.00). GardenAdmin's sign-off was refused
  (preparer); SuperUser signed off. The first post stopped with nothing written (no purchase price list; fixed to use
  any price list in the base currency) and then posted: invoices 200003 and 200004, supplier invoices 10000010 and
  10000011, credit memo 10000012 (original dates, accounted 1 Nov), an opening KCB statement of 850,000.00 and journal
  1008 (6 lines). A second post was refused. After the accounting processor ran, 11190 showed 850,000.00, 12110
  392,500.00, 21100 -310,000.00 and the clearing account 19990 nil.
- Board pack, 10 November 2026 (business unit dimension with Rentals, Workshops, Incubation; ZC_BUSINESS_UNIT_REQUIRED
  on):
  - Journal ZC-BP3 (salaries without a business unit) was refused ("line 10: set the business unit").
  - ZC-BP1 and ZC-BP2 completed, and the ledger carried their units.
  - "Board pack November 2026 (test)", version 1 generated by GardenAdmin: 4 pages, financial position balanced at
    3,256,516.96; business units Rentals 750,000.00 surplus, Workshops 60,000.00, Incubation (40,000.00), plus
    555,805.63 of earlier test expenditure not assigned. The first version set the whole expenditure against a
    partial budget (absorption 527.7%); fixed so totals compare the budget only with budgeted lines (variance
    (127,500.00)). Version 2 generated.
  - GardenAdmin's approval was refused (generated it). SuperUser approved; every page carries the approval stamp, and
    the Generate button is then disabled.
- Asset tags and verification, 10 November 2026 (8 test assets in 3 locations, prefix GW-):
  - Assign Asset Tags gave GW-000001-8 to GW-000008-3. The label sheet was printed, and all 8 QR codes decoded to the
    right tags with ZXing's reader from a 300 dpi image.
  - Verification "Block A, November 2026 (test)": Load Assets gave 4 assets and the sheet printed.
  - Results entered: found; found elsewhere (Boardroom); not found (with the search done); found in poor condition;
    plus an asset not in the register.
  - Completion was refused for all 4 assets (the verifier GardenUser is their custodian) and for the mistyped tag
    GW-000123-1. The refusal first said "you are the custodian" while GardenAdmin was completing; it now names the
    person verifying.
  - With SuperUser as verifier and the tag corrected to GW-000123-0, it completed: 2 found, 1 moved (the projector is
    now in the Boardroom in the register), 1 not found (register unchanged), 1 not in the register, 1 for the board
    of survey. Condition and date were recorded on the assets.
- Kenya setup, 10 November 2026: new tenant "JKUAT IP (test)" created as above.
  - Initial Tenant Setup: 54 default accounts, schema in KES.
  - Import Accounts: 195 accounts imported, 42 summary and 23 control; the CSV header line is rejected as expected.
  - Apply Kenya Setup added 19 records (3 VAT categories, 3 VAT rates, 5 charges, 3 withholding types, 5 rates); run
    again, it added 0.
  - Supplier invoice KE-T1 (bank charges 1,000.00 plus VAT 16%) posted Dr 53940 1,000.00, Dr 12610 VAT input
    160.00, Cr 21100 1,160.00.
  - The chart check in CI caught five account names with commas, which iDempiere's setup would have turned into
    spaces; renamed.
- Expiry, stock reports and labels, 10 November 2026 (test data in `testdata/garden-world-stock-expiry.sql`: four
  lots of Fertilizer #50, one expired, plus 40 units without a lot):
  - Stock Expiry Report: expired 180.00 (GW-L01), within 30 days 144.00, 91-180 days 216.00, over 180 days 360.00,
    no date 1,732.50 (the unlotted fertilizer and GardenWorld's Patio Chairs, whose attribute set also records a
    guarantee date).
  - Internal use ZC-T30 by GardenUser was refused for both lines: 2 of expired lot GW-L01 for "Issue to cost
    centre", and 1 unit with no lot. With the Expired reason on line 10 and lot GW-L02 on line 20 it completed.
  - Slow and Non-moving Stock: 19 non-moving items (8,044.97) and Fertilizer #50 slow on cover (1 issued in 12
    months; the 2 written off do not count as issues).
  - Stock Wastage Report for 2026: 271.85 written off on 4 lines (Damaged, Count variance, Expired), and 36.00
    rejected on receipt (Damaged), shown apart.
  - Print Stock Labels for HQ Warehouse: 4 lot labels; all 4 QR codes decoded with ZXing to the product, lot and
    expiry printed on them.
- Phase 1 roles, 11 November 2026 (GardenWorld; test users in `testdata/garden-world-roles.sql`, password = user name):
  - The first run of Set Up Phase 1 Roles refused and changed nothing: iDempiere 13's Cash Journal window is
    inactive (cash is a Bank/Cash account), so it was taken out of the Cashier role.
  - Second run: 9 roles created with 250 access records. Third run: 0 changes.
  - Logged in as each test user, the menu search found only that role's windows. For example, Counter found Blind
    Stock Count only, Cashier found Payment and Receipt only, and Verifier found Asset Verification only.
  - The report found that Store Keeper had been given Complete, Prepare and Void on Cost Adjustment, which shares
    the MMI base type with counts and internal use. The file now names MMI/PI and MMI/IU, the process lists
    document actions that are not in the file, and the report marks them. The three leftover actions were removed
    on the role.
  - With ZC_ROLE_CONFLICTS = Y, GardenAdmin's attempt to give Counter (Stock Counter) the Store Keeper role in the
    User window was refused ("Counter already holds Stock Counter, so cannot also be Store Keeper: a custodian
    would count their own store"). Giving Counter the Cashier role saved; it was then removed.
  - Roles and Duties Report: 1 user with conflicting roles, Buyer as Procurement Officer and Inspection Committee
    (planted by the test data). SuperUser, which holds every role, is listed apart as a support login.
- Not yet exercised in the UI: completing documents while logged in under each Phase 1 role (document action access
  per role is in place and listed on the report); blacklisted supplier, supplier payments (gate and budget), LPO completion under
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
`testdata/garden-world-mpesa-statement.csv` with the Import File Loader) and
`testdata/garden-world-opening-balances.sql` (clearing account and charge, a KCB test account, an opening balance
record; attach `testdata/opening-trial-balance.csv` and `testdata/opening-sub-ledgers.csv`) and `testdata/garden-world-board-pack.sql` (business-unit dimension, journals by unit,
a board pack record; restart the server after loading) and `testdata/garden-world-assets.sql` (tag settings, three
locations, eight untagged assets, a verification of Block A).

## Notes for developers

- The completion checks are one event handler, `ControlsEventHandler` (OSGi component listening to
  `DOC_BEFORE_COMPLETE`). Add new document controls there; put the rule in `zenithcore-controls` with tests.
- A ZenithCore process that has enforced its own approvals sets the PO attribute `ZC_ApprovedElsewhere` on the
  document before completing it (the payroll journal does).
- Windows on iDempiere tables must include the AD_Client_ID and AD_Org_ID fields (hidden is fine), or the
  records are read-only.
- Dictionary IDs 1091500–1091999.
