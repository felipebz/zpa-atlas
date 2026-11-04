import {describe, expect, it} from "vitest";
import type {ColumnPlace, EntitySummary, PlaceGroupsPage} from "../src/api";
import type {DetailLine} from "../src/places-view";
import en from "../src/i18n/en";
import pt from "../src/i18n/pt";
import {sayIn} from "../src/i18n-format";
import {
    callsText,
    compareNote,
    compareUnitText,
    compiledUnitName,
    concatenatedText,
    DEFAULT_VERDICTS,
    fileName,
    functionsCode,
    functionsText,
    groupCountsText,
    howText,
    moreText,
    optionSizedText,
    parameterText,
    placeDetail,
    rowAt,
    shortName,
    placesTotal,
    reasonText,
    roomText,
    shownText,
    sizeShown,
    sizeText,
    sourceText,
    typeSourcePhrase,
    unitSourceText,
    verdictButtons,
    verdictLabel,
    verdictReasonText,
    verdictTitle,
    VERDICTS,
    viewOf
} from "../src/places-view";

const say = sayIn(en, "en");
const sayPt = sayIn(pt, "pt");

// Invented names: the column SHOP.ORDERS.CODE read into PK_ORD.LOAD's L_CODE, its subtype PK_ORD.T_CODE, the view SHOP.V_ORDERS.
function summary(display: string, kind: string): EntitySummary {
  return { key: "db:" + display, kind, display, external: false, name: display.slice(display.lastIndexOf(".") + 1) };
}

const CODE = summary("SHOP.ORDERS.CODE", "COLUMN");
const NOTE = summary("SHOP.ORDERS.NOTE", "COLUMN");
const L_CODE = summary("SHOP.PK_ORD.LOAD.L_CODE", "VARIABLE");
const T_CODE = summary("SHOP.PK_ORD.T_CODE", "SUBTYPE");
const P_CODE = summary("SHOP.PK_LV.SHOW_LV.P_CODE", "PARAMETER");
const SHOW_LV = summary("SHOP.PK_LV.SHOW_LV", "PROCEDURE");

function place(over: Partial<ColumnPlace>): ColumnPlace {
  return {
    read: CODE, viaView: false, source: "SELECT_INTO", bulk: false, path: "pk_ord.pkb", line: 12, column: 4,
    container: summary("SHOP.PK_ORD.LOAD", "PROCEDURE"), targetWritten: "l_code", target: L_CODE, field: null,
    placeClass: "FIXED_SIZE", declaredType: "varchar2(12)", length: 12, unit: "CHAR", scale: null, typeSource: L_CODE,
    parameterMode: null, reason: null, functions: [], argumentOf: null, routine: null, callCount: null, callPath: null, callLine: null, concatenated: false,
    verdict: "FITS", verdictReason: null, size: 12, sizeUnit: "CHAR", unitSource: "WRITTEN", unitCompiledIn: null, room: null, ...over
  };
}

const COLUMN = { datatype: "VARCHAR2", length: 10, unit: "BYTE" as const, scale: null };

