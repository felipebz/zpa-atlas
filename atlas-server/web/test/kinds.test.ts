import {describe, expect, it} from "vitest";
import {kindMark} from "../src/kinds";
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
