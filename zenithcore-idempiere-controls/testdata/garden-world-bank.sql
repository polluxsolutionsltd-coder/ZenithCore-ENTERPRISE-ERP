-- GardenWorld test data for bank and M-Pesa statement matching. Test only: the paybill, references and
-- payers are invented. Safe to re-run.
--   * switches: statement matching on, 3-day window, receipts may be recorded by Match Statement
--   * M-Pesa paybill bank account PAYBILL-TEST with its own GL accounts 11180 / 11185 (both schemas)
--   * charge "Unidentified receipts (test)" (copies Bank Charge's accounts; a real setup maps it to a
--     suspense liability), statement rule "Pay Bill Charge" -> Bank Charge
--   * import loader format (the statement date defaults to the import date; a constant date would be cut to
--     the column length)
--   * import loader format "ZenithCore test statement CSV" for I_BankStatement (date, receipt code,
--     account reference, payer, description, amount); not a real bank or M-Pesa layout
--   * drafts to complete in the UI: receipts ZC-R1 1,500.00 (QK12ABC3DE), ZC-R2 2,000.00 (no reference),
--     ZC-R3 700.00 (not on the statement), payment ZC-P1 12,000.00 to Patio Fun (not on the statement),
--     receipt ZC-R4 1,500.00 repeating ZC-R1's M-Pesa code (to be refused)
-- The statement itself is imported from testdata/garden-world-mpesa-statement.csv with that format.

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT v.id,11,0,'Y',now(),100,now(),100,v.name,v.val,'GardenWorld test','ZENITH','C',gen_random_uuid()
FROM (VALUES (1091612,'ZC_BANK_MATCHING','Y'),(1091613,'ZC_BANK_MATCH_DAYS','3'),(1091614,'ZC_BANK_CREATE_RECEIPTS','Y')) v(id,name,val)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig s WHERE s.AD_SysConfig_ID=v.id);

DO $$
DECLARE
  r RECORD;