describe("verdict buttons", () => {
  it("lists the verdicts in the page's order, FOLLOWS off by default", () => {
    expect(VERDICTS).toEqual(["SMALLER", "CALLER_DECIDES", "UNKNOWN", "FITS", "FOLLOWS", "OWN_SIZE"]);
    expect(DEFAULT_VERDICTS).toEqual(["SMALLER", "CALLER_DECIDES", "UNKNOWN", "FITS", "OWN_SIZE"]);
  });

  it("orders the counts and says each in words with the size compared", () => {
    const counts = [{ name: "FITS" as const, count: 4 }, { name: "SMALLER" as const, count: 2 }, { name: "FOLLOWS" as const, count: 0 }];
    expect(verdictButtons(say, counts, 10).map(b => [b.verdict, b.count, b.label]))
      .toEqual([["SMALLER", 2, "smaller than 10"], ["FITS", 4, "fits"], ["FOLLOWS", 0, "follows the column"]]);
  });

  it("puts a verdict it does not know last, as its code", () => {
    const counts = [{ name: "SOMETHING_NEW", count: 1 }, { name: "UNKNOWN", count: 3 }] as unknown as { name: "UNKNOWN"; count: number }[];
    expect(verdictButtons(say, counts, 10).map(b => b.label)).toEqual(["not known", "SOMETHING_NEW"]);
  });

  it("says every verdict in both languages", () => {
    expect(VERDICTS.map(v => verdictLabel(say, v, 10)))
      .toEqual(["smaller than 10", "caller decides", "not known", "fits", "follows the column", "own size"]);
    expect(VERDICTS.map(v => verdictLabel(sayPt, v, 10)))
      .toEqual(["menor que 10", "quem chama decide", "não se sabe", "cabe", "segue a coluna", "tamanho próprio"]);
    expect(verdictLabel(say, "SMALLER", null)).toBe("smaller than ?");
  });

  it("gives each verdict a tooltip: the class's meaning, or its own words for SMALLER and FITS", () => {
    expect(verdictTitle(say, "FOLLOWS", 10)).toBe(en["places.classTitle.ANCHORED_TO_COLUMN"]);
    expect(verdictTitle(say, "CALLER_DECIDES", 10)).toBe(en["places.classTitle.CALLER_DECIDES"]);
    expect(verdictTitle(say, "UNKNOWN", 10)).toBe(en["places.classTitle.UNKNOWN"]);
    expect(verdictTitle(say, "OWN_SIZE", null)).toBe(en["places.classTitle.FIXED_SIZE"]);
    expect(verdictTitle(say, "SMALLER", 10)).toBe("Declared with a size of its own smaller than 10: a longer value does not fit.");
    expect(verdictTitle(sayPt, "FITS", 10)).toBe("Recebe um valor de 10: tamanho 10 ou maior, ou sem limite de tamanho.");
  });
});

describe("sizeShown", () => {
  it("shows the size compared with a bar of how much of S it holds, full at S or more", () => {
    expect(sizeShown(say, place({ verdict: "SMALLER", size: 5, sizeUnit: "BYTE" }), 10, "BYTE")).toEqual({ text: "5", fill: 0.5 });
    expect(sizeShown(say, place({ verdict: "FITS", size: 40, sizeUnit: "BYTE" }), 10, "BYTE")).toEqual({ text: "40", fill: 1 });
  });

  it("adds the unit only when it is not the column's", () => {
    expect(sizeShown(say, place({ verdict: "FITS", size: 12, sizeUnit: "CHAR" }), 10, "BYTE").text).toBe("12 CHAR");
    expect(sizeShown(say, place({ verdict: "FITS", size: 12, sizeUnit: "CHAR" }), 10, "CHAR").text).toBe("12");
  });

  it("says %TYPE, the parameter's mode or ? for the verdicts that have no size", () => {
    expect(sizeShown(say, place({ verdict: "FOLLOWS" }), 10, "BYTE")).toEqual({ text: "%TYPE", fill: null });
    expect(sizeShown(say, place({ verdict: "CALLER_DECIDES", parameterMode: "IN OUT" }), 10, "BYTE")).toEqual({ text: "IN OUT", fill: null });
    expect(sizeShown(say, place({ verdict: "UNKNOWN" }), 10, "BYTE")).toEqual({ text: "?", fill: null });
  });

  it("shows an own size without a bar when S is not known, and a dash for a target without a size", () => {
    expect(sizeShown(say, place({ verdict: "OWN_SIZE", size: 12, sizeUnit: "BYTE" }), null, null)).toEqual({ text: "12 BYTE", fill: null });
    expect(sizeShown(say, place({ verdict: "FITS", size: null, sizeUnit: null }), 10, "BYTE")).toEqual({ text: "—", fill: null });
  });
});

