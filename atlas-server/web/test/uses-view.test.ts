import {describe, expect, it} from "vitest";
import type {CallArgument, EntitySummary, OccurrenceRow} from "../src/api";
import {shownLine} from "../src/text-search";
import {
    ARGUMENT_CUT,
    argumentChip,
    enclosingRoutine,
    groupOf,
    nameLength,
    programWithin,
    rowLine,
    rowNaming,
    shownStatement,
    useMark
} from "../src/uses-view";

const entity = (key: string, kind: string, display: string, name: string): EntitySummary => ({ key, kind, display, external: false, name });

const ledger = entity("db:APP.LEDGER_PKG", "PACKAGE", "APP.LEDGER_PKG", "LEDGER_PKG");
const post = entity("db:APP.LEDGER_PKG.POST(P_ID IN NUMBER)", "PROCEDURE", "APP.LEDGER_PKG.POST(P_ID IN NUMBER)", "POST");
const billing = entity("db:APP.BILLING_PKG", "PACKAGE", "APP.BILLING_PKG", "BILLING_PKG");
const closeMonth = entity("db:APP.BILLING_PKG@BODY.CLOSE_MONTH()", "PROCEDURE", "APP.BILLING_PKG@BODY.CLOSE_MONTH()", "CLOSE_MONTH");
const billingBody = entity("db:APP.BILLING_PKG@BODY", "PACKAGE", "APP.BILLING_PKG@BODY", "BILLING_PKG");

function row(overrides: Partial<OccurrenceRow>): OccurrenceRow {
  return {
    id: 1, path: "app/billing.pkb", span: { startLine: 10, startCol: 5, endLine: 10, endCol: 20 }, written: "LEDGER_PKG.POST",
    roles: ["CALL"], status: "RESOLVED", reason: null, origin: "STATIC", statementKind: null, arguments: [], values: [],
    container: closeMonth, unit: { kind: "PACKAGE_BODY", name: "BILLING_PKG", entity: billing }, matched: post,
    viaView: false, viaCandidate: false, candidates: [], entity: post,
    ...overrides
  } as OccurrenceRow;
}

describe("groupOf", () => {
  it("groups by the routine a row is in, a script by its unit and file", () => {
    expect(groupOf(row({}))).toEqual({ key: closeMonth.key, program: closeMonth });
    const script = row({ container: null, unit: { kind: "ANONYMOUS_BLOCK", name: "block 1", entity: null } });
    expect(groupOf(script)).toEqual({ key: "file:app/billing.pkb|ANONYMOUS_BLOCK block 1", program: null });
  });

  it("groups the scripts of one file as one program", () => {
    const block = row({ container: null, unit: { kind: "ANONYMOUS_BLOCK", name: "block 1", entity: null }, path: "demo/run.sql" });
    const statement = row({ container: null, unit: { kind: "SQL_STATEMENT", name: "statement 2", entity: null }, path: "demo/run.sql" });
    expect(groupOf(block, "program")).toEqual({ key: "file:demo/run.sql", program: null });
    expect(groupOf(statement, "program")).toEqual(groupOf(block, "program"));
    expect(groupOf(statement)).not.toEqual(groupOf(block));
  });

  it("groups by program: the package a routine is in, across its spec and body", () => {
    expect(groupOf(row({}), "program")).toEqual({ key: billing.key, program: billing });
    const inSpec = row({ container: billing, unit: { kind: "PACKAGE", name: "BILLING_PKG", entity: billing }, path: "app/billing.pks" });
    expect(groupOf(inSpec, "program").key).toBe(groupOf(row({}), "program").key);
  });

  it("falls back to the unit's program when a row has no routine", () => {
    expect(groupOf(row({ container: null }))).toEqual({ key: billing.key, program: billing });
  });
});

describe("enclosingRoutine", () => {
  it("names the routine of a row grouped by program, not the program itself or its body", () => {
    expect(enclosingRoutine(row({}))).toBe(closeMonth);
    expect(enclosingRoutine(row({ container: billing }))).toBe(null);
    expect(enclosingRoutine(row({ container: billingBody }))).toBe(null);
    expect(enclosingRoutine(row({ container: null }))).toBe(null);
  });
});

