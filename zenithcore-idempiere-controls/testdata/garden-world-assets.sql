-- GardenWorld test data for asset tags and verification. Test only: assets, serial numbers and rooms are
-- invented. Safe to re-run.
--   * tag prefix GW- (6 digits), label text
--   * locations: Incubation Block A Room 12 (responsible GardenAdmin), Workshop 1, Boardroom
--   * 8 assets without tags: 4 in Block A (custodian GardenUser), 2 in Workshop 1 (custodian GardenAdmin),
--     1 in the Boardroom, 1 vehicle with no location
--   * verification "Block A, November 2026 (test)" of Block A, verifier GardenUser (the custodian: to be refused)
-- Assign tags with Assign Asset Tags, then Load Assets on the verification.

INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU)
SELECT v.id,11,0,'Y',now(),100,now(),100,v.name,v.val,'GardenWorld test','ZENITH','C',gen_random_uuid()
FROM (VALUES (1091616,'ZC_ASSET_TAG_PREFIX','GW-'),(1091617,'ZC_ASSET_TAG_DIGITS','6'),
             (1091618,'ZC_ASSET_LABEL_TEXT','Property of GardenWorld. If found, call +254 700 000 000 (test).')) v(id,name,val)
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig s WHERE s.AD_SysConfig_ID=v.id);

INSERT INTO ZC_AssetLocation (ZC_AssetLocation_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_AssetLocation_UU,Value,Name,ZC_ResponsibleUser_ID)
SELECT v.id,11,0,'Y',now(),100,now(),100,gen_random_uuid(),v.val,v.nm,v.resp
FROM (VALUES (1000000,'BLK-A-12','Incubation Block A, Room 12',101),(1000001,'WKS-1','Workshop 1',101),(1000002,'BOARD','Boardroom',101)) v(id,val,nm,resp)
WHERE NOT EXISTS (SELECT 1 FROM ZC_AssetLocation l WHERE l.ZC_AssetLocation_ID=v.id);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000003) WHERE Name='ZC_AssetLocation' AND IsTableID='Y';

INSERT INTO A_Asset (A_Asset_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,A_Asset_UU,Value,Name,
  A_Asset_Group_ID,M_Product_ID,SerNo,IsDepreciated,IsDisposed,IsFullyDepreciated,IsInPosession,IsOwned,A_Asset_Status,
  AssetServiceDate,ZC_AssetLocation_ID,ZC_AssetCustodian_ID)
SELECT v.id,11,11,'Y',now(),101,now(),101,gen_random_uuid(),v.val,v.nm,v.grp,129,v.ser,'N','N','N','Y','Y','AC','2025-07-01',v.loc,v.cust
FROM (VALUES
  (1090001,'FA-0001','Laptop Dell Latitude 5440',50003,'DL5440-0001',1000000,102),
  (1090002,'FA-0002','Projector Epson EB-X49',50003,'EPX49-0007',1000000,102),
  (1090003,'FA-0003','Office desk, double pedestal',50004,NULL,1000000,102),
  (1090004,'FA-0004','Laser printer HP LaserJet M404',50003,'HPM404-2231',1000000,102),
  (1090005,'FA-0005','Centre lathe 1.5 m',50007,'LT1500-88',1000001,101),
  (1090006,'FA-0006','Welding set 250 A',50007,'WS250-13',1000001,101),
  (1090007,'FA-0007','Conference table, 12 seats',50004,NULL,1000002,101),
  (1090008,'FA-0008','Toyota Hilux double cabin KDA 123A (test)',50006,'JTF-0000123',NULL::numeric,101)) v(id,val,nm,grp,ser,loc,cust)
WHERE NOT EXISTS (SELECT 1 FROM A_Asset a WHERE a.A_Asset_ID=v.id);

INSERT INTO ZC_AssetVerification (ZC_AssetVerification_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,
  ZC_AssetVerification_UU,Name,DateDoc,ZC_AssetLocation_ID,ZC_VerifierUser_ID,Description,ZC_VerificationStatus,Processed)
SELECT 1000000,11,0,'Y',now(),101,now(),101,gen_random_uuid(),'Block A, November 2026 (test)','2026-11-10',1000000,102,
  'Board of survey appointed by the CEO, letter ref. TEST/2026/11 (test)','DR','N'
WHERE NOT EXISTS (SELECT 1 FROM ZC_AssetVerification WHERE ZC_AssetVerification_ID=1000000);
UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000001) WHERE Name='ZC_AssetVerification' AND IsTableID='Y';
