-- Development only: four GardenWorld employees matching the worked payslips in
-- KenyaPayrollEngineTest, plus a "ghost" record that shares E001's bank account and has no NSSF
-- number, so the pre-payroll checks have something to catch. Sample names and numbers only.
-- Employees belong to the Fertilizer organization (50001), the default organization of GardenUser.
-- Clones GardenUser BP (119) for the business partner defaults. Safe to re-run.
INSERT INTO C_BPartner
SELECT (jsonb_populate_record(NULL::C_BPartner, to_jsonb(b) || jsonb_build_object(
  'c_bpartner_id', n.id, 'value', n.val, 'name', n.nm, 'name2', NULL, 'isemployee', 'Y', 'iscustomer', 'N',
  'isvendor', 'N', 'c_bpartner_uu', gen_random_uuid(), 'created', now(), 'updated', now()))).*
FROM C_BPartner b,
 (VALUES (1090901,'E001','Joseph Kamau'),(1090904,'E004','Ruth Njeri'),(1090906,'E006','Tom Wafula'),(1090905,'E005','Peter Otieno')) n(id,val,nm)
WHERE b.C_BPartner_ID=119 AND NOT EXISTS (SELECT 1 FROM C_BPartner x WHERE x.C_BPartner_ID=n.id);

INSERT INTO ZC_EmployeePay (ZC_EmployeePay_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_EmployeePay_UU,
  C_BPartner_ID,ZC_KRAPin,ZC_NSSFNo,ZC_SHANo,ZC_Department,ZC_BankName,ZC_BankAccountNo,ZC_StartDate,ZC_IsSuspended,
  ZC_BasicSalary,ZC_HouseAllowance,ZC_CommuterAllowance,ZC_PensionContribution,ZC_InsurancePremium,ZC_MortgageInterest,
  ZC_StaffLoan,ZC_SACCO,ZC_UnionDues)
SELECT e.id,11,50001,'Y',now(),100,now(),100,gen_random_uuid(),e.bp,e.pin,e.nssf,e.sha,e.dept,e.bank,e.acct,'2022-03-01','N',
  e.basic,e.house,e.comm,e.pen,e.ins,e.mort,e.loan,e.sacco,e.uni
FROM (VALUES
 (1000000,1090901,'A000004100K','NS5200000','SHA7700000','Workshop','Equity Bank','0170-1234-5678',62000,12000,4000,0,0,0,0,3000,500),
 (1000001,1090904,'A000004511K','NS5202739','SHA7700933','Workshop','Equity Bank','0170-5566-7788',18000,0,0,0,0,0,6000,5000,0),
 (1000002,1090906,'A000004785K','NS5204565','SHA7701555','Call Centre','NCBA Bank','3301-2200-114',82000,15000,5000,10000,3000,25000,0,0,0),
 (1000003,1090905,'A000004648K',NULL,'SHA7701244','Workshop','Equity Bank','0170 1234 5678',30000,6000,3000,0,0,0,0,0,0)
) e(id,bp,pin,nssf,sha,dept,bank,acct,basic,house,comm,pen,ins,mort,loan,sacco,uni)
WHERE NOT EXISTS (SELECT 1 FROM ZC_EmployeePay x WHERE x.ZC_EmployeePay_ID=e.id);

UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000004) WHERE Name='ZC_EmployeePay' AND IsTableID='Y';

-- Sample national ID numbers (needed on the NSSF and HELB schedules)
UPDATE ZC_EmployeePay e SET ZC_NationalIDNo=v.idno, Updated=now()
FROM (VALUES (1000000,'27114455'),(1000001,'30225566'),(1000002,'24336677'),(1000003,'29447788')) v(id,idno)
WHERE e.ZC_EmployeePay_ID=v.id AND e.ZC_NationalIDNo IS NULL;
