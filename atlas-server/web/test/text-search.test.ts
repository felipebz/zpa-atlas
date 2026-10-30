import {describe, expect, it} from "vitest";
import type {EntitySummary} from "../src/api";
import {longEnough, matchesIn, placeRows, shownLine, textHref, textParamsOf} from "../src/text-search";

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

describe("shownLine", () => {
  const KEYWORD = 0, LITERAL = 1;
  /** The pieces as `text[color][mark]`, to read them at a glance. */
  const read = (line: string, text: string, colors: number[][] | null = null) => {
    const shown = shownLine(line, matchesIn(line, text), colors);
    return { text: shown.text, pieces: shown.pieces.map(p => `${shown.text.slice(p.from, p.to)}${p.color === null ? "" : `[${p.color}]`}${p.mark ? "!" : ""}`) };
  };

  it("leaves out the leading whitespace and keeps the marks on the matched text", () => {
    expect(read("    v := dbms_lob.getlength(l_blob);", "dbms_lob.")).toEqual({
      text: "v := dbms_lob.getlength(l_blob);", pieces: ["v := ", "dbms_lob.!", "getlength(l_blob);"]
    });
    expect(read("\t\tl_x := 1;", "l_x").text).toBe("l_x := 1;");
  });

  it("starts a match that begins in the indentation's last character at the shown text's first", () => {
    expect(read("    foo(1);", " foo")).toEqual({ text: "foo(1);", pieces: ["foo!", "(1);"] });
  });

  it("colors the shown text with the line's colors, the match over them", () => {
    // `  return 'it''s' || x;`: return is a keyword, the string a literal, the match covers both.
    const line = "  return 'it''s' || x;";
    expect(read(line, "return 'it", [[2, 8, KEYWORD], [9, 16, LITERAL]]).pieces).toEqual(["return[0]!", " !", "'it[1]!", "''s'[1]", " || x;"]);
  });

  it("shows a line with no match or color as one piece, and an empty line as none", () => {
    expect(read("  x := 1;", "zzz").pieces).toEqual(["x := 1;"]);
    expect(read("    ", "zzz").pieces).toEqual([]);
  });
});

describe("placeRows", () => {
  const entity = (key: string): EntitySummary => ({ key, kind: "PROCEDURE", display: key.slice(3), external: false, name: key });
  const fmt = entity("db:APP.TIME_PKG.FMT()"), parse = entity("db:APP.TIME_PKG.PARSE()");
  const row = (path: string, container: EntitySummary | null) => ({ path, entity: container });
  const show = (places: ReturnType<typeof placeRows>) => places.map(p => `${p.newGroup ? "new " : ""}${p.container?.key ?? "-"}`);

  it("shows a container once for each run of rows in it, again when the run is broken", () => {
    const shown = new Map<string, string | null>();
    expect(show(placeRows([row("a.pkb", fmt), row("a.pkb", fmt), row("a.pkb", parse), row("a.pkb", fmt), row("b.sql", null), row("b.sql", null)], shown)))
      .toEqual([`new ${fmt.key}`, "-", parse.key, fmt.key, "new -", "-"]);
  });

  it("keeps one header for a file whose rows continue on the next page, and the container of its run", () => {
    const shown = new Map<string, string | null>();
    expect(show(placeRows([row("a.pkb", fmt), row("b.pkb", parse)], shown))).toEqual([`new ${fmt.key}`, `new ${parse.key}`]);
    expect(show(placeRows([row("b.pkb", parse), row("b.pkb", fmt), row("c.pkb", null)], shown))).toEqual(["-", fmt.key, "new -"]);
  });
});
