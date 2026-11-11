# Kenyan statutory payroll rules implemented in `zenithcore-payroll-ke`

As at 9 November 2026. **Every rate here must be validated by a Kenyan payroll specialist against current KRA, NSSF and SHA guidance before any production payroll.** Rates live in `StatutoryRateTable.kenyaDefaults()` as dated rate sets.

## Rules

| Rule | Value | In force from | Legal basis |
| --- | --- | --- | --- |
| PAYE bands (monthly) | 0–24,000 at 10%; next 8,333 at 25%; next 467,667 at 30%; next 300,000 at 32.5%; above 800,000 at 35% | 1 Jul 2023 | Income Tax Act, Third Schedule (Finance Act 2023) |
| Personal relief | KES 2,400 per month | In force | Income Tax Act s.30 |
| Insurance relief | 15% of qualifying premiums, max KES 5,000 per month | In force | Income Tax Act s.31 |
| NSSF Year 3 | 6% employee + 6% employer; Tier I to 8,000; Tier II 8,001–72,000 | 1 Feb 2025 to 31 Jan 2026 | NSSF Act 2013 |
| NSSF Year 4 | 6% + 6%; Tier I to 9,000; Tier II 9,001–108,000 (max 6,480 each side) | 1 Feb 2026 | NSSF Act 2013 |
| SHIF | 2.75% of gross salary, minimum KES 300 | 1 Oct 2024 | Social Health Insurance Act 2023 |
| Affordable Housing Levy | 1.5% employee + 1.5% employer of gross salary | 19 Mar 2024 | Affordable Housing Act 2024 |
| SHIF and Housing Levy deductible before PAYE | Insurance relief no longer given on SHIF | 27 Dec 2024 | Tax Laws (Amendment) Act 2024 |
| Pension allowance cap | NSSF + registered pension up to KES 30,000 per month | 27 Dec 2024 | Tax Laws (Amendment) Act 2024 |
| Post-retirement medical fund cap | KES 15,000 per month | 27 Dec 2024 | Tax Laws (Amendment) Act 2024 |
| Mortgage interest cap | KES 30,000 per month | 27 Dec 2024 | Tax Laws (Amendment) Act 2024 |
| Non-cash benefits | Not taxable up to KES 5,000 per month in total; above that, taxable in full | 27 Dec 2024 | Tax Laws (Amendment) Act 2024 |
| NITA levy | KES 50 per employee per month, employer only | In force | Industrial Training Act |
| HELB | Amount per HELB deduction notice | Per notice | HELB Act |
| Two-thirds rule | Total deductions may not exceed two-thirds of wages | In force | Employment Act 2007 s.19(3) |

## Design choices to confirm with the payroll specialist

1. **Base for NSSF, SHIF and Housing Levy** is gross cash pay (basic plus cash allowances and overtime). Non-cash benefits are excluded from these bases.
2. **Wages for the two-thirds rule** are taken as gross cash pay. Statutory deductions are applied first; then HELB, pension, insurance premium, staff loan, SACCO and union dues in that order, each reduced if needed. Shortfalls of HELB, staff loan, SACCO and union dues are carried forward to the next payroll as arrears, added to that month's amount and tested against the rule again (`DeductionArrears`). Shortfalls of pension and insurance premium are not carried forward; they are reported for HR to settle with the scheme or insurer.
3. **Rounding**: each statutory component is rounded to the cent, half-up. Tax is summed across bands before rounding.
4. **Post-retirement medical fund and mortgage interest** are treated as tax allowances paid outside payroll.
5. **Insurance relief** is given on premiums deducted through payroll.
6. **Pension and insurance cut by the two-thirds rule.** The engine currently gives allowable pension and insurance relief on the amounts due, even when the two-thirds rule deducts less. Confirm whether relief should follow the amount actually deducted in such months.

## Still to add

- Finance Act 2026: confirm commencement of the gratuity exemption (up to 31% of emoluments for contracts of three years or more) and any other employment-tax changes; no change to PAYE bands or personal relief was found.
- Non-resident, casual and director rates; secondary employment.
- Disability exemption (first KES 150,000 per month exempt with a certificate).
- Per diem and meal benefit thresholds.
- Arrears and back pay spread over prior periods.
- P10, NSSF, SHIF, Housing Levy, NITA, HELB and bank file formats, validated against the current templates (CRIT-02).