describe("howText", () => {
  it("says the statement as SQL writes it, with BULK COLLECT", () => {
    expect(howText(say, place({ source: "SELECT_INTO" }))).toBe("SELECT INTO");
    expect(howText(say, place({ source: "FETCH", bulk: true }))).toBe("FETCH · BULK COLLECT");
    expect(howText(say, place({ source: "RETURNING" }))).toBe("RETURNING INTO");
    expect(howText(say, place({ source: "ASSIGNMENT" }))).toBe("assignment");
    expect(howText(sayPt, place({ source: "ASSIGNMENT" }))).toBe("atribuição");
  });

  it("names the parameter and the calls, the routine of an OUT, and the function of a result", () => {
    expect(howText(say, place({ source: "IN_ARGUMENT", argumentOf: P_CODE, routine: SHOW_LV, callCount: 3 }))).toBe("in P_CODE, from 3 calls");
    expect(howText(sayPt, place({ source: "IN_ARGUMENT", argumentOf: P_CODE, routine: SHOW_LV, callCount: 1 }))).toBe("em P_CODE, de 1 chamada");
    expect(howText(say, place({ source: "OUT_ARGUMENT", argumentOf: P_CODE, routine: SHOW_LV }))).toBe("OUT of SHOW_LV");
    expect(howText(sayPt, place({ source: "OUT_ARGUMENT", argumentOf: P_CODE, routine: SHOW_LV }))).toBe("OUT de SHOW_LV");
    expect(howText(say, place({ source: "RETURNED", routine: summary("SHOP.PK_LV.GET_CODE", "FUNCTION") }))).toBe("result of GET_CODE");
    expect(howText(sayPt, place({ source: "RETURNED", routine: summary("SHOP.PK_LV.GET_CODE", "FUNCTION") }))).toBe("resultado de GET_CODE");
  });

  it("falls back to the filter's words when the call is not known", () => {
    expect(howText(say, place({ source: "OUT_ARGUMENT" }))).toBe("argument of an OUT");
    expect(sourceText(sayPt, { source: "RETURNED", bulk: false })).toBe("resultado de função");
  });

  it("joins the functions in one short code", () => {
    expect(functionsCode({ functions: ["UPPER", "TRIM"] })).toBe("UPPER·TRIM");
    expect(functionsCode({ functions: [] })).toBeNull();
  });
});

describe("sizeText", () => {
  it("says a size with its unit", () => {
    expect(sizeText(say, { length: 12, unit: "CHAR", scale: null })).toBe("12 CHAR");
    expect(sizeText(say, { length: 20, unit: "BYTE", scale: null })).toBe("20 BYTE");
    expect(sizeText(say, { length: 10, unit: "DIGITS", scale: null })).toBe("10 digits");
    expect(sizeText(say, { length: 1, unit: "DIGITS", scale: null })).toBe("1 digit");
    expect(sizeText(sayPt, { length: 10, unit: "DIGITS", scale: null })).toBe("10 dígitos");
  });

  it("says a number's digits before the point with its scale, a negative one too", () => {
    expect(sizeText(say, { length: 8, unit: "DIGITS", scale: 2 })).toBe("8 digits before the point, scale 2");
    expect(sizeText(say, { length: 7, unit: "DIGITS", scale: -2 })).toBe("7 digits before the point, scale -2");
    expect(sizeText(sayPt, { length: 1, unit: "DIGITS", scale: 0 })).toBe("1 dígito antes da vírgula, escala 0");
  });

  it("gives a length without a unit alone, and nothing without a length", () => {
    expect(sizeText(say, { length: 12, unit: null, scale: null })).toBe("12");
    expect(sizeText(say, { length: null, unit: null, scale: null })).toBeNull();
  });
});

describe("unitSourceText", () => {
  it("names the unit whose setting gave an unwritten unit, a body as such", () => {
    const compiled = place({ unitSource: "COMPILED", unitCompiledIn: { owner: "SHOP", name: "PK_LV", type: "PACKAGE BODY" } });
    expect(unitSourceText(say, compiled)).toBe("no unit written: CHAR, as PK_LV body was compiled");
    expect(unitSourceText(sayPt, compiled)).toBe("sem unidade escrita: CHAR, como corpo de PK_LV foi compilado");
    expect(compiledUnitName(say, { name: "PK_LV", type: "PACKAGE" })).toBe("PK_LV");
    expect(compiledUnitName(sayPt, { name: "T_ORDER", type: "TYPE BODY" })).toBe("corpo de T_ORDER");
  });

  it("says the index's default, and nothing for a written unit or a size without one", () => {
    expect(unitSourceText(say, place({ unitSource: "OPTION", sizeUnit: "BYTE", unit: "BYTE" }))).toBe("no unit written: BYTE, the index's default");
    expect(unitSourceText(sayPt, place({ unitSource: "OPTION", sizeUnit: "BYTE", unit: "BYTE" }))).toBe("sem unidade escrita: BYTE, o padrão do índice");
    expect(unitSourceText(say, place({ unitSource: "WRITTEN" }))).toBeNull();
    expect(unitSourceText(say, place({ unitSource: null, unit: null, sizeUnit: null }))).toBeNull();
  });
});

