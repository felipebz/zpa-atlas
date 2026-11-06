import type {CallArgument, CodeAt, EntitySummary, OccurrenceRow} from "./api";
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
 * (one group per script file), else by its unit and file. A use Forms makes without code is in no unit: its block or
 * item heads its group either way.
 */
export function groupOf(row: OccurrenceRow, grouping: UsesGrouping = "routine"): UsesGroup {
  const unit = row.unit;
  const program = unit === null ? row.container : grouping === "program" ? unit.entity ?? null : row.container ?? unit.entity ?? null;
  if (program) return { key: (grouping === "program" ? specKey(program.key) : program.key), program };
  return { key: "file:" + row.path + (grouping === "program" || unit === null ? "" : "|" + unit.kind + " " + unit.name), program: null };
}

/**
 * The routine a row grouped by program sits in, when it is not the program itself (code of a package body outside
 * its routines is in the package): `GET_STR` in `FMT_TIME`. None for a use Forms makes without code: its block or
 * item is its group.
 */
export function enclosingRoutine(row: OccurrenceRow): EntitySummary | null {
  const container = row.container;
  if (!container || row.unit === null) return null;
  const program = row.unit.entity;
  return program && specKey(container.key) === specKey(program.key) ? null : container;
}

/**
 * The line a row is on, as its link and its tooltip say it: the line in the file, the one shown (inside Forms code
 * the unit's own, which [code] leads to) and the column; null for a use Forms makes without code, which is on no line
 * (no link, no snippet: its block or item heads its group, and the row says Forms made it).
 */
export function rowLine(row: Pick<OccurrenceRow, "span" | "code">): { line: number; shown: number; column: number; code: CodeAt | null } | null {
  if (row.span === null) return null;
  return { line: row.span.startLine, shown: row.code?.line ?? row.span.startLine, column: row.span.startCol, code: row.code ?? null };
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

/** A use's span: lines from 1, columns from 0, the end exclusive. */
export interface UseSpan {
  startLine: number;
  startCol: number;
  endLine: number;
  endCol: number;
}

/** The places a use marks on its line, `[startCol, endCol]`. */
export type UseMark = Array<[number, number]>;

/**
 * The place a use marks on [line], the line its span starts on as indexed: from `span.startCol` to the end of the
 * entity's own name when [named] says where that is (a chain's start, `p_img.color_tab`, marks `p_img`), else to
 * `span.endCol`, or to the end of the line when the span goes past it. None when the span does not fall on the line.
 */
export function useMark(span: UseSpan, line: string, named?: { written: string; length: number | null }): UseMark {
  let to = span.endLine > span.startLine ? line.length : Math.min(span.endCol, line.length);
  // The name's end, when the line still has the written name where the span starts.
  if (named && named.length !== null && line.startsWith(named.written.slice(0, named.length), span.startCol)) {
    to = Math.min(to, span.startCol + named.length);
  }
  return span.startCol < to ? [[span.startCol, to]] : [];
}

/**
 * How much of [written], a use as written, names the entity [name]: up to the end of the first dotted segment that is
 * the name, a qualifier before it included (`pkg.g_x.f` is `pkg.g_x` for `G_X`); null when no segment is the name or
 * the name is the last segment (then the span is the name already). A segment is compared as Oracle names it: a
 * double-quoted one as written, any other in upper case.
 */
export function nameLength(written: string, name: string | null | undefined): number | null {
  if (!name) return null;
  let at = 0;
  while (at < written.length) {
    // The segment's identifier: a quoted name, or the run of identifier characters.
    let end: number;
    let segment: string;
    if (written[at] === "\"") {
      const close = written.indexOf("\"", at + 1);
      if (close < 0) return null;
      end = close + 1;
      segment = written.slice(at + 1, close);
    } else {
      const match = /^[A-Za-z0-9_$#]*/.exec(written.slice(at))!;
      end = at + match[0].length;
      segment = match[0].toUpperCase();
    }
    if (segment === name) {
      const rest = written.slice(end);
      return /^\s*$/.test(rest) ? null : end;
    }
    // The next segment: after the next dot outside quotes and parentheses; none ends the name.
    let depth = 0;
    let next = -1;
    for (let i = end; i < written.length; i++) {
      const c = written[i];
      if (c === "(") depth++;
      else if (c === ")") depth--;
      else if (c === "." && depth === 0) { next = i + 1; break; }
    }
    if (next < 0) return null;
    at = next;
    while (at < written.length && /\s/.test(written[at])) at++;
  }
  return null;
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
