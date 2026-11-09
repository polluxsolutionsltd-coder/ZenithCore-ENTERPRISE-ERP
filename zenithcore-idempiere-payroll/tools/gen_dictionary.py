#!/usr/bin/env python3
"""
Generates the iDempiere dictionary migration for ZenithCore Kenyan payroll (ZC-0002).

The spec below is the single source of truth for the payroll tables, windows, processes and menu.
Columns that reference a user end in _ID, never "By": iDempiere fills any column ending in "tedBy"
with the current user when a record is created.

IDs come from fixed ranges per table (columns 1090100 + 100*table + position, fields 1090400 + 100*tab
+ position, new elements 1091000 + 100*table + position) and UUIDs are derived from names, so the
output is deterministic. Only APPEND: add tables at the end of TABLES and columns at the end of a
table's list. Reordering would change IDs.

    python3 tools/gen_dictionary.py > migration/postgresql/202611092000_ZC-0002_PayrollTables.sql
"""
import uuid

NS = uuid.UUID("5a0c1d30-9b5e-4f0e-9c1a-2a6f0b7e0000")
ENTITY = "ZENITH"


def uu(kind, key):
    return str(uuid.uuid5(NS, f"{kind}:{key}"))


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


# Reference IDs
STRING, TEXT, ID, TABLEDIR, TABLE, YESNO, DATETIME, DATE, AMOUNT, INTEGER, LIST, SEARCH, BUTTON, UUIDREF = \
    10, 14, 13, 19, 18, 20, 16, 15, 12, 11, 17, 30, 28, 200231
AD_USER_REF = 110

# Existing iDempiere elements reused by name (ColumnName -> AD_Element_ID looked up at run time)
CORE_ELEMENTS = {"AD_Client_ID", "AD_Org_ID", "IsActive", "Created", "CreatedBy", "Updated", "UpdatedBy",
                 "C_BPartner_ID", "Name", "Description", "Processed", "C_AcctSchema_ID", "GL_Journal_ID",
                 "GL_Category_ID", "Value", "ValidFrom", "ValidTo"}

STATUS_REF_ID = 1090000
STATUS_LIST = [("DR", "Drafted"), ("CA", "Calculated"), ("AP", "Approved"), ("CO", "Completed"), ("RV", "Reversed")]
EMPLOYEE_VAL_RULE_ID = 1090000

PROC_CALC, PROC_APPROVE, PROC_COMPLETE, PROC_PAYSLIPS, PROC_SCHEDULES = 1090001, 1090002, 1090003, 1090004, 1090005
PROC_ROLES = 1090006
PROC_REQ_REVERSAL, PROC_REVERSE = 1090007, 1090008
ACCOUNT_REF_VALUE = 362  # C_ElementValue (trx): posting accounts only
ACCOUNT_VAL_RULE = 252   # accounts of the record's accounting schema
PROCESSES = [
    (PROC_CALC, "ZC_PayrollCalculate", "Calculate Payroll",
     "Runs pre-payroll checks, then calculates every active employee for the pay date and replaces the payroll lines.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunCalculate"),
    (PROC_APPROVE, "ZC_PayrollApprove", "Approve Payroll",
     "Approves a calculated payroll. The person who calculated it cannot approve it.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunApprove"),
    (PROC_COMPLETE, "ZC_PayrollComplete", "Complete Payroll",
     "Posts an approved payroll to the general ledger as a completed GL journal and closes the run. "
     "The person who calculated it cannot complete it.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunComplete"),
    (PROC_PAYSLIPS, "ZC_PayrollPayslips", "Print Payslips",
     "Creates a PDF with one payslip per employee for an approved or completed payroll and attaches it to the run.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunPayslips"),
    (PROC_SCHEDULES, "ZC_PayrollSchedules", "Statutory Schedules",
     "Creates the PAYE (P10), NSSF, SHIF, Housing Levy, NITA, HELB and bank schedules for an approved or completed "
     "payroll as CSV files in a zip, and attaches it to the run.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunSchedules"),
    (PROC_ROLES, "ZC_PayrollRoleSetup", "Set Up Payroll Roles",
     "Creates or refreshes the Payroll Officer, HR Head and Finance Head roles in this client, so that no one person "
     "can calculate, approve and post a payroll.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRoleSetup"),
    (PROC_REQ_REVERSAL, "ZC_PayrollRequestReversal", "Request Reversal",
     "Asks for a completed payroll to be reversed, giving the reason. A different person must then reverse it.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunRequestReversal"),
    (PROC_REVERSE, "ZC_PayrollReverse", "Reverse Payroll",
     "Reverses the GL journal of a completed payroll whose reversal was requested by someone else, and marks the "
     "payroll Reversed. A corrected payroll can then be calculated for the same pay date.",
     "ke.co.pollux.zenithcore.payroll.idempiere.process.PayrollRunReverse"),
]

