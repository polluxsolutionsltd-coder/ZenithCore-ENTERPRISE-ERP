-- GardenWorld test data for the board pack and business units. Test only: units and figures are invented.
-- Safe to re-run. Restart the server afterwards (accounting schema elements are cached).
--   * business-unit dimension: element "Business Unit (test)" as User List 1 of schema 101, values
--     Rentals, Workshops, Incubation; ZC_BUSINESS_UNIT_REQUIRED = Y
--   * draft journals prepared by GardenAdmin, to complete as someone else (second-person rule):
--       ZC-BP1 31 Oct 2026  income by unit: Rentals 600,000, Workshops 250,000, Incubation 80,000 (services
--                            revenue against other current assets)
--       ZC-BP2 15 Nov 2026  Rentals income 300,000; salaries Rentals 150,000 and Incubation 120,000; Workshops
--                            commissions 190,000 on cost centre WS (budgeted)
--       ZC-BP3 20 Nov 2026  salaries 5,000 without a business unit (to be refused)
--   * board pack "Board pack November 2026 (test)" for Nov-26, created by GardenAdmin

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091615,11,0,'Y',now(),100,now(),100,'ZC_BUSINESS_UNIT_REQUIRED','Y','GardenWorld test','ZENITH','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig WHERE AD_SysConfig_ID=1091615);

