import {describe, expect, it} from "vitest";
import type {Declared, Member} from "../src/api";
import {declaredFacts, memberType, parameterDefaults, returnType} from "../src/declared-view";

// Invented names: a package PACK_LEDGER with a routine POST_ENTRY.
function declared(over: Partial<Declared>): Declared {
  return { type: null, value: null, valueCut: false, valueFrom: null, specText: null, ...over };
}

function member(name: string, kind: string, d: Declared | null): Member {
  const key = `db:HR.PACK_LEDGER.POST_ENTRY(P_ID IN NUMBER).${name}`;
  return { entity: { key, kind, display: key.slice(3), external: false, name }, usages: 0, declared: d, documentation: [] };
}

describe("returnType", () => {
  it("is a function's written return type, and nothing for other kinds", () => {
    expect(returnType("FUNCTION", declared({ type: "t_lines%rowtype" }))).toBe("t_lines%rowtype");
    expect(returnType("FUNCTION", null)).toBeNull();
    expect(returnType("CURSOR", declared({ type: "r_line" }))).toBeNull();
  });
});

describe("declaredFacts", () => {
  it("says a variable's type and initial value, a parameter's default, a cursor's return type", () => {
    expect(declaredFacts("PACKAGE_CONSTANT", declared({ type: "varchar2(3)", value: "'EUR'" })).map(f => [f.label, f.text]))
      .toEqual([["type", "varchar2(3)"], ["initial", "'EUR'"]]);
    expect(declaredFacts("PARAMETER", declared({ type: "date", value: "sysdate + 1" })).map(f => f.label)).toEqual(["type", "default"]);
    expect(declaredFacts("CURSOR", declared({ type: "r_line" })).map(f => f.label)).toEqual(["returns"]);
  });

  it("marks a default taken from the spec, and a cut value", () => {
    const [, value] = declaredFacts("PARAMETER", declared({ type: "varchar2", value: "'none'", valueFrom: "SPEC", specText: { type: "varchar2", value: "'none'", valueCut: false } }));
    expect(value).toMatchObject({ label: "default", text: "'none'", fromSpec: true, spec: null });
    expect(declaredFacts("PACKAGE_VARIABLE", declared({ type: "varchar2(4000)", value: "'abc…", valueCut: true }))[1].cut).toBe(true);
  });

  it("says what the spec says where the body differs, ignoring case, and that the spec has none", () => {
    const facts = declaredFacts("PARAMETER", declared({ type: "NUMBER", value: "1", specText: { type: "number", value: "2", valueCut: false } }));
    expect(facts.map(f => f.spec)).toEqual([null, "2"]);
    expect(declaredFacts("PARAMETER", declared({ type: "number", value: "1", specText: { type: "number", value: null, valueCut: false } }))[1].spec).toBe("");
  });

  it("gives a routine no fact of its own, only the spec's return type where its body differs", () => {
    expect(declaredFacts("FUNCTION", declared({ type: "number" }))).toEqual([]);
    expect(declaredFacts("FUNCTION", declared({ type: "integer", specText: { type: "number", value: null, valueCut: false } })))
      .toMatchObject([{ label: "returns", text: "integer", spec: "number" }]);
  });
});

describe("parameterDefaults", () => {
  const params = [{ name: "P_ID" }, { name: '"P_Note"' }];

  it("is null when no parameter has a default, so the column is left out", () => {
    expect(parameterDefaults(params, [member("P_ID", "PARAMETER", declared({ type: "number" }))])).toBeNull();
  });

  it("lines the defaults up with the parameters in order, a quoted name matching its child", () => {
    const defaults = parameterDefaults(params, [
      member("P_Note", "PARAMETER", declared({ type: "varchar2", value: "null" })),
      member("P_ID", "PARAMETER", declared({ type: "number" }))
    ]);
    expect(defaults?.map(d => d?.text ?? null)).toEqual([null, "null"]);
  });
});

describe("memberType", () => {
  it("is shown for a package variable or constant only", () => {
    expect(memberType(member("G_TOTAL", "PACKAGE_VARIABLE", declared({ type: "number" })))).toBe("number");
    expect(memberType(member("C_LINES", "CURSOR", declared({ type: "r_line" })))).toBeNull();
  });
});
