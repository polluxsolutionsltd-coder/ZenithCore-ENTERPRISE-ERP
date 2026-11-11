# Pending critical items

Items that block a go-live and are waiting on something outside Pollux. Review these at every project board meeting.

| ID | Item | Status | Blocks | Waiting on |
| --- | --- | --- | --- | --- |
| CRIT-01 | KRA eTIMS OSCU connector | **On hold, critical** (recorded 9 Nov 2026) | Finance go-live (week 26 of the Phase 1 plan) | KRA OSCU specification documents |
| CRIT-02 | Official upload layouts for payroll returns and the bank file | **Open, critical** (recorded 9 Nov 2026) | Payroll go-live (week 37 of the Phase 1 plan) | Current templates from KRA, NSSF, SHA, NITA, HELB and JKUAT IP's bank |

## CRIT-01: KRA eTIMS OSCU connector

**Why it is critical.** Every customer invoice raised in ZenithCore must be signed and transmitted to KRA through eTIMS. eTIMS has no file mode, so Finance cannot go live without a certified, live connection. KRA sandbox testing, vetting and certification is the longest external dependency in the Phase 1 plan.

**What unblocks it.**
- [ ] KRA OSCU (and VSCU) specification documents, current version
- [ ] KRA Trader Invoicing System technical specification
- [ ] eTIMS sandbox account and credentials for Pollux
- [ ] Pollux registration as an eTIMS third-party integrator (bio-data form and supporting documents)

When the documents arrive, add them under `docs/etims/` and start the work below.

**Work once unblocked.**
1. New module `zenithcore-etims-ke` (standalone Java, no iDempiere dependency), following the same conventions as the payroll engine.
2. Device initialisation and code-list synchronisation as the specification requires.
3. Send invoices and credit notes; store the control-unit invoice number, receipt signature and QR data on the invoice.
4. Queue and retry when KRA cannot be reached, so invoicing continues during outages; alert Finance when items stay queued.
5. Full message log for audit.
6. Tests against the specification's examples, then the KRA sandbox; then KRA vetting and certification.
7. iDempiere plug-in that completes the invoice only after signing, or queues it.

**Timing.** Start no later than week 6 after contract signature to protect the Finance go-live in week 26.

**Fallback.** If certification is late, connect through an existing KRA-approved third-party integrator for go-live and switch to the ZenithCore connector once certified.

**Owner.** Pollux Solutions Ltd.

## CRIT-02: Official upload layouts for payroll returns and the bank file

**Why it is critical.** Every month the employer must file and pay PAYE (iTax P10), NSSF, SHIF, the Affordable Housing Levy, NITA and HELB, and pay net salaries through the bank. ZenithCore already produces a working schedule for each (Payroll Run → Statutory Schedules) with the right figures and totals, but not in the exact column layout each portal or bank imports. Without the official layouts, payroll staff must re-key figures into the templates every month, which is slow and error-prone.

**What unblocks it.** The current version of each template, added under `docs/payroll/templates/`:
- [ ] KRA iTax P10 (PAYE) return template, current version
- [ ] KRA iTax withholding income tax and withholding VAT upload templates (for the Withholding Tax Register)
- [ ] KRA iTax VAT return (VAT3) template
- [ ] NSSF employer contributions upload format (NSSF Act 2013 tiers)
- [ ] SHA employer SHIF contributions upload format
- [ ] Affordable Housing Levy return format (if separate from the P10)
- [ ] NITA levy return format
- [ ] HELB employer remittance schedule
- [ ] Bulk salary payment file specification from JKUAT IP's bank (and each future client's bank)
- [ ] Sample statement exports (CSV or Excel, a few real but anonymised lines) of JKUAT IP's M-Pesa paybill or till and each bank account, to set up the Import Loader Format for each (statement matching is built; only the formats are missing). Needed for Finance go-live (week 26)

**Work once unblocked.** Hold each layout as dated data (columns, order, formats, validation), the same way statutory rates are held, so a new template version is a data change. Generate each file from the approved payroll lines, test it against the portal's validation or the bank's test environment, and keep the working schedules for reconciliation. Do not build a layout from guesses.

**Owner.** Pollux Solutions Ltd, with JKUAT IP payroll for the bank specification.
