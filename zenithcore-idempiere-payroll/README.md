# ZenithCore Kenyan Payroll for iDempiere

OSGi plug-in that brings the `zenithcore-payroll-ke` engine into iDempiere 13.

## What it does today

- **Employee Pay Data (Kenya)** window: KRA PIN, NSSF and SHA numbers, bank, start and end dates, a Suspended flag, and every pay component. Employees are business partners flagged as employees. Changes are recorded in iDempiere's change log.
- **Payroll Run (Kenya)** window with a read-only Lines tab:
  - **Calculate Payroll** runs the pre-payroll checks (shared bank accounts, shared or invalid KRA PINs, missing NSSF/SHA numbers, missing bank details). Any blocking check stops the run and the results are saved on it. Otherwise it calculates every active, unsuspended employee of the run's organization whose start and end dates cover the pay date, and replaces the lines; each line keeps the full calculation working.
  - **Approve Payroll** needs a calculated run, a different person from the one who calculated it, and no pay-data changes since the calculation. Approval sets Processed, which locks the run.
  - **Complete Payroll** posts an approved run to the general ledger as one completed GL journal, dated on the pay date, and marks the run Completed with the poster and journal recorded on it. The person who calculated the payroll cannot complete it. The journal must balance to the cent and the period must be open, or nothing is posted.
  - **Print Payslips** (approved or completed runs only) creates one PDF with an A4 payslip per employee: earnings, deductions, net pay and masked bank account, the PAYE working, any amount carried forward under the two-thirds rule, and employer contributions. The PDF is attached to the run and downloaded. Sample: [docs/images/payslip-pdf-sample.png](../docs/images/payslip-pdf-sample.png).
  - **Statutory Schedules** (approved or completed runs only) creates a zip of CSV schedules: PAYE (P10 data), NSSF, SHIF, Housing Levy, NITA, HELB, bank transfers and a remittance summary, each with a total row. It is attached to the run and downloaded. These are working schedules, not yet the official upload layouts (pending item CRIT-02).
  - Each payroll line keeps a copy of the employee's pay components and statutory numbers as they were at calculation, so payslips and schedules never change if pay data is edited later.
- **Payroll Accounts (Kenya)** window: the ledger accounts the payroll posts to, one setup per accounting schema, plus an optional GL category. Each institution maps its own chart of accounts:

  | Account | Debit | Credit |
  | --- | --- | --- |
  | Salaries expense | gross pay | |
  | Employer statutory cost | employer NSSF + employer Housing Levy + NITA | |
  | PAYE payable | | PAYE |
  | NSSF payable | | employee + employer NSSF |
  | SHIF payable | | SHIF |
  | Housing Levy payable | | employee + employer Housing Levy |
  | NITA payable | | NITA |
  | HELB payable | | HELB |
  | Pension and insurance payable | | pension + insurance premium |
  | Staff loans receivable | | staff loan recoveries |
  | SACCO and union payable | | SACCO + union dues |
  | Net salaries payable | | net pay |
- **Kenyan Payslip Preview** process (menu: ZenithCore → Kenyan Payslip Preview). Enter pay date and pay components; it calculates PAYE, NSSF, SHIF, Housing Levy, reliefs and the two-thirds rule, and shows the full working in the process log. It saves nothing.
- A start-up smoke calculation, logged at INFO level, so a broken engine shows up when the server starts.

Payroll run verified on 9 November 2026 in the same environment, through the web UI:
- GardenUser's first calculation was blocked: E001 and E005 share a bank account, E005 has no NSSF number. Nothing was calculated.
- After suspending E005, GardenUser calculated 3 employees: gross 198,000.00, PAYE 22,281.70, net 128,768.30. Every line matches the worked payslips, including E004's SACCO reduced to 4,155.00 with 845.00 carried forward.
- GardenUser's own approval was refused; GardenAdmin's approval succeeded and locked the run.
- With pay data changed after calculation, approval was refused.
- GardenUser's attempt to complete the approved run was refused; GardenAdmin completed it. GL journal 1003 was completed and posted for November 2026 in the Fertilizer organization: debits 198,000.00 salaries and 15,000.00 employer cost, credits 22,281.70 PAYE, 23,760.00 NSSF, 5,445.00 SHIF, 5,940.00 Housing Levy, 150.00 NITA, 13,000.00 pension, 6,000.00 staff loans, 7,655.00 SACCO and union, 128,768.30 net pay; 213,000.00 each side. A second completion was refused.
- GardenWorld's own demo setup adds ledger entries after posting (its "Distribute Salaries" GL distribution and inter-organization balancing). These come from GardenWorld's configuration, not from ZenithCore.
- On a December 2026 run calculated by GardenUser and approved by GardenAdmin, Print Payslips produced three payslips matching the worked payslips (E001 net 53,120.15; E004 net 6,000.00 with 845.00 carried forward; E006 net 69,648.15). Statutory Schedules totals agree with the payroll and the journal: PAYE 22,281.70, NSSF 23,760.00, SHIF 5,445.00, Housing Levy 5,940.00, NITA 150.00, bank 128,768.30. Both buttons were refused on a draft run.
Screenshots: [approved](../docs/images/payroll-run-approved-idempiere13.png), [completed and posted](../docs/images/payroll-run-completed-idempiere13.png).

