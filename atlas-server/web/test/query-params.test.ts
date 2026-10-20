import { describe, expect, it } from "vitest";
import { filtersOf, hasFilter, moreSet, paramsOf } from "../src/query-params";

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

  it("is false for the modifiers alone, which only narrow another filter", () => {
    for (const search of ["?roles=", "?mode=groups", "?lang=pt", "?inside=true", "?candidates=false", "?containerInside=false", "?entity=&inside=true"]) {
      expect(hasFilter(paramsOf(search)), search).toBe(false);
    }
    for (const search of ["?roles=WRITE", "?entity=db:HR.COMPANY&inside=true", "?containerName=PACK_PROCESS&containerInside=false", "?value=%20"]) {
      expect(hasFilter(paramsOf(search)), search).toBe(true);
    }
  });
});

describe("paramsOf", () => {
  it("reads the page's query without the language, empty values left out, and groups by entity by default", () => {
    expect(paramsOf("?roles=")).toEqual({});
    expect(paramsOf("?path=&roles=WRITE")).toEqual({ roles: "WRITE" });
    expect(paramsOf("?lang=pt")).toEqual({});
    expect(paramsOf("?lang=pt&roles=WRITE")).toEqual({ roles: "WRITE" });
    expect(paramsOf("?mode=groups")).toEqual({ mode: "groups", groupBy: "ENTITY" });
    expect(paramsOf("?mode=groups&groupBy=")).toEqual({ mode: "groups", groupBy: "ENTITY" });
    expect(paramsOf("?mode=groups&groupBy=ROLE,STATUS")).toEqual({ mode: "groups", groupBy: "ROLE,STATUS" });
    // A space is a value: readFilters keeps `value` untrimmed.
    expect(paramsOf("?value=%20")).toEqual({ value: " " });
    expect(paramsOf("")).toEqual({});
  });
});

describe("moreSet", () => {
  it("lists the filters behind \"more filters\" that are set, in the form's order, an empty one included", () => {
    expect(moreSet({ name: "x", roles: "WRITE" })).toEqual([]);
    expect(moreSet({ value: "S", schema: "HR", candidates: "false", containerInside: "false", entityKinds: "COLUMN" })).toEqual(["entityKinds", "schema", "candidates", "containerInside", "value"]);
    expect(moreSet({ path: "" })).toEqual(["path"]);
  });
});
