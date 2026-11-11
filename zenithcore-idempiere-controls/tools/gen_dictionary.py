#!/usr/bin/env python3
"""
Generates the iDempiere dictionary migration for ZenithCore Phase 1 controls (ZC-0004).

Covers: document approval rules (second person), supplier pre-qualification (columns on C_BPartner, its
own window, read-only fields on the Business Partner window's Vendor tab), the audit-query register,
client switches (AD_SysConfig) and the menu.

ID range: 1091500-1091999 in every dictionary table (payroll uses 1090000-1091499).
  tables, windows, tabs, processes, menus, references    1091500 + n
  reference list values                                  1091500 + running number
  columns and their new elements (same ID)               1091500 + 40*table + position
  fields of new tabs                                     1091500 + 40*tab + position
  columns added to iDempiere tables (and their elements) 1091900 + n
  fields added to iDempiere tabs                         1091950 + n
UUIDs are derived from names, so the output is deterministic. APPEND ONLY: add at the end of a list.

    python3 tools/gen_dictionary.py > migration/postgresql/202611100800_ZC-0004_Controls.sql
"""
import importlib.util
import os

_spec = importlib.util.spec_from_file_location(
    "payroll_gen", os.path.join(os.path.dirname(__file__), "..", "..", "zenithcore-idempiere-payroll", "tools", "gen_dictionary.py"))
_pg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pg)

q, std_cols, ENTITY = _pg.q, _pg.std_cols, _pg.ENTITY
STRING, TEXT, ID, TABLEDIR, TABLE, YESNO, DATETIME, DATE, AMOUNT, INTEGER, LIST, SEARCH, BUTTON = (
    _pg.STRING, _pg.TEXT, _pg.ID, _pg.TABLEDIR, _pg.TABLE, _pg.YESNO, _pg.DATETIME, _pg.DATE, _pg.AMOUNT, _pg.INTEGER,
    _pg.LIST, _pg.SEARCH, _pg.BUTTON)
AD_USER_REF = 110
DOCBASETYPE_REF = 183
ZENITHCORE_FOLDER = 1090001  # payroll's ZenithCore menu folder


def uu(kind, key):
    return _pg.uu("controls-" + kind, key)


CORE_ELEMENTS = {"Value", "Name", "Description", "Processed", "DocBaseType", "C_Year_ID", "C_Activity_ID", "Account_ID"}
ACCOUNT_REF_VALUE = 362  # C_ElementValue (trx): posting accounts only

# --- list references -------------------------------------------------------------------------------
REF_PREQUAL, REF_AQ_STATUS, REF_AQ_SOURCE, REF_RISK = 1091500, 1091501, 1091502, 1091503
REFERENCES = [
    (REF_PREQUAL, "ZC Pre-qualification Status", [("P", "Pending"), ("Q", "Pre-qualified"), ("S", "Suspended"),
                                                 ("B", "Blacklisted")]),
    (REF_AQ_STATUS, "ZC Audit Query Status", [("OP", "Open"), ("IP", "In progress"), ("CL", "Closed")]),
    (REF_AQ_SOURCE, "ZC Audit Source", [("EA", "External audit"), ("IA", "Internal audit"), ("BD", "Board or committee"),
                                        ("KR", "KRA"), ("OT", "Other")]),
    (REF_RISK, "ZC Risk Rating", [("H", "High"), ("M", "Medium"), ("L", "Low")]),
]

# --- processes -------------------------------------------------------------------------------------
PROC_CLOSE_AQ = 1091500
PROC_BUDGET_APPROVE, PROC_BUDGET_STATUS = 1091501, 1091502
PROCESSES = [
    (PROC_CLOSE_AQ, "ZC_AuditQueryClose", "Close Audit Query",
     "Closes an audit query once the management response and evidence are recorded. The person responsible for "
     "resolving it cannot close it.",
     "ke.co.pollux.zenithcore.controls.idempiere.process.AuditQueryClose"),
    (PROC_BUDGET_APPROVE, "ZC_BudgetApprove", "Approve Budget",
     "Approves a budget so that it counts in budget checks, and locks it. The person who prepared it cannot "
     "approve it.",
     "ke.co.pollux.zenithcore.controls.idempiere.process.BudgetApprove"),
    (PROC_BUDGET_STATUS, "ZC_BudgetStatus", "Budget Status Report",
     "Budget, spent, committed and available by cost centre and account for a financial year.",
     "ke.co.pollux.zenithcore.controls.idempiere.process.BudgetStatusReport"),
]

