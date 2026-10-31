import {describe, expect, it} from "vitest";
import type {Documentation} from "../src/api";
import {docSummary} from "../src/doc-view";

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
