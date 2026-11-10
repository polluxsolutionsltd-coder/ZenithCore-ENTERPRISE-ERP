-- Development only (GardenWorld demo database). Safe to re-run. Run after garden-world-budget.sql.
-- Withholding in GardenWorld:
--   * Charges "Withholding tax payable" (account 22210) and "Withholding VAT payable" (22240).
--   * Withholding types and rates as in KRA's guides (rates must be re-checked against current law before go-live):
--       PROF professional fees 5% resident (monthly threshold 24,000), 20% non-resident
--       CONT contractual fees 3% resident (threshold 24,000), 20% non-resident
--       WHVAT withholding VAT 2% of the taxable value
--   * Charge "Commissions Paid" is marked as professional fees (test only).
--   * Withholding on; GardenWorld is a withholding VAT agent.
--   * Supplementary budget (approved): Workshop / 51800 Commissions Paid 150,000, so the test LSO passes the budget check.
--   * ZC-T10 LSO, Patio Fun, Workshop, Commissions 100,000 (GST 7% stands in for VAT 16%).
--   * ZC-T11 supplier invoice for ZC-T10. Prepared: deduction lines 5% WHT 5,000.00 and 2% WHVAT 2,000.00.

INSERT INTO C_Charge (C_Charge_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,ChargeAmt,IsSameTax,IsSameCurrency,
  IsTaxIncluded,IsExcludedFromDiscount,C_TaxCategory_ID,C_Charge_UU)
SELECT c.id,11,0,'Y',now(),100,now(),100,c.nm,0,'N','N','N','N',(SELECT C_TaxCategory_ID FROM C_Charge WHERE C_Charge_ID=100),gen_random_uuid()
FROM (VALUES (1090001,'Withholding tax payable'),(1090002,'Withholding VAT payable')) c(id,nm)
WHERE NOT EXISTS (SELECT 1 FROM C_Charge x WHERE x.C_Charge_ID=c.id);
INSERT INTO C_Charge_Acct (C_Charge_ID,C_AcctSchema_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Ch_Expense_Acct,C_Charge_Acct_UU)
SELECT c.id, a.C_AcctSchema_ID, 11, 0, 'Y', now(), 100, now(), 100,
  CASE WHEN a.C_AcctSchema_ID=101 THEN c.combo ELSE a.Ch_Expense_Acct END, gen_random_uuid()
FROM (VALUES (1090001,1000003),(1090002,1000006)) c(id,combo)
JOIN C_Charge_Acct a ON a.C_Charge_ID=100
WHERE NOT EXISTS (SELECT 1 FROM C_Charge_Acct x WHERE x.C_Charge_ID=c.id AND x.C_AcctSchema_ID=a.C_AcctSchema_ID);

INSERT INTO ZC_WHTType (ZC_WHTType_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_WHTType_UU,Value,Name,ZC_TaxKind,C_Charge_ID,Description)
SELECT t.id,11,0,'Y',now(),100,now(),100,gen_random_uuid(),t.v,t.n,t.k,t.ch,t.d
FROM (VALUES (1000000,'PROF','Professional fees','W',1090001,'Management, professional and training fees'),
             (1000001,'CONT','Contractual fees','W',1090001,'Contractual fees'),
             (1000002,'WHVAT','Withholding VAT','V',1090002,'Withholding VAT by appointed agents')) t(id,v,n,k,ch,d)
WHERE NOT EXISTS (SELECT 1 FROM ZC_WHTType x WHERE x.ZC_WHTType_ID=t.id);
INSERT INTO ZC_WHTRate (ZC_WHTRate_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_WHTRate_UU,ZC_WHTType_ID,ZC_IsNonResident,ValidFrom,ZC_Rate,ZC_MonthlyThreshold,Description)
SELECT r.id,11,0,'Y',now(),100,now(),100,gen_random_uuid(),r.t,r.nr,'2023-01-01',r.rate,r.thr,r.src
FROM (VALUES (1000000,1000000,'N',5,24000,'KRA Withholding Income Tax guide, Nov 2023'),
             (1000001,1000000,'Y',20,NULL,'KRA Withholding Income Tax guide, Nov 2023'),
             (1000002,1000001,'N',3,24000,'KRA Withholding Income Tax guide, Nov 2023'),
             (1000003,1000001,'Y',20,NULL,'KRA Withholding Income Tax guide, Nov 2023'),
             (1000004,1000002,'N',2,NULL,'KRA Overview of Withholding VAT, Dec 2024')) r(id,t,nr,rate,thr,src)
