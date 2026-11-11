-- Development only (GardenWorld demo database). Safe to re-run.
-- Budget control in GardenWorld:
--   * Cost centres WS Workshop, CC Call Centre, ADM Administration.
--   * Budget "Approved budget 2026" (prepared by GardenAdmin, not yet approved):
--       WS Workshop / 54000 Freight in 50,000;  WS Workshop / 51800 Commissions Paid 10,000.
--   * Budget control on, no tolerance.
-- Draft documents dated 10 Nov 2026, from Patio Fun (pre-qualified):
--   * ZC-T5 LPO, Workshop, Freight Charges 30,000: within budget.
--   * ZC-T6 LPO, Workshop, Freight Charges 25,000: over budget once ZC-T5 is committed (by 5,000).
--   * ZC-T7 LPO, no cost centre, Bank Charge 1,000: blocked, no cost centre.
--   * ZC-T8 LPO, Call Centre, Commissions 2,000: blocked, no budget for that cost centre and account.

INSERT INTO C_Activity (C_Activity_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,IsSummary,C_Activity_UU)
SELECT a.id,11,0,'Y',now(),100,now(),100,a.v,a.n,'N',gen_random_uuid()
FROM (VALUES (1090001,'WS','Workshop'),(1090002,'CC','Call Centre'),(1090003,'ADM','Administration')) a(id,v,n)
WHERE NOT EXISTS (SELECT 1 FROM C_Activity x WHERE x.C_Activity_ID=a.id);

INSERT INTO ZC_Budget (ZC_Budget_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_Budget_UU,Name,C_Year_ID,Description,Processed)
SELECT 1000000,11,0,'Y',now(),101,now(),101,gen_random_uuid(),'Approved budget 2026',
  (SELECT y.C_Year_ID FROM C_Year y JOIN C_Calendar c ON c.C_Calendar_ID=y.C_Calendar_ID WHERE c.AD_Client_ID=11 AND y.FiscalYear='2026'),
  'Sample: board minute 9/2025','N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_Budget WHERE ZC_Budget_ID=1000000);
INSERT INTO ZC_BudgetLine (ZC_BudgetLine_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_BudgetLine_UU,ZC_Budget_ID,C_Activity_ID,Account_ID,ZC_Amount,Processed)
SELECT l.id,11,0,'Y',now(),101,now(),101,gen_random_uuid(),1000000,1090001,
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value=l.acct AND C_Element_ID=(SELECT C_Element_ID FROM C_Element WHERE AD_Client_ID=11 AND ElementType='A' ORDER BY C_Element_ID LIMIT 1)),
  l.amt,'N'
FROM (VALUES (1000000,'54000',50000),(1000001,'51800',10000)) l(id,acct,amt)
WHERE NOT EXISTS (SELECT 1 FROM ZC_BudgetLine x WHERE x.ZC_BudgetLine_ID=l.id);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_Budget' AND IsTableID='Y';
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000002) WHERE Name='ZC_BudgetLine' AND IsTableID='Y';

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091603,11,0,'Y',now(),100,now(),100,'ZC_BUDGET_CONTROL','Y','GardenWorld test','U','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name='ZC_BUDGET_CONTROL' AND x.AD_Client_ID=11);

DO $$
DECLARE
  o RECORD;
