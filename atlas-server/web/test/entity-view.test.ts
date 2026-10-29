import {describe, expect, it} from "vitest";
import type {EntityDetail, EntitySummary, Location, Partner} from "../src/api";
import {entityPath, entityTags, places, routineRows} from "../src/entity-view";

// Invented names: a package PACK_ORDERS with a routine SHIP_ORDER in its spec and body, and a private one.
const pack: EntitySummary = { key: "db:HR.PACK_ORDERS", kind: "PACKAGE", display: "HR.PACK_ORDERS", external: false, name: "PACK_ORDERS" };
const spec: EntitySummary = { key: "db:HR.PACK_ORDERS.SHIP_ORDER(P_ID IN NUMBER)", kind: "PROCEDURE", display: "HR.PACK_ORDERS.SHIP_ORDER(P_ID IN NUMBER)", external: false, name: "SHIP_ORDER" };
const body: EntitySummary = { ...spec, key: spec.key + "@BODY", display: spec.display + "@BODY" };

function at(path: string, line: number, unitKind: string | null, roles = ["DEFINITION"]): Location {
  return { path, span: { startLine: line, startCol: 2, endLine: line, endCol: 12 }, roles, status: "RESOLVED", written: "", unitKind };
}

function entity(over: Partial<EntityDetail> & Pick<EntityDetail, "key" | "kind">): EntityDetail {
  return {
    schema: "HR", name: "X", display: "HR.X", external: false, parent: null, ancestors: [], children: [], relations: [],
    definitions: [], overloads: [], partners: [], usage: { byRole: [], byStatus: [], asCandidate: 0 }, triggerEvents: [], reads: [],
    views: [], baseColumns: [], viewColumns: [], ...over
  };
}

describe("entityPath", () => {
  it("reads the schema, the holders outermost first, and the name; an unknown or placeholder schema is null", () => {
    const parameter = entity({ key: spec.key + ".P_ID", kind: "PARAMETER", name: "P_ID", ancestors: [pack, spec] });
    expect(entityPath(parameter)).toEqual({ schema: "HR", ancestors: [pack, spec], name: "P_ID" });
    expect(entityPath(entity({ key: "db:~.EXT_PKG", kind: "PACKAGE", schema: "~", name: "EXT_PKG" })).schema).toBeNull();
    expect(entityPath(entity({ key: "db:HR.X", kind: "TABLE", schema: null })).schema).toBeNull();
  });
});

describe("places", () => {
  const toBody: Partner = { entity: body, direction: "IN", definitions: [at("pkg/pack_orders.pkb", 59, "PACKAGE_BODY")] };
  const toSpec: Partner = { entity: spec, direction: "OUT", definitions: [at("pkg/pack_orders.pks", 41, "PACKAGE", ["DECLARATION"])] };

  it("gives a routine's spec page its own spec line, then the body's line with the body as the partner", () => {
    const page = entity({ key: spec.key, kind: "PROCEDURE", definitions: [at("pkg/pack_orders.pks", 41, "PACKAGE", ["DECLARATION"])], partners: [toBody] });
    expect(places(page).map(p => [p.half, p.line, p.partner?.key ?? null])).toEqual([["spec", 41, null], ["body", 59, body.key]]);
  });

  it("gives a body's page the spec first, with the spec as the partner", () => {
    const page = entity({ key: body.key, kind: "PROCEDURE", definitions: [at("pkg/pack_orders.pkb", 59, "PACKAGE_BODY")], partners: [toSpec] });
    expect(places(page).map(p => [p.half, p.line, p.partner?.key ?? null])).toEqual([["spec", 41, spec.key], ["body", 59, null]]);
  });

  it("tells a package's and a type's halves by their unit, each naming its file, and leaves other places without a half", () => {
    const type = entity({ key: "db:HR.TY_BOX", kind: "TYPE", definitions: [at("ty/ty_box.tpb", 1, "TYPE_BODY"), at("ty/ty_box.typ", 1, "TYPE")] });
    expect(places(type).map(p => [p.half, p.path, p.file])).toEqual([["spec", "ty/ty_box.typ", true], ["body", "ty/ty_box.tpb", true]]);
    const pkg = entity({ key: pack.key, kind: "PACKAGE",
      definitions: [at("pkg/pack_orders.pks", 1, "PACKAGE", ["DECLARATION"]), at("pkg/pack_orders.pkb", 1, "PACKAGE_BODY")] });
    expect(places(pkg).map(p => [p.half, p.path, p.file])).toEqual([["spec", "pkg/pack_orders.pks", true], ["body", "pkg/pack_orders.pkb", true]]);
    const view = entity({ key: "db:HR.V_ORDERS", kind: "VIEW", definitions: [at("views/v_orders.sql", 3, "VIEW")] });
    expect(places(view).map(p => [p.half, p.line, p.file])).toEqual([[null, 3, true]]);
  });

  it("leaves the file out of a routine's places: it is its package's", () => {
    const page = entity({ key: spec.key, kind: "PROCEDURE", definitions: [at("pkg/pack_orders.pks", 41, "PACKAGE", ["DECLARATION"])], partners: [toBody] });
    expect(places(page).map(p => p.file)).toEqual([false, false]);
  });
});

describe("entityTags", () => {
  it("marks a package routine that exists only in the body as private, and a body with a spec as a body", () => {
    const privateOne = entity({ key: "db:HR.PACK_ORDERS.CHECK_STOCK()@BODY", kind: "FUNCTION", parent: pack });
    expect(entityTags(privateOne)).toEqual(["private"]);
    const withSpec = entity({ key: body.key, kind: "PROCEDURE", parent: pack, partners: [{ entity: spec, direction: "OUT", definitions: [] }] });
    expect(entityTags(withSpec)).toEqual(["body"]);
    expect(entityTags(entity({ key: spec.key, kind: "PROCEDURE", parent: pack }))).toEqual([]);
    expect(entityTags(entity({ key: "db:~.EXT_PKG", kind: "PACKAGE", external: true }))).toEqual(["external"]);
  });
});

describe("routineRows", () => {
  const rows = [
    { name: "SHIP_ORDER", key: "a", usages: 3 },
    { name: "SHIPMENT_COST", key: "b", usages: 9 },
    { name: "cancel_order", key: "c", usages: 3 },
    { name: "BILL", key: "d", usages: 0 }
  ];
  it("keeps the names that hold the text, ignoring case, by name with _ before the letters", () => {
    expect(routineRows(rows, "ORDER", "az").map(r => r.name)).toEqual(["cancel_order", "SHIP_ORDER"]);
    expect(routineRows(rows, "ship", "az").map(r => r.name)).toEqual(["SHIP_ORDER", "SHIPMENT_COST"]);
  });
  it("orders by uses, most first, then by name", () => {
    expect(routineRows(rows, "", "uses").map(r => r.name)).toEqual(["SHIPMENT_COST", "cancel_order", "SHIP_ORDER", "BILL"]);
  });
});
