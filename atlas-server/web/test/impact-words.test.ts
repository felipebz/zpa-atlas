import {describe, expect, it} from "vitest";
import type {EntitySummary} from "../src/api";
import en from "../src/i18n/en";
import pt from "../src/i18n/pt";
import {sayIn} from "../src/i18n-format";
import type {Phrase} from "../src/impact-words";
import {distanceText, pathEnd, pathLine, reachPhrase, summaryPhrase, triggerNote} from "../src/impact-words";
import type {ImpactView, ImpactViewNode} from "../src/impact-model";

const say = sayIn(en, "en");
const sayPt = sayIn(pt, "pt");
const text = (phrase: Phrase, using = say) => using(phrase.key, phrase.params);

describe("summaryPhrase", () => {
  it("says how many objects may be affected and how many for certain, singular and plural", () => {
    expect(text(summaryPhrase(164, 162))).toBe("164 objects may be affected, 162 of them for certain.");
    expect(text(summaryPhrase(2, 1))).toBe("2 objects may be affected, 1 of them for certain.");
    expect(text(summaryPhrase(1, 1))).toBe("1 object may be affected, for certain.");
    expect(text(summaryPhrase(1, 0))).toBe("1 object may be affected, not for certain.");
  });

  it("says when all of them or none of them are certain", () => {
    expect(text(summaryPhrase(20, 20))).toBe("20 objects may be affected, all of them for certain.");
    expect(text(summaryPhrase(55, 0))).toBe("55 objects may be affected, none of them for certain.");
    expect(text(summaryPhrase(1234, 1000))).toBe("1,234 objects may be affected, 1,000 of them for certain.");
  });

  it("reads in Portuguese with the same numbers", () => {
    expect(text(summaryPhrase(164, 162), sayPt)).toBe("164 objetos podem ser afetados, 162 deles com certeza.");
    expect(text(summaryPhrase(1, 0), sayPt)).toBe("1 objeto pode ser afetado, sem certeza.");
    expect(text(summaryPhrase(55, 0), sayPt)).toBe("55 objetos podem ser afetados, nenhum com certeza.");
  });
});

describe("reachPhrase and distanceText", () => {
  it("counts the objects that use it directly and those reached through other code, with the longest path", () => {
    expect(text(reachPhrase([112, 24, 20, 7, 1]))).toBe("112 directly, 52 through other code (up to 5 steps).");
    expect(text(reachPhrase([47]))).toBe("47 directly, none through other code.");
  });

  it("says a distance in words", () => {
    expect(distanceText(say, 1)).toBe("directly");
    expect(distanceText(say, 3)).toBe("3 steps away");
    expect(distanceText(sayPt, 1)).toBe("diretamente");
  });
});

describe("triggerNote", () => {
  const view = (name: string): EntitySummary => ({ key: "db:APP." + name, kind: "VIEW", display: "APP." + name, external: false, name });

  it("uses the words of the entity page's trigger table, the table by its short name", () => {
    expect(triggerNote(say, { table: view("V_ORDERS"), events: [{ timing: "INSTEAD_OF", level: "ROW", event: "INSERT", column: null }] }))
      .toBe("instead of insert, for each row, on V_ORDERS");
    const column: EntitySummary = { key: "db:APP.ORDERS.STATE", kind: "COLUMN", display: "APP.ORDERS.STATE", external: false, name: "STATE" };
    expect(triggerNote(say, { table: view("ORDERS"), events: [
      { timing: "BEFORE", level: "ROW", event: "UPDATE", column },
      { timing: "AFTER", level: "STATEMENT", event: "DELETE", column: null }
    ] })).toBe("before update of STATE, for each row; after delete, statement, on ORDERS");
  });
});

describe("pathLine", () => {
  const entity = (key: string, kind: string, display: string, name: string): EntitySummary => ({ key, kind, display, external: false, name });
  const node = (entityIndex: number | null, path: number | null): ImpactViewNode => ({
    kind: "PROCEDURE", entity: entityIndex, path, uses: null, firstLine: 1, depth: 1, certain: false, stop: "ENTRY_POINT",
    owner: null, schema: "APP", via: { node: null, uses: 0, path: 0, line: 7, status: "UNVERIFIED", fires: null }, trigger: null
  });
  const graph: ImpactView = {
    entity: entity("db:APP.ORDERS.STATE", "COLUMN", "APP.ORDERS.STATE", "STATE"), included: [], maxDepth: 10, followTriggers: true,
    levels: [2], stops: [], certain: 0,
    entities: [entity("db:APP.ORDERS.STATE", "COLUMN", "APP.ORDERS.STATE", "STATE"), entity("db:APP.CLOSE_ORDER(P_ID)", "PROCEDURE", "APP.CLOSE_ORDER(P_ID)", "CLOSE_ORDER")],
    paths: ["app/close_order.prc", "scripts/fix.sql"], nodes: [node(1, null), node(null, 1)]
  };

  it("reads like a Used by row: the short name, the line in the file, the status in words", () => {
    expect(pathLine(say, graph, { node: 0, path: "app/close_order.prc", line: 7, status: "UNVERIFIED", fires: null })).toEqual({
      entity: graph.entities[1], name: "CLOSE_ORDER(P_ID)", place: "line 7", path: "app/close_order.prc", line: 7, status: "unverified", fires: null
    });
  });

  it("names a script file by its path, and says what a step fires", () => {
    const line = pathLine(say, graph, { node: 1, path: "scripts/fix.sql", line: 3, status: "RESOLVED", fires: "UPDATE" });
    expect(line).toMatchObject({ entity: null, name: "scripts/fix.sql", place: "line 3", status: null, fires: "fires on update" });
  });

  it("ends on the object the page starts from, or on what the first step reached from it", () => {
    expect(pathEnd(say, graph, graph.entities[0])).toBe("the object this page starts from");
    expect(pathEnd(say, graph, graph.entities[1])).toBe("reached from the object this page starts from");
  });
});
