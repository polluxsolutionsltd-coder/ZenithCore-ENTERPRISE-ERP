-- Development only (GardenWorld demo database). Safe to re-run.
-- Run AFTER "Set Up Payroll Roles" has been run in GardenWorld (as GardenAdmin).
-- Adds three test users, one per payroll role, password = user name:
--   PayrollOfficer -> Payroll Officer, HRHead -> HR Head, FinanceHead -> Finance Head.
-- The users are copies of GardenUser without its business partner link.
INSERT INTO AD_User (AD_User_ID,Name,Value,Password,AD_User_UU,Description,Created,Updated,ad_client_id,ad_org_id,isactive,createdby,updatedby,processing,emailuser,emailuserpw,c_greeting_id,title,comments,phone,phone2,fax,lastcontact,lastresult,birthday,ad_orgtrx_id,emailverify,emailverifydate,notificationtype,isfullbpaccess,c_job_id,ldapuser,connectionprofile,userpin,isinpayroll,ismenuautoexpand,salt,islocked,dateaccountlocked,isnopasswordreset,isexpired,securityquestion,answer,issaleslead,c_location_id,leadsource,leadstatus,leadsourcedescription,leadstatusdescription,c_campaign_id,salesrep_id,bpname,bp_location_id,isaddmailtextautomatically,r_defaultmailtext_id,ad_image_id,isnoexpire,issupportuser,isbillto,isshipto,isvendorlead,authenticationtype,passwordhashalgorithm,saltalgorithm)
SELECT u.id,u.nm,lower(u.nm),u.nm,gen_random_uuid(),'ZenithCore payroll test user',now(),now(),ad_client_id,ad_org_id,isactive,createdby,updatedby,processing,emailuser,emailuserpw,c_greeting_id,title,comments,phone,phone2,fax,lastcontact,lastresult,birthday,ad_orgtrx_id,emailverify,emailverifydate,notificationtype,isfullbpaccess,c_job_id,ldapuser,connectionprofile,userpin,isinpayroll,ismenuautoexpand,salt,islocked,dateaccountlocked,isnopasswordreset,isexpired,securityquestion,answer,issaleslead,c_location_id,leadsource,leadstatus,leadsourcedescription,leadstatusdescription,c_campaign_id,salesrep_id,bpname,bp_location_id,isaddmailtextautomatically,r_defaultmailtext_id,ad_image_id,isnoexpire,issupportuser,isbillto,isshipto,isvendorlead,authenticationtype,passwordhashalgorithm,saltalgorithm
FROM AD_User g, (VALUES (1090911,'PayrollOfficer'),(1090912,'HRHead'),(1090913,'FinanceHead')) u(id,nm)
WHERE g.AD_User_ID=102 AND NOT EXISTS (SELECT 1 FROM AD_User x WHERE x.AD_User_ID=u.id);

INSERT INTO AD_User_Roles (AD_User_ID,AD_Role_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,AD_User_Roles_UU)
SELECT u.id,r.AD_Role_ID,11,0,'Y',now(),100,now(),100,gen_random_uuid()
FROM (VALUES (1090911,'Payroll Officer'),(1090912,'HR Head'),(1090913,'Finance Head')) u(id,role)
JOIN AD_Role r ON r.Name=u.role AND r.AD_Client_ID=11
WHERE NOT EXISTS (SELECT 1 FROM AD_User_Roles x WHERE x.AD_User_ID=u.id AND x.AD_Role_ID=r.AD_Role_ID);
