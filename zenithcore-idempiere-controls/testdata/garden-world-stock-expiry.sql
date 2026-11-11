-- Development only (GardenWorld demo database). Safe to re-run. Run after garden-world-inspection.sql.
-- Lots with expiry dates for Fertilizer #50 (product 136, attribute set "Fertilizer Lot": lot and guarantee date).
-- The stock rows are inserted directly (test only; in production stock arrives on goods received notes, so
-- iDempiere also writes the transactions and costs). Dates are relative to 10 Nov 2026:
--   * GW-L01 expired 31 Oct 2026, 10 units      * GW-L02 expires 25 Nov 2026 (15 days), 8 units
--   * GW-L03 expires 15 Mar 2027 (125 days), 12  * GW-L04 expires 30 Sep 2027 (324 days), 20 units
--   * the 40 units already on hand have no lot (received before lots were recorded)
-- Expired stock control on (ZC_BLOCK_EXPIRED_ISSUE=Y). Draft ZC-T30 internal use, prepared by GardenUser (the
-- HQ Warehouse custodian): line 10 uses 2 of expired lot GW-L01 for "Issue to cost centre", line 20 uses 1
-- unit without a lot -> refused for both. Line 10 with reason Expired and line 20 on lot GW-L02 -> completes.
INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091619,11,0,'Y',now(),100,now(),100,'ZC_BLOCK_EXPIRED_ISSUE','Y','GardenWorld test','U','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name='ZC_BLOCK_EXPIRED_ISSUE' AND x.AD_Client_ID=11);

INSERT INTO M_AttributeSetInstance (M_AttributeSetInstance_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,M_AttributeSet_ID,Lot,GuaranteeDate,Description,M_AttributeSetInstance_UU)
SELECT v.id,11,0,'Y',now(),100,now(),100,101,v.lot,v.exp::timestamp,v.lot || '_' || to_char(v.exp::date,'DD/MM/YYYY'),gen_random_uuid()
FROM (VALUES (1090001,'GW-L01','2026-10-31'),(1090002,'GW-L02','2026-11-25'),(1090003,'GW-L03','2027-03-15'),
             (1090004,'GW-L04','2027-09-30')) v(id,lot,exp)
WHERE NOT EXISTS (SELECT 1 FROM M_AttributeSetInstance a WHERE a.M_AttributeSetInstance_ID=v.id);

INSERT INTO M_StorageOnHand (AD_Client_ID,AD_Org_ID,Created,CreatedBy,IsActive,M_AttributeSetInstance_ID,M_Locator_ID,M_Product_ID,QtyOnHand,Updated,UpdatedBy,M_StorageOnHand_UU,DateMaterialPolicy)
SELECT 11,11,now(),100,'Y',v.asi,101,136,v.qty,now(),100,gen_random_uuid(),'2026-06-01'
FROM (VALUES (1090001,10),(1090002,8),(1090003,12),(1090004,20)) v(asi,qty)
WHERE NOT EXISTS (SELECT 1 FROM M_StorageOnHand s WHERE s.M_AttributeSetInstance_ID=v.asi AND s.M_Locator_ID=101);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM M_Inventory WHERE M_Inventory_ID=1090030) THEN
    CREATE TEMP TABLE t_i ON COMMIT DROP AS SELECT * FROM M_Inventory WHERE M_Inventory_ID=1090013;
    UPDATE t_i SET M_Inventory_ID=1090030, DocumentNo='ZC-T30', C_DocType_ID=200000, DocStatus='DR', DocAction='CO',
      Processed='N', Posted='N', ZC_CountStatus='O', MovementDate='2026-11-10', Description='ZenithCore expiry test',
      Created=now(), Updated=now(), CreatedBy=102, UpdatedBy=102, M_Inventory_UU=gen_random_uuid();
    INSERT INTO M_Inventory SELECT * FROM t_i;
    CREATE TEMP TABLE t_il ON COMMIT DROP AS SELECT * FROM M_InventoryLine WHERE M_InventoryLine_ID=1100013;
    UPDATE t_il SET M_InventoryLine_ID=1100030, Line=10, M_Inventory_ID=1090030, M_Product_ID=136, M_AttributeSetInstance_ID=1090001,
      M_Locator_ID=101, QtyBook=0, QtyCount=0, QtyInternalUse=2, QtyEntered=2, C_Charge_ID=100, ZC_AdjustmentReason_ID=1000005,
      Processed='N', Created=now(), Updated=now(), CreatedBy=102, UpdatedBy=102, M_InventoryLine_UU=gen_random_uuid();
    INSERT INTO M_InventoryLine SELECT * FROM t_il;
    UPDATE t_il SET M_InventoryLine_ID=1100031, Line=20, M_AttributeSetInstance_ID=0, QtyInternalUse=1, QtyEntered=1,
      M_InventoryLine_UU=gen_random_uuid();
    INSERT INTO M_InventoryLine SELECT * FROM t_il;
  END IF;
END $$;