BEGIN
  -- GL accounts for the paybill
  FOR r IN SELECT * FROM (VALUES (1090010,508,'11180','M-Pesa Paybill (test)'),(1090011,509,'11185','M-Pesa in transit (test)')) v(id,src,val,nm) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_ElementValue WHERE C_ElementValue_ID=r.id) THEN
      CREATE TEMP TABLE t_ev ON COMMIT DROP AS SELECT * FROM C_ElementValue WHERE C_ElementValue_ID=r.src;
      UPDATE t_ev SET C_ElementValue_ID=r.id, Value=r.val, Name=r.nm, Description=NULL, Created=now(), Updated=now(),
        C_ElementValue_UU=gen_random_uuid();
      INSERT INTO C_ElementValue SELECT * FROM t_ev;
      CREATE TEMP TABLE t_tn ON COMMIT DROP AS SELECT * FROM AD_TreeNode WHERE AD_Tree_ID=101 AND Node_ID=r.src;
      UPDATE t_tn SET Node_ID=r.id, SeqNo=SeqNo+10, Created=now(), Updated=now(), AD_TreeNode_UU=gen_random_uuid();
      INSERT INTO AD_TreeNode SELECT * FROM t_tn;
      DROP TABLE t_ev; DROP TABLE t_tn;
    END IF;
  END LOOP;
  FOR r IN SELECT * FROM (VALUES (1090010,258,1090010),(1090011,257,1090011),(1090012,200052,1090010),(1090013,200053,1090011)) v(id,src,acct) LOOP
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

  -- the paybill as an own bank account
  IF NOT EXISTS (SELECT 1 FROM C_Bank WHERE C_Bank_ID=1090001) THEN
    CREATE TEMP TABLE t_b ON COMMIT DROP AS SELECT * FROM C_Bank WHERE C_Bank_ID=100;
    UPDATE t_b SET C_Bank_ID=1090001, Name='Safaricom M-Pesa (test)', RoutingNo='MPESA', SwiftCode=NULL, IsOwnBank='Y',
      Created=now(), Updated=now(), C_Bank_UU=gen_random_uuid();
    INSERT INTO C_Bank SELECT * FROM t_b;
    CREATE TEMP TABLE t_ba ON COMMIT DROP AS SELECT * FROM C_BankAccount WHERE C_BankAccount_ID=100;
    UPDATE t_ba SET C_BankAccount_ID=1090001, C_Bank_ID=1090001, Name='M-Pesa Paybill (test)', AccountNo='PAYBILL-TEST',
      Value='PAYBILL-TEST', IsDefault='N', CurrentBalance=0, AD_Org_ID=11, Created=now(), Updated=now(),
      C_BankAccount_UU=gen_random_uuid();
    INSERT INTO C_BankAccount SELECT * FROM t_ba;
    CREATE TEMP TABLE t_baa ON COMMIT DROP AS SELECT * FROM C_BankAccount_Acct WHERE C_BankAccount_ID=100;
    UPDATE t_baa SET C_BankAccount_ID=1090001,
      B_Asset_Acct=CASE WHEN C_AcctSchema_ID=101 THEN 1090010 ELSE 1090012 END,
      B_InTransit_Acct=CASE WHEN C_AcctSchema_ID=101 THEN 1090011 ELSE 1090013 END,
      Created=now(), Updated=now(), C_BankAccount_Acct_UU=gen_random_uuid();
    INSERT INTO C_BankAccount_Acct SELECT * FROM t_baa;
  END IF;

  -- charge for unidentified receipts
  IF NOT EXISTS (SELECT 1 FROM C_Charge WHERE C_Charge_ID=1090003) THEN
    CREATE TEMP TABLE t_c ON COMMIT DROP AS SELECT * FROM C_Charge WHERE C_Charge_ID=100;
    UPDATE t_c SET C_Charge_ID=1090003, Name='Unidentified receipts (test)', Description='Money in not yet identified',
      Created=now(), Updated=now(), C_Charge_UU=gen_random_uuid();
    INSERT INTO C_Charge SELECT * FROM t_c;
    CREATE TEMP TABLE t_ca ON COMMIT DROP AS SELECT * FROM C_Charge_Acct WHERE C_Charge_ID=100;
    UPDATE t_ca SET C_Charge_ID=1090003, Created=now(), Updated=now(), C_Charge_Acct_UU=gen_random_uuid();
    INSERT INTO C_Charge_Acct SELECT * FROM t_ca;
  END IF;

  -- receipts and a payment on the paybill, to complete in the UI
  FOR r IN SELECT * FROM (VALUES
      (1090031,'ZC-R1','Y',118,'2026-11-09',1500.00,'QK12ABC3DE'),
      (1090032,'ZC-R2','Y',117,'2026-11-08',2000.00,NULL),
      (1090033,'ZC-R3','Y',118,'2026-11-10',700.00,NULL),
      (1090034,'ZC-P1','N',121,'2026-11-07',12000.00,'CHQ000101'),
      (1090035,'ZC-R4','Y',118,'2026-11-10',1500.00,'qk12abc3de')) v(id,docno,rcpt,bp,d,amt,ref) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_Payment WHERE C_Payment_ID=r.id) THEN
      CREATE TEMP TABLE t_p ON COMMIT DROP AS SELECT * FROM C_Payment WHERE C_Payment_ID=101;
      UPDATE t_p SET C_Payment_ID=r.id, DocumentNo=r.docno, IsReceipt=r.rcpt, C_DocType_ID=CASE WHEN r.rcpt='Y' THEN 119 ELSE 125 END,
        C_BankAccount_ID=1090001, C_BPartner_ID=r.bp, C_BP_BankAccount_ID=NULL, DateTrx=r.d::timestamp, DateAcct=r.d::timestamp,
        PayAmt=r.amt, DiscountAmt=0, WriteOffAmt=0, OverUnderAmt=0, IsOverUnderPayment='N', TaxAmt=0, ChargeAmt=0,
        C_Invoice_ID=NULL, C_Order_ID=NULL, C_Charge_ID=NULL, C_Currency_ID=100, CheckNo=NULL, ZC_TrxReference=r.ref, TenderType='A',
        DocStatus='DR', DocAction='CO', Processed='N', Posted='N', IsReconciled='N', IsAllocated='N', IsPrepayment='N',
        IsApproved='Y', Reversal_ID=NULL, Ref_Payment_ID=NULL, C_DepositBatch_ID=NULL, ProcessedOn=NULL,
        Description='ZenithCore bank matching test', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101,
        C_Payment_UU=gen_random_uuid();
      INSERT INTO C_Payment SELECT * FROM t_p;
      DROP TABLE t_p;
    END IF;
  END LOOP;
