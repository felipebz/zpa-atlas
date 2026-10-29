import {describe, expect, it} from "vitest";
import {
    displayParts,
    firstOpenParenthesis,
    listParameterDetail,
    matchingParenthesis,
    parameterNamesOnly,
    parameterParts,
    parentKeyOf,
    readAlike,
    shownNameText,
    spacedSignature,
    splitTopLevel
} from "../src/names";

// The displays come from `SELECT DISTINCT display FROM entity` on the fixture index, except where a case says it is
// written by hand: the fixture has no key collision and no quoted identifier. The expected values are what the code
// returns today.

/** A second declaration with the same written signature: `DeclarationCatalog` appends `#<count>` after the signature and `@BODY`. */
const COLLIDED = "HR.PACK_CALLS.P_MIX(P_A IN NUMBER)#2";
const COLLIDED_BODY = "HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2";

describe("displayParts", () => {
  it("a package member with a parameter list", () => {
    expect(displayParts("HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)", "RUN_JOB")).toEqual({
      qualifier: "HR.PACK_PROCESS.",
      shortQualifier: "HR.PACK_PROCESS.",
      qualifierBody: false,
      text: "RUN_JOB(P_A IN NUMBER, P_B IN VARCHAR2, P_C IN NUMBER, P_D IN DATE)",
      base: "RUN_JOB",
      params: [
        { name: "P_A", mode: "IN", type: "NUMBER" },
        { name: "P_B", mode: "IN", type: "VARCHAR2" },
        { name: "P_C", mode: "IN", type: "NUMBER" },
        { name: "P_D", mode: "IN", type: "DATE" }
      ],
      body: false
    });
  });
  it("an overload with #2 (by hand, as DeclarationCatalog keys a second declaration with the same signature)", () => {
    expect(displayParts(COLLIDED, "P_MIX")).toEqual({
      qualifier: "HR.PACK_CALLS.",
      shortQualifier: "HR.PACK_CALLS.",
      qualifierBody: false,
      text: "P_MIX(P_A IN NUMBER)#2",
      base: "P_MIX#2",
      params: [{ name: "P_A", mode: "IN", type: "NUMBER" }],
      body: false
    });
  });
  it("a body", () => {
    expect(displayParts("HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY", "CALLER")).toEqual({
      qualifier: "HR.PACK_ARGS.",
      shortQualifier: "HR.PACK_ARGS.",
      qualifierBody: false,
      text: "CALLER(P_IN IN NUMBER)",
      base: "CALLER",
      params: [{ name: "P_IN", mode: "IN", type: "NUMBER" }],
      body: true
    });
  });
  it("a body with @BODY#2 (by hand, the body of the collided overload)", () => {
    expect(displayParts(COLLIDED_BODY, "P_MIX")).toEqual({
      qualifier: "HR.PACK_CALLS.",
      shortQualifier: "HR.PACK_CALLS.",
      qualifierBody: false,
      text: "P_MIX(P_A IN NUMBER)#2",
      base: "P_MIX#2",
      params: [{ name: "P_A", mode: "IN", type: "NUMBER" }],
      body: true
    });
  });
  it("a member of a body: the qualifier says body, the short qualifier drops it and keeps parameter names", () => {
    expect(displayParts("HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY.P_IN", "P_IN")).toEqual({
      qualifier: "HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY.",
      shortQualifier: "HR.PACK_ARGS.CALLER(P_IN).",
      qualifierBody: true,
      text: "P_IN",
      base: "P_IN",
      params: null,
      body: false
    });
  });
  it("a member of the collided body (by hand): #2 stays in the short qualifier", () => {
    expect(displayParts("HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2.P_A", "P_A")).toEqual({
      qualifier: "HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2.",
      shortQualifier: "HR.PACK_CALLS.P_MIX(P_A)#2.",
      qualifierBody: true,
      text: "P_A",
      base: "P_A",
      params: null,
      body: false
    });
  });
  it("IN, OUT and IN OUT parameters", () => {
    expect(displayParts("HR.PACK_PROCESS.GET_EVENT(P_ID IN NUMBER,P_OUT OUT NUMBER,P_IO IN OUT NUMBER)", "GET_EVENT")).toEqual({
      qualifier: "HR.PACK_PROCESS.",
      shortQualifier: "HR.PACK_PROCESS.",
      qualifierBody: false,
      text: "GET_EVENT(P_ID IN NUMBER, P_OUT OUT NUMBER, P_IO IN OUT NUMBER)",
      base: "GET_EVENT",
      params: [
        { name: "P_ID", mode: "IN", type: "NUMBER" },
        { name: "P_OUT", mode: "OUT", type: "NUMBER" },
        { name: "P_IO", mode: "IN OUT", type: "NUMBER" }
      ],
      body: false
    });
  });
  it("an empty parameter list", () => {
    expect(displayParts("HR.BROKEN_PROC()", "BROKEN_PROC")).toEqual({
      qualifier: "HR.",
      shortQualifier: "HR.",
      qualifierBody: false,
      text: "BROKEN_PROC()",
      base: "BROKEN_PROC",
      params: [],
      body: false
    });
  });
  it("an empty parameter list of a body", () => {
    expect(displayParts("HR.PACK_COLLECTION.Q()@BODY", "Q")).toEqual({
      qualifier: "HR.PACK_COLLECTION.",
      shortQualifier: "HR.PACK_COLLECTION.",
      qualifierBody: false,
      text: "Q()",
      base: "Q",
      params: [],
      body: true
    });
  });
  it("a member of a remote package: the name is found at a segment boundary although the link holds dots", () => {
    expect(displayParts("DESENV.PACK_REQ@FAR.EXAMPLE.COM.CLOSE_REQ", "CLOSE_REQ")).toEqual({
      qualifier: "DESENV.PACK_REQ@FAR.EXAMPLE.COM.",
      shortQualifier: "DESENV.PACK_REQ@FAR.EXAMPLE.COM.",
      qualifierBody: false,
      text: "CLOSE_REQ",
      base: "CLOSE_REQ",
      params: null,
      body: false
    });
  });
  it("a remote package: its name holds the link", () => {
    expect(displayParts("DESENV.PACK_REQ@FAR.EXAMPLE.COM", "PACK_REQ@FAR.EXAMPLE.COM")).toEqual({
      qualifier: "DESENV.",
      shortQualifier: "DESENV.",
      qualifierBody: false,
      text: "PACK_REQ@FAR.EXAMPLE.COM",
      base: "PACK_REQ@FAR.EXAMPLE.COM",
      params: null,
      body: false
    });
  });
  it("a parameter type with a remote link and %TYPE", () => {
    expect(displayParts("HR.PACK_REMOTE.SUBJECT(P_REQ IN REMOTE_REQ.CD_REQ@FAR.EXAMPLE.COM%TYPE)", "SUBJECT")).toEqual({
      qualifier: "HR.PACK_REMOTE.",
      shortQualifier: "HR.PACK_REMOTE.",
      qualifierBody: false,
      text: "SUBJECT(P_REQ IN REMOTE_REQ.CD_REQ@FAR.EXAMPLE.COM%TYPE)",
      base: "SUBJECT",
      params: [
        {
          name: "P_REQ",
          mode: "IN",
          type: "REMOTE_REQ.CD_REQ@FAR.EXAMPLE.COM%TYPE"
        }
      ],
      body: false
    });
  });
  it("a name that also occurs inside the signature: the match at a segment boundary wins", () => {
    expect(displayParts("HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN)", "P")).toEqual({
      qualifier: "HR.PACK_ARGS.",
      shortQualifier: "HR.PACK_ARGS.",
      qualifierBody: false,
      text: "P(P_CODE IN NUMBER, P_NAME IN VARCHAR2, P_FLAG IN BOOLEAN)",
      base: "P",
      params: [
        { name: "P_CODE", mode: "IN", type: "NUMBER" },
        { name: "P_NAME", mode: "IN", type: "VARCHAR2" },
        { name: "P_FLAG", mode: "IN", type: "BOOLEAN" }
      ],
      body: false
    });
  });
  it("an identifier the indexer unquoted, holding a comma (by hand: normalizeIdentifier drops the quotes), splits at that comma", () => {
    expect(displayParts("HR.PACK_Q.P(p,a IN NUMBER,P_B IN DATE)", "P")).toEqual({
      qualifier: "HR.PACK_Q.",
      shortQualifier: "HR.PACK_Q.",
      qualifierBody: false,
      text: "P(p, a IN NUMBER, P_B IN DATE)",
      base: "P",
      params: [
        { name: "p", mode: "", type: "" },
        { name: "a", mode: "IN", type: "NUMBER" },
        { name: "P_B", mode: "IN", type: "DATE" }
      ],
      body: false
    });
  });
  it("quoted identifiers holding a comma and a parenthesis (by hand: no display has quotes today)", () => {
    expect(displayParts("HR.PACK_Q.P(\"p,a\" IN NUMBER,\"x(y\" IN DATE)", "P")).toEqual({
      qualifier: "HR.PACK_Q.",
      shortQualifier: "HR.PACK_Q.",
      qualifierBody: false,
      text: "P(\"p,a\" IN NUMBER, \"x(y\" IN DATE)",
      base: "P",
      params: [
        { name: "\"p,a\"", mode: "IN", type: "NUMBER" },
        { name: "\"x(y\"", mode: "IN", type: "DATE" }
      ],
      body: false
    });
  });
  it("a column", () => {
    expect(displayParts("HR.COMPANY.ST_ACTIVE", "ST_ACTIVE")).toEqual({
      qualifier: "HR.COMPANY.",
      shortQualifier: "HR.COMPANY.",
      qualifierBody: false,
      text: "ST_ACTIVE",
      base: "ST_ACTIVE",
      params: null,
      body: false
    });
  });
  it("without a name, the whole display is the text", () => {
    expect(displayParts("HR.COMPANY.ST_ACTIVE")).toEqual({
      qualifier: "",
      shortQualifier: "",
      qualifierBody: false,
      text: "HR.COMPANY.ST_ACTIVE",
      base: "HR.COMPANY.ST_ACTIVE",
      params: null,
      body: false
    });
  });
  it("a name not at a segment boundary is not found", () => {
    expect(displayParts("HR.XCOMPANY", "COMPANY")).toEqual({
      qualifier: "",
      shortQualifier: "",
      qualifierBody: false,
      text: "HR.XCOMPANY",
      base: "HR.XCOMPANY",
      params: null,
      body: false
    });
  });
  it("an empty name is no name", () => {
    expect(displayParts("HR.PACK_PROCESS", "")).toEqual({
      qualifier: "",
      shortQualifier: "",
      qualifierBody: false,
      text: "HR.PACK_PROCESS",
      base: "HR.PACK_PROCESS",
      params: null,
      body: false
    });
  });
});

