import {describe, expect, it} from "vitest";
import {OPENED_LAST, openedLast, recordOpened} from "../src/recent";

/** A storage in memory, as much of `Storage` as the list uses. */
class MemoryStorage {
  readonly items = new Map<string, string>();
  getItem(key: string) { return this.items.get(key) ?? null; }
  setItem(key: string, value: string) { this.items.set(key, value); }
}

/** A storage that refuses every read and write, as a private window or a full quota does. */
class RefusingStorage {
  getItem(): string | null { throw new Error("refused"); }
  setItem(): void { throw new Error("refused"); }
}

const entity = (name: string) => ({ key: `db:HR.PACK_ORDERS.${name}`, kind: "PROCEDURE", display: `HR.PACK_ORDERS.${name}()`, name });
const names = (list: { name: string }[]) => list.map(o => o.name);

describe("opened last", () => {
  it("keeps the last opened first, each entity once", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    for (const name of ["SHIP", "BILL", "SHIP", "PACK"]) recordOpened(storage, null, entity(name));
    expect(names(openedLast(storage, null))).toEqual(["PACK", "SHIP", "BILL"]);
  });

  it("keeps at most the limit, dropping the oldest", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    for (let i = 1; i <= OPENED_LAST + 2; i++) recordOpened(storage, null, entity("STEP_" + i));
    const list = names(openedLast(storage, null));
    expect(list).toHaveLength(OPENED_LAST);
    expect(list[0]).toBe("STEP_" + (OPENED_LAST + 2));
    expect(list).not.toContain("STEP_1");
    expect(list).not.toContain("STEP_2");
  });

  it("keeps one list per project, apart from the server without projects", () => {
    const storage = new MemoryStorage() as unknown as Storage;
    recordOpened(storage, "sales", entity("SHIP"));
    recordOpened(storage, "billing", entity("BILL"));
    recordOpened(storage, null, entity("PACK"));
    expect(names(openedLast(storage, "sales"))).toEqual(["SHIP"]);
    expect(names(openedLast(storage, "billing"))).toEqual(["BILL"]);
    expect(names(openedLast(storage, null))).toEqual(["PACK"]);
  });

  it("gives an empty list and records nothing when the storage throws, is missing or holds something else", () => {
    const refusing = new RefusingStorage() as unknown as Storage;
    expect(() => recordOpened(refusing, null, entity("SHIP"))).not.toThrow();
    expect(openedLast(refusing, null)).toEqual([]);
    expect(openedLast(null, null)).toEqual([]);
    const storage = new MemoryStorage();
    storage.setItem("atlas.openedLast", "{not json");
    expect(openedLast(storage as unknown as Storage, null)).toEqual([]);
    storage.setItem("atlas.openedLast", JSON.stringify([{ key: 1 }, entity("SHIP")]));
    expect(names(openedLast(storage as unknown as Storage, null))).toEqual(["SHIP"]);
  });
});
