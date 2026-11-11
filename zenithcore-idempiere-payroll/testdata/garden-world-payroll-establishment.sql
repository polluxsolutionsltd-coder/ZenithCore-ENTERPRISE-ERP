-- Development only (GardenWorld demo database). Safe to re-run.
-- Approved positions (establishment) in the Fertilizer organization, and the sample employees' positions.
-- E005 (the ghost record) has no position.
INSERT INTO ZC_Position (ZC_Position_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ZC_Position_UU,
  Value,Name,ZC_Department,ZC_JobGrade,ZC_ApprovedHeadcount,ZC_MaxBasicSalary,ValidFrom,Description)
SELECT p.id,11,50001,'Y',now(),100,now(),100,gen_random_uuid(),p.code,p.title,p.dept,p.grade,p.hc,p.ceiling,'2026-01-01',p.note
FROM (VALUES
 (1000000,'WS-TECH','Workshop Technician','Workshop','JKIP 6',2,70000,'Sample: approved establishment 2026/27'),
 (1000001,'CC-SUP','Call Centre Supervisor','Call Centre','JKIP 8',1,100000,'Sample: approved establishment 2026/27')
) p(id,code,title,dept,grade,hc,ceiling,note)
WHERE NOT EXISTS (SELECT 1 FROM ZC_Position x WHERE x.ZC_Position_ID=p.id);

UPDATE AD_Sequence SET CurrentNext=GREATEST(CurrentNext,1000002) WHERE Name='ZC_Position' AND IsTableID='Y';

UPDATE ZC_EmployeePay e SET ZC_Position_ID=v.pos, Updated=now()
FROM (VALUES (1000000,1000000),(1000001,1000000),(1000002,1000001)) v(id,pos)
WHERE e.ZC_EmployeePay_ID=v.id AND e.ZC_Position_ID IS NULL;
