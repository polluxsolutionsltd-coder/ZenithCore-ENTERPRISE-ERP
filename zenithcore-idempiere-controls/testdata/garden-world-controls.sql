-- Development only (GardenWorld demo database). Safe to re-run.
-- Sets up the ZenithCore controls in GardenWorld and creates draft documents to complete through the UI.
--
-- Rules and switches
--   * GL journals always need a second person; supplier payments from 50,000.
--   * Supplier pre-qualification gate and three-way match on, price tolerance 2%.
-- Suppliers
--   * Patio Fun, Inc.: pre-qualified to 30 Jun 2027, tax compliance certificate to 31 Mar 2027.
--   * Seed Farm Inc.: pending.   * Tree Farm Inc.: blacklisted.
-- Draft documents (all dated 10 Nov 2026)
--   * ZC-T1 AP invoice, Patio Fun, three lines from LPO 800002 at LPO price, quantities received: passes.
--   * ZC-T2 AP invoice, Patio Fun, 30 x item of LPO line 121 at 40.00 (LPO 33.75): blocked on price, and once
--     ZC-T1 is completed also on quantity (60 invoiced, 30 received).
--   * ZC-T3 AP invoice, Seed Farm, one line not on an LPO: blocked by the gate and the match.
--   * ZC-T4 GL journal prepared by GardenAdmin (copy of GL journal 1006).

INSERT INTO ZC_DocControl (ZC_DocControl_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_DocControl_UU,DocBaseType,ZC_FromAmount,Description)
SELECT r.id,11,0,'Y',now(),100,now(),100,gen_random_uuid(),r.dbt,r.amt,r.note
FROM (VALUES (1000000,'GLJ',0,'Sample: every journal needs a second person'),
             (1000001,'APP',50000,'Sample: supplier payments from 50,000 need a second person')) r(id,dbt,amt,note)
WHERE NOT EXISTS (SELECT 1 FROM ZC_DocControl x WHERE x.ZC_DocControl_ID=r.id);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000002) WHERE Name='ZC_DocControl' AND IsTableID='Y';

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT s.id,11,0,'Y',now(),100,now(),100,s.nm,s.val,'GardenWorld test','U','C',gen_random_uuid()
FROM (VALUES (1091600,'ZC_SUPPLIER_GATE','Y'),(1091601,'ZC_THREE_WAY_MATCH','Y'),(1091602,'ZC_THREE_WAY_PRICE_TOLERANCE_PCT','2')) s(id,nm,val)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name=s.nm AND x.AD_Client_ID=11);

UPDATE C_BPartner SET ZC_PrequalStatus='Q', ZC_PrequalValidTo='2027-06-30', ZC_TCCNo='KRAWPN0012345', ZC_TCCExpiry='2027-03-31' WHERE C_BPartner_ID=121;
UPDATE C_BPartner SET ZC_PrequalStatus='P' WHERE C_BPartner_ID=120;
UPDATE C_BPartner SET ZC_PrequalStatus='B', ZC_BlacklistReason='Test: fake delivery notes, board minute 14/2026' WHERE C_BPartner_ID=114;

-- November 2026 open for all documents
UPDATE C_PeriodControl pc SET PeriodStatus='O', Updated=now()
FROM C_Period p JOIN C_Year y ON y.C_Year_ID=p.C_Year_ID
WHERE pc.C_Period_ID=p.C_Period_ID AND pc.AD_Client_ID=11 AND y.FiscalYear='2026' AND p.PeriodNo=11 AND pc.PeriodStatus<>'O';

DO $$
DECLARE
  inv RECORD;
BEGIN
  FOR inv IN SELECT * FROM (VALUES (1090001,'ZC-T1',121,800002),(1090002,'ZC-T2',121,800002),(1090003,'ZC-T3',120,NULL::int)) v(id,docno,bp,ord) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_Invoice WHERE C_Invoice_ID=inv.id) THEN
      CREATE TEMP TABLE t_inv ON COMMIT DROP AS SELECT * FROM C_Invoice WHERE DocumentNo='10000002' AND AD_Client_ID=11;
      UPDATE t_inv SET C_Invoice_ID=inv.id, DocumentNo=inv.docno, C_BPartner_ID=inv.bp,
        C_BPartner_Location_ID=(SELECT MIN(C_BPartner_Location_ID) FROM C_BPartner_Location WHERE C_BPartner_ID=inv.bp),
        AD_User_ID=NULL, C_Order_ID=(SELECT C_Order_ID FROM C_Order WHERE DocumentNo=inv.ord::text AND AD_Client_ID=11 AND IsSOTrx='N'),
        DocStatus='DR', DocAction='CO', Processed='N', Posted='N', IsPaid='N', IsApproved='N', Processing='N',
        DateInvoiced='2026-11-10', DateAcct='2026-11-10', DateOrdered='2026-11-10', TotalLines=0, GrandTotal=0,
        Reversal_ID=NULL, C_Payment_ID=NULL, Ref_Invoice_ID=NULL, C_CashLine_ID=NULL, POReference=NULL,
        Description='ZenithCore controls test', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101,
        C_Invoice_UU=gen_random_uuid();
      INSERT INTO C_Invoice SELECT * FROM t_inv;
      DROP TABLE t_inv;
    END IF;
  END LOOP;
