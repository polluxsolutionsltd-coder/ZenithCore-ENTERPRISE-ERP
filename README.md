# ZenithCore Enterprise ERP

ZenithCore is Pollux Solutions Ltd's ERP product for Kenyan institutions, built on the open-source [iDempiere](https://www.idempiere.org) platform with Pollux's own plug-ins for Kenyan payroll, KRA eTIMS and statutory returns. The first customer is JKUAT Industrial Park (JKUAT IP).

## Repository layout

| Path | What it holds |
| --- | --- |
| `zenithcore-payroll-ke/` | Kenyan payroll engine: PAYE, NSSF, SHIF, Affordable Housing Levy, NITA, HELB, reliefs, caps and the two-thirds deduction rule. Plain Java, no iDempiere dependency, so it can be tested on its own and wrapped as an iDempiere plug-in. |
| `zenithcore-idempiere-payroll/` | iDempiere 13 plug-in: runs the payroll engine inside iDempiere. First process: Kenyan Payslip Preview. Build and deploy steps in its README. |
| `zenithcore-controls/` | Phase 1 control rules: second-person approval, supplier pre-qualification gate, three-way match, audit-query closure. Plain Java with tests. |
| `zenithcore-idempiere-controls/` | iDempiere plug-in applying those rules when documents are completed, with the Document Approval Rules, Supplier Pre-qualification and Audit Query Register windows. |
| `zenithcore-idempiere-theme/` | ZenithCore look and feel for iDempiere: logo, brand colours, login page and browser title. |
| `docs/adr/` | Architecture decision records. |
| `docs/pending-critical.md` | Items that block a go-live and wait on outside parties. |
| `docs/phase1/` | Phase 1 Finance, Inventory and Procurement: standard iDempiere versus ZenithCore work, build order. |
| `docs/payroll/` | The Kenyan statutory rules the engine implements, with legal basis and what must be re-validated. |

Built so far in iDempiere: employee pay data, payroll run with pre-payroll checks, calculation, approval and GL posting, payroll account mapping per institution, PDF payslips and statutory schedules, arrears carried between payrolls, Payroll Officer / HR Head / Finance Head roles, establishment control (approved positions, headcount, salary ceilings), two-person reopen and reversal, and an establishment report. Phase 1 controls built: second-person approval rules, supplier pre-qualification gate, three-way match, the audit-query register, budgets by cost centre with a budget check at requisition, LPO, supplier invoice and payment, withholding income tax and withholding VAT on supplier invoices, stores controls (custodians, reason-coded adjustments, write-off approval), blind stock counts with a variance report, goods-received inspection with quarantine and rejected-stock locations, M-Pesa and bank statement matching with a bank reconciliation statement, the opening-balance workbench for go-live, the monthly board finance committee pack with business-unit accounts, fixed asset tags (QR labels) with physical verification, expired-stock control with expiry, slow-moving and wastage reports and lot QR labels, a Kenyan chart of accounts and tax setup for new institutions, and Phase 1 roles with a separation-of-duties check and a roles report for auditors. Payroll still to finish: official statutory upload files (CRIT-02), P9 certificates, emailed payslips. Remaining Phase 1 work and its order: [docs/phase1/finance-inventory-procurement.md](docs/phase1/finance-inventory-procurement.md).

## Pending critical items

- **CRIT-01, KRA eTIMS OSCU connector: on hold, critical.** Waiting for the KRA OSCU specification documents. Finance cannot go live without it.
- **CRIT-02, official upload layouts for payroll returns and the bank file: open, critical.** Waiting for the current KRA, NSSF, SHA, NITA, HELB and bank templates. Payroll can go live on the working schedules, with re-keying, but should not stay that way.

See [docs/pending-critical.md](docs/pending-critical.md).

## Build

Requires JDK 17 or later and Maven 3.9+.

```bash
cd zenithcore-payroll-ke && mvn -B verify
cd ../zenithcore-controls && mvn -B verify
```

GitHub Actions runs the same build on every push and pull request.

## Delivery plan

Phase 1 for JKUAT IP: Finance & Accounting, HR & Kenyan Payroll, Inventory & Stores and Procurement (light), followed by Phase 2 modules. See the implementation proposal shared with Pollux for scope and timeline.

## Status

Early development. Statutory rates are held as dated data and must be validated against current KRA, NSSF and SHA guidance before any production payroll.