# (id, process, column name, name, reference, length, mandatory, seq). The column's element must exist.
PROCESS_PARAMS = [
    (1090020, PROC_REQ_REVERSAL, "ZC_ReversalReason", "Reason for reversal", TEXT, 2000, True, 10),
]


def acct(col, name, help_text, mandatory=True):
    return dict(col=col, name=name, ref=SEARCH, refvalue=ACCOUNT_REF_VALUE, valrule=ACCOUNT_VAL_RULE,
                ddl="NUMERIC(10)" + (" NOT NULL" if mandatory else ""), mandatory=mandatory, fk="C_ElementValue",
                help=help_text)


def amt(col, name, **kw):
    return dict(col=col, name=name, ref=AMOUNT, ddl="NUMERIC DEFAULT 0", default="0", **kw)


TABLES = [
    dict(id=1090000, table="ZC_EmployeePay", name="Employee Pay Data (Kenya)", window=1090000, cols=[
        dict(col="C_BPartner_ID", name="Employee", ref=SEARCH, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             identifier=1, valrule=EMPLOYEE_VAL_RULE_ID, fk="C_BPartner"),
        dict(col="ZC_KRAPin", name="KRA PIN", ref=STRING, ddl="VARCHAR(11) NOT NULL", length=11, mandatory=True),
        dict(col="ZC_NSSFNo", name="NSSF number", ref=STRING, ddl="VARCHAR(20)", length=20),
        dict(col="ZC_SHANo", name="SHA number", ref=STRING, ddl="VARCHAR(20)", length=20),
        dict(col="ZC_Department", name="Department", ref=STRING, ddl="VARCHAR(60)", length=60),
        dict(col="ZC_BankName", name="Bank", ref=STRING, ddl="VARCHAR(60)", length=60),
        dict(col="ZC_BankAccountNo", name="Bank account", ref=STRING, ddl="VARCHAR(40)", length=40),
        dict(col="ZC_StartDate", name="Start date", ref=DATE, ddl="TIMESTAMP NOT NULL", mandatory=True),
        dict(col="ZC_EndDate", name="End date", ref=DATE, ddl="TIMESTAMP"),
        dict(col="ZC_IsSuspended", name="Suspended", ref=YESNO, ddl="CHAR(1) DEFAULT 'N' NOT NULL", default="N",
             mandatory=True,
             help="Suspended employees are left out of payroll, for example while a ghost-worker check is investigated."),
        amt("ZC_BasicSalary", "Basic salary", mandatory=True),
        amt("ZC_HouseAllowance", "House allowance"),
        amt("ZC_CommuterAllowance", "Commuter allowance"),
        amt("ZC_Overtime", "Overtime"),
        amt("ZC_OtherAllowances", "Other allowances"),
        amt("ZC_NonCashBenefits", "Non-cash benefits"),
        amt("ZC_PensionContribution", "Pension contribution"),
        amt("ZC_InsurancePremium", "Insurance premium"),
        amt("ZC_PostRetirementMedical", "Post-retirement medical fund"),
        amt("ZC_MortgageInterest", "Mortgage interest"),
        amt("ZC_HELB", "HELB"),
        amt("ZC_StaffLoan", "Staff loan"),
        amt("ZC_SACCO", "SACCO"),
        amt("ZC_UnionDues", "Union dues"),
        dict(col="ZC_NationalIDNo", name="National ID number", ref=STRING, ddl="VARCHAR(20)", length=20,
             help="Needed on the NSSF and HELB schedules."),
        dict(col="ZC_Position_ID", name="Position", ref=TABLEDIR, ddl="NUMERIC(10)", fk="ZC_Position",
             help="Approved position the employee holds. Payroll is blocked for anyone without one."),
    ]),
    dict(id=1090001, table="ZC_PayrollRun", name="Payroll Run (Kenya)", window=1090001, cols=[
        dict(col="Name", name="Name", ref=STRING, ddl="VARCHAR(60) NOT NULL", length=60, mandatory=True, identifier=1),
        dict(col="ZC_PayDate", name="Pay date", ref=DATE, ddl="TIMESTAMP NOT NULL", mandatory=True),
        dict(col="ZC_PayrollStatus", name="Payroll status", ref=LIST, refvalue=STATUS_REF_ID,
             ddl="VARCHAR(2) DEFAULT 'DR' NOT NULL", length=2, default="DR", mandatory=True, readonly=True),
        dict(col="ZC_CalculatedUser_ID", name="Calculated by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_ApprovedUser_ID", name="Approved by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_PostedUser_ID", name="Posted by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_EmployeeCount", name="Employees paid", ref=INTEGER, ddl="NUMERIC(10) DEFAULT 0", readonly=True),
        amt("ZC_TotalGross", "Total gross pay", readonly=True),
        amt("ZC_TotalPAYE", "Total PAYE", readonly=True),
        amt("ZC_TotalNSSF", "Total NSSF (employee)", readonly=True),
        amt("ZC_TotalSHIF", "Total SHIF", readonly=True),
        amt("ZC_TotalHousingLevy", "Total Housing Levy (employee)", readonly=True),
        amt("ZC_TotalNet", "Total net pay", readonly=True),
        amt("ZC_TotalEmployerCost", "Total employer cost", readonly=True),
        dict(col="ZC_CheckResults", name="Pre-payroll check results", ref=TEXT, ddl="VARCHAR(4000)", length=4000,
             readonly=True),
        dict(col="ZC_Calculate", name="Calculate Payroll", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_CALC),
        dict(col="ZC_Approve", name="Approve Payroll", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_APPROVE),
        dict(col="Description", name="Description", ref=TEXT, ddl="VARCHAR(255)", length=255),
        dict(col="Processed", name="Processed", ref=YESNO, ddl="CHAR(1) DEFAULT 'N' NOT NULL", default="N",
             mandatory=True, readonly=True,
             help="Set when the payroll is approved; an approved payroll cannot be changed."),
        dict(col="ZC_Complete", name="Complete Payroll", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_COMPLETE,
             always=True),
        dict(col="GL_Journal_ID", name="GL journal", ref=TABLEDIR, ddl="NUMERIC(10)", readonly=True, fk="GL_Journal"),
        dict(col="ZC_PrintPayslips", name="Print Payslips", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_PAYSLIPS,
             always=True),
        dict(col="ZC_StatutorySchedules", name="Statutory Schedules", ref=BUTTON, ddl="CHAR(1)", length=1,
             process=PROC_SCHEDULES, always=True),
        dict(col="ZC_ReversalReason", name="Reason for reversal", ref=TEXT, ddl="VARCHAR(2000)", length=2000,
             readonly=True),
        dict(col="ZC_ReversalRequestedUser_ID", name="Reversal requested by", ref=TABLE, refvalue=AD_USER_REF,
             ddl="NUMERIC(10)", readonly=True, fk="AD_User"),
        dict(col="ZC_ReversedUser_ID", name="Reversed by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_RequestReversal", name="Request Reversal", ref=BUTTON, ddl="CHAR(1)", length=1,
             process=PROC_REQ_REVERSAL, always=True),
        dict(col="ZC_Reverse", name="Reverse Payroll", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_REVERSE,
             always=True),
    ]),
    dict(id=1090002, table="ZC_PayrollLine", name="Payroll Line (Kenya)", window=1090001, cols=[
        dict(col="ZC_PayrollRun_ID", name="Payroll run", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             parent=True, readonly=True, fk="ZC_PayrollRun"),
        dict(col="ZC_EmployeePay_ID", name="Employee pay data", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL",
             mandatory=True, readonly=True, fk="ZC_EmployeePay"),
        dict(col="C_BPartner_ID", name="Employee", ref=SEARCH, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             identifier=1, readonly=True, fk="C_BPartner"),
        amt("ZC_GrossPay", "Gross pay", readonly=True),
        amt("ZC_TaxableNonCash", "Taxable non-cash benefits", readonly=True),
        amt("ZC_NSSFTier1", "NSSF Tier I", readonly=True),
        amt("ZC_NSSFTier2", "NSSF Tier II", readonly=True),
        amt("ZC_NSSF", "NSSF (employee)", readonly=True),
        amt("ZC_SHIF", "SHIF", readonly=True),
        amt("ZC_HousingLevy", "Housing Levy (employee)", readonly=True),
        amt("ZC_AllowablePension", "Allowable pension", readonly=True),
        amt("ZC_TaxablePay", "Taxable pay", readonly=True),
        amt("ZC_TaxCharged", "Tax charged", readonly=True),
        amt("ZC_PersonalRelief", "Personal relief", readonly=True),
        amt("ZC_InsuranceRelief", "Insurance relief", readonly=True),
        amt("ZC_PAYE", "PAYE", readonly=True),
        amt("ZC_HELB", "HELB", readonly=True),
        amt("ZC_PensionContribution", "Pension contribution", readonly=True),
        amt("ZC_InsurancePremium", "Insurance premium", readonly=True),
        amt("ZC_StaffLoan", "Staff loan", readonly=True),
        amt("ZC_SACCO", "SACCO", readonly=True),
        amt("ZC_UnionDues", "Union dues", readonly=True),
        amt("ZC_CarriedForward", "Carried forward (two-thirds rule)", readonly=True),
        amt("ZC_TotalDeductions", "Total deductions", readonly=True),
        amt("ZC_NetPay", "Net pay", readonly=True),
        amt("ZC_EmployerNSSF", "Employer NSSF", readonly=True),
        amt("ZC_EmployerHousingLevy", "Employer Housing Levy", readonly=True),
        amt("ZC_NITA", "NITA levy", readonly=True),
        amt("ZC_EmployerCost", "Employer cost", readonly=True),
        dict(col="ZC_Trace", name="Calculation working", ref=TEXT, ddl="VARCHAR(4000)", length=4000, readonly=True),
        # Calculation-time copy of the pay data, so payslips and schedules never change afterwards
        amt("ZC_BasicSalary", "Basic salary", readonly=True),
        amt("ZC_HouseAllowance", "House allowance", readonly=True),
        amt("ZC_CommuterAllowance", "Commuter allowance", readonly=True),
        amt("ZC_Overtime", "Overtime", readonly=True),
        amt("ZC_OtherAllowances", "Other allowances", readonly=True),
        amt("ZC_NonCashBenefits", "Non-cash benefits", readonly=True),
        amt("ZC_PostRetirementMedical", "Post-retirement medical fund", readonly=True),
        amt("ZC_MortgageInterest", "Mortgage interest", readonly=True),
        dict(col="ZC_KRAPin", name="KRA PIN", ref=STRING, ddl="VARCHAR(11)", length=11, readonly=True),
        dict(col="ZC_NSSFNo", name="NSSF number", ref=STRING, ddl="VARCHAR(20)", length=20, readonly=True),
        dict(col="ZC_SHANo", name="SHA number", ref=STRING, ddl="VARCHAR(20)", length=20, readonly=True),
        dict(col="ZC_NationalIDNo", name="National ID number", ref=STRING, ddl="VARCHAR(20)", length=20, readonly=True),
        dict(col="ZC_Department", name="Department", ref=STRING, ddl="VARCHAR(60)", length=60, readonly=True),
        dict(col="ZC_BankName", name="Bank", ref=STRING, ddl="VARCHAR(60)", length=60, readonly=True),
        dict(col="ZC_BankAccountNo", name="Bank account", ref=STRING, ddl="VARCHAR(40)", length=40, readonly=True),
        # Arrears (two-thirds rule): brought into this run, and carried out of it to the next
        amt("ZC_BroughtForward", "Arrears brought forward", readonly=True),
        amt("ZC_HELBArrears", "HELB carried forward", readonly=True),
        amt("ZC_StaffLoanArrears", "Staff loan carried forward", readonly=True),
        amt("ZC_SACCOArrears", "SACCO carried forward", readonly=True),
        amt("ZC_UnionArrears", "Union dues carried forward", readonly=True),
    ]),
    dict(id=1090003, table="ZC_PayrollAcct", name="Payroll Accounts (Kenya)", window=1090002, cols=[
        dict(col="C_AcctSchema_ID", name="Accounting schema", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL",
             mandatory=True, identifier=1, default="@$C_AcctSchema_ID@", fk="C_AcctSchema"),
        acct("ZC_SalariesExpenseAcct_ID", "Salaries expense", "Debited with gross pay."),
        acct("ZC_EmployerCostExpenseAcct_ID", "Employer statutory cost",
             "Debited with employer NSSF, employer Housing Levy and NITA."),
        acct("ZC_PAYEPayableAcct_ID", "PAYE payable", "Credited with PAYE, due to KRA by the 9th."),
        acct("ZC_NSSFPayableAcct_ID", "NSSF payable", "Credited with employee and employer NSSF."),
        acct("ZC_SHIFPayableAcct_ID", "SHIF payable", "Credited with SHIF, due to SHA."),
        acct("ZC_HousingLevyPayableAcct_ID", "Housing Levy payable",
             "Credited with employee and employer Affordable Housing Levy."),
        acct("ZC_NITAPayableAcct_ID", "NITA payable", "Credited with the NITA levy."),
        acct("ZC_HELBPayableAcct_ID", "HELB payable", "Credited with HELB loan deductions."),
        acct("ZC_PensionInsPayableAcct_ID", "Pension and insurance payable",
             "Credited with employee pension contributions and insurance premiums."),
        acct("ZC_StaffLoanAcct_ID", "Staff loans receivable", "Credited with staff loan recoveries."),
        acct("ZC_SaccoUnionPayableAcct_ID", "SACCO and union payable", "Credited with SACCO deductions and union dues."),
        acct("ZC_NetSalariesPayableAcct_ID", "Net salaries payable", "Credited with net pay, cleared by the bank payment."),
        dict(col="GL_Category_ID", name="GL category", ref=TABLEDIR, ddl="NUMERIC(10)", fk="GL_Category",
             help="Category of the payroll journal, for example Payroll. Empty means the default category."),
    ]),
    dict(id=1090004, table="ZC_Position", name="Establishment Position (Kenya)", window=1090003, cols=[
        dict(col="Value", name="Position code", ref=STRING, ddl="VARCHAR(40) NOT NULL", length=40, mandatory=True,
             identifier=1),
        dict(col="Name", name="Position title", ref=STRING, ddl="VARCHAR(60) NOT NULL", length=60, mandatory=True,
             identifier=2),
        dict(col="ZC_Department", name="Department", ref=STRING, ddl="VARCHAR(60)", length=60),
        dict(col="ZC_JobGrade", name="Job grade", ref=STRING, ddl="VARCHAR(20)", length=20),
        dict(col="ZC_ApprovedHeadcount", name="Approved headcount", ref=INTEGER, ddl="NUMERIC(10) DEFAULT 1 NOT NULL",
             default="1", mandatory=True,
             help="Number of people who may be paid in this position. Payroll is blocked if more are paid."),
        amt("ZC_MaxBasicSalary", "Salary ceiling (basic)",
            help="Highest basic salary approved for this position. Zero means no ceiling."),
        dict(col="ValidFrom", name="Valid from", ref=DATE, ddl="TIMESTAMP NOT NULL", mandatory=True),
        dict(col="ValidTo", name="Valid to", ref=DATE, ddl="TIMESTAMP"),
        dict(col="Description", name="Description", ref=TEXT, ddl="VARCHAR(255)", length=255,
             help="For example the board minute that approved the position."),
    ]),
]