describe("rowNaming", () => {
  const packagePage = { page: { key: ledger.key, kind: "PACKAGE", display: ledger.display } };
  const routinePage = { page: { key: post.key, kind: "PROCEDURE", display: post.display } };

  it("names the member a row hits on its package's page", () => {
    expect(rowNaming(row({}), packagePage)).toBe("member");
    expect(rowNaming(row({ matched: ledger }), packagePage)).toBe("none");
    expect(rowNaming(row({ matched: entity(ledger.key + "@BODY", "PACKAGE", "APP.LEDGER_PKG@BODY", "LEDGER_PKG") }), packagePage)).toBe("none");
  });

  it("leaves out the name of a direct hit on the routine's own page", () => {
    expect(rowNaming(row({}), routinePage)).toBe("none");
    expect(rowNaming(row({}), {})).toBe("none");
  });

  it("writes the name of a hit through a view or as a candidate, and names the entity in a listing", () => {
    expect(rowNaming(row({ viaView: true }), routinePage)).toBe("written");
    expect(rowNaming(row({ viaCandidate: true }), packagePage)).toBe("written");
    expect(rowNaming(row({}), { listing: true })).toBe("entity");
    expect(rowNaming(row({ matched: null }), { listing: true })).toBe("written");
    expect(rowNaming(row({ viaCandidate: true }), { listing: true })).toBe("written");
  });
});

describe("shownStatement", () => {
  it("shows the statement only when no role says it", () => {
    expect(shownStatement(row({ statementKind: "SELECT" }))).toBe("SELECT");
    expect(shownStatement(row({ roles: ["SQL_SELECT"], statementKind: "SELECT" }))).toBe(null);
    expect(shownStatement(row({ roles: ["DDL_TRUNCATE"], statementKind: "DDL" }))).toBe(null);
    expect(shownStatement(row({ roles: ["READ"], statementKind: "DDL" }))).toBe("DDL");
    expect(shownStatement(row({ roles: [], status: "DYNAMIC", statementKind: "DYNAMIC" }))).toBe(null);
    expect(shownStatement(row({}))).toBe(null);
  });
});

describe("argumentChip", () => {
  const arg = (valueKind: string, value: string | null, label: string | null = null, parameter: EntitySummary | null = null): CallArgument =>
    ({ ordinal: 1, label, parameter, span: { startLine: 1, startCol: 1, endLine: 1, endCol: 2 }, valueKind, value });

  it("names the parameter by its label, else the bound parameter, else nothing", () => {
    expect(argumentChip(arg("NUMBER", "1", "P_ID")).name).toBe("P_ID");
    expect(argumentChip(arg("NUMBER", "1", null, entity("k", "PARAMETER", "d", "P_ID"))).name).toBe("P_ID");
    expect(argumentChip(arg("NUMBER", "1")).name).toBe(null);
  });

  it("reads a string quoted, NULL as written and an expression as a word", () => {
    expect(argumentChip(arg("STRING", "it's"))).toMatchObject({ text: "'it''s'", look: "string" });
    expect(argumentChip(arg("NULL", null))).toMatchObject({ text: "NULL", look: "null" });
    expect(argumentChip(arg("EXPRESSION", null))).toMatchObject({ text: "", look: "expression" });
    expect(argumentChip(arg("NAME", "V_TOTAL"))).toMatchObject({ text: "V_TOTAL", look: "plain" });
  });

  it("cuts a value past the limit and keeps the whole for the tooltip", () => {
    const fits = "x".repeat(ARGUMENT_CUT - 2);
    expect(argumentChip(arg("STRING", fits))).toMatchObject({ text: `'${fits}'`, whole: `'${fits}'` });
    const long = "y".repeat(ARGUMENT_CUT);
    const chip = argumentChip(arg("STRING", long));
    expect(chip.text).toHaveLength(ARGUMENT_CUT);
    expect(chip.text.endsWith("…")).toBe(true);
    expect(chip.whole).toBe(`'${long}'`);
  });
});

describe("programWithin", () => {
  const page = { key: ledger.key, display: ledger.display };

  it("drops the page's own package from its routines, in its spec or body", () => {
    expect(programWithin(post, page)).toBe("APP.LEDGER_PKG");
    expect(programWithin(entity("db:APP.LEDGER_PKG@BODY.SETTLE()", "PROCEDURE", "APP.LEDGER_PKG@BODY.SETTLE()", "SETTLE"), page)).toBe("APP.LEDGER_PKG");
  });

  it("keeps the owner of a caller from elsewhere, without its schema", () => {
    expect(programWithin(closeMonth, page)).toBe("APP");
    expect(programWithin(entity("db:APP.LEDGER_PKG_EXT.RUN()", "PROCEDURE", "APP.LEDGER_PKG_EXT.RUN()", "RUN"), page)).toBe("APP");
    expect(programWithin(post)).toBe("APP");
  });
});