describe("roomText", () => {
  it("says the room a concatenated place leaves for the rest of the text", () => {
    expect(roomText(say, { concatenated: true, room: 4 })).toBe("4 left for the rest of the text");
    expect(roomText(sayPt, { concatenated: true, room: 4 })).toBe("sobram 4 para o resto do texto");
    expect(roomText(sayPt, { concatenated: true, room: 1 })).toBe("sobra 1 para o resto do texto");
    expect(roomText(say, { concatenated: false, room: null })).toBeNull();
  });
});

describe("the section's lines", () => {
  const page = (over: Partial<PlaceGroupsPage>) => ({ column: COLUMN, size: 10, sizeGiven: false, ...over });

  it("says the size compared: the column's today, one typed with the way back, or not known", () => {
    expect(compareNote(say, page({}))).toEqual({ text: "(the column's size today)", back: null });
    expect(compareNote(say, page({ size: 20, sizeGiven: true }))).toEqual({ text: "(if the column becomes 20)", back: "back to 10" });
    expect(compareNote(sayPt, page({ size: 20, sizeGiven: true }))).toEqual({ text: "(se a coluna passar a ter 20)", back: "voltar para 10" });
    expect(compareNote(sayPt, page({ column: { ...COLUMN, length: null }, size: null }))).toEqual({ text: "o tamanho da coluna não é conhecido", back: null });
  });

  it("names the column's unit", () => {
    expect(compareUnitText(say, "BYTE")).toBe("bytes");
    expect(compareUnitText(say, "CHAR")).toBe("characters");
    expect(compareUnitText(sayPt, "DIGITS")).toBe("dígitos antes da vírgula");
    expect(compareUnitText(say, null)).toBe("");
  });

  it("counts the sizes that took the index's default unit", () => {
    expect(optionSizedText(say, { optionSized: 3, optionUnit: "BYTE" })).toBe("3 sizes have no unit written and count as BYTE, the index's default");
    expect(optionSizedText(sayPt, { optionSized: 1, optionUnit: "CHAR" })).toBe("1 tamanho sem unidade escrita conta como CHAR, o padrão do índice");
    expect(optionSizedText(say, { optionSized: 0, optionUnit: "BYTE" })).toBeNull();
  });

  it("counts the places and routines shown of those the filters leave", () => {
    expect(shownText(say, { places: 3, totalPlaces: 8, routines: 2, totalRoutines: 5 })).toBe("3 of 8 places, in 2 of 5 routines");
    expect(shownText(sayPt, { places: 3, totalPlaces: 8, routines: 2, totalRoutines: 5 })).toBe("3 de 8 lugares, em 2 de 5 rotinas");
  });

  it("counts a group's places and the smaller ones", () => {
    expect(groupCountsText(say, { smaller: 1, places: 3 })).toBe("1 smaller · 3 places");
    expect(groupCountsText(sayPt, { smaller: 2, places: 1 })).toBe("2 menores · 1 lugar");
    // None smaller: the places alone.
    expect(groupCountsText(sayPt, { smaller: 0, places: 2 })).toBe("2 lugares");
    expect(groupCountsText(say, { smaller: 0, places: 1 })).toBe("1 place");
  });

  it("says how many routines the next page shows of those left", () => {
    expect(moreText(say, 40, 15)).toBe("Show 15 more routines (40 left)");
    expect(moreText(sayPt, 4, 15)).toBe("Mostrar mais 4 rotinas (faltam 4)");
  });

  it("takes a file's name from its path", () => {
    expect(fileName("db/pkg/pk_lv.pkb")).toBe("pk_lv.pkb");
    expect(fileName("script.sql")).toBe("script.sql");
  });
});

describe("concatenatedText", () => {
  it("says the value was concatenated with other text, and nothing when it lands alone", () => {
    expect(concatenatedText(say, { concatenated: true })).toBe("concatenated with other text: the target takes more than the column's value");
    expect(concatenatedText(sayPt, { concatenated: true })).toBe("concatenado a outro texto: o destino recebe mais que o valor da coluna");
    expect(concatenatedText(say, { concatenated: false })).toBeNull();
  });
});