WINDOWS = [
    dict(id=1090000, name="Employee Pay Data (Kenya)", desc="Pay components and statutory numbers for each employee",
         wtype="M", tabs=[dict(id=1090000, table=1090000, name="Employee Pay Data", level=0, seq=10)]),
    dict(id=1090001, name="Payroll Run (Kenya)",
         desc="Monthly Kenyan payroll: checks, calculation and approval", wtype="T",
         tabs=[dict(id=1090001, table=1090001, name="Payroll Run", level=0, seq=10),
               dict(id=1090002, table=1090002, name="Lines", level=1, seq=20, readonly=True, link="ZC_PayrollRun_ID")]),
    dict(id=1090002, name="Payroll Accounts (Kenya)",
         desc="General ledger accounts the payroll posts to, one setup per accounting schema", wtype="M",
         tabs=[dict(id=1090003, table=1090003, name="Payroll Accounts", level=0, seq=10)]),
    dict(id=1090003, name="Establishment (Kenya)",
         desc="Approved positions: headcount and salary ceiling. Nobody is paid without an approved position.",
         wtype="M", tabs=[dict(id=1090004, table=1090004, name="Position", level=0, seq=10)]),
]

MENUS = [  # (id, name, window, seq) under the ZenithCore folder 1090001
    (1090002, "Employee Pay Data (Kenya)", 1090000, 20),
    (1090003, "Payroll Run (Kenya)", 1090001, 30),
    (1090004, "Payroll Accounts (Kenya)", 1090002, 40),
    (1090006, "Establishment (Kenya)", 1090003, 15),
]

