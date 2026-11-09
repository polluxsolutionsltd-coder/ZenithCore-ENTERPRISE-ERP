# ADR 0001: Build ZenithCore on iDempiere

Date: 9 November 2026 · Status: Accepted

## Decision
ZenithCore Enterprise ERP is built on iDempiere, with Pollux's functionality delivered as OSGi plug-ins and standalone Java modules. iDempiere core is not modified.

## Why
- ZenithCore will be sold to many institutions. iDempiere runs many tenants (clients and organisations) on one platform with data kept separate.
- Mature, proven accounting engine, document workflow and application dictionary.
- Open source (GPLv2): no third-party licence fees.
- Pollux has Java developers.

## Consequences
- iDempiere has no maintained Kenyan payroll (the old Libero payroll plug-in is unmaintained). Pollux builds the Kenyan payroll engine and plug-in itself.
- No KRA eTIMS plug-in exists for iDempiere. Pollux builds the OSCU connector and takes it through KRA certification.
- The standard ZK interface differs from the HTML demos. Phase 1 uses a ZenithCore ZK theme with role-based menus and dashboards.
- Licensing: when Pollux hosts ZenithCore as a service, plug-ins are not distributed. If a client self-hosts and receives the plug-ins, GPLv2 terms may apply to them. Legal advice is needed before offering self-hosting.

## Alternatives considered
- Own stack from scratch: full ownership, but 12 to 18 months to Phase 1 and the highest correctness risk.
- Frappe/ERPNext: faster for a single client because Kenyan payroll and eTIMS apps exist, but less suited to running many institutions on one platform.
