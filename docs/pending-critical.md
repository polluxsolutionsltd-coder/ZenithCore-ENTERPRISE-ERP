# Pending critical items

Items that block a go-live and are waiting on something outside Pollux. Review these at every project board meeting.

| ID | Item | Status | Blocks | Waiting on |
| --- | --- | --- | --- | --- |
| CRIT-01 | KRA eTIMS OSCU connector | **On hold, critical** (recorded 9 Nov 2026) | Finance go-live (week 26 of the Phase 1 plan) | KRA OSCU specification documents |

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
