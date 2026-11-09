-- Development only (GardenWorld demo database): give the GardenWorld Admin (102) and
-- GardenWorld User (103) roles access to ZenithCore windows and processes. Safe to re-run.
INSERT INTO AD_Process_Access (AD_Process_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Process_Access_UU)
SELECT p.id,r.id,11,0,'Y',now(),100,now(),100,'Y',gen_random_uuid()
FROM (VALUES (1090000),(1090001),(1090002),(1090003),(1090004),(1090005),(1090006),(1090007),(1090008)) p(id), (VALUES (102),(103)) r(id)
WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Access WHERE AD_Process_ID=p.id AND AD_Role_ID=r.id);

INSERT INTO AD_Window_Access (AD_Window_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,IsReadWrite,AD_Window_Access_UU)
SELECT w.id,r.id,11,0,'Y',now(),100,now(),100,'Y',gen_random_uuid()
FROM (VALUES (1090000),(1090001),(1090002),(1090003)) w(id), (VALUES (102),(103)) r(id)
WHERE NOT EXISTS (SELECT 1 FROM AD_Window_Access WHERE AD_Window_ID=w.id AND AD_Role_ID=r.id);