PROCESS_MENUS = [  # (id, name, process, seq) under the ZenithCore folder 1090001
    (1090005, "Set Up Payroll Roles", PROC_ROLES, 50),
]

STD = ["AD_Client_ID", "AD_Org_ID", "IsActive", "Created", "CreatedBy", "Updated", "UpdatedBy"]


def std_cols(t):
    tn = t["table"]
    return [
        dict(col="AD_Client_ID", ref=SEARCH, ddl="NUMERIC(10) NOT NULL", mandatory=True, default="@#AD_Client_ID@",
             readonly=True, std=True),
        dict(col="AD_Org_ID", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL", mandatory=True, default="@#AD_Org_ID@",
             readonly=True, std=True),
        dict(col="IsActive", ref=YESNO, ddl="CHAR(1) DEFAULT 'Y' NOT NULL", mandatory=True, default="Y", std=True),
        dict(col="Created", ref=DATETIME, ddl="TIMESTAMP DEFAULT statement_timestamp() NOT NULL", mandatory=True,
             readonly=True, std=True, hidden=True),
        dict(col="CreatedBy", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             readonly=True, std=True, hidden=True),
        dict(col="Updated", ref=DATETIME, ddl="TIMESTAMP DEFAULT statement_timestamp() NOT NULL", mandatory=True,
             readonly=True, std=True, hidden=True),
        dict(col="UpdatedBy", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             readonly=True, std=True, hidden=True),
        dict(col=f"{tn}_ID", name=t["name"], ref=ID, ddl="NUMERIC(10) NOT NULL", mandatory=True, key=True,
             readonly=True, hidden=True),
        dict(col=f"{tn}_UU", name=f"{tn}_UU", ref=UUIDREF, ddl="UUID DEFAULT NULL", length=36, hidden=True),
    ]