DO $$
DECLARE
  r RECORD;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM C_Element WHERE C_Element_ID=1090001) THEN
    CREATE TEMP TABLE t_t ON COMMIT DROP AS SELECT * FROM AD_Tree WHERE AD_Tree_ID=101;
    UPDATE t_t SET AD_Tree_ID=1090001, Name='Business Unit (test)', Description=NULL, Created=now(), Updated=now(),
      AD_Tree_UU=gen_random_uuid();
    INSERT INTO AD_Tree SELECT * FROM t_t;
    CREATE TEMP TABLE t_e ON COMMIT DROP AS SELECT * FROM C_Element WHERE C_Element_ID=105;
    UPDATE t_e SET C_Element_ID=1090001, Name='Business Unit (test)', ElementType='U', AD_Tree_ID=1090001, Created=now(),
      Updated=now(), C_Element_UU=gen_random_uuid();
    INSERT INTO C_Element SELECT * FROM t_e;
  END IF;
  FOR r IN SELECT * FROM (VALUES (1090030,'BU-RENT','Rentals'),(1090031,'BU-WKS','Workshops'),(1090032,'BU-INC','Incubation')) v(id,val,nm) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_ElementValue WHERE C_ElementValue_ID=r.id) THEN
      CREATE TEMP TABLE t_ev ON COMMIT DROP AS SELECT * FROM C_ElementValue WHERE C_ElementValue_ID=1090012;
      UPDATE t_ev SET C_ElementValue_ID=r.id, C_Element_ID=1090001, Value=r.val, Name=r.nm, Description=NULL, IsSummary='N',
        IsDocControlled='N', AccountType='E', Created=now(), Updated=now(), C_ElementValue_UU=gen_random_uuid();
      INSERT INTO C_ElementValue SELECT * FROM t_ev;
      CREATE TEMP TABLE t_tn ON COMMIT DROP AS SELECT * FROM AD_TreeNode WHERE AD_Tree_ID=101 AND Node_ID=1090012;
      UPDATE t_tn SET AD_Tree_ID=1090001, Node_ID=r.id, Parent_ID=0, SeqNo=r.id-1090030, Created=now(), Updated=now(),
        AD_TreeNode_UU=gen_random_uuid();
      INSERT INTO AD_TreeNode SELECT * FROM t_tn;
      DROP TABLE t_ev; DROP TABLE t_tn;
    END IF;
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM C_AcctSchema_Element WHERE C_AcctSchema_ID=101 AND ElementType='U1') THEN
    CREATE TEMP TABLE t_ae ON COMMIT DROP AS SELECT * FROM C_AcctSchema_Element WHERE C_AcctSchema_ID=101 AND ElementType='PJ';
    UPDATE t_ae SET C_AcctSchema_Element_ID=1090001, ElementType='U1', Name='Business unit', C_Element_ID=1090001,
      C_ElementValue_ID=NULL, C_Project_ID=NULL, SeqNo=200, IsMandatory='N', IsBalanced='N', Created=now(), Updated=now(),
      C_AcctSchema_Element_UU=gen_random_uuid();
    INSERT INTO C_AcctSchema_Element SELECT * FROM t_ae;
  END IF;

  -- account combinations with business unit (and cost centre WS for the budgeted commissions)
  FOR r IN SELECT * FROM (VALUES (1090030,'42000',1090030,NULL::int),(1090031,'42000',1090031,NULL),(1090032,'42000',1090032,NULL),
      (1090033,'15300',NULL,NULL),(1090034,'60130',1090030,NULL),(1090035,'60130',1090032,NULL),(1090036,'51800',1090031,1090001)) v(id,acct,unit,act) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_ValidCombination WHERE C_ValidCombination_ID=r.id) THEN
      CREATE TEMP TABLE t_vc ON COMMIT DROP AS SELECT * FROM C_ValidCombination WHERE C_ValidCombination_ID=297;
      UPDATE t_vc SET C_ValidCombination_ID=r.id, AD_Org_ID=11,
        Account_ID=(SELECT C_ElementValue_ID FROM C_ElementValue WHERE C_Element_ID=105 AND Value=r.acct), User1_ID=r.unit,
        C_Activity_ID=r.act, Combination='HQ-' || r.acct || COALESCE('-' || (SELECT Value FROM C_ElementValue WHERE C_ElementValue_ID=r.unit), ''),
        Description='HQ-' || (SELECT Name FROM C_ElementValue WHERE C_Element_ID=105 AND Value=r.acct)
          || COALESCE('-' || (SELECT Name FROM C_ElementValue WHERE C_ElementValue_ID=r.unit), ''),
        Created=now(), Updated=now(), C_ValidCombination_UU=gen_random_uuid();
      INSERT INTO C_ValidCombination SELECT * FROM t_vc;
      DROP TABLE t_vc;
    END IF;
  END LOOP;

  -- draft journals (header copied from the opening journal)
  FOR r IN SELECT * FROM (VALUES (1090041,'ZC-BP1','2026-10-31'),(1090042,'ZC-BP2','2026-11-15'),(1090043,'ZC-BP3','2026-11-20')) v(id,docno,d) LOOP
    IF NOT EXISTS (SELECT 1 FROM GL_Journal WHERE GL_Journal_ID=r.id) THEN
      CREATE TEMP TABLE t_j ON COMMIT DROP AS SELECT * FROM GL_Journal WHERE GL_Journal_ID=(SELECT GL_Journal_ID FROM ZC_OpeningBalance WHERE ZC_OpeningBalance_ID=1000000);
      UPDATE t_j SET GL_Journal_ID=r.id, DocumentNo=r.docno, DateDoc=r.d::timestamp, DateAcct=r.d::timestamp,
        C_Period_ID=(SELECT p.C_Period_ID FROM C_Period p JOIN C_Year y ON y.C_Year_ID=p.C_Year_ID WHERE y.AD_Client_ID=11 AND r.d::date BETWEEN p.StartDate AND p.EndDate),
        Description='ZenithCore board pack test', DocStatus='DR', DocAction='CO', Processed='N', Posted='N', IsApproved='Y',
        TotalDr=0, TotalCr=0, GL_JournalBatch_ID=NULL, Reversal_ID=NULL, ProcessedOn=NULL, Created=now(), Updated=now(),
        CreatedBy=101, UpdatedBy=101, GL_Journal_UU=gen_random_uuid();
      INSERT INTO GL_Journal SELECT * FROM t_j;
      DROP TABLE t_j;
    END IF;
  END LOOP;
  FOR r IN SELECT * FROM (VALUES
      (1090041,10,1090033,930000,0),(1090041,20,1090030,0,600000),(1090041,30,1090031,0,250000),(1090041,40,1090032,0,80000),
      (1090042,10,1090033,300000,0),(1090042,20,1090030,0,300000),(1090042,30,1090034,150000,0),(1090042,40,1090035,120000,0),
      (1090042,50,1090036,190000,0),(1090042,60,1090033,0,460000),
      (1090043,10,1000012,5000,0),(1090043,20,1090033,0,5000)) v(j,line,vc,dr,cr) LOOP
    IF NOT EXISTS (SELECT 1 FROM GL_JournalLine WHERE GL_Journal_ID=r.j AND Line=r.line) THEN
      CREATE TEMP TABLE t_l ON COMMIT DROP AS SELECT * FROM GL_JournalLine WHERE GL_Journal_ID=(SELECT GL_Journal_ID FROM ZC_OpeningBalance WHERE ZC_OpeningBalance_ID=1000000) ORDER BY Line LIMIT 1;
      UPDATE t_l SET GL_JournalLine_ID=r.j*100+r.line, GL_Journal_ID=r.j, Line=r.line, C_ValidCombination_ID=r.vc,
        Account_ID=(SELECT Account_ID FROM C_ValidCombination WHERE C_ValidCombination_ID=r.vc),
        User1_ID=(SELECT User1_ID FROM C_ValidCombination WHERE C_ValidCombination_ID=r.vc),
        C_Activity_ID=(SELECT C_Activity_ID FROM C_ValidCombination WHERE C_ValidCombination_ID=r.vc),
        AmtSourceDr=r.dr, AmtSourceCr=r.cr, AmtAcctDr=r.dr, AmtAcctCr=r.cr,
        DateAcct=(SELECT DateAcct FROM GL_Journal WHERE GL_Journal_ID=r.j), Description=NULL, Processed='N',
        Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, GL_JournalLine_UU=gen_random_uuid();
      INSERT INTO GL_JournalLine SELECT * FROM t_l;
      DROP TABLE t_l;
    END IF;
  END LOOP;
  UPDATE GL_Journal j SET TotalDr=(SELECT SUM(AmtSourceDr) FROM GL_JournalLine WHERE GL_Journal_ID=j.GL_Journal_ID),
    TotalCr=(SELECT SUM(AmtSourceCr) FROM GL_JournalLine WHERE GL_Journal_ID=j.GL_Journal_ID)
  WHERE j.GL_Journal_ID IN (1090041,1090042,1090043) AND j.DocStatus='DR';
END $$;

INSERT INTO ZC_BoardPack (ZC_BoardPack_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_BoardPack_UU,
  Name,C_Period_ID,ZC_GroupLevel,ZC_Commentary,ZC_BoardPackStatus,ZC_Version,Processed)
SELECT 1000000,11,0,'Y',now(),101,now(),101,gen_random_uuid(),'Board pack November 2026 (test)',
  (SELECT p.C_Period_ID FROM C_Period p JOIN C_Year y ON y.C_Year_ID=p.C_Year_ID WHERE y.AD_Client_ID=11 AND p.StartDate='2026-11-01'),2,
  'Test commentary. Revenue to date is ahead of budget, driven by Rentals. Workshops commissions have absorbed more than the '
  || 'budget to date; Finance proposes a reallocation at the next committee. Opening balances were posted on 1 November after '
  || 'audit sign-off. Two supplier invoices remain overdue pending inspection.','DR',0,'N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_BoardPack WHERE ZC_BoardPack_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_BoardPack' AND IsTableID='Y';
