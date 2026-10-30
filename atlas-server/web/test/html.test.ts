import {describe, expect, it} from "vitest";
import {esc, getOrPut, literalText, qualifierHtml, scrollTable, stackCell} from "../src/html";

// The expected values are what the code returns today.

describe("esc", () => {
  it("escapes the five HTML characters and shows null and undefined as nothing", () => {
    expect(esc(`<a href="x">Tom & 'Jerry'</a>`)).toBe("&lt;a href=&quot;x&quot;&gt;Tom &amp; &#39;Jerry&#39;&lt;/a&gt;");
    expect(esc(null)).toBe("");
    expect(esc(undefined)).toBe("");
    expect(esc(12)).toBe("12");
    expect(esc("")).toBe("");
  });
});

describe("getOrPut", () => {
  it("creates the value once and returns the stored one after", () => {
    const map = new Map<string, number[]>();
    let calls = 0;
    const first = getOrPut(map, "k", () => { calls++; return [1]; });
    const second = getOrPut(map, "k", () => { calls++; return [2]; });
    expect(first).toEqual([1]);
    expect(second).toBe(first);
    expect(calls).toBe(1);
    expect(map.size).toBe(1);
  });
});

describe("qualifierHtml", () => {
  it("escapes the qualifier and lets it break after each dot, a remote link's dots included", () => {
    expect(qualifierHtml("HR.PACK_PROCESS.")).toBe("HR.<wbr>PACK_PROCESS.<wbr>");
    expect(qualifierHtml("DESENV.PACK_REQ@FAR.EXAMPLE.COM.")).toBe("DESENV.<wbr>PACK_REQ@FAR.<wbr>EXAMPLE.<wbr>COM.<wbr>");
    expect(qualifierHtml("A<B>.")).toBe("A&lt;B&gt;.<wbr>");
    expect(qualifierHtml("")).toBe("");
  });
});

describe("scrollTable", () => {
  it("wraps a table in its own scrolling container, with a class when given", () => {
    expect(scrollTable("<tr></tr>", "uses")).toBe("<div class=\"scroll\"><table class=\"uses\"><tr></tr></table></div>");
    expect(scrollTable("<tr></tr>")).toBe("<div class=\"scroll\"><table><tr></tr></table></div>");
  });
});

describe("literalText", () => {
  it("writes strings quoted, numbers, booleans, NULL and DEFAULT as stored, and nothing for the other kinds", () => {
    expect(literalText("STRING", "it's")).toBe("'it''s'");
    expect(literalText("STRING", "")).toBe("''");
    expect(literalText("NUMBER", "1.5")).toBe("1.5");
    expect(literalText("BOOLEAN", "TRUE")).toBe("TRUE");
    expect(literalText("NULL", null)).toBe("NULL");
    expect(literalText("DEFAULT", null)).toBe("DEFAULT");
    expect(literalText("NAME", "SYSDATE")).toBe(null);
    expect(literalText("BIND", ":B1")).toBe(null);
    expect(literalText("EXPRESSION", null)).toBe(null);
    expect(literalText(null, null)).toBe(null);
  });
});

describe("stackCell", () => {
  it("carries the label a stacked value shows before it, escaped, and none when the value says what it is", () => {
    expect(stackCell("3", "depth", "num")).toBe(`<td class="num" data-label="depth">3</td>`);
    expect(stackCell("12", `"sites" & more`)).toBe(`<td data-label="&quot;sites&quot; &amp; more">12</td>`);
    expect(stackCell(`<span class="badge">string</span>`)).toBe(`<td><span class="badge">string</span></td>`);
  });
});
