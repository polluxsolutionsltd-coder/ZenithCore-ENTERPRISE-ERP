-- GardenWorld test data for the opening-balance workbench. Test only: the legacy codes, figures and
-- references are invented. Safe to re-run.
--   * GL accounts 19990 Opening balance clearing (test), 11190 / 11195 KCB Main (test) and in transit
--   * charge "Opening balance clearing (test)" on 19990
--   * bank account KCB-TEST-001 (own bank "KCB (test)") on 11190 / 11195
--   * opening balance "GardenWorld go-live (test)" at 1 Nov 2026, prepared by GardenAdmin
-- Attach testdata/opening-trial-balance.csv and testdata/opening-sub-ledgers.csv to it in the window and press
-- Load Attached Files. The first load leaves three problems on purpose: an adjustment without a reason
-- (1101), an unmapped legacy account (1999) and a supplier credit note missing from the sub-ledger file.

DO $$
DECLARE
  r RECORD;
BEGIN
  FOR r IN SELECT * FROM (VALUES (1090012,15300,'19990','Opening balance clearing (test)'),
                                 (1090013,508,'11190','KCB Main (test)'),(1090014,509,'11195','KCB in transit (test)')) v(id,src,val,nm) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_ElementValue WHERE C_ElementValue_ID=r.id) THEN
      CREATE TEMP TABLE t_ev ON COMMIT DROP AS SELECT * FROM C_ElementValue
        WHERE C_ElementValue_ID=CASE WHEN r.src=15300 THEN (SELECT C_ElementValue_ID FROM C_ElementValue WHERE C_Element_ID=105 AND Value='15300') ELSE r.src END;
      UPDATE t_ev SET C_ElementValue_ID=r.id, Value=r.val, Name=r.nm, Description=NULL, Created=now(), Updated=now(),
        C_ElementValue_UU=gen_random_uuid();
      INSERT INTO C_ElementValue SELECT * FROM t_ev;
      CREATE TEMP TABLE t_tn ON COMMIT DROP AS SELECT tn.* FROM AD_TreeNode tn JOIN t_ev ON 1=1
        WHERE tn.AD_Tree_ID=101 AND tn.Node_ID=CASE WHEN r.src=15300 THEN (SELECT C_ElementValue_ID FROM C_ElementValue WHERE C_Element_ID=105 AND Value='15300') ELSE r.src END;
      UPDATE t_tn SET Node_ID=r.id, SeqNo=SeqNo+20, Created=now(), Updated=now(), AD_TreeNode_UU=gen_random_uuid();
      INSERT INTO AD_TreeNode SELECT * FROM t_tn;
      DROP TABLE t_ev; DROP TABLE t_tn;
    END IF;
  END LOOP;
  -- valid combinations in both accounting schemas
  FOR r IN SELECT * FROM (VALUES (1090020,258,1090012),(1090021,200052,1090012),(1090022,258,1090013),(1090023,257,1090014),
                                 (1090024,200052,1090013),(1090025,200053,1090014)) v(id,src,acct) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_ValidCombination WHERE C_ValidCombination_ID=r.id) THEN
      CREATE TEMP TABLE t_vc ON COMMIT DROP AS SELECT * FROM C_ValidCombination WHERE C_ValidCombination_ID=r.src;
      UPDATE t_vc SET C_ValidCombination_ID=r.id, Account_ID=r.acct,
        Combination=replace(Combination, (SELECT Value FROM C_ElementValue WHERE C_ElementValue_ID=t_vc.Account_ID),
                            (SELECT Value FROM C_ElementValue WHERE C_ElementValue_ID=r.acct)),
        Description=replace(Description, (SELECT Name FROM C_ElementValue WHERE C_ElementValue_ID=t_vc.Account_ID),
                            (SELECT Name FROM C_ElementValue WHERE C_ElementValue_ID=r.acct)),
        Created=now(), Updated=now(), C_ValidCombination_UU=gen_random_uuid();
      INSERT INTO C_ValidCombination SELECT * FROM t_vc;
      DROP TABLE t_vc;
    END IF;
  END LOOP;

  IF NOT EXISTS (SELECT 1 FROM C_Charge WHERE C_Charge_ID=1090004) THEN
    CREATE TEMP TABLE t_c ON COMMIT DROP AS SELECT * FROM C_Charge WHERE C_Charge_ID=100;
    UPDATE t_c SET C_Charge_ID=1090004, Name='Opening balance clearing (test)', Description='Offsets opening documents; ends at nil',
      Created=now(), Updated=now(), C_Charge_UU=gen_random_uuid();
    INSERT INTO C_Charge SELECT * FROM t_c;
    CREATE TEMP TABLE t_ca ON COMMIT DROP AS SELECT * FROM C_Charge_Acct WHERE C_Charge_ID=100;
    UPDATE t_ca SET C_Charge_ID=1090004, Ch_Expense_Acct=CASE WHEN C_AcctSchema_ID=101 THEN 1090020 ELSE 1090021 END,
      Created=now(), Updated=now(), C_Charge_Acct_UU=gen_random_uuid();
    INSERT INTO C_Charge_Acct SELECT * FROM t_ca;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM C_Bank WHERE C_Bank_ID=1090002) THEN
    CREATE TEMP TABLE t_b ON COMMIT DROP AS SELECT * FROM C_Bank WHERE C_Bank_ID=100;
    UPDATE t_b SET C_Bank_ID=1090002, Name='KCB (test)', RoutingNo='KCB', SwiftCode=NULL, IsOwnBank='Y',
      Created=now(), Updated=now(), C_Bank_UU=gen_random_uuid();
    INSERT INTO C_Bank SELECT * FROM t_b;
    CREATE TEMP TABLE t_ba ON COMMIT DROP AS SELECT * FROM C_BankAccount WHERE C_BankAccount_ID=100;
    UPDATE t_ba SET C_BankAccount_ID=1090002, C_Bank_ID=1090002, Name='KCB Main (test)', AccountNo='KCB-TEST-001',
      Value='KCB-TEST-001', IsDefault='N', CurrentBalance=0, AD_Org_ID=11, Created=now(), Updated=now(),
      C_BankAccount_UU=gen_random_uuid();
    INSERT INTO C_BankAccount SELECT * FROM t_ba;
    CREATE TEMP TABLE t_baa ON COMMIT DROP AS SELECT * FROM C_BankAccount_Acct WHERE C_BankAccount_ID=100;
    UPDATE t_baa SET C_BankAccount_ID=1090002,
      B_Asset_Acct=CASE WHEN C_AcctSchema_ID=101 THEN 1090022 ELSE 1090024 END,
      B_InTransit_Acct=CASE WHEN C_AcctSchema_ID=101 THEN 1090023 ELSE 1090025 END,
      Created=now(), Updated=now(), C_BankAccount_Acct_UU=gen_random_uuid();
    INSERT INTO C_BankAccount_Acct SELECT * FROM t_baa;
  END IF;
END $$;

INSERT INTO ZC_OpeningBalance (ZC_OpeningBalance_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_OpeningBalance_UU,
  Name,ZC_CutoverDate,Description,C_Charge_ID,ZC_OBStatus,Processed)
SELECT 1000000,11,11,'Y',now(),101,now(),101,gen_random_uuid(),'GardenWorld go-live (test)','2026-11-01',
  'Legacy trial balance at 31 Oct 2026 (test figures); audited financial statements FY 2025/26, auditor''s report dated 30 Sep 2026 (test)',
  1090004,'DR','N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_OpeningBalance WHERE ZC_OpeningBalance_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_OpeningBalance' AND IsTableID='Y';
