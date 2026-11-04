import {describe, expect, it} from "vitest";
import en from "../src/i18n/en";
import pt from "../src/i18n/pt";

describe("catalogs", () => {
  it("say the same keys in English and Portuguese", () => {
    expect(Object.keys(pt).sort()).toEqual(Object.keys(en).sort());
  });
});
