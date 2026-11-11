# Phase 1 roles

Roles shipped with ZenithCore, loaded into an institution's tenant by **Set Up Phase 1 Roles** (menu). The
files are checked in CI (`RoleSetupTest`): every role described, valid access values and document actions,
conflicts between known roles, each with a reason.

| File | What it holds |
| --- | --- |
| `roles.csv` | Each role: what it is for, whether it sees accounting, and what it must not do (printed on the Roles and Duties Report) |
| `role-access.csv` | Per role: windows (`RW` read and write, `R` read only), forms and processes (`run`), and document actions per document base type (`PR` prepare, `CO` complete, `VO` void, `CL` close, `RC`/`RA` reverse). Inventory and sales documents can be narrowed to a sub-type: `MMI/PI` physical inventory, `MMI/IU` internal use, `MMI/CA` cost adjustment |
| `role-conflicts.csv` | Pairs of roles one person must not hold, with the reason. Includes the payroll roles from Set Up Payroll Roles |

## The roles

| Role | Does | Must not |
| --- | --- | --- |
| Store Keeper | Receives, issues, moves and sets aside stock; prepares counts and internal use; stock reports and labels | Count blind; inspect; order; approve write-offs; post invoices or payments |
| Stock Counter | Counts blind and submits counts | See book quantities; move stock; post counts |
| Inspection Committee | Inspects goods received: accepts or rejects each line with a reason | Receive or issue stock; order; pay |
| Procurement Officer | Requisitions, LPOs, suppliers and their pre-qualification; budget status | Receive or inspect goods; post supplier invoices; pay |
| Cashier | Records receipts (cash, cheque, M-Pesa, transfers) | Post invoices or journals; pay suppliers; reconcile the bank |
| Finance Officer | Prepares journals, invoices and payments; bank statements, matching and reconciliation; budgets, opening balances, asset register and tags; prepares the board pack | Receive cash; approve what they prepared; approve budgets, sign off opening balances or approve the board pack |
| Finance Manager | Completes journals and payments prepared by others; approves budgets; signs off opening balances; closes audit queries; approves write-offs; reverses documents; maintains approval rules and reasons | Receive cash; order; keep stores; approve the board pack |
| Board Pack Approver | Approves the board finance committee pack | Prepare it; post or approve transactions |
| Asset Verifier | Verifies assets in the field | Keep the asset register; tag assets |

The roles narrow what each person can open; the ZenithCore controls still check every document, whoever
holds which role (for example, a journal's preparer cannot complete it from the approval amount upwards).

## Changing them for an institution

Edit the files, keep the tests passing, and run Set Up Phase 1 Roles again: it adds or restores what the
files give. Access added to a role by hand is kept, listed when the process runs, and marked "added locally"
on the Roles and Duties Report, so auditors see every difference from the agreed matrix.

Each name must match the iDempiere 13 window, form or process name exactly (for example "Purchase Invoice and
Credit/Debit Note"). If any name is not found, the process changes nothing and lists the names.
