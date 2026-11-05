// What the entity page shows, worked out from its answer without the browser: the path, the places in the header,
// the tags of the title, and the routines' filter and order.

import type {CodeAt, EntityDetail, EntitySummary, Location} from "./api";

const ROUTINE_KINDS = ["PROCEDURE", "FUNCTION"];
/** A package body's own variables, constants and types: always private to the body. */
const BODY_OWN_KINDS = ["PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "TYPE"];

/** Whether [member] is a package body's own variable, constant or type, private to the body. */
export function isBodyOwn(member: Pick<EntitySummary, "key" | "kind">) {
  return BODY_OWN_KINDS.includes(member.kind) && isBodyKey(member.key);
}

/**
 * For a routine's own variable or constant, the routine its page names: its parent (the routine, or for a method of a
 * type body, the type), then what its key holds between the parent's key and its own name (a method's or a nested
 * subprogram's name and signature, which tell overloads apart: `RUN.HELPER(P_A IN NUMBER)`), and the parent's key to
 * link to. Null for any other entity.
 */
export function localOf(entity: Pick<EntityDetail, "key" | "kind" | "parent">): { name: string; key: string } | null {
  if (!["LOCAL_VARIABLE", "LOCAL_CONSTANT"].includes(entity.kind) || !entity.parent) return null;
  const parent = entity.parent;
  const rest = entity.key.startsWith(parent.key + ".") ? entity.key.slice(parent.key.length + 1) : "";
  // The segments split at the dots outside a signature's parentheses (a parameter's type may be `PKG.T%TYPE`).
  const segments: string[] = [];
  let depth = 0, start = 0;
  for (let i = 0; i < rest.length; i++) {
    if (rest[i] === "(") depth++;
    else if (rest[i] === ")") depth--;
    else if (rest[i] === "." && depth === 0) { segments.push(rest.slice(start, i)); start = i + 1; }
  }
  return { name: [parent.name ?? parent.display, ...segments].join("."), key: parent.key };
}

/** The reasons the page names, the first one found wins: what most often hides names comes first. */
const GAP_REASONS = ["CONDITIONAL_COMPILATION", "SUBSTITUTION", "PARSE_RECOVERY", "INCOMPLETE_SOURCE", "DECLARATION_OUTSIDE_FILE", "UNSUPPORTED_REFERENCE"];

/**
 * The message key that says why an entity's uses may be incomplete (`entity.usesIncomplete.<REASON>`, or
 * `entity.usesIncomplete` for a reason the page does not name), or null when its uses are complete.
 */
export function usesGapKey(reasons: string[] | null | undefined): string | null {
  if (!reasons?.length) return null;
  const named = GAP_REASONS.find(r => reasons.includes(r));
  return named ? `entity.usesIncomplete.${named}` : "entity.usesIncomplete";
}

/** The message key that says why a type's listed attributes may not be all, `ALTER TYPE` first; null when complete. */
export function membersGapKey(reasons: string[] | null | undefined): string | null {
  if (!reasons?.length) return null;
  return reasons.includes("ALTER_TYPE") ? "entity.membersIncomplete.ALTER_TYPE" : "entity.membersIncomplete.NOT_EXTRACTED";
}

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
 * Inside Forms code, [code] says which piece and the line there, where the place leads: the code page.
 */
export interface Place {
  half: Half | null;
  path: string;
  line: number;
  col: number;
  partner: EntitySummary | null;
  file: boolean;
  code: CodeAt | null;
}

export function halfOf(location: { unitKind?: string | null }): Half | null {
  switch (location.unitKind) {
    case "PACKAGE": case "TYPE": return "spec";
    case "PACKAGE_BODY": case "TYPE_BODY": return "body";
    default: return null;
  }
}

/**
 * Where [entity] is: its own definitions, then its partners' (a routine's body, or a body's spec), the spec before
 * the body; each partner place names the partner, whose page it is. A package's or a type's places name their files
 * (its spec and body may be two); a member's places do not, its file being its package's or type's. A package body's
 * own type is a member of the package. A place in Forms code is in the half its code is (a program unit's
 * specification or body).
 */
export function places(entity: Pick<EntityDetail, "key" | "kind" | "definitions" | "partners">): Place[] {
  const named = (half: Half | null) => half === null || (entity.kind === "PACKAGE" || entity.kind === "TYPE") && !isBodyOwn(entity);
  const own = entity.definitions.map(d => {
    const half = halfOf(d.code ?? d);
    return { half, path: d.path, line: d.span.startLine, col: d.span.startCol, partner: null, file: named(half), code: d.code ?? null };
  });
  const others = entity.partners.flatMap(p => p.definitions.map(d => {
    const half = halfOf(d) ?? (p.direction === "IN" ? "body" as const : "spec" as const);
    return { half, path: d.path, line: d.span.startLine, col: d.span.startCol, partner: p.entity, file: named(half), code: d.code ?? null };
  }));
  const rank = (place: Place) => place.half === "spec" ? 0 : place.half === "body" ? 1 : 2;
  return [...own, ...others].sort((a, b) => rank(a) - rank(b));
}

/**
 * The tags after the title: external; private for a package routine that exists only in the body and for a package
 * body's own variable, constant or type; body for a body with a spec.
 */
export function entityTags(entity: Pick<EntityDetail, "key" | "kind" | "external" | "partners" | "parent">): ("external" | "private" | "body")[] {
  const tags: ("external" | "private" | "body")[] = [];
  if (entity.external) tags.push("external");
  if (isBodyKey(entity.key)) {
    const hasSpec = entity.partners.some(p => p.direction === "OUT");
    const routine = ROUTINE_KINDS.includes(entity.kind) && entity.parent !== null && ["PACKAGE", "TYPE"].includes(entity.parent.kind);
    if (!hasSpec && (routine || isBodyOwn(entity))) tags.push("private");
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