END $$;

-- invoice lines: copy a template line and set the item, LPO line, quantity and price
DO $$
DECLARE
  l RECORD;
BEGIN
  FOR l IN SELECT * FROM (VALUES
      (1090011,1090001,10,121,30::numeric,NULL::numeric),
      (1090012,1090001,20,122,12,NULL),
      (1090013,1090001,30,123,15,NULL),
      (1090021,1090002,10,121,30,40.00),
      (1090031,1090003,10,NULL,5,120.00)) v(id,inv,lineno,ol,qty,price) LOOP
    IF NOT EXISTS (SELECT 1 FROM C_InvoiceLine WHERE C_InvoiceLine_ID=l.id) THEN
      CREATE TEMP TABLE t_l ON COMMIT DROP AS
        SELECT il.* FROM C_InvoiceLine il JOIN C_Invoice i ON i.C_Invoice_ID=il.C_Invoice_ID
        WHERE i.DocumentNo='10000002' AND i.AD_Client_ID=11 ORDER BY il.Line LIMIT 1;
      UPDATE t_l SET C_InvoiceLine_ID=l.id, C_Invoice_ID=l.inv, Line=l.lineno, C_OrderLine_ID=l.ol, M_InOutLine_ID=NULL,
        M_Product_ID=COALESCE((SELECT M_Product_ID FROM C_OrderLine WHERE C_OrderLine_ID=l.ol), t_l.M_Product_ID),
        C_UOM_ID=COALESCE((SELECT C_UOM_ID FROM C_OrderLine WHERE C_OrderLine_ID=l.ol), t_l.C_UOM_ID),
        C_Tax_ID=COALESCE((SELECT C_Tax_ID FROM C_OrderLine WHERE C_OrderLine_ID=l.ol), t_l.C_Tax_ID),
        QtyInvoiced=l.qty, QtyEntered=l.qty,
        PriceActual=COALESCE(l.price,(SELECT PriceActual FROM C_OrderLine WHERE C_OrderLine_ID=l.ol)),
        PriceEntered=COALESCE(l.price,(SELECT PriceActual FROM C_OrderLine WHERE C_OrderLine_ID=l.ol)),
        PriceList=COALESCE(l.price,(SELECT PriceList FROM C_OrderLine WHERE C_OrderLine_ID=l.ol)),
        Processed='N', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, A_Asset_ID=NULL,
        C_InvoiceLine_UU=gen_random_uuid();
      UPDATE t_l SET LineNetAmt=QtyInvoiced*PriceActual, LineTotalAmt=QtyInvoiced*PriceActual, TaxAmt=0;
      INSERT INTO C_InvoiceLine SELECT * FROM t_l;
      DROP TABLE t_l;
    END IF;
  END LOOP;
END $$;

-- GL journal prepared by GardenAdmin
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM GL_Journal WHERE GL_Journal_ID=1090004) THEN
    CREATE TEMP TABLE t_j ON COMMIT DROP AS SELECT * FROM GL_Journal WHERE GL_Journal_ID=1000003;
    UPDATE t_j SET GL_Journal_ID=1090004, DocumentNo='ZC-T4', DocStatus='DR', DocAction='CO', Processed='N', Posted='N',
      IsApproved='N', Processing='N', Reversal_ID=NULL, DateDoc='2026-11-10', DateAcct='2026-11-10',
      C_Period_ID=(SELECT p.C_Period_ID FROM C_Period p JOIN C_Year y ON y.C_Year_ID=p.C_Year_ID JOIN C_Calendar c ON c.C_Calendar_ID=y.C_Calendar_ID
                   WHERE c.AD_Client_ID=11 AND y.FiscalYear='2026' AND p.PeriodNo=11),
      Description='ZenithCore controls test: accrual', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101,
      GL_Journal_UU=gen_random_uuid();
    INSERT INTO GL_Journal SELECT * FROM t_j;
    CREATE TEMP TABLE t_jl ON COMMIT DROP AS SELECT * FROM GL_JournalLine WHERE GL_Journal_ID=1000003;
    UPDATE t_jl SET GL_JournalLine_ID=GL_JournalLine_ID+90000, GL_Journal_ID=1090004, Processed='N', DateAcct='2026-11-10',
      Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, GL_JournalLine_UU=gen_random_uuid();
    INSERT INTO GL_JournalLine SELECT * FROM t_jl;
  END IF;
END $$;

-- An audit query assigned to GardenAdmin, without a response or evidence yet
INSERT INTO ZC_AuditQuery (ZC_AuditQuery_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_AuditQuery_UU,
  Value,Name,ZC_AuditSource,ZC_AuditYear,ZC_RiskRating,ZC_Finding,ZC_Recommendation,ZC_ResponsibleUser_ID,ZC_DueDate,ZC_AuditQueryStatus,Processed)
SELECT 1000000,11,0,'Y',now(),100,now(),100,gen_random_uuid(),'ML 2024/25-07','Unreconciled suspense account','EA','2024/25','H',
  'Suspense account 2999 carried an unexplained balance of KES 4.2 million at year end.',
  'Investigate, clear the balance with supporting journals and reconcile the account monthly.',101,'2026-12-31','OP','N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_AuditQuery WHERE ZC_AuditQuery_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_AuditQuery' AND IsTableID='Y';
