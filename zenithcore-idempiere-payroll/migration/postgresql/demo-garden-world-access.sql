-- Development only: give the GardenWorld Admin demo role access to the ZenithCore process.
INSERT INTO AD_Process_Access (AD_Process_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Process_Access_UU)
SELECT 1090000,102,11,0,'Y',now(),100,now(),100,'Y','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0501'
WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Access WHERE AD_Process_ID=1090000 AND AD_Role_ID=102);
