import {describe, expect, it, vi} from "vitest";
import {
    codeBreaksHtml,
    codeHref,
    fileHref,
    esc,
    fillMarks,
    getOrPut,
    lineHref,
    lineLink,
    literalText,
    nameBreaksHtml,
    qualifierHtml,
    scrollTable,
    stackCell
} from "../src/html";

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

describe("fillMarks", () => {
  it("puts each piece in its mark as written, a dollar sign in it included", () => {
    expect(fillMarks("Initial value \u00010\u0001 (\u00011\u0001)", ["<code>'^a+$&#39;</code>", "$' $& $1"]))
      .toBe("Initial value <code>'^a+$&#39;</code> ($' $& $1)");
  });
});

describe("nameBreaksHtml and codeBreaksHtml", () => {
  it("lets a name break only after an underscore or a dot, never inside a word", () => {
    expect(nameBreaksHtml("P_LINE_HEIGHT")).toBe("P_<wbr>LINE_<wbr>HEIGHT");
    expect(nameBreaksHtml("V$LOCK")).toBe("V$LOCK");
    expect(nameBreaksHtml("PK_ACCORDION.INIT")).toBe("PK_<wbr>ACCORDION.<wbr>INIT");
  });

  it("lets code break after a comma, a dot, a percent sign or an operator run followed by more code, never inside a word, and escapes it", () => {
    expect(codeBreaksHtml("trunc(sysdate)")).toBe("trunc(sysdate)");
    expect(codeBreaksHtml("'ab'||'<c>'")).toBe("&#39;ab&#39;||<wbr>&#39;&lt;c&gt;&#39;");
    expect(codeBreaksHtml("nvl(a,b) + 1")).toBe("nvl(a,<wbr>b) + 1");
    expect(codeBreaksHtml("pack_ledger.gc_rate%type")).toBe("pack_ledger.<wbr>gc_rate%<wbr>type");
  });

  it("keeps a number whole, and a minus that starts a number with it; a minus between two values is an operator", () => {
    expect(codeBreaksHtml("1.5")).toBe("1.5");
    expect(codeBreaksHtml("-1")).toBe(`<span class="nobr">-1</span>`);
    expect(codeBreaksHtml("nvl(p_x,-2.5)")).toBe(`nvl(p_x,<wbr><span class="nobr">-2.5</span>)`);
    expect(codeBreaksHtml("g_limit-1")).toBe("g_limit-<wbr>1");
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

describe("line links", () => {
  vi.stubGlobal("location", { search: "" });
  // A line of the trigger WHEN-BUTTON-PRESSED of the item PB_EXIT, at line 412 of the module's text, its line 3.
  const code = { key: "forms:DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED", line: 3, unitKind: null };
  const pressed = "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FSTEUERBLOCK%2FPB_EXIT%2FWHEN-BUTTON-PRESSED&line=3#L3";

  it("leads to the code page of a Forms module: the unit, a package's half, the line marked", () => {
    expect(codeHref("demo0004_fmb.xml", code, 3)).toBe(pressed);
    expect(codeHref("forms/demo0004_fmb.xml", { key: "forms:DEMO0004/PK_ACCORDION", unitKind: "PACKAGE_BODY" }, 78))
      .toBe("file.html?path=forms%2Fdemo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY&line=78#L78");
    // A node chosen in the tree: its unit, no line marked.
    expect(codeHref("demo0004_fmb.xml", { key: "forms:DEMO0004/PK_ACCORDION", unitKind: "PACKAGE" }, null))
      .toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE");
    // Without a unit, the page opens the module's first.
    expect(codeHref("demo0004_fmb.xml", null, null)).toBe("file.html?path=demo0004_fmb.xml");
  });

  it("keeps the language", () => {
    vi.stubGlobal("location", { search: "?path=x&lang=pt" });
    expect(codeHref("demo0004_fmb.xml", code, 3)).toBe(pressed.replace("#L3", "&lang=pt#L3"));
    vi.stubGlobal("location", { search: "" });
  });

  it("leads a line of Forms code (a Used by row, a search row) to its unit at its own line, any other line to its file", () => {
    expect(lineHref("pkg/pack_orders.pkb", 59)).toBe("file.html?path=pkg%2Fpack_orders.pkb&line=59#L59");
    expect(lineHref("demo0004_fmb.xml", 412, code)).toBe(pressed);
    expect(lineLink("demo0004_fmb.xml", 412, "line 3", code)).toBe(`<a class="mono" href="${pressed}" title="line 3">3</a>`);
    expect(lineLink("pkg/pack_orders.pkb", 59, "line 59", null)).toBe(`<a class="mono" href="file.html?path=pkg%2Fpack_orders.pkb&line=59#L59" title="line 59">59</a>`);
  });

  it("leads a file link of a row in Forms code (a Used by group's file, a search group's header) to the row's unit", () => {
    expect(fileHref("demo0004_fmb.xml", code)).toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FSTEUERBLOCK%2FPB_EXIT%2FWHEN-BUTTON-PRESSED");
    expect(fileHref("pkg/pack_orders.pkb", null)).toBe("file.html?path=pkg%2Fpack_orders.pkb");
    expect(fileHref("pkg/pack_orders.pkb")).toBe("file.html?path=pkg%2Fpack_orders.pkb");
  });
});