END $$;

INSERT INTO ZC_StatementRule (ZC_StatementRule_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_StatementRule_UU,Name,ZC_MatchText,C_Charge_ID,C_BankAccount_ID,Description)
SELECT 1000000,11,0,'Y',now(),100,now(),100,gen_random_uuid(),'M-Pesa tariff','Pay Bill Charge',100,1090001,'Tariff deducted by the paybill'
WHERE NOT EXISTS (SELECT 1 FROM ZC_StatementRule WHERE ZC_StatementRule_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_StatementRule' AND IsTableID='Y';

-- import loader format (test layout only)
INSERT INTO AD_ImpFormat (AD_ImpFormat_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,AD_Table_ID,FormatType,SeparatorChar,Processing,AD_ImpFormat_UU)
SELECT 1090001,11,0,'Y',now(),100,now(),100,'ZenithCore test statement CSV','Test layout for GardenWorld; not a bank or M-Pesa format',600,'C',NULL,'N',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_ImpFormat WHERE AD_ImpFormat_ID=1090001);
INSERT INTO AD_ImpFormat_Row (AD_ImpFormat_Row_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,AD_ImpFormat_ID,SeqNo,Name,AD_Column_ID,StartNo,EndNo,DataType,DataFormat,DecimalPoint,DivideBy100,ConstantValue,AD_ImpFormat_Row_UU)
SELECT 1090000+v.seq,11,0,'Y',now(),100,now(),100,1090001,v.seq*10,v.col,
  (SELECT AD_Column_ID FROM AD_Column WHERE AD_Table_ID=600 AND ColumnName=v.col),v.startno,0,v.dt,v.fmt,'.','N',v.const,gen_random_uuid()
FROM (VALUES (1,'StatementLineDate',1,'D','yyyy-MM-dd',NULL),(2,'EftTrxID',2,'S',NULL,NULL),(3,'EftReference',3,'S',NULL,NULL),
             (4,'EftPayee',4,'S',NULL,NULL),(5,'LineDescription',5,'S',NULL,NULL),(6,'StmtAmt',6,'N',NULL,NULL),
             (7,'Name',0,'C',NULL,'ZC-S1 M-Pesa 10 Nov'),
             (9,'ValutaDate',1,'D','yyyy-MM-dd',NULL),(10,'BankAccountNo',0,'C',NULL,'PAYBILL-TEST'),
             (11,'TrxAmt',6,'N',NULL,NULL)) v(seq,col,startno,dt,fmt,const)
WHERE NOT EXISTS (SELECT 1 FROM AD_ImpFormat_Row r WHERE r.AD_ImpFormat_Row_ID=1090000+v.seq);
DELETE FROM AD_ImpFormat_Row WHERE AD_ImpFormat_Row_ID=1090008;
UPDATE AD_ImpFormat_Row SET AD_Column_ID=(SELECT AD_Column_ID FROM AD_Column WHERE AD_Table_ID=600 AND ColumnName='LineDescription'), Name='LineDescription' WHERE AD_ImpFormat_Row_ID=1090005;
