# Kenya setup data

Data for a new Kenyan institution on ZenithCore. Nothing here is a statutory upload layout.

| File | Loaded by | Contents |
| --- | --- | --- |
| `AccountingKE.csv` | iDempiere **Initial Tenant Setup** (default accounts), then **Import File Loader** with format *Accounting - Accounts* and **Import Account** (whole chart and tree) | Chart of accounts in iDempiere's format, grouped the IPSAS way (cash, receivables from exchange and non-exchange transactions, inventories, property plant and equipment; payables, taxes, payroll deductions; net assets; revenue from exchange and non-exchange transactions; expenses by nature) with Kenyan accounts: M-Pesa, VAT input and output, withholding tax and withholding VAT, PAYE, NSSF, SHIF, Housing Levy, NITA, HELB, tenant deposits, retention money, imprests, opening balance clearing |
| `taxes.csv` | ZenithCore **Apply Kenya Setup** | VAT tax categories and rates |
| `charges.csv` | ZenithCore **Apply Kenya Setup** | Charges ZenithCore controls use, on the chart's accounts |
| `withholding.csv` | ZenithCore **Apply Kenya Setup** | Withholding income tax and withholding VAT types and rates |

Rules for this data (checked by `SetupDataTest` in `zenithcore-controls`):

- Every rate has a `valid_from` date and a source. `valid_from` is the date of the source that confirms the rate; the rate
  may have applied earlier. A change in law is a new row with a new `valid_from`, never an edit.
- Only rates confirmed by a cited source are loaded. Not loaded, for the tax adviser to confirm with dates before they
  are added: the 8% VAT on fuel (temporary orders; the last one seen ran 15 Jul to 14 Oct 2026, Legal Notice 128 of 2026),
  withholding on supply of goods to public entities (0.5% resident), sales promotion, marketing and advertising (5%),
  rent (non-residents 30%), scrap metal (1.5%), digital platforms, royalties and interest. Rates from PwC Worldwide Tax
  Summaries, Kenya, reviewed 17 July 2026.
- The chart codes are ZenithCore's. If an institution must report on the National Treasury Standard Chart of Accounts
  or IFMIS codes, the mapping is added once the official code list is supplied; codes are not guessed.