describe("spacedSignature", () => {
  it("spaces commas inside parentheses only, not inside quotes, not twice", () => {
    expect(spacedSignature("RUN_JOB(P_A IN NUMBER,P_B IN DATE)")).toEqual("RUN_JOB(P_A IN NUMBER, P_B IN DATE)");
    expect(spacedSignature("A,B")).toEqual("A,B");
    expect(spacedSignature("P(A, B)")).toEqual("P(A, B)");
    expect(spacedSignature("P(\"a,b\" IN NUMBER,C)")).toEqual("P(\"a,b\" IN NUMBER, C)");
    expect(spacedSignature("P(A NUMBER(10,2),B)")).toEqual("P(A NUMBER(10, 2), B)");
    expect(spacedSignature("P(A,)")).toEqual("P(A, )");
  });
});

describe("parameterNamesOnly", () => {
  it("keeps only the names of every top-level parameter list; an unclosed list is dropped", () => {
    expect(parameterNamesOnly("HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN DATE).")).toEqual("HR.PACK_PROCESS.RUN_JOB(P_A, P_B).");
    expect(parameterNamesOnly("HR.BROKEN_PROC().")).toEqual("HR.BROKEN_PROC().");
    expect(parameterNamesOnly("HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2.")).toEqual("HR.PACK_CALLS.P_MIX(P_A)@BODY#2.");
    expect(parameterNamesOnly("A(X IN NUMBER).B(Y OUT DATE).")).toEqual("A(X).B(Y).");
    expect(parameterNamesOnly("P(\"a(b\" IN NUMBER,C IN DATE).")).toEqual("P(\"a(b\", C).");
    expect(parameterNamesOnly("HR.PACK.")).toEqual("HR.PACK.");
    expect(parameterNamesOnly("P(A IN NUMBER")).toEqual("P");
    expect(parameterNamesOnly("P)X(")).toEqual("P)X");
  });
});

