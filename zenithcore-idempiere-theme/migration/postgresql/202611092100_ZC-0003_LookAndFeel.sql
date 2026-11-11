-- ZC-0003 ZenithCore look and feel. Switches the web UI to the "zenithcore" theme
-- (bundle ke.co.pollux.zenithcore.theme), sets the browser title and a white desktop header.
-- System-level settings. Safe to re-run.
INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT c.id,0,0,'Y',now(),100,now(),100,c.name,c.val,c.descr,'ZENITH','S',c.uu::uuid
FROM (VALUES
 (1090005,'ZK_THEME','zenithcore','ZenithCore web UI theme','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0606'),
 (1090003,'ZK_BROWSER_TITLE','ZenithCore Enterprise ERP','Browser tab title','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0604'),
 (1090004,'ZK_DESKTOP_HEADER_BACKGROUND_COLOR','#FFFFFF','Desktop header background','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0605')
) AS c(id,name,val,descr,uu)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig s WHERE s.Name=c.name AND s.AD_Client_ID=0 AND s.AD_Org_ID=0);

-- iDempiere seeds some of these keys; point them at ZenithCore
UPDATE AD_SysConfig SET Value='zenithcore', Updated=now() WHERE Name='ZK_THEME' AND AD_Client_ID=0 AND AD_Org_ID=0 AND Value<>'zenithcore';
UPDATE AD_SysConfig SET Value='ZenithCore Enterprise ERP', Updated=now() WHERE Name='ZK_BROWSER_TITLE' AND AD_Client_ID=0 AND AD_Org_ID=0 AND Value<>'ZenithCore Enterprise ERP';
UPDATE AD_SysConfig SET Value='#FFFFFF', Updated=now() WHERE Name='ZK_DESKTOP_HEADER_BACKGROUND_COLOR' AND AD_Client_ID=0 AND AD_Org_ID=0 AND Value<>'#FFFFFF';
-- Logos come from the theme's images folder; remove any logo overrides so the theme's are used
DELETE FROM AD_SysConfig WHERE Name IN ('ZK_LOGO_LARGE','ZK_LOGO_SMALL','ZK_BROWSER_ICON') AND AD_Client_ID=0 AND AD_Org_ID=0 AND Value LIKE '~./zenithcore/%';
