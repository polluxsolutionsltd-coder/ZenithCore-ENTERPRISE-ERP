# ZenithCore Enterprise ERP: working notes for contributors

## Product
- ERP product by Pollux Solutions Ltd for Kenyan institutions; first customer JKUAT Industrial Park.
- Platform: iDempiere (GPLv2, Java, OSGi plug-ins, PostgreSQL, ZK web UI). Never modify iDempiere core; all ZenithCore code lives in plug-ins or standalone modules.
- No third-party licence fees. Clients pay Pollux for implementation, support and hosting.

## Code conventions
- Java 17 language level (runs on 17 and 21). Base package `ke.co.pollux.zenithcore`.
- Money is always `BigDecimal`, scale 2, `RoundingMode.HALF_UP`, rounded per statutory component. Never `double` for amounts.
- Statutory values (rates, bands, caps, thresholds) are data in dated rate sets, never constants inside calculation code. A change in law must be a data change.
- Calculation code returns a full breakdown plus a human-readable trace, so payroll staff and auditors can see how each figure was reached.
- Every rule has a test, including boundary tests (exact threshold, one cent over).

## Payroll engine (`zenithcore-payroll-ke`)
- No iDempiere dependency. The iDempiere plug-in will call it.
- Worked-payslip tests in `KenyaPayrollEngineTest` were checked by hand; a Kenyan payroll specialist must confirm them before go-live.
- Rule sources and items still to validate: `docs/payroll/kenya-statutory-rules.md`.

## iDempiere plug-ins
- Target platform: an installed iDempiere 13 server (`-Didempiere.home=...`). See `zenithcore-idempiere-payroll/README.md`.
- Process classes need `@org.adempiere.base.annotation.Process`, or iDempiere will not find them.
- Entity type `ZENITH`; dictionary IDs 1090000–1090999 until the dictionary ships as a 2Pack. Migration scripts must be safe to re-run.

## Pending critical items
- CRIT-01 KRA eTIMS OSCU connector is on hold until the KRA specification documents are added under `docs/etims/`. Do not build it from guesses. Details: `docs/pending-critical.md`.

## Workflow
- Branch from `main`, open a pull request, a Pollux developer reviews before merge.
- `mvn -B verify` must pass (CI runs it).