describe("functionsText", () => {
  it("names the functions a value goes through, outer first, and nothing for a value read directly", () => {
    expect(functionsText(say, { functions: ["MAX"] })).toBe("through MAX(…)");
    expect(functionsText(sayPt, { functions: ["MAX"] })).toBe("por MAX(…)");
    expect(functionsText(say, { functions: ["UPPER", "TRIM"] })).toBe("through UPPER(TRIM(…))");
    expect(functionsText(sayPt, { functions: ["UPPER", "TRIM"] })).toBe("por UPPER(TRIM(…))");
  });

  it("says the value may change length through REPLACE or REGEXP_REPLACE, with or without another argument", () => {
    expect(functionsText(say, { functions: ["REPLACE"] })).toBe("through REPLACE(…): the value may change length");
    expect(functionsText(sayPt, { functions: ["REPLACE"] })).toBe("por REPLACE(…): o valor pode mudar de tamanho");
    expect(functionsText(say, { functions: ["NVL", "REPLACE"] }))
      .toBe("through NVL(REPLACE(…)): another argument may be read instead, and the value may change length");
    expect(functionsText(sayPt, { functions: ["NVL", "REPLACE"] }))
      .toBe("por NVL(REPLACE(…)): pode ser lido outro argumento no lugar, e o valor pode mudar de tamanho");
    expect(functionsText(say, { functions: ["UPPER", "REGEXP_REPLACE"] })).toBe("through UPPER(REGEXP_REPLACE(…)): the value may change length");
    expect(functionsText(sayPt, { functions: ["UPPER", "REGEXP_REPLACE"] })).toBe("por UPPER(REGEXP_REPLACE(…)): o valor pode mudar de tamanho");
  });

  it("says another argument may be read when NVL, NVL2 or COALESCE is in the chain", () => {
    expect(functionsText(say, { functions: ["NVL"] })).toBe("through NVL(…): another argument may be read instead");
    expect(functionsText(sayPt, { functions: ["NVL"] })).toBe("por NVL(…): pode ser lido outro argumento no lugar");
    expect(functionsText(say, { functions: ["UPPER", "NVL"] })).toBe("through UPPER(NVL(…)): another argument may be read instead");
    expect(functionsText(sayPt, { functions: ["UPPER", "NVL"] })).toBe("por UPPER(NVL(…)): pode ser lido outro argumento no lugar");
    expect(functionsText(say, { functions: [] })).toBeNull();
  });
});

describe("parameterText", () => {
  it("leaves the size of an OUT or IN OUT parameter to its callers, and says nothing for other targets", () => {
    expect(parameterText(say, { parameterMode: "IN OUT" })).toBe("IN OUT parameter: each caller's argument decides its type and size");
    expect(parameterText(sayPt, { parameterMode: "OUT" })).toBe("parâmetro OUT: o argumento de cada chamada decide o tipo e o tamanho");
    expect(parameterText(say, { parameterMode: null })).toBeNull();
  });
});

describe("typeSourcePhrase", () => {
  it("is nothing when the target decides its own type, or nothing does", () => {
    expect(typeSourcePhrase(place({}))).toBeNull();
    expect(typeSourcePhrase(place({ placeClass: "UNKNOWN", typeSource: null, length: null, unit: null }))).toBeNull();
  });

  it("is nothing when the target is anchored to the very column read", () => {
    expect(typeSourcePhrase(place({ placeClass: "ANCHORED_TO_COLUMN", declaredType: "orders.code%type", typeSource: CODE, length: null, unit: null }))).toBeNull();
  });

  it("names the column an anchor ends at", () => {
    const phrase = typeSourcePhrase(place({ placeClass: "ANCHORED_ELSEWHERE", declaredType: "orders.note%type", typeSource: NOTE, length: null, unit: null }));
    expect(phrase).toEqual({ key: "places.typeSource.anchor", entity: NOTE });
    expect(say(phrase!.key, { source: NOTE.display })).toBe("anchored to SHOP.ORDERS.NOTE");
  });

  it("names the declaration that writes a fixed size, else the one that declares the type", () => {
    const sized = typeSourcePhrase(place({ declaredType: "t_code", typeSource: T_CODE }));
    expect(sized).toEqual({ key: "places.typeSource.size", entity: T_CODE });
    expect(say(sized!.key, { source: T_CODE.display })).toBe("size written in SHOP.PK_ORD.T_CODE");
    expect(typeSourcePhrase(place({ placeClass: "NO_SIZE", declaredType: "t_text", typeSource: T_CODE, length: null, unit: null }))?.key)
      .toBe("places.typeSource.type");
  });

  it("shows the type's source for a target of an anonymous block, which has no entity", () => {
    expect(typeSourcePhrase(place({ target: null, typeSource: T_CODE }))?.entity).toBe(T_CODE);
  });
});

