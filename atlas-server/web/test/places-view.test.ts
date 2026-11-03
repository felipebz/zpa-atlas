import {describe, expect, it} from "vitest";
import type {ColumnPlace, EntitySummary} from "../src/api";
import en from "../src/i18n/en";
import pt from "../src/i18n/pt";
import {sayIn} from "../src/i18n-format";
import {
    callsText,
    classCounts,
    classLabel,
    classSummary,
    concatenatedText,
    functionsText,
    needsAttention,
    parameterText,
    PLACE_CLASSES,
    placesTotal,
    reasonText,
    sizeNote,
    sizeText,
    sourceText,
    typeSourcePhrase,
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

function place(over: Partial<ColumnPlace>): ColumnPlace {
  return {
    read: CODE, viaView: false, source: "SELECT_INTO", bulk: false, path: "pk_ord.pkb", line: 12, column: 4,
    container: summary("SHOP.PK_ORD.LOAD", "PROCEDURE"), targetWritten: "l_code", target: L_CODE, field: null,
    placeClass: "FIXED_SIZE", declaredType: "varchar2(12)", length: 12, unit: "CHAR", scale: null, typeSource: L_CODE,
    parameterMode: null, reason: null, functions: [], argumentOf: null, routine: null, callCount: null, callPath: null, callLine: null, concatenated: false, ...over
  };
}

describe("sizeText", () => {
  it("says a written size with its unit", () => {
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

  it("gives the length alone when no unit is written, and nothing without a length", () => {
    expect(sizeText(say, { length: 12, unit: "NLS_LENGTH_SEMANTICS", scale: null })).toBe("12");
    expect(sizeText(say, { length: 12, unit: null, scale: null })).toBe("12");
    expect(sizeText(say, { length: null, unit: null, scale: null })).toBeNull();
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

describe("sizeNote", () => {
  it("explains a size without a written unit, and only that", () => {
    expect(sizeNote(say, { length: 12, unit: "NLS_LENGTH_SEMANTICS" })).toBe("unit not written: NLS_LENGTH_SEMANTICS decides");
    expect(sizeNote(sayPt, { length: 12, unit: "NLS_LENGTH_SEMANTICS" })).toBe("unidade não escrita: NLS_LENGTH_SEMANTICS decide");
    expect(sizeNote(say, { length: 12, unit: "CHAR" })).toBeNull();
    expect(sizeNote(say, { length: null, unit: "NLS_LENGTH_SEMANTICS" })).toBeNull();
  });
});

describe("needsAttention", () => {
  it("marks a fixed size, an unknown declaration and one its callers decide, not the others", () => {
    expect(PLACE_CLASSES.filter(needsAttention)).toEqual(["FIXED_SIZE", "UNKNOWN", "CALLER_DECIDES"]);
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

describe("classCounts and classSummary", () => {
  const byClass = [
    { value: "ANCHORED_TO_COLUMN", count: 5 }, { value: "UNKNOWN", count: 1 }, { value: "FIXED_SIZE", count: 2 }, { value: "NO_SIZE", count: 0 }
  ];

  it("says the counts in the page's order, leaving out an empty class", () => {
    expect(classSummary(say, byClass)).toBe("2 fixed size, 1 unknown, 5 anchored to the column");
    expect(classSummary(sayPt, byClass)).toBe("2 tamanho fixo, 1 desconhecido, 5 ancorado na coluna");
    expect(classCounts(say, byClass).map(c => c.value)).toEqual(["FIXED_SIZE", "UNKNOWN", "ANCHORED_TO_COLUMN"]);
  });

  it("puts a class it does not know last, as its code", () => {
    expect(classSummary(say, [{ value: "SOMETHING_NEW", count: 3 }, { value: "NO_SIZE", count: 1 }])).toBe("1 no size, 3 SOMETHING_NEW");
  });

  it("adds the counts up", () => {
    expect(placesTotal(byClass)).toBe(8);
    expect(placesTotal([])).toBe(0);
  });
});

describe("labels", () => {
  it("says every class in words in both languages", () => {
    for (const placeClass of PLACE_CLASSES) {
      expect(classLabel(say, placeClass)).not.toBe(placeClass);
      expect(classLabel(sayPt, placeClass)).not.toBe(placeClass);
    }
  });

  it("says a reason in words, an unknown one as its code", () => {
    expect(reasonText(say, "ANCHOR_UNRESOLVED")).toBe("what its %TYPE names could not be resolved");
    expect(reasonText(say, "SOME_NEW_REASON")).toBe("SOME_NEW_REASON");
  });

  it("says how the value is read, with BULK COLLECT", () => {
    expect(sourceText(say, { source: "SELECT_INTO", bulk: false })).toBe("SELECT INTO");
    expect(sourceText(say, { source: "FETCH", bulk: true })).toBe("FETCH INTO · BULK COLLECT");
    expect(sourceText(say, { source: "RETURNING", bulk: false })).toBe("RETURNING INTO");
    expect(sourceText(say, { source: "ASSIGNMENT", bulk: false })).toBe("assignment (:=)");
    expect(sourceText(say, { source: "OUT_ARGUMENT", bulk: false })).toBe("argument of an OUT parameter");
    expect(sourceText(say, { source: "IN_ARGUMENT", bulk: false })).toBe("value passed to a parameter");
    expect(sourceText(say, { source: "RETURNED", bulk: false })).toBe("value returned by a function");
    expect(sourceText(sayPt, { source: "RETURNED", bulk: false })).toBe("valor devolvido por uma função");
  });

  it("counts the calls that pass a column to a parameter", () => {
    expect(callsText(say, { callCount: 3 })).toBe("by 3 calls, one at");
    expect(callsText(sayPt, { callCount: 1 })).toBe("por 1 chamada:");
    expect(callsText(say, { callCount: null })).toBeNull();
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
