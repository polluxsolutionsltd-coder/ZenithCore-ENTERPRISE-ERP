# ZenithCore Enterprise ERP

ZenithCore is Pollux Solutions Ltd's ERP product for Kenyan institutions, built on the open-source [iDempiere](https://www.idempiere.org) platform with Pollux's own plug-ins for Kenyan payroll, KRA eTIMS and statutory returns. The first customer is JKUAT Industrial Park (JKUAT IP).

## Repository layout

| Path | What it holds |
| --- | --- |
| `zenithcore-payroll-ke/` | Kenyan payroll engine: PAYE, NSSF, SHIF, Affordable Housing Levy, NITA, HELB, reliefs, caps and the two-thirds deduction rule. Plain Java, no iDempiere dependency, so it can be tested on its own and wrapped as an iDempiere plug-in. |
| `zenithcore-idempiere-payroll/` | iDempiere 13 plug-in: runs the payroll engine inside iDempiere. First process: Kenyan Payslip Preview. Build and deploy steps in its README. |
| `zenithcore-idempiere-theme/` | ZenithCore look and feel for iDempiere: logo, brand colours, login page and browser title. |
| `docs/adr/` | Architecture decision records. |
| `docs/pending-critical.md` | Items that block a go-live and wait on outside parties. |
| `docs/payroll/` | The Kenyan statutory rules the engine implements, with legal basis and what must be re-validated. |

Built so far in iDempiere: employee pay data, payroll run with pre-payroll checks, calculation, approval and GL posting, payroll account mapping per institution, PDF payslips and statutory schedules, arrears carried between payrolls, Payroll Officer / HR Head / Finance Head roles, establishment control (approved positions, headcount, salary ceilings), two-person reopen and reversal, and an establishment report. Planned modules (not yet started): official statutory upload files (CRIT-02), P9 certificates, statutory return files, Phase 1 control plug-ins (supplier pre-qualification gate, audit-query register, opening-balance workbench, board pack).

## Pending critical items

- **CRIT-01, KRA eTIMS OSCU connector: on hold, critical.** Waiting for the KRA OSCU specification documents. Finance cannot go live without it.
- **CRIT-02, official upload layouts for payroll returns and the bank file: open, critical.** Waiting for the current KRA, NSSF, SHA, NITA, HELB and bank templates. Payroll can go live on the working schedules, with re-keying, but should not stay that way.

See [docs/pending-critical.md](docs/pending-critical.md).

## Build

Requires JDK 17 or later and Maven 3.9+.

```bash
cd zenithcore-payroll-ke
mvn -B verify
```

GitHub Actions runs the same build on every push and pull request.

## Delivery plan

Phase 1 for JKUAT IP: Finance & Accounting, HR & Kenyan Payroll, Inventory & Stores and Procurement (light), followed by Phase 2 modules. See the implementation proposal shared with Pollux for scope and timeline.

## Status

Early development. Statutory rates are held as dated data and must be validated against current KRA, NSSF and SHA guidance before any production payroll.