Payslip preview verified on 9 November 2026 against a locally built iDempiere 13 (`release-13`) with PostgreSQL 16: for E001 (basic 62,000, house 12,000, commuter 4,000, other deductions 3,500) it returned PAYE 13,384.85 and net pay 53,120.15, matching the worked payslip. Screenshot: [docs/images/payslip-preview-idempiere13.png](../docs/images/payslip-preview-idempiere13.png).

## Build

The target platform is the `plugins/` folder of an installed iDempiere 13 server.

```bash
# 1. Engine (OSGi bundle) into the local Maven repository
cd zenithcore-payroll-ke && mvn -B install
# 2. Plug-in
cd ../zenithcore-idempiere-payroll && mvn -B verify -Didempiere.home=/path/to/idempiere-server
```

GitHub Actions does not build this module yet, because it needs an iDempiere installation.

## Deploy (development)

1. Copy the two bundles into `<idempiere-server>/plugins/`:
   - `zenithcore-payroll-ke/target/zenithcore-payroll-ke-0.1.0-SNAPSHOT.jar` as `ke.co.pollux.zenithcore.payroll.ke_0.1.0.SNAPSHOT.jar`
   - `target/ke.co.pollux.zenithcore.payroll.idempiere-0.1.0-SNAPSHOT.jar` as `ke.co.pollux.zenithcore.payroll.idempiere_<Bundle-Version>.jar`
2. Add both to `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` with start level 4 and auto-start `true`.
3. Stop the server, delete `configuration/org.eclipse.osgi` (the OSGi cache), start the server.
4. Apply the migrations in order. They are safe to re-run:
   - `migration/postgresql/202611091700_ZC-0001_KenyaPayslipPreview.sql`
   - `migration/postgresql/202611092000_ZC-0002_PayrollTables.sql` (generated: edit `tools/gen_dictionary.py`, then regenerate; never edit the SQL by hand)
5. On a GardenWorld demo database only:
   - `migration/postgresql/demo-garden-world-access.sql` gives the GardenWorld Admin and GardenWorld User roles access.
   - `testdata/garden-world-payroll-employees.sql` adds four sample employees, with national ID numbers, in the Fertilizer organization, including a ghost record for the checks.
   - `testdata/garden-world-payroll-accounts.sql` maps GardenWorld's US payroll accounts as stand-ins for the Kenyan ones and opens the November 2026 GL Journal period.
6. Log in as GardenUser to calculate and as GardenAdmin to approve and complete.

## Notes for developers

- Process classes must carry `@org.adempiere.base.annotation.Process`; iDempiere's annotation-based factory ignores classes without it.
- Process parameters are filled from `@Parameter(name = "...")` fields; the names match `AD_Process_Para.ColumnName`.
- Entity type `ZENITH`. Dictionary IDs 1090000–1092000 are reserved for ZenithCore until the dictionary ships as a 2Pack; `tools/gen_dictionary.py` explains how they are allocated.
- Never name a column ending in "tedBy": iDempiere fills such columns with the current user on new records. User references end in `_ID`.
- A button that must work on a processed (locked) record needs `always=True` in the generator (IsAlwaysUpdateable), as Complete Payroll does.
- Read-only payroll columns are set with `set_ValueNoCheck`; a normal `set_Value` refuses non-updateable columns on saved records.
- The build copies iDempiere's split-package work-around for `org.eclipse.core.runtime`.

## Next

- Reverse a completed payroll (reverse the GL journal and reopen the run) with its own approval.
- Official upload layouts for P10, NSSF, SHA, Housing Levy, NITA, HELB and the bank file, once the templates are on file (CRIT-02).
- Email payslips to employees (password-protected PDF) and an employee self-service view.
- Annual P9 certificates.
- Establishment control (approved positions) and carry-forward of deductions to the next run.
- Role setup for Payroll Officer, HR Head and Finance Head.
- Ship the dictionary as a 2Pack instead of SQL; build the plug-in in CI.