describe("splitTopLevel", () => {
  it("splits at commas outside parentheses and quotes", () => {
    expect(splitTopLevel("A,B")).toEqual(["A", "B"]);
    expect(splitTopLevel("A,B(C,D),\"E,F\"")).toEqual(["A", "B(C,D)", "\"E,F\""]);
    expect(splitTopLevel("")).toEqual([""]);
    expect(splitTopLevel("A,,B")).toEqual(["A", "", "B"]);
    expect(splitTopLevel(",")).toEqual(["", ""]);
  });
});

describe("parameterParts", () => {
  it("reads the name, the mode (IN OUT, OUT, IN or none) and the type", () => {
    expect(parameterParts("P_A IN OUT NUMBER")).toEqual({ name: "P_A", mode: "IN OUT", type: "NUMBER" });
    expect(parameterParts("P_B OUT DATE")).toEqual({ name: "P_B", mode: "OUT", type: "DATE" });
    expect(parameterParts("P_C IN VARCHAR2")).toEqual({ name: "P_C", mode: "IN", type: "VARCHAR2" });
    expect(parameterParts("P_D NUMBER")).toEqual({ name: "P_D", mode: "", type: "NUMBER" });
    expect(parameterParts("  P_E  IN  VARCHAR2  ")).toEqual({ name: "P_E", mode: "IN", type: "VARCHAR2" });
    expect(parameterParts("\"p a\" IN DATE")).toEqual({ name: "\"p a\"", mode: "IN", type: "DATE" });
    expect(parameterParts("\"p a")).toEqual({ name: "\"p a", mode: "", type: "" });
    expect(parameterParts("P_F")).toEqual({ name: "P_F", mode: "", type: "" });
    expect(parameterParts("P_G IN")).toEqual({ name: "P_G", mode: "IN", type: "" });
    expect(parameterParts("P_H IN OUT")).toEqual({ name: "P_H", mode: "IN OUT", type: "" });
    expect(parameterParts("P_I OUT IN NUMBER")).toEqual({ name: "P_I", mode: "OUT", type: "IN NUMBER" });
    expect(parameterParts("P_J IN REMOTE_REQ.CD_REQ@FAR.EXAMPLE.COM%TYPE")).toEqual({ name: "P_J", mode: "IN", type: "REMOTE_REQ.CD_REQ@FAR.EXAMPLE.COM%TYPE" });
    expect(parameterParts("")).toEqual({ name: "", mode: "", type: "" });
  });
});

