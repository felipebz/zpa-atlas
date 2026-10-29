// What the entity page shows, worked out from its answer without the browser: the path, the places in the header,
// the tags of the title, and the routines' filter and order.

import type {EntityDetail, EntitySummary, Location} from "./api";

const ROUTINE_KINDS = ["PROCEDURE", "FUNCTION"];

/** Whether [key] is a body's (`…@BODY`, before a collision's `#2`). */
export function isBodyKey(key: string) {
  return /@BODY(#\d+)?$/.test(key);
}

/**
 * The path above the title: the schema (null when it is unknown: none recorded, or the `~` placeholder), the entities
 * that hold the object, outermost first, then the object's own name.
 */
export function entityPath(entity: Pick<EntityDetail, "schema" | "ancestors" | "name">) {
  return { schema: entity.schema && entity.schema !== "~" ? entity.schema : null, ancestors: entity.ancestors, name: entity.name };
}

/** Which half of a package or type a place is in: from the unit it sits in; null outside a package or type. */
export type Half = "spec" | "body";

/**
 * A place of the header: a line of a file, the [half] it is in (null outside a package or type), [partner], the entity
 * whose page this half is when it is not this page (a routine's body seen from its spec, and the other way), and
 * whether it names its [file]: everywhere but inside a package or type, whose file a member's place leaves to its holder.
 */
export interface Place {
  half: Half | null;
  path: string;
  line: number;
  col: number;
  partner: EntitySummary | null;
  file: boolean;
}

export function halfOf(location: Location): Half | null {
  switch (location.unitKind) {
    case "PACKAGE": case "TYPE": return "spec";
    case "PACKAGE_BODY": case "TYPE_BODY": return "body";
    default: return null;
  }
}

/**
 * Where [entity] is: its own definitions, then its partners' (a routine's body, or a body's spec), the spec before
 * the body; each partner place names the partner, whose page it is. A package's or a type's places name their files
 * (its spec and body may be two); a member's places do not, its file being its package's or type's.
 */
export function places(entity: Pick<EntityDetail, "kind" | "definitions" | "partners">): Place[] {
  const named = (half: Half | null) => half === null || entity.kind === "PACKAGE" || entity.kind === "TYPE";
  const own = entity.definitions.map(d => {
    const half = halfOf(d);
    return { half, path: d.path, line: d.span.startLine, col: d.span.startCol, partner: null, file: named(half) };
  });
  const others = entity.partners.flatMap(p => p.definitions.map(d => {
    const half = halfOf(d) ?? (p.direction === "IN" ? "body" as const : "spec" as const);
    return { half, path: d.path, line: d.span.startLine, col: d.span.startCol, partner: p.entity, file: named(half) };
  }));
  const rank = (place: Place) => place.half === "spec" ? 0 : place.half === "body" ? 1 : 2;
  return [...own, ...others].sort((a, b) => rank(a) - rank(b));
}

/** The tags after the title: external, private for a package routine that exists only in the body, body for a body with a spec. */
export function entityTags(entity: Pick<EntityDetail, "key" | "kind" | "external" | "partners" | "parent">): ("external" | "private" | "body")[] {
  const tags: ("external" | "private" | "body")[] = [];
  if (entity.external) tags.push("external");
  if (isBodyKey(entity.key)) {
    const hasSpec = entity.partners.some(p => p.direction === "OUT");
    const routine = ROUTINE_KINDS.includes(entity.kind) && entity.parent !== null && ["PACKAGE", "TYPE"].includes(entity.parent.kind);
    if (!hasSpec && routine) tags.push("private");
    else tags.push("body");
  }
  return tags;
}

/** A row of a routines table: what the filter and the order read. */
export interface RoutineRow {
  name: string;
  key: string;
  usages: number;
}

/** The order of a routines table: by name (`az`, the answer's order) or by uses, most first, then by name. */
export type RoutineOrder = "az" | "uses";

/** The rows whose name holds [text] (ignoring case), in [order]; names compare ignoring case, `_` before the letters. */
export function routineRows<T extends RoutineRow>(rows: T[], text: string, order: RoutineOrder): T[] {
  const wanted = text.trim().toLowerCase();
  const byName = (a: T, b: T) => {
    const x = a.name.toLowerCase(), y = b.name.toLowerCase();
    return x < y ? -1 : x > y ? 1 : a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
  };
  const kept = rows.filter(row => row.name.toLowerCase().includes(wanted));
  return kept.sort(order === "uses" ? (a, b) => b.usages - a.usages || byName(a, b) : byName);
}