BEGIN
  FOR o IN SELECT * FROM (VALUES
      (1090005,'ZC-T5',1090001,200000,30000::numeric),
      (1090006,'ZC-T6',1090001,200000,25000),
      (1090007,'ZC-T7',NULL,100,1000),
      (1090008,'ZC-T8',1090002,101,2000)) v(id,docno,act,charge,amt) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_Order WHERE C_Order_ID=o.id) THEN
      CREATE TEMP TABLE t_o ON COMMIT DROP AS SELECT * FROM C_Order WHERE DocumentNo='800002' AND AD_Client_ID=11 AND IsSOTrx='N';
      UPDATE t_o SET C_Order_ID=o.id, DocumentNo=o.docno, C_Activity_ID=o.act, DocStatus='DR', DocAction='CO', Processed='N',
        Posted='N', IsApproved='N', IsDelivered='N', IsInvoiced='N', IsPrinted='N', Processing='N',
        DateOrdered='2026-11-10', DateAcct='2026-11-10', DatePromised='2026-11-10', TotalLines=0, GrandTotal=0,
        Link_Order_ID=NULL, Ref_Order_ID=NULL, C_Payment_ID=NULL, C_CashLine_ID=NULL, POReference=NULL,
        Description='ZenithCore budget test', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, C_Order_UU=gen_random_uuid();
      INSERT INTO C_Order SELECT * FROM t_o;
      CREATE TEMP TABLE t_ol ON COMMIT DROP AS
        SELECT l.* FROM C_OrderLine l JOIN C_Order h ON h.C_Order_ID=l.C_Order_ID WHERE h.DocumentNo='800002' AND h.AD_Client_ID=11 AND h.IsSOTrx='N'
        ORDER BY l.Line LIMIT 1;
      UPDATE t_ol SET C_OrderLine_ID=o.id+10000, C_Order_ID=o.id, Line=10, M_Product_ID=NULL, M_AttributeSetInstance_ID=0,
        C_Charge_ID=o.charge, QtyOrdered=1, QtyEntered=1, QtyReserved=0, QtyDelivered=0, QtyInvoiced=0, QtyLostSales=0,
        PriceActual=o.amt, PriceEntered=o.amt, PriceList=o.amt, PriceLimit=0, LineNetAmt=o.amt, Discount=0, FreightAmt=0,
        C_Activity_ID=NULL, Processed='N', DateOrdered='2026-11-10', DatePromised='2026-11-10', DateDelivered=NULL, DateInvoiced=NULL,
        Link_OrderLine_ID=NULL, Ref_OrderLine_ID=NULL, Description=NULL,
        Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, C_OrderLine_UU=gen_random_uuid();
      INSERT INTO C_OrderLine SELECT * FROM t_ol;
      DROP TABLE t_o; DROP TABLE t_ol;
    END IF;
  END LOOP;
END $$;

-- ZC-T9 supplier invoice for LPO ZC-T5 (Freight 30,000, Workshop): once completed and posted, the 30,000 moves
-- from committed to spent.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM C_Invoice WHERE C_Invoice_ID=1090009) THEN
    CREATE TEMP TABLE t_inv ON COMMIT DROP AS SELECT * FROM C_Invoice WHERE DocumentNo='10000002' AND AD_Client_ID=11;
    UPDATE t_inv SET C_Invoice_ID=1090009, DocumentNo='ZC-T9', C_BPartner_ID=121,
      C_BPartner_Location_ID=(SELECT MIN(C_BPartner_Location_ID) FROM C_BPartner_Location WHERE C_BPartner_ID=121),
      AD_User_ID=NULL, C_Order_ID=1090005, C_Activity_ID=1090001, DocStatus='DR', DocAction='CO', Processed='N', Posted='N',
      IsPaid='N', IsApproved='N', Processing='N', DateInvoiced='2026-11-10', DateAcct='2026-11-10', DateOrdered='2026-11-10',
      TotalLines=0, GrandTotal=0, Reversal_ID=NULL, C_Payment_ID=NULL, Ref_Invoice_ID=NULL, C_CashLine_ID=NULL,
      POReference=NULL, Description='ZenithCore budget test', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101,
      C_Invoice_UU=gen_random_uuid();
    INSERT INTO C_Invoice SELECT * FROM t_inv;
    CREATE TEMP TABLE t_l ON COMMIT DROP AS
      SELECT il.* FROM C_InvoiceLine il JOIN C_Invoice i ON i.C_Invoice_ID=il.C_Invoice_ID
      WHERE i.DocumentNo='10000002' AND i.AD_Client_ID=11 ORDER BY il.Line LIMIT 1;
    UPDATE t_l SET C_InvoiceLine_ID=1090091, C_Invoice_ID=1090009, Line=10, C_OrderLine_ID=1100005, M_InOutLine_ID=NULL,
      M_Product_ID=NULL, M_AttributeSetInstance_ID=0, C_Charge_ID=200000,
      C_UOM_ID=(SELECT C_UOM_ID FROM C_OrderLine WHERE C_OrderLine_ID=1100005),
      C_Tax_ID=(SELECT C_Tax_ID FROM C_OrderLine WHERE C_OrderLine_ID=1100005),
      QtyInvoiced=1, QtyEntered=1, PriceActual=30000, PriceEntered=30000, PriceList=30000, LineNetAmt=30000, LineTotalAmt=30000,
      TaxAmt=0, C_Activity_ID=NULL, Processed='N', A_Asset_ID=NULL, Description=NULL,
      Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, C_InvoiceLine_UU=gen_random_uuid();
    INSERT INTO C_InvoiceLine SELECT * FROM t_l;
  END IF;
END $$;