describe("useMark", () => {
  // Invented code: a list with two uses on one line, the first at column 12, the second at column 46.
  const line = "      if (l.list_data(i).mapname is null or l.list_data(i).mapname like 'row%') then";
  const at = (startCol: number, endCol: number, endLine = 79) => ({ startLine: 79, startCol, endLine, endCol });
  /** The marked text of [line] as a row shows it, its indentation left out. */
  const marked = (marks: Array<[number, number]>) => {
    const shown = shownLine(line, marks, null);
    return shown.pieces.filter(p => p.mark).map(p => shown.text.slice(p.from, p.to));
  };

  it("marks each row's own span: two uses on one line mark two places", () => {
    const first = useMark(at(12, 21), line);
    const second = useMark(at(46, 55), line);
    expect(first).toEqual([[12, 21]]);
    expect(second).toEqual([[46, 55]]);
    expect(marked(first)).toEqual(["list_data"]);
    expect(marked(second)).toEqual(["list_data"]);
    expect(shownLine(line, second, null).pieces.find(p => p.mark)!.from).toBe(46 - 6);
  });

  it("marks a span that goes past its line to the end of the line", () => {
    const from = line.indexOf("'row%'");
    expect(useMark(at(from, 3, 81), line)).toEqual([[from, line.length]]);
    expect(marked(useMark(at(from, 3, 81), line))).toEqual(["'row%') then"]);
  });

  it("marks nothing past the end of a line that does not reach the span", () => {
    expect(useMark(at(90, 99), line)).toEqual([]);
  });

  it("ends the mark of a chain's start at the entity's own name", () => {
    // `l.list_data(i).mapname`: a use of the parameter L, its span and written the whole chain.
    const written = "l.list_data(i).mapname";
    const named = { written, length: nameLength(written, "L") };
    expect(marked(useMark(at(10, 32), line, named))).toEqual(["l"]);
    // The use of LIST_DATA itself: its span is the name already.
    expect(marked(useMark(at(12, 21), line, { written: "list_data", length: nameLength("list_data", "LIST_DATA") }))).toEqual(["list_data"]);
    // A line that no longer has the written name where the span starts: the whole span, never a guessed place.
    expect(useMark(at(10, 32), line, { written: "x.list_data", length: 1 })).toEqual([[10, 32]]);
  });
});

describe("nameLength", () => {
  it("is the length up to the end of the entity's name, a qualifier before it included", () => {
    expect(nameLength("p_img.color_tab", "P_IMG")).toBe(5);
    expect(nameLength("m_ctx.h", "M_CTX")).toBe(5);
    expect(nameLength("workbook.numFmtIndexes", "WORKBOOK")).toBe(8);
    expect(nameLength("bindvar.get_keys", "BINDVAR")).toBe(7);
    expect(nameLength("ledger_pkg.g_total.amount", "G_TOTAL")).toBe("ledger_pkg.g_total".length);
    expect(nameLength("l_rows (l_count).data_type", "L_ROWS")).toBe(6);
    expect(nameLength("\"MyRec\".f", "MyRec")).toBe(7);
  });

  it("is null when the written name is the entity's name alone, or does not hold it", () => {
    expect(nameLength("p_img", "P_IMG")).toBeNull();
    expect(nameLength("ledger_pkg.g_total", "G_TOTAL")).toBeNull();
    expect(nameLength("other.thing", "P_IMG")).toBeNull();
    expect(nameLength("\"MyRec\".f", "MYREC")).toBeNull();
    expect(nameLength("p_img.color_tab", null)).toBeNull();
  });
});

describe("a use Forms makes without code", () => {
  // The data block DATA_DESK/COMPANY inserts into HR.COMPANY by itself: a use on no line, in no unit.
  const block = entity("forms:DATA_DESK/COMPANY", "FORMS_BLOCK", "DATA_DESK/COMPANY", "COMPANY");
  const company = entity("db:HR.COMPANY", "TABLE", "HR.COMPANY", "COMPANY");
  const implicit = row({ path: "forms/data_desk_fmb.xml", span: null, unit: null, code: null, written: "COMPANY", roles: ["IMPLICIT", "SQL_INSERT"],
    origin: "FORMS_IMPLICIT", container: block, matched: company, entity: company });

  it("has no line: no link, no snippet", () => {
    expect(rowLine(implicit)).toBeNull();
    expect(rowLine(row({}))).toEqual({ line: 10, shown: 10, column: 5, code: null });
  });

  it("goes under its block, which links to the block's page, however the rows are grouped", () => {
    expect(groupOf(implicit)).toEqual({ key: block.key, program: block });
    expect(groupOf(implicit, "program")).toEqual({ key: block.key, program: block });
    expect(enclosingRoutine(implicit)).toBeNull();
    expect(shownStatement(implicit)).toBeNull();
  });

  it("still has a group without a block, by its file", () => {
    expect(groupOf({ ...implicit, container: null })).toEqual({ key: "file:forms/data_desk_fmb.xml", program: null });
  });
});
