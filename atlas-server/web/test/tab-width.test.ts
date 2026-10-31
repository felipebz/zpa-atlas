import {describe, expect, it} from "vitest";
import {DEFAULT_TAB_WIDTH, storedTabWidth, storeTabWidth, tabWidthOf, widestLine} from "../src/tab-width";

/** A storage in memory, as much of `Storage` as the choice uses. */
class MemoryStorage {
  readonly items = new Map<string, string>();
  getItem(key: string) { return this.items.get(key) ?? null; }
  setItem(key: string, value: string) { this.items.set(key, value); }
  removeItem(key: string) { this.items.delete(key); }
}

/** A storage that refuses every read and write, as a private window may. */
class RefusingStorage {
  getItem(): string | null { throw new Error("refused"); }
  setItem(): void { throw new Error("refused"); }
  removeItem(): void { throw new Error("refused"); }
}

describe("widestLine", () => {
  // `\tv_total\t:= 1;`: a tab, 7 characters, a tab, 5 characters.
  const lines = ["\tv_total\t:= 1;", "begin", "\t\tnull;"];

  it("counts a tab to the next multiple of the width", () => {
    // Width 2: 2 + 7 = 9, the tab to 10, then 5: 15. The second tab line: 4 + 5 = 9.
    expect(widestLine(lines, 2)).toBe(15);
    // Width 8: 8 + 7 = 15, the tab to 16, then 5: 21.
    expect(widestLine(lines, 8)).toBe(21);
  });

  it("counts a line without tabs in characters, whatever the width", () => {
    expect(widestLine(["select 1 from dual;"], 2)).toBe(19);
    expect(widestLine(["select 1 from dual;"], 8)).toBe(19);
  });
});

describe("the tab width shown", () => {
  it("is the project's default when the reader chose none, and 8 when the project gives none either", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    expect(tabWidthOf(storedTabWidth(storage, "hr"), 2)).toBe(2);
    expect(tabWidthOf(storedTabWidth(storage, "hr"), undefined)).toBe(DEFAULT_TAB_WIDTH);
  });

  it("is the reader's choice, kept per project, over the project's default", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    expect(storeTabWidth(storage, "hr", 4, 2)).toBe(true);
    expect(tabWidthOf(storedTabWidth(storage, "hr"), 2)).toBe(4);
    // Another project, and the server without projects, keep their own.
    expect(tabWidthOf(storedTabWidth(storage, "billing"), 8)).toBe(8);
    expect(storedTabWidth(storage, null)).toBeNull();
  });

  it("follows the project's default again once the reader picks it", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    storeTabWidth(storage, "hr", 4, 2);
    storeTabWidth(storage, "hr", 2, 2);
    expect(storedTabWidth(storage, "hr")).toBeNull();
    // The project's default changes later: the reader sees the new one.
    expect(tabWidthOf(storedTabWidth(storage, "hr"), 8)).toBe(8);
  });

  it("ignores a stored value that is not a width", () => {
    const storage = new MemoryStorage();
    storage.setItem("atlas.tabWidth.hr", "3");
    expect(tabWidthOf(storedTabWidth(storage as unknown as Storage, "hr"), 2)).toBe(2);
  });

  it("falls back to the project's default when storage refuses, and says the choice was not kept", () => {
    const storage = new RefusingStorage() as unknown as Storage;
    expect(storeTabWidth(storage, "hr", 4, 2)).toBe(false);
    expect(tabWidthOf(storedTabWidth(storage, "hr"), 2)).toBe(2);
    expect(storeTabWidth(null, "hr", 4, 2)).toBe(false);
  });
});
