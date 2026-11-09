-- Development only (GardenWorld demo database). Safe to re-run.
-- 1. Payroll Accounts (Kenya) for GardenWorld's accounting schema. GardenWorld has a US chart of
--    accounts, so its payroll accounts stand in for the Kenyan ones (for example Federal Tax
--    Withholding for PAYE). A Kenyan client gets its own chart with KRA, NSSF, SHA and Housing Levy
--    payables.
-- 2. Opens the November 2026 to January 2027 GL Journal periods so the demo payrolls can be posted.
INSERT INTO ZC_PayrollAcct (ZC_PayrollAcct_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,
  ZC_PayrollAcct_UU,C_AcctSchema_ID,GL_Category_ID,ZC_SalariesExpenseAcct_ID,ZC_EmployerCostExpenseAcct_ID,ZC_PAYEPayableAcct_ID,ZC_NSSFPayableAcct_ID,ZC_SHIFPayableAcct_ID,ZC_HousingLevyPayableAcct_ID,ZC_NITAPayableAcct_ID,ZC_HELBPayableAcct_ID,ZC_PensionInsPayableAcct_ID,ZC_StaffLoanAcct_ID,ZC_SaccoUnionPayableAcct_ID,ZC_NetSalariesPayableAcct_ID)
SELECT 1000000,11,0,'Y',now(),100,now(),100,'0f6a3c52-5b1e-4d0a-9e3c-1a2b3c4d5e01'::uuid,101,
  (SELECT GL_Category_ID FROM GL_Category WHERE AD_Client_ID=11 AND Name='Payroll'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='60130'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='60900'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22210'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22220'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22230'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22240'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22300'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22270'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22260'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='12410'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22290'),
  (SELECT C_ElementValue_ID FROM C_ElementValue WHERE AD_Client_ID=11 AND Value='22100')
WHERE NOT EXISTS (SELECT 1 FROM ZC_PayrollAcct WHERE ZC_PayrollAcct_ID=1000000);

UPDATE C_PeriodControl pc SET PeriodStatus='O', Updated=now()
FROM C_Period p JOIN C_Year y ON y.C_Year_ID=p.C_Year_ID
WHERE pc.C_Period_ID=p.C_Period_ID AND pc.AD_Client_ID=11
  AND ((y.FiscalYear='2026' AND p.PeriodNo IN (11,12)) OR (y.FiscalYear='2027' AND p.PeriodNo=1))
  AND pc.DocBaseType='GLJ' AND pc.PeriodStatus<>'O';
