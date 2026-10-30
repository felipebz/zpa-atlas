import {describe, expect, it} from "vitest";
import type {EntitySummary, FileObject, FileOccurrence} from "../src/api";
import {currentEntry, outlineEntries, segmentClass, segmentsByLine} from "../src/file-view";

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