PROCESS_PARAMS = [  # (id, process, column, name, reference, mandatory, seq, default)
    (1091500, PROC_BUDGET_STATUS, "C_Year_ID", "Financial year", TABLEDIR, True, 10, None),
    (1091501, PROC_BUDGET_STATUS, "C_Activity_ID", "Cost centre", TABLEDIR, False, 20, None),
]

# --- client switches -------------------------------------------------------------------------------
SYSCONFIG = [  # (id, name, system default, description); each client sets its own value
    (1091500, "ZC_SUPPLIER_GATE", "N",
     "Y blocks LPOs, supplier invoices and supplier payments for suppliers who are not pre-qualified, have an "
     "expired tax compliance certificate or are blacklisted"),
    (1091501, "ZC_THREE_WAY_MATCH", "N",
     "Y blocks supplier invoices that do not match an LPO and the goods received"),
    (1091502, "ZC_THREE_WAY_PRICE_TOLERANCE_PCT", "0",
     "Percentage by which a supplier invoice price may exceed the LPO price"),
    (1091503, "ZC_BUDGET_CONTROL", "N",
     "Y blocks requisitions, LPOs, supplier invoices not from an LPO, and direct payments that exceed the "
     "approved budget for their cost centre and account"),
    (1091504, "ZC_BUDGET_TOLERANCE_PCT", "0", "Percentage by which spending may exceed a budget line"),
]

# --- new tables ------------------------------------------------------------------------------------
TABLES = [
    dict(id=1091500, table="ZC_DocControl", name="Document Approval Rule", window=1091500, cols=[
        dict(col="DocBaseType", name="Document base type", ref=LIST, refvalue=DOCBASETYPE_REF, ddl="CHAR(3) NOT NULL",
             length=3, mandatory=True, identifier=1,
             help="Documents of this kind need a second person to complete them."),
        dict(col="ZC_FromAmount", name="From amount", ref=AMOUNT, ddl="NUMERIC DEFAULT 0 NOT NULL", default="0",
             mandatory=True, help="The rule applies from this amount upwards. Zero means every document."),
        dict(col="Description", name="Description", ref=STRING, ddl="VARCHAR(255)", length=255,
             help="For example the delegation-of-authority clause this rule implements."),
    ]),
    dict(id=1091501, table="ZC_AuditQuery", name="Audit Query", window=1091501, cols=[
        dict(col="Value", name="Query reference", ref=STRING, ddl="VARCHAR(40) NOT NULL", length=40, mandatory=True,
             identifier=1, help="Reference in the audit report or management letter, for example ML 2024/25-07."),
        dict(col="Name", name="Title", ref=STRING, ddl="VARCHAR(120) NOT NULL", length=120, mandatory=True,
             identifier=2),
        dict(col="ZC_AuditSource", name="Raised by", ref=LIST, refvalue=REF_AQ_SOURCE, ddl="CHAR(2) DEFAULT 'EA' NOT NULL",
             length=2, default="EA", mandatory=True),
        dict(col="ZC_AuditYear", name="Financial year", ref=STRING, ddl="VARCHAR(9)", length=9,
             help="Financial year audited, for example 2024/25."),
        dict(col="ZC_RiskRating", name="Risk", ref=LIST, refvalue=REF_RISK, ddl="CHAR(1) DEFAULT 'M' NOT NULL", length=1,
             default="M", mandatory=True),
        dict(col="ZC_Finding", name="Finding", ref=TEXT, ddl="VARCHAR(2000)", length=2000),
        dict(col="ZC_Recommendation", name="Recommendation", ref=TEXT, ddl="VARCHAR(2000)", length=2000),
        dict(col="ZC_ResponsibleUser_ID", name="Responsible person", ref=TABLE, refvalue=AD_USER_REF,
             ddl="NUMERIC(10)", fk="AD_User"),
        dict(col="ZC_DueDate", name="Due date", ref=DATE, ddl="TIMESTAMP"),
        dict(col="ZC_ManagementResponse", name="Management response", ref=TEXT, ddl="VARCHAR(2000)", length=2000,
             help="What was done to resolve the query. Attach the evidence to this record."),
        dict(col="ZC_AuditQueryStatus", name="Status", ref=LIST, refvalue=REF_AQ_STATUS,
             ddl="CHAR(2) DEFAULT 'OP' NOT NULL", length=2, default="OP", mandatory=True),
        dict(col="ZC_ClosedUser_ID", name="Closed by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_DateClosed", name="Date closed", ref=DATE, ddl="TIMESTAMP", readonly=True),
        dict(col="Processed", name="Processed", ref=YESNO, ddl="CHAR(1) DEFAULT 'N' NOT NULL", default="N",
             readonly=True, hidden=True),
        dict(col="ZC_CloseQuery", name="Close Audit Query", ref=BUTTON, ddl="CHAR(1)", length=1, process=PROC_CLOSE_AQ),
    ]),
    dict(id=1091502, table="ZC_Budget", name="Budget", window=1091503, cols=[
        dict(col="Name", name="Budget", ref=STRING, ddl="VARCHAR(120) NOT NULL", length=120, mandatory=True,
             identifier=1, help="For example Approved budget 2026/27, or Supplementary budget 1 2026/27."),
        dict(col="C_Year_ID", name="Financial year", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             fk="C_Year"),
        dict(col="Description", name="Description", ref=STRING, ddl="VARCHAR(255)", length=255,
             help="For example the board minute that approved the budget."),
        dict(col="ZC_ApprovedUser_ID", name="Approved by", ref=TABLE, refvalue=AD_USER_REF, ddl="NUMERIC(10)",
             readonly=True, fk="AD_User"),
        dict(col="ZC_DateApproved", name="Date approved", ref=DATE, ddl="TIMESTAMP", readonly=True),
        dict(col="Processed", name="Processed", ref=YESNO, ddl="CHAR(1) DEFAULT 'N' NOT NULL", default="N",
             readonly=True, hidden=True),
        dict(col="ZC_ApproveBudget", name="Approve Budget", ref=BUTTON, ddl="CHAR(1)", length=1,
             process=PROC_BUDGET_APPROVE),
    ]),
    dict(id=1091503, table="ZC_BudgetLine", name="Budget Line", window=1091503, cols=[
        dict(col="ZC_Budget_ID", name="Budget", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             parent=True, readonly=True, fk="ZC_Budget"),
        dict(col="C_Activity_ID", name="Cost centre", ref=TABLEDIR, ddl="NUMERIC(10) NOT NULL", mandatory=True,
             fk="C_Activity"),
        dict(col="Account_ID", name="Account", ref=SEARCH, refvalue=ACCOUNT_REF_VALUE, ddl="NUMERIC(10) NOT NULL",
             mandatory=True, fk="C_ElementValue"),
        dict(col="ZC_Amount", name="Annual amount", ref=AMOUNT, ddl="NUMERIC DEFAULT 0 NOT NULL", default="0",
             mandatory=True),
        dict(col="Description", name="Description", ref=STRING, ddl="VARCHAR(255)", length=255),
        dict(col="Processed", name="Processed", ref=YESNO, ddl="CHAR(1) DEFAULT 'N' NOT NULL", default="N",
             readonly=True, hidden=True),
    ]),
]

