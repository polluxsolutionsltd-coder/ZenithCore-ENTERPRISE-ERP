-- Development only (GardenWorld demo database). Safe to re-run. Run after garden-world-stores.sql.
-- Goods inspection in GardenWorld: inspection on; HQ Warehouse gets a quarantine and a rejected location.
-- Drafts dated 10 Nov 2026 (prepared by GardenAdmin; GardenUser is the HQ Warehouse custodian):
--   * ZC-T18 goods received note: 10 Oak Trees into the normal location -> refused (must go to quarantine).
--   * ZC-T20 LPO: 10 Holly Bush from Patio Fun at 18.00.
--   * ZC-T19 goods received note for ZC-T20 into quarantine.
--   * ZC-T21 supplier invoice for ZC-T20, 10 Holly Bush -> refused until inspected, and then for more than accepted.
--   * ZC-T22 internal use of 1 Holly Bush from quarantine (prepared by GardenUser) -> refused.
--   * ZC-I1 inspection of ZC-T19 (no lines yet).
INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091611,11,0,'Y',now(),100,now(),100,'ZC_GRN_INSPECTION','Y','GardenWorld test','U','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name='ZC_GRN_INSPECTION' AND x.AD_Client_ID=11);

DO $$
DECLARE
  r RECORD;
