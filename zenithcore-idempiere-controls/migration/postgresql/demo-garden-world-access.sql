-- Development only: gives the GardenWorld Admin role (102) access to the ZenithCore controls windows and process.
INSERT INTO AD_Window_Access (AD_Window_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Window_Access_UU)
SELECT w.id,102,11,0,'Y',now(),100,now(),100,'Y',gen_random_uuid()
FROM (VALUES (1091500),(1091501),(1091502)) w(id)
WHERE NOT EXISTS (SELECT 1 FROM AD_Window_Access x WHERE x.AD_Window_ID=w.id AND x.AD_Role_ID=102);
INSERT INTO AD_Process_Access (AD_Process_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Process_Access_UU)
SELECT p.id,102,11,0,'Y',now(),100,now(),100,'Y',gen_random_uuid()
FROM (VALUES (1091500)) p(id)
WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Access x WHERE x.AD_Process_ID=p.id AND x.AD_Role_ID=102);