# --- columns added to iDempiere tables ----------------------------------------------------------------
EXTENSIONS = [  # (table, column spec)
    ("C_BPartner", dict(col="ZC_PrequalStatus", name="Pre-qualification", ref=LIST, refvalue=REF_PREQUAL,
                        ddl="CHAR(1) DEFAULT 'P'", length=1, default="P",
                        help="Pending, Pre-qualified, Suspended or Blacklisted.")),
    ("C_BPartner", dict(col="ZC_PrequalValidTo", name="Pre-qualified until", ref=DATE, ddl="TIMESTAMP",
                        help="Last day of the pre-qualification period.")),
    ("C_BPartner", dict(col="ZC_TCCNo", name="Tax compliance certificate no.", ref=STRING, ddl="VARCHAR(30)",
                        length=30)),
    ("C_BPartner", dict(col="ZC_TCCExpiry", name="Tax compliance certificate expiry", ref=DATE, ddl="TIMESTAMP")),
    ("C_BPartner", dict(col="ZC_BlacklistReason", name="Blacklist reason", ref=TEXT, ddl="VARCHAR(255)", length=255,
                        help="Why the supplier is blacklisted, with the approving minute.")),
    ("M_Requisition", dict(col="C_Activity_ID", name="Cost centre", ref=TABLEDIR, ddl="NUMERIC(10)",
                           help="Cost centre whose budget the requisition is checked against.")),
]
EXTENSION_FIELDS = [  # (field id, tab id, table, column, seq): editable fields on iDempiere tabs
    (1091954, 641, "M_Requisition", "C_Activity_ID", 75),
]

