import {describe, expect, it} from "vitest";
import type {EntitySummary, FileObject, FileOccurrence} from "../src/api";
import {
    currentEntry,
    innermost,
    occurrencesOf,
    outlineEntries,
    routineMemberName,
    segmentClass,
    segmentsByLine
} from "../src/file-view";

const entity = (key: string, kind: string, display: string, name: string): EntitySummary => ({ key, kind, display, external: false, name });
const occurrence = (at: number[], status = "RESOLVED", entityIndex: number | null = 0): FileOccurrence =>
  ({ at, roles: ["READ"], status, reason: null, entity: entityIndex, candidates: [] });

const KEYWORD = 0, LITERAL = 1, COMMENT = 2;

describe("segmentsByLine", () => {
  const lines = ["select type, 'a' from ledger -- note", "/* two", "lines */ x"];

  it("splits a line at every name and color edge; a span over several lines is cut per line", () => {
    const colors = [[1, 0, 1, 6, KEYWORD], [1, 7, 1, 11, KEYWORD], [1, 13, 1, 16, LITERAL], [1, 31, 1, 38, COMMENT], [2, 0, 3, 8, COMMENT]];
    const segments = segmentsByLine(lines, [occurrence([1, 7, 1, 11]), occurrence([1, 22, 1, 28])], colors);
    expect(segments.get(1)).toEqual([
      { from: 0, to: 6, occurrences: [], color: KEYWORD },
      // A keyword word the index links: both the color and the name.
      { from: 7, to: 11, occurrences: [0], color: KEYWORD },
      { from: 13, to: 16, occurrences: [], color: LITERAL },
      { from: 22, to: 28, occurrences: [1], color: null },
      { from: 31, to: 38, occurrences: [], color: COMMENT }
    ]);
    expect(segments.get(2)).toEqual([{ from: 0, to: 6, occurrences: [], color: COMMENT }]);
    expect(segments.get(3)).toEqual([{ from: 0, to: 8, occurrences: [], color: COMMENT }]);
  });

  it("reads the names alone when there are no colors", () => {
    expect(segmentsByLine(lines, [occurrence([1, 7, 1, 11])], null).get(1)).toEqual([{ from: 7, to: 11, occurrences: [0], color: null }]);
  });
});

describe("segmentClass", () => {
  it("puts the link classes after the color, so a linked keyword word is a link", () => {
    const occurrences = [occurrence([1, 7, 1, 11]), occurrence([1, 7, 1, 11], "AMBIGUOUS", null)];
    expect(segmentClass({ from: 7, to: 11, occurrences: [0], color: KEYWORD }, occurrences)).toBe("tok-keyword occ link");
    expect(segmentClass({ from: 0, to: 6, occurrences: [], color: KEYWORD }, occurrences)).toBe("tok-keyword");
    expect(segmentClass({ from: 7, to: 11, occurrences: [1], color: null }, occurrences)).toBe("occ none");
  });
});

describe("outlineEntries", () => {
  const ledger = entity("db:APP.LEDGER_PKG", "PACKAGE", "APP.LEDGER_PKG", "LEDGER_PKG");
  const post1 = entity("db:APP.LEDGER_PKG.POST(P_ID IN NUMBER)", "PROCEDURE", "APP.LEDGER_PKG.POST(P_ID IN NUMBER)", "POST");
  const post2 = entity("db:APP.LEDGER_PKG.POST(P_CODE IN VARCHAR2)", "PROCEDURE", "APP.LEDGER_PKG.POST(P_CODE IN VARCHAR2)", "POST");
  const rate = entity("db:APP.LEDGER_PKG.G_RATE", "PACKAGE_CONSTANT", "APP.LEDGER_PKG.G_RATE", "G_RATE");
  const settle = entity("db:APP.LEDGER_PKG.SETTLE()@BODY", "FUNCTION", "APP.LEDGER_PKG.SETTLE()@BODY", "SETTLE");
  const spec: FileObject = { unitKind: "PACKAGE", entity: ledger, line: 1, members: [{ entity: rate, line: 2 }, { entity: post1, line: 4 }, { entity: post2, line: 5 }] };
  const body: FileObject = { unitKind: "PACKAGE_BODY", entity: ledger, line: 9, members: [{ entity: settle, line: 10 }] };
  const point = entity("db:APP.TY_POINT", "TYPE", "APP.TY_POINT", "TY_POINT");

  it("lists each object and then its members in file order, names short, overloads told apart", () => {
    const entries = outlineEntries([spec, body, { unitKind: "TYPE", entity: point, line: 14, members: [] }]);
    expect(entries.map(e => `${"  ".repeat(e.depth)}${e.name}${e.body ? " (body)" : ""} ${e.line}`)).toEqual([
      "LEDGER_PKG 1", "  G_RATE 2", "  POST(P_ID) 4", "  POST(P_CODE) 5", "LEDGER_PKG (body) 9", "  SETTLE() 10", "TY_POINT 14"
    ]);
  });

  it("marks the entry the reader is in: the last one at or above the line", () => {
    const entries = outlineEntries([spec, body]);
    expect(currentEntry(entries, 0)).toBe(-1);
    expect(entries[currentEntry(entries, 4)].name).toBe("POST(P_ID)");
    expect(entries[currentEntry(entries, 8)].name).toBe("POST(P_CODE)");
    expect(entries[currentEntry(entries, 30)].name).toBe("SETTLE()");
  });
});

