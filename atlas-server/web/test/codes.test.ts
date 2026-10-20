import { describe, expect, it } from "vitest";
import type { Codes } from "../src/api";
import { codesOf, searchKinds, usageRoles } from "../src/codes";

const code = (name: string, reserved = false) => ({ code: name, reserved });

/** A small answer of `/api/codes`, in the shape and order the server gives. */
const CODES: Codes = {
  roles: ["DEFINITION", "DECLARATION", "READ", "WRITE", "CALL"].map(c => code(c)),
  statuses: [code("RESOLVED"), code("AMBIGUOUS")],
  entityKinds: [code("SCHEMA", true), code("TABLE"), code("COLUMN"), code("SYNONYM"), code("SEQUENCE"), code("LABEL", true), code("UNKNOWN_RELATION")],
  unitKinds: [code("PACKAGE"), code("FORMS_TRIGGER", true)],
  origins: [code("STATIC"), code("HEURISTIC", true)],
  statementKinds: [code("SELECT")],
  valueKinds: [code("STRING"), code("NULL")],
  groupDimensions: [code("ENTITY"), code("ROLE")]
};

describe("codesOf", () => {
  it("keeps every code in its order, reserved ones included", () => {
    expect(codesOf(CODES.entityKinds)).toEqual(["SCHEMA", "TABLE", "COLUMN", "SYNONYM", "SEQUENCE", "LABEL", "UNKNOWN_RELATION"]);
    expect(codesOf(CODES.origins)).toEqual(["STATIC", "HEURISTIC"]);
    expect(codesOf([])).toEqual([]);
  });
});

describe("searchKinds", () => {
  it("offers the entity kinds the index produces, synonyms included, in their order", () => {
    expect(searchKinds(CODES)).toEqual(["TABLE", "COLUMN", "SYNONYM", "SEQUENCE", "UNKNOWN_RELATION"]);
  });
});

describe("usageRoles", () => {
  it("is every role but the definition and the declaration", () => {
    expect(usageRoles(CODES)).toEqual(["READ", "WRITE", "CALL"]);
  });
});