# Supplier pre-qualification window: a tab on C_BPartner limited to vendors
PREQUAL_WINDOW, PREQUAL_TAB = 1091502, 1091502
PREQUAL_FIELDS = [  # (column, read-only)
    ("Value", True), ("Name", True), ("TaxID", True), ("ZC_PrequalStatus", False), ("ZC_PrequalValidTo", False),
    ("ZC_TCCNo", False), ("ZC_TCCExpiry", False), ("ZC_BlacklistReason", False),
    # hidden, but iDempiere needs the tenant and organization in the tab to allow updates
    ("AD_Client_ID", True, True), ("AD_Org_ID", True, True), ("IsActive", True, True),
]
# Read-only copies on the Business Partner window's Vendor tab, so buyers see the status
VENDOR_TAB = 224
VENDOR_TAB_FIELDS = ["ZC_PrequalStatus", "ZC_PrequalValidTo", "ZC_TCCExpiry", "ZC_BlacklistReason"]

WINDOWS = [
    dict(id=1091500, name="Document Approval Rules", wtype="M",
         desc="Kinds of document that a second person must complete, and from what amount",
         tabs=[dict(id=1091500, table=1091500, name="Rule")]),
    dict(id=1091501, name="Audit Query Register", wtype="T",
         desc="Audit queries and management letter points, tracked to closure with evidence",
         tabs=[dict(id=1091501, table=1091501, name="Audit Query")]),
]

MENU_FOLDER = 1091500
MENUS = [  # (id, name, action, window or process, seq)
    (1091501, "Document Approval Rules", "W", 1091500, 10),
    (1091502, "Supplier Pre-qualification (Kenya)", "W", PREQUAL_WINDOW, 20),
    (1091503, "Audit Query Register", "W", 1091501, 30),
    (1091504, "Budgets (Kenya)", "W", 1091503, 40),
    (1091505, "Budget Status Report", "P", PROC_BUDGET_STATUS, 50),
]

# Windows with header and line tabs. Field IDs: field_base + position.
MULTI_WINDOWS = [
    dict(id=1091503, name="Budgets (Kenya)", wtype="M",
         desc="Approved annual budgets by cost centre and account, used by the budget check",
         tabs=[dict(id=1091503, table=1091502, name="Budget", level=0, seq=10, field_base=1091600),
               dict(id=1091504, table=1091503, name="Budget Line", level=1, seq=20, field_base=1091640,
                    link="ZC_Budget_ID")]),
]


def column_sql(w, cid, tid_expr, tn, c, el, element_ids):
    name_expr = q(c["name"]) if "name" in c else f"(SELECT Name FROM AD_Element WHERE AD_Element_ID={el})"
    updateable = "N" if c.get("readonly") or c.get("key") else "Y"
    if c["col"] == "IsActive":
        updateable = "Y"
    length = c.get("length", {ID: 22, SEARCH: 22, TABLEDIR: 22, TABLE: 22, YESNO: 1, DATETIME: 7, DATE: 7,
                              AMOUNT: 22, INTEGER: 10}.get(c["ref"], 10))
    w(f"INSERT INTO AD_Column (AD_Column_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,Version,EntityType,ColumnName,AD_Table_ID,AD_Reference_ID,AD_Reference_Value_ID,AD_Val_Rule_ID,AD_Process_ID,FieldLength,DefaultValue,IsKey,IsParent,IsMandatory,IsUpdateable,IsIdentifier,SeqNo,IsTranslated,IsEncrypted,IsSelectionColumn,AD_Element_ID,IsAlwaysUpdateable,IsAutocomplete,IsAllowCopy,IsToolbarButton,IsSecure,IsPartitionKey,FKConstraintType,AD_Column_UU) "
      f"SELECT {cid},0,0,'Y',now(),100,now(),100,{name_expr},{q(c.get('help'))},0,{q(ENTITY)},{q(c['col'])},{tid_expr},{c['ref']},{q(c.get('refvalue'))},{q(c.get('valrule'))},{q(c.get('process'))},{length},{q(c.get('default'))},"
      f"{q('Y' if c.get('key') else 'N')},{q('Y' if c.get('parent') else 'N')},{q('Y' if c.get('mandatory') else 'N')},{q(updateable)},{q('Y' if c.get('identifier') else 'N')},{c.get('identifier', 0)},'N','N','N',{el},'N','N','Y',{q('N' if c['ref'] == BUTTON else 'Y')},'N','N',{q('D' if c.get('fk') else 'N')},{q(uu('column', tn + '.' + c['col']))} "
      f"WHERE NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Column_ID={cid});")


