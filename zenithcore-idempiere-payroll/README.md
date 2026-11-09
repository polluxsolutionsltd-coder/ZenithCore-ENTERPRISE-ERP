# ZenithCore Kenyan Payroll for iDempiere

OSGi plug-in that brings the `zenithcore-payroll-ke` engine into iDempiere 13.

## What it does today

- **Employee Pay Data (Kenya)** window: KRA PIN, NSSF and SHA numbers, bank, start and end dates, a Suspended flag, and every pay component. Employees are business partners flagged as employees. Changes are recorded in iDempiere's change log.
- **Payroll Run (Kenya)** window with a read-only Lines tab:
  - **Calculate Payroll** runs the pre-payroll checks (shared bank accounts, shared or invalid KRA PINs, missing NSSF/SHA numbers, missing bank details). Any blocking check stops the run and the results are saved on it. Otherwise it calculates every active, unsuspended employee of the run's organization whose start and end dates cover the pay date, and replaces the lines; each line keeps the full calculation working.
  - **Approve Payroll** needs a calculated run, a different person from the one who calculated it, and no pay-data changes since the calculation. Approval sets Processed, which locks the run.
- **Kenyan Payslip Preview** process (menu: ZenithCore → Kenyan Payslip Preview). Enter pay date and pay components; it calculates PAYE, NSSF, SHIF, Housing Levy, reliefs and the two-thirds rule, and shows the full working in the process log. It saves nothing.
- A start-up smoke calculation, logged at INFO level, so a broken engine shows up when the server starts.

Payroll run verified on 9 November 2026 in the same environment, through the web UI:
- GardenUser's first calculation was blocked: E001 and E005 share a bank account, E005 has no NSSF number. Nothing was calculated.
- After suspending E005, GardenUser calculated 3 employees: gross 198,000.00, PAYE 22,281.70, net 128,768.30. Every line matches the worked payslips, including E004's SACCO reduced to 4,155.00 with 845.00 carried forward.
- GardenUser's own approval was refused; GardenAdmin's approval succeeded and locked the run.
- With pay data changed after calculation, approval was refused.
Screenshot: [docs/images/payroll-run-approved-idempiere13.png](../docs/images/payroll-run-approved-idempiere13.png).

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
   - `testdata/garden-world-payroll-employees.sql` adds four sample employees in the Fertilizer organization, including a ghost record for the checks.
6. Log in as GardenUser to calculate and as GardenAdmin to approve.

## Notes for developers

- Process classes must carry `@org.adempiere.base.annotation.Process`; iDempiere's annotation-based factory ignores classes without it.
- Process parameters are filled from `@Parameter(name = "...")` fields; the names match `AD_Process_Para.ColumnName`.
- Entity type `ZENITH`. Dictionary IDs 1090000–1092000 are reserved for ZenithCore until the dictionary ships as a 2Pack; `tools/gen_dictionary.py` explains how they are allocated.
- Never name a column ending in "tedBy": iDempiere fills such columns with the current user on new records. User references end in `_ID`.
- Read-only payroll columns are set with `set_ValueNoCheck`; a normal `set_Value` refuses non-updateable columns on saved records.
- The build copies iDempiere's split-package work-around for `org.eclipse.core.runtime`.

## Next

- Complete (lock and post): GL journal from the payroll lines, with an account mapping per tenant; Finance Head posts.
- Payslip and P10 reports; NSSF, SHIF, Housing Levy, NITA, HELB and bank files from the approved lines.
- Establishment control (approved positions) and carry-forward of deductions to the next run.
- Role setup for Payroll Officer, HR Head and Finance Head.
- Ship the dictionary as a 2Pack instead of SQL; build the plug-in in CI.