def main():
    out = []
    w = out.append
    w("-- ZC-0002 ZenithCore Kenyan payroll: tables, windows, processes and menu.")
    w("-- GENERATED by tools/gen_dictionary.py. Do not edit by hand. Safe to re-run.")
    w("")
    # validation rule and list reference
    w(f"INSERT INTO AD_Val_Rule (AD_Val_Rule_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,Type,Code,EntityType,AD_Val_Rule_UU) "
      f"SELECT {EMPLOYEE_VAL_RULE_ID},0,0,'Y',now(),100,now(),100,'ZC Employees only','Business partners flagged as employees','S',"
      f"'C_BPartner.IsEmployee=''Y''',{q(ENTITY)},{q(uu('valrule', 'employees'))} WHERE NOT EXISTS (SELECT 1 FROM AD_Val_Rule WHERE AD_Val_Rule_ID={EMPLOYEE_VAL_RULE_ID});")
    w(f"INSERT INTO AD_Reference (AD_Reference_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,ValidationType,EntityType,AD_Reference_UU) "
      f"SELECT {STATUS_REF_ID},0,0,'Y',now(),100,now(),100,'ZC Payroll Status','Drafted, Calculated, Approved, Completed','L',{q(ENTITY)},{q(uu('ref', 'status'))} "
      f"WHERE NOT EXISTS (SELECT 1 FROM AD_Reference WHERE AD_Reference_ID={STATUS_REF_ID});")
    for i, (v, n) in enumerate(STATUS_LIST):
        rid = 1090000 + i
        w(f"INSERT INTO AD_Ref_List (AD_Ref_List_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,AD_Reference_ID,EntityType,AD_Ref_List_UU) "
          f"SELECT {rid},0,0,'Y',now(),100,now(),100,{q(v)},{q(n)},{STATUS_REF_ID},{q(ENTITY)},{q(uu('reflist', v))} WHERE NOT EXISTS (SELECT 1 FROM AD_Ref_List WHERE AD_Ref_List_ID={rid});")
    w("")
    # processes
    for pid, value, name, desc, cls in PROCESSES:
        w(f"INSERT INTO AD_Process (AD_Process_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,Description,AccessLevel,EntityType,IsReport,Classname,IsServerProcess,ShowHelp,IsBetaFunctionality,AllowMultipleExecution,AD_Process_UU) "
          f"SELECT {pid},0,0,'Y',now(),100,now(),100,{q(value)},{q(name)},{q(desc)},'3',{q(ENTITY)},'N',{q(cls)},'N','Y','N','P',{q(uu('process', value))} WHERE NOT EXISTS (SELECT 1 FROM AD_Process WHERE AD_Process_ID={pid});")
    w("")
    # windows (needed before tables reference them)
    for win in WINDOWS:
        w(f"INSERT INTO AD_Window (AD_Window_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,WindowType,IsSOTrx,EntityType,IsDefault,IsBetaFunctionality,AD_Window_UU) "
          f"SELECT {win['id']},0,0,'Y',now(),100,now(),100,{q(win['name'])},{q(win['desc'])},{q(win['wtype'])},'N',{q(ENTITY)},'N','N',{q(uu('window', win['name']))} WHERE NOT EXISTS (SELECT 1 FROM AD_Window WHERE AD_Window_ID={win['id']});")
    w("")

    elements = {}  # columnname -> id expression
    col_ids = {}

    def element(c, ti, ci):
        cn = c["col"]
        if cn in elements:
            return elements[cn]
        if cn in CORE_ELEMENTS:
            expr = f"(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName={q(cn)} AND AD_Client_ID=0)"
        else:
            eid = 1091000 + 100 * ti + ci
            nm = c.get("name", cn)
            w(f"INSERT INTO AD_Element (AD_Element_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ColumnName,EntityType,Name,PrintName,Description,AD_Element_UU) "
              f"SELECT {eid},0,0,'Y',now(),100,now(),100,{q(cn)},{q(ENTITY)},{q(nm)},{q(nm)},{q(c.get('help'))},{q(uu('element', cn))} WHERE NOT EXISTS (SELECT 1 FROM AD_Element WHERE AD_Element_ID={eid});")
            expr = str(eid)
        elements[cn] = expr
        return expr

    fks = []  # foreign keys are added once every table exists
    for ti, t in enumerate(TABLES):
        tid, tn = t["id"], t["table"]
        w(f"-- {tn}")
        cols = std_cols(t) + t["cols"]
        # physical table
        ddl = [f"  {c['col']} {c['ddl']}" for c in cols]
        ddl.append(f"  CONSTRAINT {tn}_Key PRIMARY KEY ({tn}_ID)")
        ddl.append(f"  CONSTRAINT {tn}_UU_idx UNIQUE ({tn}_UU)")
        w(f"CREATE TABLE IF NOT EXISTS {tn} (\n" + ",\n".join(ddl) + "\n);")
        # Columns added to the spec after a table exists are added here
        for c in cols:
            w(f"ALTER TABLE {tn} ADD COLUMN IF NOT EXISTS {c['col']} {c['ddl'].replace(' NOT NULL', '')};")
        for c in cols:
            if c.get("fk"):
                cname = f"{tn[:12]}_{c['col'][:14]}_fk"
                fks.append(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=lower({q(cname)})) THEN "
                           f"ALTER TABLE {tn} ADD CONSTRAINT {cname} FOREIGN KEY ({c['col']}) REFERENCES {c['fk']} ({c['fk']}_ID) DEFERRABLE INITIALLY DEFERRED; END IF; END $$;")
        w(f"INSERT INTO AD_Table (AD_Table_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,TableName,IsView,AccessLevel,EntityType,AD_Window_ID,IsSecurityEnabled,IsDeleteable,IsHighVolume,IsChangeLog,ReplicationType,IsPartition,AD_Table_UU) "
          f"SELECT {tid},0,0,'Y',now(),100,now(),100,{q(t['name'])},{q(tn)},'N','3',{q(ENTITY)},{t['window']},'N','Y','N','Y','L','N',{q(uu('table', tn))} WHERE NOT EXISTS (SELECT 1 FROM AD_Table WHERE AD_Table_ID={tid});")
        w(f"INSERT INTO AD_Sequence (AD_Sequence_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,IsTableID,IsAutoSequence,IncrementNo,StartNo,CurrentNext,CurrentNextSys,StartNewMonth,IsOrgLevelSequence,AD_Sequence_UU) "
          f"SELECT {tid},0,0,'Y',now(),100,now(),100,{q(tn)},{q('Table ' + tn)},'Y','Y',1,1000000,1000000,200000,'N','N',{q(uu('sequence', tn))} WHERE NOT EXISTS (SELECT 1 FROM AD_Sequence WHERE Name={q(tn)} AND IsTableID='Y');")
        for ci, c in enumerate(cols):
            el = element(c, ti, ci)
            cid = 1090100 + 100 * ti + ci
            col_ids[(tid, c["col"])] = cid
            name_expr = q(c["name"]) if "name" in c else f"(SELECT Name FROM AD_Element WHERE AD_Element_ID={el})"
            updateable = "N" if c.get("readonly") or c.get("key") else "Y"
            if c["col"] == "IsActive":
                updateable = "Y"
            length = c.get("length", {ID: 22, SEARCH: 22, TABLEDIR: 22, TABLE: 22, YESNO: 1, DATETIME: 7, DATE: 7,
                                      AMOUNT: 22, INTEGER: 10}.get(c["ref"], 10))
            w(f"INSERT INTO AD_Column (AD_Column_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,Version,EntityType,ColumnName,AD_Table_ID,AD_Reference_ID,AD_Reference_Value_ID,AD_Val_Rule_ID,AD_Process_ID,FieldLength,DefaultValue,IsKey,IsParent,IsMandatory,IsUpdateable,IsIdentifier,SeqNo,IsTranslated,IsEncrypted,IsSelectionColumn,AD_Element_ID,IsAlwaysUpdateable,IsAutocomplete,IsAllowCopy,IsToolbarButton,IsSecure,IsPartitionKey,FKConstraintType,AD_Column_UU) "
              f"SELECT {cid},0,0,'Y',now(),100,now(),100,{name_expr},{q(c.get('help'))},0,{q(ENTITY)},{q(c['col'])},{tid},{c['ref']},{q(c.get('refvalue'))},{q(c.get('valrule'))},{q(c.get('process'))},{length},{q(c.get('default'))},"
              f"{q('Y' if c.get('key') else 'N')},{q('Y' if c.get('parent') else 'N')},{q('Y' if c.get('mandatory') else 'N')},{q(updateable)},{q('Y' if c.get('identifier') else 'N')},{c.get('identifier', 0)},'N','N','N',{el},{q('Y' if c.get('always') else 'N')},'N','Y',{q('N' if c['ref'] == BUTTON else 'Y')},'N','N',{q('D' if c.get('fk') else 'N')},{q(uu('column', tn + '.' + c['col']))} "
              f"WHERE NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Column_ID={cid});")
        w("")

    w("-- foreign keys")
    for f in fks:
        w(f)
    w("")

    # tabs and fields
    tab_index = 0
    for win in WINDOWS:
        for tab in win["tabs"]:
            tab_no = tab_index
            tab_index += 1
            t = next(x for x in TABLES if x["id"] == tab["table"])
            link = f"{col_ids[(t['id'], tab['link'])]}" if tab.get("link") else "NULL"
            ro = "Y" if tab.get("readonly") else "N"
            ins = "N" if tab.get("readonly") else "Y"
            w(f"INSERT INTO AD_Tab (AD_Tab_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Table_ID,AD_Window_ID,SeqNo,TabLevel,IsSingleRow,IsTranslationTab,IsReadOnly,AD_Column_ID,HasTree,IsSortTab,EntityType,IsInsertRecord,IsAdvancedTab,TreeDisplayedOn,AD_Tab_UU) "
              f"SELECT {tab['id']},0,0,'Y',now(),100,now(),100,{q(tab['name'])},{t['id']},{win['id']},{tab['seq']},{tab['level']},'Y','N',{q(ro)},{link},'N','N',{q(ENTITY)},{q(ins)},'N','B',{q(uu('tab', str(tab['id'])))} WHERE NOT EXISTS (SELECT 1 FROM AD_Tab WHERE AD_Tab_ID={tab['id']});")
            seq = 0
            for ci, c in enumerate(std_cols(t) + t["cols"]):
                fid = 1090400 + 100 * tab_no + ci
                hidden = c.get("hidden") or (tab.get("link") and c["col"] == tab["link"])
                if not hidden:
                    seq += 10
                cid = col_ids[(t["id"], c["col"])]
                same = "Y" if c["col"] in ("AD_Org_ID",) else "N"
                w(f"INSERT INTO AD_Field (AD_Field_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,IsCentrallyMaintained,AD_Tab_ID,AD_Column_ID,IsDisplayed,IsDisplayedGrid,SeqNo,SeqNoGrid,IsReadOnly,IsSameLine,IsHeading,IsFieldOnly,IsEncrypted,EntityType,IsQuickEntry,IsDefaultFocus,IsQuickForm,AD_Field_UU) "
                  f"SELECT {fid},0,0,'Y',now(),100,now(),100,(SELECT Name FROM AD_Column WHERE AD_Column_ID={cid}),'Y',{tab['id']},{cid},{q('N' if hidden else 'Y')},{q('N' if hidden else 'Y')},{0 if hidden else seq},{0 if hidden else seq},'N',{q(same)},'N','N','N',{q(ENTITY)},'N','N','N',{q(uu('field', str(tab['id']) + '.' + c['col']))} "
                  f"WHERE NOT EXISTS (SELECT 1 FROM AD_Field WHERE AD_Field_ID={fid});")
        w("")

    for mid, name, wid, seqno in MENUS:
        w(f"INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Action,AD_Window_ID,IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU) "
          f"SELECT {mid},0,0,'Y',now(),100,now(),100,{q(name)},'W',{wid},'N','N','N',{q(ENTITY)},{q(uu('menu', name))} WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID={mid});")
        w(f"INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU) "
          f"SELECT 10,{mid},0,0,'Y',now(),100,now(),100,1090001,{seqno},{q(uu('treenode', str(mid)))} WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID={mid});")
    for mid, name, pid, seqno in PROCESS_MENUS:
        w(f"INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Action,AD_Process_ID,IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU) "
          f"SELECT {mid},0,0,'Y',now(),100,now(),100,{q(name)},'P',{pid},'N','N','N',{q(ENTITY)},{q(uu('menu', name))} WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID={mid});")
        w(f"INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU) "
          f"SELECT 10,{mid},0,0,'Y',now(),100,now(),100,1090001,{seqno},{q(uu('treenode', str(mid)))} WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID={mid});")
    for ppid, pid, cn, name, ref, length, mand, seq in PROCESS_PARAMS:
        w(f"INSERT INTO AD_Process_Para (AD_Process_Para_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Process_ID,SeqNo,AD_Reference_ID,IsRange,FieldLength,IsMandatory,ColumnName,IsCentrallyMaintained,EntityType,IsEncrypted,AD_Element_ID,AD_Process_Para_UU) "
          f"SELECT {ppid},0,0,'Y',now(),100,now(),100,{q(name)},{pid},{seq},{ref},'N',{length},{q('Y' if mand else 'N')},{q(cn)},'N',{q(ENTITY)},'N',"
          f"(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName={q(cn)}),{q(uu('para', str(ppid)))} WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Para WHERE AD_Process_Para_ID={ppid});")
    print("\n".join(out))


if __name__ == "__main__":
    main()
