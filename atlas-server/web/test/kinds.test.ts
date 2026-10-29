import {describe, expect, it} from "vitest";
import {kindMark, kindOrder} from "../src/kinds";
import en from "../src/i18n/en";

// Every kind the catalog labels: the kinds the index stores.
const KINDS = Object.keys(en).filter(key => key.startsWith("kind.")).map(key => key.slice("kind.".length));

describe("kindMark", () => {
  it("gives every known kind its own letters", () => {
    const letters = KINDS.map(kind => kindMark(kind)[0]);
    expect(letters).not.toContain("·");
    expect(new Set(letters).size).toBe(KINDS.length);
  });

  it("puts an unknown kind in the neutral group", () => {
    expect(kindMark("NOT_A_KIND")).toEqual(["·", "other"]);
  });
});

describe("kindOrder", () => {
  it("puts program units before a package's declarations, and kinds it does not list last", () => {
    const sorted = ["PACKAGE_CONSTANT", "PARAMETER", "FUNCTION", "TYPE", "PROCEDURE", "COLUMN"]
      .sort((a, b) => kindOrder(a) - kindOrder(b));
    expect(sorted).toEqual(["COLUMN", "PROCEDURE", "FUNCTION", "TYPE", "PACKAGE_CONSTANT", "PARAMETER"]);
  });
});
