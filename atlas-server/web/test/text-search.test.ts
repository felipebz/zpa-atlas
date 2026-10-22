import { describe, expect, it } from "vitest";
import { longEnough, matchesIn, textHref, textParamsOf } from "../src/text-search";

describe("matchesIn", () => {
  it("finds the text whatever its case", () => {
    expect(matchesIn("  v := '<fixTag>';", "FIXTAG")).toEqual([[9, 15]]);
    expect(matchesIn("-- FIXTAG here", "fixtag")).toEqual([[3, 9]]);
  });

  it("folds accented letters as the server does", () => {
    expect(matchesIn("raise_application_error(-20001, 'Taxa de PEDÁGIO inválida');", "pedágio")).toEqual([[41, 48]]);
    expect(matchesIn("-- ação", "AÇÃO")).toEqual([[3, 7]]);
  });

  it("marks every place on a line, without overlaps", () => {
    expect(matchesIn("abcabc abc", "abc")).toEqual([[0, 3], [3, 6], [7, 10]]);
    expect(matchesIn("aaaa", "aa")).toEqual([[0, 2], [2, 4]]);
  });

  it("keeps positions in the line when a letter's lower case has another length", () => {
    expect(matchesIn("İx_tag", "x_tag")).toEqual([[1, 6]]);
  });

  it("is null when the line came back but the text cannot be placed in it", () => {
    // The server found it by a folding the page does not reproduce: the line is shown unmarked.
    expect(matchesIn("STRASSE", "straße")).toBeNull();
    expect(matchesIn("anything", "")).toBeNull();
  });
});

describe("the search in the URL", () => {
  it("reads every parameter, empty ones left out", () => {
    expect(textParamsOf("?text=fixTag&path=hr/&containerName=pkg_x.run&container=&lang=pt"))
      .toEqual({ text: "fixTag", path: "hr/", containerName: "pkg_x.run" });
    expect(textParamsOf("")).toEqual({});
  });

  it("writes it back, keeping the language, so a link reopens the same search", () => {
    const params = { text: "a b&c", container: "db:HR.PKG_X", path: "hr/*.pkb" };
    const href = textHref(params, "pt");
    expect(href).toBe("text.html?text=a+b%26c&path=hr%2F*.pkb&container=db%3AHR.PKG_X&lang=pt");
    expect(textParamsOf(href.slice("text.html".length))).toEqual(params);
    expect(textHref({}, null)).toBe("text.html");
  });
});

describe("longEnough", () => {
  it("counts characters, as the server does", () => {
    expect(longEnough("ab")).toBe(false);
    expect(longEnough("abc")).toBe(true);
    expect(longEnough("a😀")).toBe(false);
  });
});