BEGIN
  FOR r IN SELECT * FROM (VALUES (1090001,'HQ Quarantine','Q'),(1090002,'HQ Rejected','R')) v(id,val,use) LOOP
    IF NOT EXISTS (SELECT 1 FROM M_Locator WHERE M_Locator_ID=r.id) THEN
      CREATE TEMP TABLE t_loc ON COMMIT DROP AS SELECT * FROM M_Locator WHERE M_Locator_ID=101;
      UPDATE t_loc SET M_Locator_ID=r.id, Value=r.val, X=r.use, Y='0', Z='0', IsDefault='N', ZC_LocatorUse=r.use,
        Created=now(), Updated=now(), M_Locator_UU=gen_random_uuid();
      INSERT INTO M_Locator SELECT * FROM t_loc;
      DROP TABLE t_loc;
    END IF;
  END LOOP;

  -- LPO ZC-T20: 10 Holly Bush at 18.00
  IF NOT EXISTS (SELECT 1 FROM C_Order WHERE C_Order_ID=1090020) THEN
    CREATE TEMP TABLE t_o ON COMMIT DROP AS SELECT * FROM C_Order WHERE C_Order_ID=1090005;
    UPDATE t_o SET C_Order_ID=1090020, DocumentNo='ZC-T20', C_Activity_ID=NULL, DocStatus='DR', DocAction='CO', Processed='N',
      Posted='N', IsApproved='N', IsDelivered='N', IsInvoiced='N', TotalLines=0, GrandTotal=0, M_Warehouse_ID=103,
      Description='ZenithCore inspection test', Created=now(), Updated=now(), C_Order_UU=gen_random_uuid();
    INSERT INTO C_Order SELECT * FROM t_o;
    CREATE TEMP TABLE t_ol ON COMMIT DROP AS
      SELECT l.* FROM C_OrderLine l JOIN C_Order h ON h.C_Order_ID=l.C_Order_ID WHERE h.DocumentNo='800002' AND h.AD_Client_ID=11 AND h.IsSOTrx='N'
      ORDER BY l.Line LIMIT 1;
    UPDATE t_ol SET C_OrderLine_ID=1100020, C_Order_ID=1090020, Line=10, M_Product_ID=129, C_Charge_ID=NULL,
      M_AttributeSetInstance_ID=0, M_Warehouse_ID=103, QtyOrdered=10, QtyEntered=10, QtyReserved=0, QtyDelivered=0,
      QtyInvoiced=0, PriceActual=18, PriceEntered=18, PriceList=18, LineNetAmt=180, Processed='N',
      DateOrdered='2026-11-10', DatePromised='2026-11-10', Created=now(), Updated=now(), C_OrderLine_UU=gen_random_uuid();
    INSERT INTO C_OrderLine SELECT * FROM t_ol;
  END IF;

  -- goods received notes ZC-T18 (normal location) and ZC-T19 (quarantine, for ZC-T20)
  FOR r IN SELECT * FROM (VALUES (1090018,'ZC-T18',NULL::int,NULL::int,123,101),(1090019,'ZC-T19',1090020,1100020,129,1090001)) v(id,docno,ord,ol,prod,loc) LOOP
    IF NOT EXISTS (SELECT 1 FROM M_InOut WHERE M_InOut_ID=r.id) THEN
      CREATE TEMP TABLE t_io ON COMMIT DROP AS SELECT * FROM M_InOut WHERE M_InOut_ID=1090015;
      UPDATE t_io SET M_InOut_ID=r.id, DocumentNo=r.docno, C_Order_ID=r.ord, DocStatus='DR', DocAction='CO', Processed='N',
        Posted='N', Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, M_InOut_UU=gen_random_uuid();
      INSERT INTO M_InOut SELECT * FROM t_io;
      CREATE TEMP TABLE t_iol ON COMMIT DROP AS SELECT * FROM M_InOutLine WHERE M_InOutLine_ID=1100015;
      UPDATE t_iol SET M_InOutLine_ID=r.id+10000, M_InOut_ID=r.id, C_OrderLine_ID=r.ol, M_Product_ID=r.prod, M_Locator_ID=r.loc,
        MovementQty=10, QtyEntered=10, Processed='N', Created=now(), Updated=now(), M_InOutLine_UU=gen_random_uuid();
      INSERT INTO M_InOutLine SELECT * FROM t_iol;
      DROP TABLE t_io; DROP TABLE t_iol;
    END IF;
  END LOOP;

  -- supplier invoice ZC-T21 for ZC-T20
  IF NOT EXISTS (SELECT 1 FROM C_Invoice WHERE C_Invoice_ID=1090021) THEN
    CREATE TEMP TABLE t_inv ON COMMIT DROP AS SELECT * FROM C_Invoice WHERE C_Invoice_ID=1090001;
    UPDATE t_inv SET C_Invoice_ID=1090021, DocumentNo='ZC-T21', C_Order_ID=1090020, C_Activity_ID=NULL, DocStatus='DR', DocAction='CO',
      Processed='N', Posted='N', IsPaid='N', TotalLines=0, GrandTotal=0, Description='ZenithCore inspection test',
      Created=now(), Updated=now(), C_Invoice_UU=gen_random_uuid();
    INSERT INTO C_Invoice SELECT * FROM t_inv;
    CREATE TEMP TABLE t_l ON COMMIT DROP AS SELECT * FROM C_InvoiceLine WHERE C_InvoiceLine_ID=1090011;
    UPDATE t_l SET C_InvoiceLine_ID=1090211, C_Invoice_ID=1090021, Line=10, C_OrderLine_ID=1100020, M_Product_ID=129,
      QtyInvoiced=10, QtyEntered=10, PriceActual=18, PriceEntered=18, PriceList=18, LineNetAmt=180, LineTotalAmt=180,
      ZC_WHTType_ID=NULL, Processed='N', Created=now(), Updated=now(), C_InvoiceLine_UU=gen_random_uuid();
    INSERT INTO C_InvoiceLine SELECT * FROM t_l;
  END IF;

  -- internal use ZC-T22 from quarantine, prepared by the custodian
  IF NOT EXISTS (SELECT 1 FROM M_Inventory WHERE M_Inventory_ID=1090022) THEN
    CREATE TEMP TABLE t_i ON COMMIT DROP AS SELECT * FROM M_Inventory WHERE M_Inventory_ID=1090013;
    UPDATE t_i SET M_Inventory_ID=1090022, DocumentNo='ZC-T22', C_DocType_ID=200000, DocStatus='DR', DocAction='CO',
      Processed='N', Posted='N', ZC_CountStatus='O', Description='ZenithCore inspection test', Created=now(), Updated=now(),
      CreatedBy=102, UpdatedBy=102, M_Inventory_UU=gen_random_uuid();
    INSERT INTO M_Inventory SELECT * FROM t_i;
    CREATE TEMP TABLE t_il ON COMMIT DROP AS SELECT * FROM M_InventoryLine WHERE M_InventoryLine_ID=1100013;
    UPDATE t_il SET M_InventoryLine_ID=1100022, M_Inventory_ID=1090022, M_Product_ID=129, M_Locator_ID=1090001, QtyBook=0,
      QtyCount=0, QtyInternalUse=1, QtyEntered=1, C_Charge_ID=100, ZC_AdjustmentReason_ID=1000005, Processed='N',
      Created=now(), Updated=now(), CreatedBy=102, UpdatedBy=102, M_InventoryLine_UU=gen_random_uuid();
    INSERT INTO M_InventoryLine SELECT * FROM t_il;
  END IF;
END $$;

-- inspection ZC-I1 of ZC-T19 (header only; lines are loaded in the window)
INSERT INTO ZC_Inspection (ZC_Inspection_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_Inspection_UU,M_InOut_ID,ZC_InspectionStatus,Description,Processed)
SELECT 1000000,11,(SELECT AD_Org_ID FROM M_InOut WHERE M_InOut_ID=1090019),'Y',now(),100,now(),100,gen_random_uuid(),1090019,'DR',
  'Inspection committee: test','N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_Inspection WHERE ZC_Inspection_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_Inspection' AND IsTableID='Y';