describe("reasons and calls", () => {
  it("says a reason in words, an unknown one as its code", () => {
    expect(reasonText(say, "ANCHOR_UNRESOLVED")).toBe("what its %TYPE names could not be resolved");
    expect(reasonText(say, "SOME_NEW_REASON")).toBe("SOME_NEW_REASON");
  });

  it("says why a size does not compare, in both languages", () => {
    expect(verdictReasonText(say, "UNIT_MISMATCH")).toBe("the column counts characters, the target counts bytes");
    expect(verdictReasonText(sayPt, "UNIT_MISMATCH")).toBe("a coluna conta caracteres, o destino conta bytes");
    expect(verdictReasonText(say, "OTHER_COLUMN_UNKNOWN")).toBe("the other column's size is not known");
    expect(verdictReasonText(sayPt, "OTHER_COLUMN_UNKNOWN")).toBe("o tamanho da outra coluna não é conhecido");
    for (const reason of ["NO_LENGTH", "OTHER_KIND"]) {
      expect(verdictReasonText(say, reason)).not.toBe(reason);
      expect(verdictReasonText(sayPt, reason)).not.toBe(reason);
    }
  });

  it("counts the calls that pass a column to a parameter", () => {
    expect(callsText(say, { callCount: 3 })).toBe("by 3 calls, one at");
    expect(callsText(sayPt, { callCount: 1 })).toBe("por 1 chamada:");
    expect(callsText(say, { callCount: null })).toBeNull();
  });

  it("adds the places up", () => {
    expect(placesTotal([{ value: "FIXED_SIZE", count: 2 }, { value: "UNKNOWN", count: 1 }])).toBe(3);
    expect(placesTotal([])).toBe(0);
  });
});

describe("viewOf", () => {
  it("reads the view and the column of a view column", () => {
    expect(viewOf(summary("SHOP.V_ORDERS.CODE", "COLUMN"))).toEqual({ view: "SHOP.V_ORDERS", viewKey: "db:SHOP.V_ORDERS", column: "CODE" });
  });

  it("has no view key when the key does not end with the column's name", () => {
    expect(viewOf({ key: "db:SHOP.V_ORDERS#2", kind: "COLUMN", display: "SHOP.V_ORDERS.CODE", external: false, name: "CODE" }).viewKey).toBeNull();
  });
});

