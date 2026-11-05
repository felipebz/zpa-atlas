import {describe, expect, it, vi} from "vitest";
import type {Documentation} from "../src/api";
import {docPlace, docSummary} from "../src/doc-view";
import {lineHref, lineTitle} from "../src/html";

// Invented names: a parameter of a ledger routine.
function doc(text: string, over: Partial<Documentation> = {}): Documentation {
  return { text, textCut: false, shape: "END_OF_LINE", path: "ledger/pack_ledger.pks", line: 4, from: null, ...over };
}

describe("docSummary", () => {
  it("is nothing without documentation, else its first line with nothing more for a one-line comment", () => {
    expect(docSummary([])).toBeNull();
    expect(docSummary([doc("the account to post to")])).toEqual({ text: "the account to post to", more: false, from: null });
  });

  it("says there is more for a second line, a cut text or the other half's comment, and keeps the other half's mark", () => {
    expect(docSummary([doc("\nThe account.\n  Never closed.", { shape: "ABOVE" })])).toEqual({ text: "The account.", more: true, from: null });
    expect(docSummary([doc("The account.\n…", { textCut: true })])?.more).toBe(true);
    expect(docSummary([doc("in the spec", { from: "SPEC" })])).toEqual({ text: "in the spec", more: false, from: "SPEC" });
    expect(docSummary([doc("in the body"), doc("in the spec", { from: "SPEC" })])?.more).toBe(true);
  });
});

describe("docPlace", () => {
  vi.stubGlobal("location", { search: "" });

  it("says a database comment's line in its file, and links the file there", () => {
    const own = doc("the account to post to");
    expect(docPlace(own)).toEqual({ key: "doc.place.endOfLine", line: 4 });
    expect(docPlace(doc("x", { shape: "ABOVE" })).key).toBe("doc.place.above");
    expect(lineHref(own.path, own.line, own.code)).toBe("file.html?path=ledger%2Fpack_ledger.pks&line=4#L4");
  });

  it("says a comment in Forms code at its unit's own line, and leads to the code page there", () => {
    // Doag corpus names: a comment above a routine of PK_ACCORDION's body, line 6 of its own text, 640 of the module's.
    const code = { key: "forms:DEMO0004/PK_ACCORDION", line: 6, unitKind: "PACKAGE_BODY" };
    const above = doc("Closes the other panels.", { shape: "ABOVE", path: "demo0004_fmb.xml", line: 640, code });
    expect(docPlace(above)).toEqual({ key: "doc.place.above", line: 6 });
    expect(lineHref(above.path, above.line, above.code)).toBe(
      "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY&line=6#L6");
    expect(lineTitle(above.path, above.line, above.code)).toBe("demo0004_fmb.xml, PK_ACCORDION:6");
  });
});
