import { describe, expect, it } from "vitest";
import { filtersOf, hasFilter, moreSet } from "../src/query-params";

// The expected values are what the code returns today.

describe("filtersOf", () => {
  it("leaves out the answer's own mode", () => {
    expect(filtersOf({ mode: "groups", groupBy: "ENTITY", roles: "WRITE", name: "x" })).toEqual({ roles: "WRITE", name: "x" });
    expect(filtersOf({})).toEqual({  });
    expect(filtersOf({ mode: "groups" })).toEqual({  });
  });
});

describe("hasFilter", () => {
  it("is true for any filter, even an empty one, and false for the mode alone", () => {
    expect(hasFilter({})).toBe(false);
    expect(hasFilter({ mode: "groups", groupBy: "ENTITY" })).toBe(false);
    expect(hasFilter({ roles: "WRITE" })).toBe(true);
    expect(hasFilter({ value: "" })).toBe(true);
  });
});

describe("moreSet", () => {
  it("lists the filters behind \"more filters\" that are set, in the form's order, an empty one included", () => {
    expect(moreSet({ name: "x", roles: "WRITE" })).toEqual([]);
    expect(moreSet({ value: "S", schema: "HR", candidates: "false", containerInside: "false", entityKinds: "COLUMN" })).toEqual(["entityKinds", "schema", "candidates", "containerInside", "value"]);
    expect(moreSet({ path: "" })).toEqual(["path"]);
  });
});