def field_sql(w, fid, tab_id, col_expr, key, seq, hidden=False, readonly=False, same_line=False):
    w(f"INSERT INTO AD_Field (AD_Field_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,IsCentrallyMaintained,AD_Tab_ID,AD_Column_ID,IsDisplayed,IsDisplayedGrid,SeqNo,SeqNoGrid,IsReadOnly,IsSameLine,IsHeading,IsFieldOnly,IsEncrypted,EntityType,IsQuickEntry,IsDefaultFocus,IsQuickForm,AD_Field_UU) "
      f"SELECT {fid},0,0,'Y',now(),100,now(),100,(SELECT Name FROM AD_Column WHERE AD_Column_ID={col_expr}),'Y',{tab_id},{col_expr},{q('N' if hidden else 'Y')},{q('N' if hidden else 'Y')},{0 if hidden else seq},{0 if hidden else seq},{q('Y' if readonly else 'N')},{q('Y' if same_line else 'N')},'N','N','N',{q(ENTITY)},'N','N','N',{q(uu('field', str(tab_id) + '.' + key))} "
      f"WHERE NOT EXISTS (SELECT 1 FROM AD_Field WHERE AD_Field_ID={fid});")


def element_sql(w, eid, c):
    nm = c.get("name", c["col"])
    w(f"INSERT INTO AD_Element (AD_Element_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,ColumnName,EntityType,Name,PrintName,Description,AD_Element_UU) "
      f"SELECT {eid},0,0,'Y',now(),100,now(),100,{q(c['col'])},{q(ENTITY)},{q(nm)},{q(nm)},{q(c.get('help'))},{q(uu('element', c['col']))} WHERE NOT EXISTS (SELECT 1 FROM AD_Element WHERE AD_Element_ID={eid} OR UPPER(ColumnName)=UPPER({q(c['col'])}));")


def core_col(table, column):
    return (f"(SELECT AD_Column_ID FROM AD_Column WHERE ColumnName={q(column)} AND AD_Table_ID="
            f"(SELECT AD_Table_ID FROM AD_Table WHERE TableName={q(table)}))")