describe("firstOpenParenthesis", () => {
  it("finds the first parenthesis outside quotes, or -1", () => {
    expect(firstOpenParenthesis("RUN_JOB(A)")).toEqual(7);
    expect(firstOpenParenthesis("ST_ACTIVE")).toEqual(-1);
    expect(firstOpenParenthesis("\"a(b\"(C)")).toEqual(5);
    expect(firstOpenParenthesis("\"a(b")).toEqual(-1);
  });
});

describe("matchingParenthesis", () => {
  it("finds the closing parenthesis, skipping nested and quoted ones; the text length when there is none", () => {
    expect(matchingParenthesis("P(A)", 1)).toEqual(3);
    expect(matchingParenthesis("P(A(B)C)D", 1)).toEqual(7);
    expect(matchingParenthesis("P(\")\")X", 1)).toEqual(5);
    expect(matchingParenthesis("P(A", 1)).toEqual(3);
  });
});

describe("parentKeyOf", () => {
  it("drops a final @BODY (before #N); a member of a body keeps it", () => {
    expect(parentKeyOf("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY")).toEqual("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)");
    expect(parentKeyOf("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2")).toEqual("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)#2");
    expect(parentKeyOf("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY.P_IN")).toEqual("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)@BODY.P_IN");
    expect(parentKeyOf("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)")).toEqual("db:HR.PACK_ARGS.CALLER(P_IN IN NUMBER)");
    expect(parentKeyOf("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)#2")).toEqual("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)#2");
    expect(parentKeyOf("db:DESENV.PACK_REQ@FAR.EXAMPLE.COM")).toEqual("db:DESENV.PACK_REQ@FAR.EXAMPLE.COM");
    expect(parentKeyOf("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2.P_A")).toEqual("db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER)@BODY#2.P_A");
    expect(parentKeyOf("db:HR.X@BODYX")).toEqual("db:HR.X@BODYX");
  });
});

// Written by hand, with invented names: a routine with nine parameters, the shape that needs a cut.
const NINE = "P_MSG IN VARCHAR2,P_VALUE1 IN VARCHAR2,P_VALUE2 IN VARCHAR2,P_VALUE3 IN VARCHAR2,P_VALUE4 IN VARCHAR2," +
  "P_VALUE5 IN VARCHAR2,P_VALUE6 IN VARCHAR2,P_VALUE7 IN VARCHAR2,P_VALUE8 IN VARCHAR2";
const named = (key: string, name: string) => ({ key, display: key.replace(/^db:/, ""), name });

