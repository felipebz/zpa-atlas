import type {CallArgument, EntitySummary, OccurrenceRow} from "./api";
import {literalText} from "./html";

// What a "who uses" row and its group show, without the DOM or the catalog.

/**
 * How rows are grouped: by the routine they are in (their container), or by the program (the package, standalone
 * routine, view, trigger or type whose unit they are in). Either way a script is grouped by its unit and file.
 */
export type UsesGrouping = "routine" | "program";

/** What a "who uses" list shows around its rows. */
export interface UsesContext {
  grouping?: UsesGrouping;
  /** The entity whose page lists the rows; a row that hits one of its members names that member. */
  page?: { key: string; kind: string; display: string };
  /** A listing (the Uses page): each row names the entity it hit. */
  listing?: boolean;
}

/**
 * The display a group's program is shortened against: the page's entity when the program is one of its members (a
 * routine of the page's own package or type, in its spec or body), so `GET_STR(…)` drops `PKG.`; else its schema.
 */
export function programWithin(program: { key: string; display: string }, page?: { key: string; display: string }) {
  if (page && (program.key.startsWith(page.key + ".") || program.key.startsWith(page.key + "@BODY."))) return page.display;
  const dot = program.display.indexOf(".");
  return dot > 0 ? program.display.slice(0, dot) : null;
}

/** The group a row goes under: [program] heads it; null for a script, which the unit and the file name. */
export interface UsesGroup {
  key: string;
  program: EntitySummary | null;
}

/** The key of a spec or a body as one: a body key ends with `@BODY` (and maybe a collision's `#2`). */
const specKey = (key: string) => key.replace(/@BODY(?=#\d+$|$)/, "");

/**
 * The group of [row]: its program for [grouping] `program`, else its container. A script: by its file for `program`
 * (one group per script file), else by its unit and file.
 */
export function groupOf(row: OccurrenceRow, grouping: UsesGrouping = "routine"): UsesGroup {
  const program = grouping === "program" ? row.unit.entity ?? null : row.container ?? row.unit.entity ?? null;
  if (program) return { key: (grouping === "program" ? specKey(program.key) : program.key), program };
  return { key: "file:" + row.path + (grouping === "program" ? "" : "|" + row.unit.kind + " " + row.unit.name), program: null };
}

/**
 * The routine a row grouped by program sits in, when it is not the program itself (code of a package body outside
 * its routines is in the package): `GET_STR` in `FMT_TIME`.
 */
export function enclosingRoutine(row: OccurrenceRow): EntitySummary | null {
  const container = row.container;
  if (!container) return null;
  const program = row.unit.entity;
  return program && specKey(container.key) === specKey(program.key) ? null : container;
}

/**
 * How a row names what it hit: `member` the member of the page's package or type, `entity` the entity hit (a
 * listing), `written` the name as written (a hit through a view, or a candidate), `none` when the page's entity is
 * the hit and the group already says where.
 */
export function rowNaming(row: OccurrenceRow, context: UsesContext): "none" | "written" | "member" | "entity" {
  if (row.viaCandidate || row.viaView) return "written";
  if (context.listing) return row.matched ? "entity" : "written";
  const page = context.page;
  if (page && row.matched && ["PACKAGE", "TYPE"].includes(page.kind) && specKey(row.matched.key) !== specKey(page.key)) return "member";
  return "none";
}

/**
 * The statement kind a row shows in words, null when a role already says it (`SQL_SELECT` in a `SELECT`, a `DDL_*`
 * role in `DDL`) or when the row is the dynamic statement itself.
 */
export function shownStatement(row: OccurrenceRow): string | null {
  const kind = row.statementKind;
  if (!kind || row.status === "DYNAMIC") return null;
  if (row.roles.includes("SQL_" + kind)) return null;
  if (kind === "DDL" && row.roles.some(r => r.startsWith("DDL_"))) return null;
  return kind;
}

/** How many argument chips show before a `+N` chip; past [ARGUMENT_CUT] characters a value is cut. */
export const ARGUMENTS_SHOWN = 4;
export const ARGUMENT_CUT = 40;

/** One argument chip: the parameter's name (null when unknown), the value's text, and how it reads. */
export interface ArgumentChip {
  name: string | null;
  /** The value as shown: cut to [ARGUMENT_CUT] characters with `…`; empty for an expression. */
  text: string;
  /** The whole value, for the tooltip. */
  whole: string;
  look: "string" | "null" | "expression" | "plain";
}

export function argumentChip(argument: CallArgument): ArgumentChip {
  const name = argument.label ?? argument.parameter?.name ?? null;
  const kind = argument.valueKind;
  if (kind === "EXPRESSION") return { name, text: "", whole: "", look: "expression" };
  const whole = literalText(kind, argument.value) ?? argument.value ?? "";
  const text = whole.length > ARGUMENT_CUT ? whole.slice(0, ARGUMENT_CUT - 1) + "…" : whole;
  return { name, text, whole, look: kind === "STRING" ? "string" : kind === "NULL" ? "null" : "plain" };
}
