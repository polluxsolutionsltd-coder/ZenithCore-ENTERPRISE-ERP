-- Development only (GardenWorld demo database). Safe to re-run.
-- Run AFTER "Set Up Phase 1 Roles" has been run in GardenWorld (as GardenAdmin).
-- Adds one test user per Phase 1 role, password = user name, and gives GardenUser (HQ Warehouse custodian) the
-- Store Keeper role:
--   GardenUser -> Store Keeper, Counter -> Stock Counter, Inspector -> Inspection Committee,
--   Buyer -> Procurement Officer, Cashier -> Cashier, FinOfficer -> Finance Officer, FinManager -> Finance Manager,
--   BoardChair -> Board Pack Approver, Verifier -> Asset Verifier.
-- Planted for the Roles and Duties Report: Buyer is also on the Inspection Committee (inserted here, so the
-- assignment check does not see it). Then switches the check on (ZC_ROLE_CONFLICTS=Y).
-- The users are copies of GardenUser without its business partner link.
INSERT INTO AD_User (AD_User_ID,Name,Value,Password,AD_User_UU,Description,Created,Updated,ad_client_id,ad_org_id,isactive,createdby,updatedby,processing,emailuser,emailuserpw,c_greeting_id,title,comments,phone,phone2,fax,lastcontact,lastresult,birthday,ad_orgtrx_id,emailverify,emailverifydate,notificationtype,isfullbpaccess,c_job_id,ldapuser,connectionprofile,userpin,isinpayroll,ismenuautoexpand,salt,islocked,dateaccountlocked,isnopasswordreset,isexpired,securityquestion,answer,issaleslead,c_location_id,leadsource,leadstatus,leadsourcedescription,leadstatusdescription,c_campaign_id,salesrep_id,bpname,bp_location_id,isaddmailtextautomatically,r_defaultmailtext_id,ad_image_id,isnoexpire,issupportuser,isbillto,isshipto,isvendorlead,authenticationtype,passwordhashalgorithm,saltalgorithm)
SELECT u.id,u.nm,lower(u.nm),u.nm,gen_random_uuid(),'ZenithCore Phase 1 roles test user',now(),now(),ad_client_id,ad_org_id,isactive,createdby,updatedby,processing,emailuser,emailuserpw,c_greeting_id,title,comments,phone,phone2,fax,lastcontact,lastresult,birthday,ad_orgtrx_id,emailverify,emailverifydate,notificationtype,isfullbpaccess,c_job_id,ldapuser,connectionprofile,userpin,isinpayroll,ismenuautoexpand,salt,islocked,dateaccountlocked,isnopasswordreset,isexpired,securityquestion,answer,issaleslead,c_location_id,leadsource,leadstatus,leadsourcedescription,leadstatusdescription,c_campaign_id,salesrep_id,bpname,bp_location_id,isaddmailtextautomatically,r_defaultmailtext_id,ad_image_id,isnoexpire,issupportuser,isbillto,isshipto,isvendorlead,authenticationtype,passwordhashalgorithm,saltalgorithm
FROM AD_User g, (VALUES (1090921,'Counter'),(1090922,'Inspector'),(1090923,'Buyer'),(1090924,'Cashier'),(1090925,'FinOfficer'),
      (1090926,'FinManager'),(1090927,'BoardChair'),(1090928,'Verifier')) u(id,nm)
WHERE g.AD_User_ID=102 AND NOT EXISTS (SELECT 1 FROM AD_User x WHERE x.AD_User_ID=u.id);;

INSERT INTO AD_User_Roles (AD_User_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,AD_User_Roles_UU)
SELECT u.id,r.AD_Role_ID,11,0,'Y',now(),100,now(),100,gen_random_uuid()
FROM (VALUES (102,'Store Keeper'),(1090921,'Stock Counter'),(1090922,'Inspection Committee'),(1090923,'Procurement Officer'),
             (1090923,'Inspection Committee'),(1090924,'Cashier'),(1090925,'Finance Officer'),(1090926,'Finance Manager'),
             (1090927,'Board Pack Approver'),(1090928,'Asset Verifier')) u(id,role)
JOIN AD_Role r ON r.Name=u.role AND r.AD_Client_ID=11
WHERE NOT EXISTS (SELECT 1 FROM AD_User_Roles x WHERE x.AD_User_ID=u.id AND x.AD_Role_ID=r.AD_Role_ID);

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT 1091620,11,0,'Y',now(),100,now(),100,'ZC_ROLE_CONFLICTS','Y','GardenWorld test','U','C',gen_random_uuid()
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig x WHERE x.Name='ZC_ROLE_CONFLICTS' AND x.AD_Client_ID=11);