describe("shownNameText", () => {
  it("cuts a long parameter list after about 40 characters, with the count of the rest kept to its ellipsis", () => {
    expect(shownNameText(named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE})`, "FORMAT_MSG")))
      .toEqual("HR.PACK_TEXT.FORMAT_MSG(P_MSG, P_VALUE1, P_VALUE2, P_VALUE3, …\u00a0+5)");
  });
  it("keeps a short list whole, an empty one as (), and shows every name when asked", () => {
    expect(shownNameText(named("db:HR.PACK_TEXT.TRIM_ALL(P_A IN NUMBER,P_B IN DATE)", "TRIM_ALL"), { relative: true })).toEqual("TRIM_ALL(P_A, P_B)");
    expect(shownNameText(named("db:HR.PACK_TEXT.NOW()", "NOW"), { relative: true })).toEqual("NOW()");
    expect(shownNameText(named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE})`, "FORMAT_MSG"), { relative: true, parameters: "names" }))
      .toEqual("FORMAT_MSG(P_MSG, P_VALUE1, P_VALUE2, P_VALUE3, P_VALUE4, P_VALUE5, P_VALUE6, P_VALUE7, P_VALUE8)");
  });
  it("shows the first name even when it alone is longer than the cut", () => {
    const long = "P_A_VERY_LONG_PARAMETER_NAME_THAT_ALONE_PASSES_THE_CUT IN NUMBER,P_B IN NUMBER";
    expect(shownNameText(named(`db:HR.PACK_TEXT.LONG_ONE(${long})`, "LONG_ONE"), { relative: true }))
      .toEqual("LONG_ONE(P_A_VERY_LONG_PARAMETER_NAME_THAT_ALONE_PASSES_THE_CUT, …\u00a0+1)");
  });
  it("keeps a key collision's suffix after the list", () => {
    expect(shownNameText(named(COLLIDED, "P_MIX"))).toEqual("HR.PACK_CALLS.P_MIX(P_A)#2");
  });
  it("cuts the parameter list of a qualifier the same way", () => {
    expect(shownNameText(named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE}).P_VALUE7`, "P_VALUE7")))
      .toEqual("HR.PACK_TEXT.FORMAT_MSG(P_MSG, P_VALUE1, P_VALUE2, P_VALUE3, …\u00a0+5).P_VALUE7");
    expect(shownNameText(named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE}).P_VALUE7`, "P_VALUE7"), { parameters: "names" }))
      .toEqual(`HR.PACK_TEXT.FORMAT_MSG(P_MSG, P_VALUE1, P_VALUE2, P_VALUE3, P_VALUE4, P_VALUE5, P_VALUE6, P_VALUE7, P_VALUE8).P_VALUE7`);
  });
});

describe("readAlike", () => {
  it("marks two overloads whose short names read the same, not a spec with its body nor a different overload", () => {
    const first = named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE})`, "FORMAT_MSG");
    const firstBody = named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE})@BODY`, "FORMAT_MSG");
    const second = named(`db:HR.PACK_TEXT.FORMAT_MSG(${NINE.replace("P_VALUE8 IN VARCHAR2", "P_EXTRA IN NUMBER")})`, "FORMAT_MSG");
    const other = named("db:HR.PACK_TEXT.FORMAT_MSG(P_MSG IN VARCHAR2)", "FORMAT_MSG");
    expect([...readAlike([first, firstBody, second, other])]).toEqual([first.key, firstBody.key, second.key]);
    expect([...readAlike([first, firstBody, other])]).toEqual([]);
  });
});

describe("listParameterDetail", () => {
  const byText = named("db:HR.PACK_LOG.WRITE_LINE(P_MSG IN VARCHAR2,P_VALUE IN VARCHAR2)", "WRITE_LINE");
  const byTextBody = named("db:HR.PACK_LOG.WRITE_LINE(P_MSG IN VARCHAR2,P_VALUE IN VARCHAR2)@BODY", "WRITE_LINE");
  const byFlag = named("db:HR.PACK_LOG.WRITE_LINE(P_MSG IN VARCHAR2,P_VALUE IN BOOLEAN)", "WRITE_LINE");
  const byCode = named("db:HR.PACK_LOG.WRITE_LINE(P_MSG IN VARCHAR2,P_CODE IN OUT NUMBER)", "WRITE_LINE");

  it("gives overloads that differ only by type their types, without the mode, and keeps one that differs by names short", () => {
    const list = [byText, byTextBody, byFlag, byCode];
    const detail = listParameterDetail(list, { relative: true });
    expect(list.map(entity => shownNameText(entity, { relative: true, parameters: detail(entity) }))).toEqual([
      "WRITE_LINE(P_MSG VARCHAR2, P_VALUE VARCHAR2)",
      "WRITE_LINE(P_MSG VARCHAR2, P_VALUE VARCHAR2)",
      "WRITE_LINE(P_MSG VARCHAR2, P_VALUE BOOLEAN)",
      "WRITE_LINE(P_MSG, P_CODE)"
    ]);
  });

  it("does not mark a spec with its body", () => {
    const detail = listParameterDetail([byText, byTextBody, byCode]);
    expect([byText, byTextBody, byCode].map(detail)).toEqual(["short", "short", "short"]);
  });
});