describe("routineMemberName", () => {
  const entity = (kind: string, key: string): EntitySummary => ({ key, kind, display: key.slice(3), external: false, name: key.slice(key.lastIndexOf(".") + 1) });
  it("names a routine's parameter, local or local type's field without the routine's path", () => {
    expect(routineMemberName(entity("LOCAL_VARIABLE", "db:SHOP.PK_LEDGER.POST(P_ID IN NUMBER)@BODY.L_PAIR"))).toBe("L_PAIR");
    expect(routineMemberName(entity("PARAMETER", "db:SHOP.PK_LEDGER.POST(P_ID IN NUMBER).P_ID"))).toBe("P_ID");
    expect(routineMemberName(entity("CURSOR", "db:SHOP.SHOW_CODE().C_OPEN"))).toBe("C_OPEN");
    expect(routineMemberName(entity("RECORD_FIELD", "db:SHOP.PK_LEDGER.POST(P_ID IN NUMBER)@BODY.R_PAIR.A"))).toBe("R_PAIR.A");
  });

  it("leaves a package's or schema's object to its qualified name", () => {
    expect(routineMemberName(entity("CURSOR", "db:SHOP.PK_LEDGER.C_OPEN"))).toBeNull();
    expect(routineMemberName(entity("PACKAGE_VARIABLE", "db:SHOP.PK_LEDGER.G_CODE"))).toBeNull();
    expect(routineMemberName(entity("RECORD_FIELD", "db:SHOP.PK_LEDGER.R_PAIR.A"))).toBeNull();
    expect(routineMemberName(entity("PROCEDURE", "db:SHOP.PK_LEDGER.POST(P_ID IN NUMBER)"))).toBeNull();
  });
});

describe("innermost", () => {
  const occ = (at: number[], entity: number | null): FileOccurrence => ({ at, roles: [], status: "RESOLVED", reason: null, entity, candidates: [] });
  it("is the occurrence of the name under the click: the smallest span that has an entity", () => {
    // `l_pair.a`: the record over the whole name, the field over `a`, and an unresolved one over `a`.
    const occurrences = [occ([6, 4, 6, 12], 0), occ([6, 11, 6, 12], 1), occ([6, 11, 6, 12], null)];
    expect(innermost(occurrences, [0, 1, 2])).toBe(1);
    expect(innermost(occurrences, [0])).toBe(0);
    expect(innermost(occurrences, [2])).toBeNull();
  });
});

describe("occurrencesOf", () => {
  it("lists an entity's occurrences in reading order, and no other's", () => {
    const occ = (entity: number | null, line: number, col: number): FileOccurrence =>
      ({ at: [line, col, line, col + 3], roles: [], status: "RESOLVED", reason: null, entity, candidates: [] });
    expect(occurrencesOf([occ(0, 9, 2), occ(1, 3, 0), occ(0, 3, 8), occ(0, 3, 2), occ(null, 1, 0)], 0)).toEqual([3, 2, 0]);
  });
});
