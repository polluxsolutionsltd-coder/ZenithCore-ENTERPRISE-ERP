# Opening balance templates

ZenithCore's own CSV templates for the Opening Balances window (not statutory layouts). Export the legacy
trial balance and open items into these columns, attach both files to the opening balance record, and press
**Load Attached Files**. Files are recognised by their header row; amounts may use thousands separators and
brackets for negatives.

## Trial balance (`trial-balance-template.csv`)

| Column | Required | Meaning |
| --- | --- | --- |
| account_code | yes | Legacy account code (unique) |
| account_name | | Legacy account name |
| debit, credit | yes | Legacy balance at the opening balance date (one of the two) |
| audited_debit, audited_credit | | Figure in the audited financial statements, if the line has one |
| new_account | | Account in the new chart; otherwise mapped by the same code, or by an earlier mapping |
| adjustment | | Clean-up or audit adjustment, debit positive |
| adjustment_reason | with an adjustment | Audit adjustment number, approval or correction |

## Sub-ledgers (`sub-ledger-template.csv`)

| Column | Required | Meaning |
| --- | --- | --- |
| type | yes | AR customer invoice, AP supplier invoice, BK bank or M-Pesa balance, ST stock, FA fixed asset |
| partner_code | AR, AP, BK | Customer or supplier code (Search Key); for BK the bank account number |
| reference | yes | Legacy invoice, credit note, statement, item or asset number |
| date | | Original document date, yyyy-mm-dd (used for ageing) |
| amount | yes | Amount owed or value in its normal direction; negative for credit notes and overdrafts |
| control_account | | Control account in the new chart; AR, AP and BK default to the partner's or bank's own account |
| description | | Free text |

AR, AP and BK items are posted by Post Opening Balances (opening invoices and an opening bank statement).
ST and FA items are reconciled to their control accounts here and loaded with iDempiere's stock count and
asset imports against the same clearing account.
