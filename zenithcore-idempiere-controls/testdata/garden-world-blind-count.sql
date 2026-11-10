-- Development only (GardenWorld demo database). Safe to re-run. Run after garden-world-stores.sql.
-- Blind counts in GardenWorld: blind counting on.
--   * ZC-T16 count sheet for HQ Warehouse prepared by GardenUser (custodian): Oak Tree, Elm Tree, Azalea Bush,
--     Holly Bush, nothing counted yet (no book quantities on the sheet).
--   * ZC-T17 count for HQ Warehouse prepared by GardenUser, not submitted: posting it is refused.
INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091610,11,0,'Y',now(),100,now(),100,'ZC_BLIND_COUNT','Y','GardenWorld test','U','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name='ZC_BLIND_COUNT' AND x.AD_Client_ID=11);

DO $$
DECLARE
  c RECORD;
  n int;
  prod int;
BEGIN
  FOR c IN SELECT * FROM (VALUES (1090016,'ZC-T16'),(1090017,'ZC-T17')) v(id,docno) LOOP
    IF NOT EXISTS (SELECT 1 FROM M_Inventory WHERE M_Inventory_ID=c.id) THEN
      CREATE TEMP TABLE t_i ON COMMIT DROP AS SELECT * FROM M_Inventory WHERE M_Inventory_ID=1090013;
      UPDATE t_i SET M_Inventory_ID=c.id, DocumentNo=c.docno, DocStatus='DR', DocAction='CO', Processed='N', Posted='N',
        Processing='N', IsApproved='N', ZC_CountStatus='O', Description='ZenithCore blind count test', Created=now(),
        Updated=now(), CreatedBy=102, UpdatedBy=102, M_Inventory_UU=gen_random_uuid();
      INSERT INTO M_Inventory SELECT * FROM t_i;
      DROP TABLE t_i;
      n := 0;
      FOREACH prod IN ARRAY CASE WHEN c.id=1090016 THEN ARRAY[123,124,128,129] ELSE ARRAY[130] END LOOP
        n := n + 10;
        CREATE TEMP TABLE t_l ON COMMIT DROP AS SELECT * FROM M_InventoryLine WHERE M_InventoryLine_ID=1100013;
        UPDATE t_l SET M_InventoryLine_ID=c.id*100+n, M_Inventory_ID=c.id, Line=n, M_Product_ID=prod, QtyBook=0, QtyCount=0,
          QtyEntered=0, ZC_QtyCounted=NULL, ZC_CountedUser_ID=NULL, ZC_DateCounted=NULL, ZC_AdjustmentReason_ID=NULL, Description=NULL,
          Processed='N', Created=now(), Updated=now(), CreatedBy=102, UpdatedBy=102, M_InventoryLine_UU=gen_random_uuid();
        INSERT INTO M_InventoryLine SELECT * FROM t_l;
        DROP TABLE t_l;
      END LOOP;
    END IF;
  END LOOP;
END $$;