// Invented names: PK_MC's FILL reads ORDERS.CODE into V_CODE, passes it to ADD_MACRO's P_VALUE, and writes its result into V_TEXT.
describe("placeDetail", () => {
  const FILL = summary("SHOP.PK_MC.FILL(P_ID IN NUMBER)@BODY", "PROCEDURE");
  const ADD_MACRO = { ...summary("SHOP.PK_MC.ADD_MACRO(P_TEXT IN VARCHAR2,P_NAME IN VARCHAR2,P_VALUE IN VARCHAR2)@BODY", "FUNCTION"), name: "ADD_MACRO" };
  const P_VALUE = { ...summary("SHOP.PK_MC.ADD_MACRO(P_TEXT IN VARCHAR2,P_NAME IN VARCHAR2,P_VALUE IN VARCHAR2)@BODY.P_VALUE", "PARAMETER") };
  const LABEL = summary("SHOP.ORDERS.LABEL", "COLUMN");

  /** Each line as text: code in backquotes, a hint as [word | its tooltip], a link as its label, a file's line as path:line. */
  const text = (lines: DetailLine[]) => lines.map(line => line.parts.map(p =>
    "text" in p ? p.text : "code" in p ? "`" + p.code + "`" : "hint" in p ? `[${p.hint} | ${p.title}]` : "entity" in p ? p.label : "view" in p ? "view" : `${p.path}:${p.line}`).join(""));

  const vCode = place({ targetWritten: "v_code", container: FILL, path: "mc.sql", line: 28, declaredType: "varchar2(5)", length: 5, unit: "BYTE",
    size: 5, sizeUnit: "BYTE", unitSource: "OPTION", typeSource: null, verdict: "SMALLER" });

  it("says a written length once, and an unwritten unit as a word with where it came from as its hint", () => {
    expect(text(placeDetail(say, vCode))).toEqual(["declared `varchar2(5)` · [BYTE | no unit written: BYTE, the index's default]", "mc.sql:28"]);
    const compiled = { ...vCode, unitSource: "COMPILED" as const, unitCompiledIn: { owner: "SHOP", name: "PK_MC", type: "PACKAGE BODY" } };
    expect(text(placeDetail(say, compiled))[0]).toBe("declared `varchar2(5)` · [BYTE | no unit written: BYTE, as PK_MC body was compiled]");
    expect(text(placeDetail(sayPt, compiled))[0]).toBe("declarado `varchar2(5)` · [BYTE | sem unidade escrita: BYTE, como corpo de PK_MC foi compilado]");
    // The unit written: nothing follows.
    const c3 = place({ targetWritten: "c3", declaredType: "varchar2(10 char)", length: 10, unit: "CHAR", size: 10, sizeUnit: "CHAR", unitSource: "WRITTEN", typeSource: null });
    expect(text(placeDetail(say, c3))).toEqual(["declared `varchar2(10 char)`", "pk_ord.pkb:12"]);
  });

  it("gives the size a type name does not write, its unit with the hint when not written, and the anchor by its short name", () => {
    const c6 = place({ targetWritten: "c6", placeClass: "ANCHORED_ELSEWHERE", declaredType: "orders.label%type", length: null, unit: null,
      size: 10, sizeUnit: "BYTE", unitSource: "OPTION", typeSource: LABEL });
    expect(text(placeDetail(say, c6))).toEqual([
      "declared `orders.label%type` · 10 [BYTE | no unit written: BYTE, the index's default]", "anchored to ORDERS.LABEL", "pk_ord.pkb:12"]);
    expect(shortName(LABEL)).toBe("ORDERS.LABEL");
    expect(shortName(ADD_MACRO)).toBe("ADD_MACRO");
  });

  it("links a parameter's row to the call that passes the column, and keeps the target's line in the detail", () => {
    const value = place({ targetWritten: "g_macros(v_pos).value", container: ADD_MACRO, path: "mc.sql", line: 17, source: "IN_ARGUMENT",
      declaredType: "varchar2(5)", length: 5, unit: "BYTE", size: 5, sizeUnit: "BYTE", unitSource: "OPTION", typeSource: null,
      argumentOf: P_VALUE, routine: ADD_MACRO, callCount: 1, callPath: "mc.sql", callLine: 26 });
    expect(rowAt(say, value, "mc.sql")).toEqual({ path: "mc.sql", line: 26, text: "l. 26" });
    expect(rowAt(say, value, "other/mc_spec.sql")).toEqual({ path: "mc.sql", line: 26, text: "mc.sql, l. 26" });
    expect(text(placeDetail(say, value))).toEqual([
      "declared `varchar2(5)` · [BYTE | no unit written: BYTE, the index's default]",
      "receives P_VALUE, which 1 call passes the column to: mc.sql:26",
      "the target: mc.sql:17"]);
    expect(text(placeDetail(sayPt, value))[1]).toBe("recebe P_VALUE, a que 1 chamada passa a coluna: mc.sql:26");
    // Any other row's link is its own line.
    expect(rowAt(say, vCode, "mc.sql")).toEqual({ path: "mc.sql", line: 28, text: "l. 28" });
  });

  it("names the function a result comes from by its short name, and leaves out its call when it is on the row's line", () => {
    const vText = place({ targetWritten: "v_text", container: FILL, path: "mc.sql", line: 26, source: "RETURNED", declaredType: "varchar2(200)",
      length: 200, unit: "BYTE", size: 200, sizeUnit: "BYTE", unitSource: "OPTION", typeSource: null, functions: ["REPLACE"], concatenated: true, room: 190,
      argumentOf: null, routine: ADD_MACRO, callCount: 1, callPath: "mc.sql", callLine: 26 });
    expect(text(placeDetail(say, vText))).toEqual([
      "declared `varchar2(200)` · [BYTE | no unit written: BYTE, the index's default]",
      "through REPLACE(…): the value may change length",
      "concatenated with other text: the target takes more than the column's value; 190 left for the rest of the text",
      "returned by ADD_MACRO",
      "mc.sql:26"]);
    // A call on another line is told.
    expect(text(placeDetail(say, { ...vText, line: 30 }))).toContain("by 1 call: mc.sql:26");
  });
});