WHERE NOT EXISTS (SELECT 1 FROM ZC_WHTRate x WHERE x.ZC_WHTRate_ID=r.id);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000003) WHERE Name='ZC_WHTType' AND IsTableID='Y';
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000005) WHERE Name='ZC_WHTRate' AND IsTableID='Y';
UPDATE C_Charge SET ZC_WHTType_ID=1000000 WHERE C_Charge_ID=101 AND ZC_WHTType_ID IS NULL;

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT s.id,11,0,'Y',now(),100,now(),100,s.nm,s.val,'GardenWorld test','U','C',gen_random_uuid()
FROM (VALUES (1091605,'ZC_WITHHOLDING','Y'),(1091606,'ZC_WHVAT_AGENT','Y')) s(id,nm,val)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name=s.nm AND x.AD_Client_ID=11);

-- supplementary budget, already approved (test data)
INSERT INTO ZC_Budget (ZC_Budget_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_Budget_UU,Name,C_Year_ID,Description,Processed,ZC_ApprovedUser_ID,ZC_DateApproved)
SELECT 1000001,11,0,'Y',now(),101,now(),100,gen_random_uuid(),'Supplementary budget 1 2026',C_Year_ID,'Sample','Y',100,now()
FROM ZC_Budget WHERE ZC_Budget_ID=1000000 AND NOT EXISTS (SELECT 1 FROM ZC_Budget WHERE ZC_Budget_ID=1000001);
INSERT INTO ZC_BudgetLine (ZC_BudgetLine_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_BudgetLine_UU,ZC_Budget_ID,C_Activity_ID,Account_ID,ZC_Amount,Processed)
SELECT 1000002,11,0,'Y',now(),101,now(),101,gen_random_uuid(),1000001,1090001,Account_ID,150000,'Y'
FROM ZC_BudgetLine WHERE ZC_BudgetLine_ID=1000001 AND NOT EXISTS (SELECT 1 FROM ZC_BudgetLine WHERE ZC_BudgetLine_ID=1000002);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000002) WHERE Name='ZC_Budget' AND IsTableID='Y';
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000003) WHERE Name='ZC_BudgetLine' AND IsTableID='Y';

-- LSO ZC-T10 and its invoice ZC-T11
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM C_Order WHERE C_Order_ID=1090010) THEN
    CREATE TEMP TABLE t_o ON COMMIT DROP AS SELECT * FROM C_Order WHERE C_Order_ID=1090005;
    UPDATE t_o SET C_Order_ID=1090010, DocumentNo='ZC-T10', DocStatus='DR', DocAction='CO', Processed='N', Posted='N',
      IsApproved='N', IsDelivered='N', IsInvoiced='N', TotalLines=0, GrandTotal=0, Description='ZenithCore withholding test',
      Created=now(), Updated=now(), C_Order_UU=gen_random_uuid();
    INSERT INTO C_Order SELECT * FROM t_o;
    CREATE TEMP TABLE t_ol ON COMMIT DROP AS SELECT * FROM C_OrderLine WHERE C_Order_ID=1090005;
    UPDATE t_ol SET C_OrderLine_ID=1100010, C_Order_ID=1090010, C_Charge_ID=101, C_Tax_ID=106, QtyReserved=0, QtyDelivered=0,
      QtyInvoiced=0, PriceActual=100000, PriceEntered=100000, PriceList=100000, LineNetAmt=100000, Processed='N',
      Created=now(), Updated=now(), C_OrderLine_UU=gen_random_uuid();
    INSERT INTO C_OrderLine SELECT * FROM t_ol;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM C_Invoice WHERE C_Invoice_ID=1090011) THEN
    CREATE TEMP TABLE t_inv ON COMMIT DROP AS SELECT * FROM C_Invoice WHERE C_Invoice_ID=1090009;
    UPDATE t_inv SET C_Invoice_ID=1090011, DocumentNo='ZC-T11', C_Order_ID=1090010, DocStatus='DR', DocAction='CO', Processed='N',
      Posted='N', IsPaid='N', IsApproved='N', TotalLines=0, GrandTotal=0, Description='ZenithCore withholding test',
      Created=now(), Updated=now(), C_Invoice_UU=gen_random_uuid();
    INSERT INTO C_Invoice SELECT * FROM t_inv;
    CREATE TEMP TABLE t_l ON COMMIT DROP AS SELECT * FROM C_InvoiceLine WHERE C_InvoiceLine_ID=1090091;
    UPDATE t_l SET C_InvoiceLine_ID=1090111, C_Invoice_ID=1090011, C_OrderLine_ID=1100010, C_Charge_ID=101, C_Tax_ID=106,
      PriceActual=100000, PriceEntered=100000, PriceList=100000, LineNetAmt=100000, LineTotalAmt=100000, TaxAmt=0,
      Processed='N', Created=now(), Updated=now(), C_InvoiceLine_UU=gen_random_uuid();
    INSERT INTO C_InvoiceLine SELECT * FROM t_l;
  END IF;
END $$;