def main():
    out = []
    w = out.append
    w("-- ZC-0004 ZenithCore Phase 1 controls: approval rules, supplier pre-qualification, audit-query register.")
    w("-- GENERATED by tools/gen_dictionary.py. Do not edit by hand. Safe to re-run.")
    w("")
    rl = 1091500
    for rid, name, values in REFERENCES:
        w(f"INSERT INTO AD_Reference (AD_Reference_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,ValidationType,EntityType,AD_Reference_UU) "
          f"SELECT {rid},0,0,'Y',now(),100,now(),100,{q(name)},'L',{q(ENTITY)},{q(uu('ref', name))} WHERE NOT EXISTS (SELECT 1 FROM AD_Reference WHERE AD_Reference_ID={rid});")
        for v, n in values:
            w(f"INSERT INTO AD_Ref_List (AD_Ref_List_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,AD_Reference_ID,EntityType,AD_Ref_List_UU) "
              f"SELECT {rl},0,0,'Y',now(),100,now(),100,{q(v)},{q(n)},{rid},{q(ENTITY)},{q(uu('reflist', name + '.' + v))} WHERE NOT EXISTS (SELECT 1 FROM AD_Ref_List WHERE AD_Ref_List_ID={rl});")
            rl += 1
    w("")
    for pid, value, name, desc, cls in PROCESSES:
        w(f"INSERT INTO AD_Process (AD_Process_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Value,Name,Description,AccessLevel,EntityType,IsReport,Classname,IsServerProcess,ShowHelp,IsBetaFunctionality,AllowMultipleExecution,AD_Process_UU) "
          f"SELECT {pid},0,0,'Y',now(),100,now(),100,{q(value)},{q(name)},{q(desc)},'3',{q(ENTITY)},'N',{q(cls)},'N','Y','N','P',{q(uu('process', value))} WHERE NOT EXISTS (SELECT 1 FROM AD_Process WHERE AD_Process_ID={pid});")
    for sid, name, value, desc in SYSCONFIG:
        w(f"INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value,Description,EntityType,ConfigurationLevel,AD_SysConfig_UU) "
          f"SELECT {sid},0,0,'Y',now(),100,now(),100,{q(name)},{q(value)},{q(desc)},{q(ENTITY)},'C',{q(uu('sysconfig', name))} WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig WHERE AD_SysConfig_ID={sid});")
    all_windows = WINDOWS + MULTI_WINDOWS + [dict(id=PREQUAL_WINDOW, name="Supplier Pre-qualification (Kenya)", wtype="M",
                                  desc="Pre-qualification status, tax compliance certificate and blacklist of suppliers")]
    for win in all_windows:
        w(f"INSERT INTO AD_Window (AD_Window_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,WindowType,IsSOTrx,EntityType,IsDefault,IsBetaFunctionality,AD_Window_UU) "
          f"SELECT {win['id']},0,0,'Y',now(),100,now(),100,{q(win['name'])},{q(win['desc'])},{q(win['wtype'])},'N',{q(ENTITY)},'N','N',{q(uu('window', win['name']))} WHERE NOT EXISTS (SELECT 1 FROM AD_Window WHERE AD_Window_ID={win['id']});")
    w("")

    elements = {}
    col_ids = {}
    fks = []
    for ti, t in enumerate(TABLES):
        tid, tn = t["id"], t["table"]
        w(f"-- {tn}")
        cols = std_cols(t) + t["cols"]
        ddl = [f"  {c['col']} {c['ddl']}" for c in cols]
        ddl.append(f"  CONSTRAINT {tn}_Key PRIMARY KEY ({tn}_ID)")
        ddl.append(f"  CONSTRAINT {tn}_UU_idx UNIQUE ({tn}_UU)")
        w(f"CREATE TABLE IF NOT EXISTS {tn} (\n" + ",\n".join(ddl) + "\n);")
        for c in cols:
            w(f"ALTER TABLE {tn} ADD COLUMN IF NOT EXISTS {c['col']} {c['ddl'].replace(' NOT NULL', '')};")
            if c.get("fk"):
                cname = f"{tn[:12]}_{c['col'][:14]}_fk"
                fks.append(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=lower({q(cname)})) THEN "
                           f"ALTER TABLE {tn} ADD CONSTRAINT {cname} FOREIGN KEY ({c['col']}) REFERENCES {c['fk']} ({c['fk']}_ID) DEFERRABLE INITIALLY DEFERRED; END IF; END $$;")
        w(f"INSERT INTO AD_Table (AD_Table_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,TableName,IsView,AccessLevel,EntityType,AD_Window_ID,IsSecurityEnabled,IsDeleteable,IsHighVolume,IsChangeLog,ReplicationType,IsPartition,AD_Table_UU) "
          f"SELECT {tid},0,0,'Y',now(),100,now(),100,{q(t['name'])},{q(tn)},'N','3',{q(ENTITY)},{t['window']},'N','Y','N','Y','L','N',{q(uu('table', tn))} WHERE NOT EXISTS (SELECT 1 FROM AD_Table WHERE AD_Table_ID={tid});")
        w(f"INSERT INTO AD_Sequence (AD_Sequence_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,IsTableID,IsAutoSequence,IncrementNo,StartNo,CurrentNext,CurrentNextSys,StartNewMonth,IsOrgLevelSequence,AD_Sequence_UU) "
          f"SELECT {tid},0,0,'Y',now(),100,now(),100,{q(tn)},{q('Table ' + tn)},'Y','Y',1,1000000,1000000,200000,'N','N',{q(uu('sequence', tn))} WHERE NOT EXISTS (SELECT 1 FROM AD_Sequence WHERE Name={q(tn)} AND IsTableID='Y');")
        for ci, c in enumerate(cols):
            cid = 1091500 + 40 * ti + ci
            cn = c["col"]
            if cn in elements:
                el = elements[cn]
            elif cn in CORE_ELEMENTS or cn in _pg.CORE_ELEMENTS or c.get("std"):
                el = f"(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName={q(cn)} AND AD_Client_ID=0)"
            else:
                # an element of the same name may already exist (for example from the payroll plug-in)
                element_sql(w, cid, c)
                el = f"(SELECT AD_Element_ID FROM AD_Element WHERE UPPER(ColumnName)=UPPER({q(cn)}))"
            elements[cn] = el
            col_ids[(tid, cn)] = cid
            column_sql(w, cid, tid, tn, c, el, elements)
        w("")

    w("-- columns added to iDempiere tables")
    for i, (tn, c) in enumerate(EXTENSIONS):
        cid = 1091900 + i
        w(f"ALTER TABLE {tn} ADD COLUMN IF NOT EXISTS {c['col']} {c['ddl'].replace(' NOT NULL', '')};")
        if c["col"] in CORE_ELEMENTS:
            el = f"(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName={q(c['col'])} AND AD_Client_ID=0)"
        else:
            element_sql(w, cid, c)
            el = str(cid)
        column_sql(w, cid, f"(SELECT AD_Table_ID FROM AD_Table WHERE TableName={q(tn)})", tn, c, el, elements)
        col_ids[(tn, c["col"])] = cid
    w("")
    w("-- foreign keys")
    for f in fks:
        w(f)
    w("")

    for tab_no, win in enumerate(WINDOWS):
        tab = win["tabs"][0]
        t = next(x for x in TABLES if x["id"] == tab["table"])
        w(f"INSERT INTO AD_Tab (AD_Tab_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Table_ID,AD_Window_ID,SeqNo,TabLevel,IsSingleRow,IsTranslationTab,IsReadOnly,HasTree,IsSortTab,EntityType,IsInsertRecord,IsAdvancedTab,TreeDisplayedOn,AD_Tab_UU) "
          f"SELECT {tab['id']},0,0,'Y',now(),100,now(),100,{q(tab['name'])},{t['id']},{win['id']},10,0,'Y','N','N','N','N',{q(ENTITY)},'Y','N','B',{q(uu('tab', str(tab['id'])))} WHERE NOT EXISTS (SELECT 1 FROM AD_Tab WHERE AD_Tab_ID={tab['id']});")
        seq = 0
        for ci, c in enumerate(std_cols(t) + t["cols"]):
            hidden = bool(c.get("hidden"))
            if not hidden:
                seq += 10
            field_sql(w, 1091500 + 40 * tab_no + ci, tab["id"], col_ids[(t["id"], c["col"])], c["col"], seq,
                      hidden=hidden, same_line=c["col"] == "AD_Org_ID")
    w("")
    # supplier pre-qualification window over C_BPartner (vendors only)
    w(f"INSERT INTO AD_Tab (AD_Tab_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Table_ID,AD_Window_ID,SeqNo,TabLevel,IsSingleRow,IsTranslationTab,IsReadOnly,HasTree,IsSortTab,EntityType,IsInsertRecord,IsAdvancedTab,TreeDisplayedOn,WhereClause,OrderByClause,AD_Tab_UU) "
      f"SELECT {PREQUAL_TAB},0,0,'Y',now(),100,now(),100,'Supplier',(SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_BPartner'),{PREQUAL_WINDOW},10,0,'N','N','N','N','N',{q(ENTITY)},'N','N','B',"
      f"'C_BPartner.IsVendor=''Y''','C_BPartner.Name',{q(uu('tab', str(PREQUAL_TAB)))} WHERE NOT EXISTS (SELECT 1 FROM AD_Tab WHERE AD_Tab_ID={PREQUAL_TAB});")
    fid = 1091500 + 40 * len(WINDOWS)
    field_sql(w, fid, PREQUAL_TAB, core_col("C_BPartner", "C_BPartner_ID"), "C_BPartner_ID", 0, hidden=True)
    for i, (cn, ro, *hidden) in enumerate(PREQUAL_FIELDS):
        expr = str(col_ids[("C_BPartner", cn)]) if ("C_BPartner", cn) in col_ids else core_col("C_BPartner", cn)
        field_sql(w, fid + 1 + i, PREQUAL_TAB, expr, cn, 10 * (i + 1), readonly=ro, hidden=bool(hidden and hidden[0]))
    w("")
    w("-- read-only pre-qualification fields on the Business Partner window's Vendor tab")
    for i, cn in enumerate(VENDOR_TAB_FIELDS):
        field_sql(w, 1091950 + i, VENDOR_TAB, col_ids[("C_BPartner", cn)], cn, 900 + 10 * i, readonly=True)
    for fid2, tab_id, tn, cn, seqno in EXTENSION_FIELDS:
        field_sql(w, fid2, tab_id, col_ids[(tn, cn)], cn, seqno)
    w("")
    # header and line windows
    for win in MULTI_WINDOWS:
        for tab in win["tabs"]:
            t = next(x for x in TABLES if x["id"] == tab["table"])
            link = col_ids[(t["id"], tab["link"])] if tab.get("link") else "NULL"
            w(f"INSERT INTO AD_Tab (AD_Tab_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Table_ID,AD_Window_ID,SeqNo,TabLevel,IsSingleRow,IsTranslationTab,IsReadOnly,AD_Column_ID,HasTree,IsSortTab,EntityType,IsInsertRecord,IsAdvancedTab,TreeDisplayedOn,AD_Tab_UU) "
              f"SELECT {tab['id']},0,0,'Y',now(),100,now(),100,{q(tab['name'])},{t['id']},{win['id']},{tab['seq']},{tab['level']},{q('Y' if tab['level'] == 0 else 'N')},'N','N',{link},'N','N',{q(ENTITY)},'Y','N','B',{q(uu('tab', str(tab['id'])))} WHERE NOT EXISTS (SELECT 1 FROM AD_Tab WHERE AD_Tab_ID={tab['id']});")
            seq = 0
            for ci, c in enumerate(std_cols(t) + t["cols"]):
                hidden = bool(c.get("hidden")) or c["col"] == tab.get("link")
                if not hidden:
                    seq += 10
                field_sql(w, tab["field_base"] + ci, tab["id"], col_ids[(t["id"], c["col"])], c["col"], seq,
                          hidden=hidden, same_line=c["col"] == "AD_Org_ID")
    w("")
    for ppid, pid, cn, name, ref, mand, seqno, default in PROCESS_PARAMS:
        w(f"INSERT INTO AD_Process_Para (AD_Process_Para_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,AD_Process_ID,SeqNo,AD_Reference_ID,IsRange,FieldLength,IsMandatory,ColumnName,IsCentrallyMaintained,EntityType,IsEncrypted,DefaultValue,AD_Element_ID,AD_Process_Para_UU) "
          f"SELECT {ppid},0,0,'Y',now(),100,now(),100,{q(name)},{pid},{seqno},{ref},'N',10,{q('Y' if mand else 'N')},{q(cn)},'N',{q(ENTITY)},'N',{q(default)},"
          f"(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName={q(cn)} AND AD_Client_ID=0),{q(uu('para', str(ppid)))} WHERE NOT EXISTS (SELECT 1 FROM AD_Process_Para WHERE AD_Process_Para_ID={ppid});")
    w("")
    # menu
    w(f"INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Description,IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU) "
      f"SELECT {MENU_FOLDER},0,0,'Y',now(),100,now(),100,'Finance and Procurement Controls','ZenithCore Phase 1 controls','Y','N','N',{q(ENTITY)},{q(uu('menu', 'folder'))} WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID={MENU_FOLDER});")
    w(f"INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU) "
      f"SELECT 10,{MENU_FOLDER},0,0,'Y',now(),100,now(),100,{ZENITHCORE_FOLDER},5,{q(uu('treenode', str(MENU_FOLDER)))} WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID={MENU_FOLDER});")
    for mid, name, action, target, seqno in MENUS:
        col = "AD_Window_ID" if action == "W" else "AD_Process_ID"
        w(f"INSERT INTO AD_Menu (AD_Menu_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Action,{col},IsSummary,IsSOTrx,IsReadOnly,EntityType,AD_Menu_UU) "
          f"SELECT {mid},0,0,'Y',now(),100,now(),100,{q(name)},{q(action)},{target},'N','N','N',{q(ENTITY)},{q(uu('menu', name))} WHERE NOT EXISTS (SELECT 1 FROM AD_Menu WHERE AD_Menu_ID={mid});")
        w(f"INSERT INTO AD_TreeNodeMM (AD_Tree_ID,Node_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Parent_ID,SeqNo,AD_TreeNodeMM_UU) "
          f"SELECT 10,{mid},0,0,'Y',now(),100,now(),100,{MENU_FOLDER},{seqno},{q(uu('treenode', str(mid)))} WHERE NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID=10 AND Node_ID={mid});")
    w("")
    w("-- one rule per kind of document in each client")
    w("CREATE UNIQUE INDEX IF NOT EXISTS ZC_DocControl_Kind ON ZC_DocControl (AD_Client_ID, DocBaseType) WHERE IsActive='Y';")
    w("-- one line per cost centre and account in a budget")
    w("CREATE UNIQUE INDEX IF NOT EXISTS ZC_BudgetLine_Key ON ZC_BudgetLine (ZC_Budget_ID, C_Activity_ID, Account_ID);")
    print("\n".join(out))


if __name__ == "__main__":
    main()
