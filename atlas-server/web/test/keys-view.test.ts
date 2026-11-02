import {describe, expect, it} from "vitest";
import type {EntitySummary, KeyColumn, ReferencingKey, TableKey} from "../src/api";
import {columnPlace, referencedColumn, referencingColumn, shownDeleteRule} from "../src/keys-view";

// Invented names: HR.ORDERS (ORDER_ID, LINE_NO, CODE) and HR.ORDER_LINES referencing it; OTHER.CURRENCIES outside.
function summary(display: string, kind: string): EntitySummary {
  return { key: "db:" + display, kind, display, external: false, name: display.slice(display.lastIndexOf(".") + 1) };
}

function columns(table: string, names: string[]): KeyColumn[] {
  return names.map(name => ({ name, column: summary(`${table}.${name}`, "COLUMN") }));
}

function key(name: string, kind: TableKey["kind"], table: string, names: string[], over: Partial<TableKey> = {}): TableKey {
  return {
    owner: "HR", name, kind, table, relation: summary(table, "TABLE"), columns: columns(table, names), enabled: true,
    deleteRule: null, referencedName: null, references: null, ...over
  };
}

const PK_ORDERS = key("PK_ORDERS", "PRIMARY_KEY", "HR.ORDERS", ["ORDER_ID", "LINE_NO"]);
const UK_ORDERS_CODE = key("UK_ORDERS_CODE", "UNIQUE", "HR.ORDERS", ["CODE"]);
const FK_LINES_ORDER = key("FK_LINES_ORDER", "FOREIGN_KEY", "HR.ORDER_LINES", ["ORDER_ID", "LINE_NO"],
  { deleteRule: "CASCADE", referencedName: "HR.PK_ORDERS", references: PK_ORDERS });
const FK_LINES_CODE = key("FK_LINES_CODE", "FOREIGN_KEY", "HR.ORDER_LINES", ["CODE"],
  { enabled: false, deleteRule: "NO ACTION", referencedName: "HR.UK_ORDERS_CODE", references: UK_ORDERS_CODE });
const FK_LINES_CURRENCY = key("FK_LINES_CURRENCY", "FOREIGN_KEY", "HR.ORDER_LINES", ["CURRENCY_ID"],
  { deleteRule: "NO ACTION", referencedName: "OTHER.PK_CURRENCIES", references: null });

describe("referencedColumn", () => {
  it("pairs a foreign key's column with the referenced key's column at the same position", () => {
    expect(referencedColumn(FK_LINES_ORDER, "db:HR.ORDER_LINES.LINE_NO")?.column?.key).toBe("db:HR.ORDERS.LINE_NO");
    expect(referencedColumn(FK_LINES_ORDER, "db:HR.ORDER_LINES.ORDER_ID")?.column?.key).toBe("db:HR.ORDERS.ORDER_ID");
  });

  it("is null for a column not in the key, and for a key the dictionary did not give, without throwing", () => {
    expect(referencedColumn(FK_LINES_ORDER, "db:HR.ORDER_LINES.CODE")).toBeNull();
    expect(referencedColumn(FK_LINES_CURRENCY, "db:HR.ORDER_LINES.CURRENCY_ID")).toBeNull();
  });
});

describe("referencingColumn", () => {
  // A referencing foreign key as "Referenced by" gives it: each column names the column it references.
  const referencing: ReferencingKey = {
    owner: "HR", name: "FK_LINES_ORDER", table: "HR.ORDER_LINES", relation: summary("HR.ORDER_LINES", "TABLE"), enabled: true,
    deleteRule: "CASCADE", references: "HR.PK_ORDERS",
    columns: [["ORDER_ID", "ORDER_ID"], ["LINE_NO", "LINE_NO"]].map(([name, references]) =>
      ({ name, column: summary("HR.ORDER_LINES." + name, "COLUMN"), references }))
  };

  it("finds the foreign key's column that references the page's column, with its position", () => {
    const found = referencingColumn(referencing, "LINE_NO");
    expect([found?.column.column?.key, found?.position, found?.count]).toEqual(["db:HR.ORDER_LINES.LINE_NO", 2, 2]);
  });

  it("is null for a column it does not reference", () => {
    expect(referencingColumn(referencing, "CODE")).toBeNull();
  });
});

describe("columnPlace", () => {
  it("says the column's position among the key's columns", () => {
    expect(columnPlace(PK_ORDERS, "db:HR.ORDERS.LINE_NO")).toEqual({ position: 2, count: 2 });
    expect(columnPlace(UK_ORDERS_CODE, "db:HR.ORDERS.CODE")).toEqual({ position: 1, count: 1 });
    expect(columnPlace(PK_ORDERS, "db:HR.ORDERS.CODE")).toBeNull();
  });

  it("finds no column the index has no entity for", () => {
    const outside = key("PK_CURRENCIES", "PRIMARY_KEY", "OTHER.CURRENCIES", [], { columns: [{ name: "CURRENCY_ID", column: null }] });
    expect(columnPlace(outside, "db:OTHER.CURRENCIES.CURRENCY_ID")).toBeNull();
  });
});

describe("shownDeleteRule", () => {
  it("shows a foreign key's rule other than NO ACTION, nothing else", () => {
    expect(shownDeleteRule(FK_LINES_ORDER)).toBe("CASCADE");
    expect(shownDeleteRule(FK_LINES_CODE)).toBeNull();
    expect(shownDeleteRule(PK_ORDERS)).toBeNull();
  });
});
