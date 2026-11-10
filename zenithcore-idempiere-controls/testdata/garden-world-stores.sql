-- Development only (GardenWorld demo database). Safe to re-run.
-- Stores controls in GardenWorld:
--   * HQ Warehouse custodian: GardenUser. Stores control on; write-offs from 100.00 need independent approval.
--   * Adjustment reasons: CNT count variance, DMG damaged, EXP expired, THF theft or loss, OBS obsolete (write-offs);
--     ISS issue to cost centre, COR data correction (not write-offs).
-- Draft documents in HQ Warehouse, dated 10 Nov 2026:
--   * ZC-T12 count prepared by GardenUser: Rose Bush book 20, counted 21, no reason -> blocked (reason).
--   * ZC-T13 count prepared by GardenUser: Oak Tree book 20, counted 18, reason Damaged (about 104 at average cost)
--     -> GardenUser cannot approve their own store's write-off; GardenAdmin can.
--   * ZC-T14 count prepared by GardenAdmin: Elm Tree book 15, counted 14, Damaged -> blocked (not prepared by custodian).
--   * ZC-T15 vendor receipt of 1 Oak Tree into HQ Warehouse -> GardenAdmin cannot complete it; GardenUser can.

UPDATE M_Warehouse SET ZC_CustodianUser_ID=102 WHERE M_Warehouse_ID=103 AND ZC_CustodianUser_ID IS NULL;

INSERT INTO ZC_AdjustmentReason (ZC_AdjustmentReason_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_AdjustmentReason_UU,Value,Name,ZC_IsWriteOff)
SELECT r.id,11,0,'Y',now(),100,now(),100,gen_random_uuid(),r.v,r.n,r.w
FROM (VALUES (1000000,'CNT','Count variance','Y'),(1000001,'DMG','Damaged','Y'),(1000002,'EXP','Expired','Y'),
             (1000003,'THF','Theft or loss','Y'),(1000004,'OBS','Obsolete','Y'),(1000005,'ISS','Issue to cost centre','N'),
             (1000006,'COR','Data correction','N')) r(id,v,n,w)
WHERE NOT EXISTS (SELECT 1 FROM ZC_AdjustmentReason x WHERE x.ZC_AdjustmentReason_ID=r.id);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000007) WHERE Name='ZC_AdjustmentReason' AND IsTableID='Y';

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT s.id,11,0,'Y',now(),100,now(),100,s.nm,s.val,'GardenWorld test','U','C',gen_random_uuid()
FROM (VALUES (1091608,'ZC_STORES_CONTROL','Y'),(1091609,'ZC_WRITEOFF_APPROVAL_FROM','100')) s(id,nm,val)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name=s.nm AND x.AD_Client_ID=11);

DO $$
DECLARE
  c RECORD;
BEGIN
  FOR c IN SELECT * FROM (VALUES
      (1090012,'ZC-T12',102,127,20::numeric,21::numeric,NULL::int),
      (1090013,'ZC-T13',102,123,20,18,1000001),
      (1090014,'ZC-T14',101,124,15,14,1000001)) v(id,docno,prep,prod,book,cnt,reason) LOOP
    IF NOT EXISTS (SELECT 1 FROM M_Inventory WHERE M_Inventory_ID=c.id) THEN
      CREATE TEMP TABLE t_i ON COMMIT DROP AS SELECT * FROM M_Inventory WHERE DocumentNo='10000000' AND AD_Client_ID=11;
      UPDATE t_i SET M_Inventory_ID=c.id, DocumentNo=c.docno, DocStatus='DR', DocAction='CO', Processed='N', Posted='N',
        Processing='N', IsApproved='N', MovementDate='2026-11-10', Reversal_ID=NULL, Description='ZenithCore stores test',
        Created=now(), Updated=now(), CreatedBy=c.prep, UpdatedBy=c.prep, M_Inventory_UU=gen_random_uuid();
      INSERT INTO M_Inventory SELECT * FROM t_i;
      CREATE TEMP TABLE t_il ON COMMIT DROP AS
        SELECT l.* FROM M_InventoryLine l JOIN M_Inventory i ON i.M_Inventory_ID=l.M_Inventory_ID WHERE i.DocumentNo='10000000' AND i.AD_Client_ID=11 LIMIT 1;
      UPDATE t_il SET M_InventoryLine_ID=c.id+10000, M_Inventory_ID=c.id, Line=10, M_Product_ID=c.prod, M_AttributeSetInstance_ID=0,
        M_Locator_ID=101, QtyBook=c.book, QtyCount=c.cnt, QtyInternalUse=0, ZC_AdjustmentReason_ID=c.reason, Processed='N',
        Created=now(), Updated=now(), CreatedBy=c.prep, UpdatedBy=c.prep, M_InventoryLine_UU=gen_random_uuid();
      INSERT INTO M_InventoryLine SELECT * FROM t_il;
      DROP TABLE t_i; DROP TABLE t_il;
    END IF;
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM M_InOut WHERE M_InOut_ID=1090015) THEN
    CREATE TEMP TABLE t_io ON COMMIT DROP AS SELECT * FROM M_InOut WHERE DocumentNo='10000004' AND AD_Client_ID=11 AND IsSOTrx='N';
    UPDATE t_io SET M_InOut_ID=1090015, DocumentNo='ZC-T15', C_Order_ID=NULL, DocStatus='DR', DocAction='CO', Processed='N',
      Posted='N', Processing='N', IsApproved='N', MovementDate='2026-11-10', DateAcct='2026-11-10', Reversal_ID=NULL,
      C_Invoice_ID=NULL, POReference=NULL, Description='ZenithCore stores test', Created=now(), Updated=now(), CreatedBy=101,
      UpdatedBy=101, M_InOut_UU=gen_random_uuid();
    INSERT INTO M_InOut SELECT * FROM t_io;
    CREATE TEMP TABLE t_iol ON COMMIT DROP AS
      SELECT l.* FROM M_InOutLine l JOIN M_InOut h ON h.M_InOut_ID=l.M_InOut_ID WHERE h.DocumentNo='10000004' AND h.AD_Client_ID=11 AND h.IsSOTrx='N' ORDER BY l.Line LIMIT 1;
    UPDATE t_iol SET M_InOutLine_ID=1100015, M_InOut_ID=1090015, Line=10, C_OrderLine_ID=NULL, M_Product_ID=123,
      M_AttributeSetInstance_ID=0, M_Locator_ID=101, MovementQty=1, QtyEntered=1, Processed='N', IsInvoiced='N',
      Created=now(), Updated=now(), CreatedBy=101, UpdatedBy=101, M_InOutLine_UU=gen_random_uuid();
    INSERT INTO M_InOutLine SELECT * FROM t_iol;
  END IF;
END $$;
