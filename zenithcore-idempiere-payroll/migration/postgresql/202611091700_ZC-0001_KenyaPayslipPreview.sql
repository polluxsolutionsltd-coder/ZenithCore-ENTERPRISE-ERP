-- ZC-0001 ZenithCore: entity type, "Kenyan Payslip Preview" process, parameters and menu entry.
-- Dictionary IDs 1090000-1090999 are reserved for ZenithCore system records until the
-- dictionary is shipped as a 2Pack. Re-running this script is safe.

INSERT INTO AD_EntityType (AD_EntityType_ID,EntityType,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,Version,AD_EntityType_UU)
SELECT 1090000,'ZENITH',0,0,'Y',now(),100,now(),100,'ZenithCore','ZenithCore Enterprise ERP by Pollux Solutions Ltd','0.1.0','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0001'
WHERE NOT EXISTS (SELECT 1 FROM AD_EntityType WHERE EntityType='ZENITH');

INSERT INTO AD_Process (AD_Process_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,Description,Help,AccessLevel,EntityType,IsReport,Classname,IsServerProcess,ShowHelp,IsBetaFunctionality,AllowMultipleExecution,AD_Process_UU)
SELECT 1090000,0,0,'Y',now(),100,now(),100,'ZC_KenyaPayslipPreview','Kenyan Payslip Preview',
 'Calculates one Kenyan payslip (PAYE, NSSF, SHIF, Housing Levy, reliefs, two-thirds rule) and shows the working. Saves nothing.',
 'Uses the statutory rate set in force on the pay date. For checking a single case against a worked payslip.',
 '3','ZENITH','N','ke.co.pollux.zenithcore.payroll.idempiere.process.KenyaPayslipPreview','N','Y','N','P','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0101'
WHERE NOT EXISTS (SELECT 1 FROM AD_Process WHERE AD_Process_ID=1090000);

INSERT INTO AD_Process_Para (AD_Process_Para_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Process_ID,SeqNo,AD_Reference_ID,IsRange,FieldLength,IsMandatory,ColumnName,IsCentrallyMaintained,EntityType,IsEncrypted,DefaultValue,AD_Process_Para_UU)
SELECT p.id,0,0,'Y',now(),100,now(),100,p.name,1090000,p.seq,p.ref,'N',p.len,p.mand,p.col,'N','ZENITH','N',p.def,p.uu::uuid
FROM (VALUES
 (1090001,'Pay date',10,15,7,'Y','ZC_PayDate','@#Date@','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0201'),
 (1090002,'Basic salary',20,12,22,'Y','ZC_BasicSalary',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0202'),
 (1090003,'House allowance',30,12,22,'N','ZC_HouseAllowance',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0203'),
 (1090004,'Commuter allowance',40,12,22,'N','ZC_CommuterAllowance',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0204'),
 (1090005,'Overtime',50,12,22,'N','ZC_Overtime',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0205'),
 (1090006,'Non-cash benefits',60,12,22,'N','ZC_NonCashBenefits',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0206'),
 (1090007,'Pension contribution',70,12,22,'N','ZC_PensionContribution',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0207'),
 (1090008,'Insurance premium',80,12,22,'N','ZC_InsurancePremium',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0208'),
 (1090009,'HELB',90,12,22,'N','ZC_HELB',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0209'),
 (1090010,'Other deductions (SACCO etc.)',100,12,22,'N','ZC_OtherDeductions',NULL,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0210')
) AS p(id,name,seq,ref,len,mand,col,def,uu)
WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Para x WHERE x.AD_Process_Para_ID=p.id);

INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,Action,AD_Process_ID,IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU)
SELECT 1090000,0,0,'Y',now(),100,now(),100,'Kenyan Payslip Preview (ZenithCore)','Calculate one Kenyan payslip and show the working','P',1090000,'N','N','N','ZENITH','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0301'
WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID=1090000);

-- "ZenithCore" folder at the top of the menu, with the process inside it
INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU)
SELECT 1090001,0,0,'Y',now(),100,now(),100,'ZenithCore','ZenithCore Enterprise ERP by Pollux Solutions Ltd','Y','N','N','ZENITH','5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0302'
WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID=1090001);

INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU)
SELECT 10,1090001,0,0,'Y',now(),100,now(),100,0,90,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0402'
WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID=1090001);

INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU)
SELECT 10,1090000,0,0,'Y',now(),100,now(),100,1090001,10,'5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0401'
WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID=1090000);
UPDATE AD_TreeNodeMM SET Parent_ID=1090001, SeqNo=10 WHERE AD_Tree_ID=10 AND Node_ID=1090000 AND Parent_ID<>1090001;
