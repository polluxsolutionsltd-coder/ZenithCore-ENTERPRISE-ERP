# ZenithCore Kenyan Payroll for iDempiere

OSGi plug-in that brings the `zenithcore-payroll-ke` engine into iDempiere 13.

## What it does today

- **Kenyan Payslip Preview** process (menu: ZenithCore → Kenyan Payslip Preview). Enter pay date and pay components; it calculates PAYE, NSSF, SHIF, Housing Levy, reliefs and the two-thirds rule, and shows the full working in the process log. It saves nothing.
- A start-up smoke calculation, logged at INFO level, so a broken engine shows up when the server starts.

Verified on 9 November 2026 against a locally built iDempiere 13 (`release-13`) with PostgreSQL 16: for E001 (basic 62,000, house 12,000, commuter 4,000, other deductions 3,500) it returned PAYE 13,384.85 and net pay 53,120.15, matching the worked payslip. Screenshot: [docs/images/payslip-preview-idempiere13.png](../docs/images/payslip-preview-idempiere13.png).

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
4. Apply `migration/postgresql/202611091700_ZC-0001_KenyaPayslipPreview.sql`. It is safe to re-run.
5. On a GardenWorld demo database only, also apply `migration/postgresql/demo-garden-world-access.sql` to give the GardenWorld Admin role access.

## Notes for developers

- Process classes must carry `@org.adempiere.base.annotation.Process`; iDempiere's annotation-based factory ignores classes without it.
- Process parameters are filled from `@Parameter(name = "...")` fields; the names match `AD_Process_Para.ColumnName`.
- Entity type `ZENITH`. Dictionary IDs 1090000–1090999 are reserved for ZenithCore until the dictionary ships as a 2Pack.
- The build copies iDempiere's split-package work-around for `org.eclipse.core.runtime`.

## Next

- Payroll tables (employee pay data, payroll run, payroll lines) and windows.
- Payroll run process with the approvals workflow, GL posting and statutory files.
- Ship the dictionary as a 2Pack instead of SQL.
