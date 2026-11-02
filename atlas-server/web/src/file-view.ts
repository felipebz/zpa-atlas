import type {EntitySummary, FileObject, FileOccurrence} from "./api";
import {CODE_COLORS} from "./api";
import {getOrPut} from "./html";
import type {NameShape} from "./names";
import {listParameterDetail, shownNameText} from "./names";

// What the file page shows, without the DOM: the pieces of each line (names and colors) and the list of the file's
// objects.

/** A cut of one line: from, to (columns), and what it is: an index of [FileView.colors]'s kinds, or an occurrence list. */
type Cut = [number, number, number];

/** Cuts each span of [spans] (`[startLine, startCol, endLine, endCol, …]`) into one cut per line, keyed by line. */
function cutsByLine(lines: string[], spans: number[][], value: (span: number[], index: number) => number) {
  const cuts = new Map<number, Cut[]>();
  spans.forEach((span, index) => {
    const [startLine, startCol, endLine, endCol] = span;
    for (let line = startLine; line <= endLine && line <= lines.length; line++) {
      const from = line === startLine ? startCol : 0;
      const to = line === endLine ? endCol : lines[line - 1].length;
      if (to > from) getOrPut(cuts, line, () => []).push([from, to, value(span, index)]);
    }
  });
  return cuts;
}

/**
 * One segment of a line: from, to (columns), the occurrences whose name covers it (indexes into the file's
 * occurrences; empty when none) and its color (an index of [CODE_COLORS], or null).
 */
export interface Segment {
  from: number;
  to: number;
  occurrences: number[];
  color: number | null;
}

/**
 * The segments of every line that has a name span or a colored span: a span over several lines is cut per line, then
 * each line is split at every edge. A segment lists every occurrence that covers it (a qualified read covers a column
 * definition) and the color that covers it. Lines with neither are left out: they are plain text.
 */
export function segmentsByLine(lines: string[], occurrences: FileOccurrence[], colors: number[][] | null) {
  const names = cutsByLine(lines, occurrences.map(o => o.at), (_, index) => index);
  const colored = cutsByLine(lines, colors ?? [], span => span[4]);
  const result = new Map<number, Segment[]>();
  for (const line of new Set([...names.keys(), ...colored.keys()])) {
    const nameCuts = names.get(line) ?? [];
    const colorCuts = colored.get(line) ?? [];
    const edges = [...new Set([...nameCuts, ...colorCuts].flatMap(c => [c[0], c[1]]))].sort((a, b) => a - b);
    const segments: Segment[] = [];
    for (let i = 0; i + 1 < edges.length; i++) {
      const [from, to] = [edges[i], edges[i + 1]];
      const covers = (c: Cut) => c[0] <= from && c[1] >= to;
      const covering = nameCuts.filter(covers).map(c => c[2]);
      const color = colorCuts.find(covers)?.[2] ?? null;
      if (covering.length || color !== null) segments.push({ from, to, occurrences: covering, color: color !== null && color < CODE_COLORS.length ? color : null });
    }
    result.set(line, segments);
  }
  return result;
}

/** Whether an occurrence leads somewhere: an entity or ambiguous candidates. */
const linkable = (o: FileOccurrence) => o.entity !== null || o.candidates.length > 0;

/**
 * One segment's classes: its color (`tok-keyword`…), then, for a name, `occ link`, `occ link quiet` when none of its
 * linked occurrences is resolved, or `occ none` when none leads anywhere. The link classes come last and the
 * stylesheet gives them the stronger rule, so a keyword word the index links still reads as a link.
 */
export function segmentClass(segment: Segment, occurrences: FileOccurrence[]) {
  const color = segment.color === null ? "" : "tok-" + CODE_COLORS[segment.color];
  if (segment.occurrences.length === 0) return color;
  const linked = segment.occurrences.map(i => occurrences[i]).filter(linkable);
  const occ = linked.length === 0 ? "occ none" : linked.some(o => o.status === "RESOLVED") ? "occ link" : "occ link quiet";
  return color ? color + " " + occ : occ;
}

/** One entry of "In this file": an object (depth 0) or one of its members (depth 1). */
export interface OutlineEntry {
  entity: EntitySummary;
  line: number;
  depth: 0 | 1;
  /** How the name is shown: an object's without its schema, a member's without its object, overloads told apart. */
  shape: NameShape;
  /** The name as [shape] shows it, as text. */
  name: string;
  /** A body (of a package or a type): its entry says so. */
  body: boolean;
}

/** A name without its schema: `PKG.PROC(…)` of `HR.PKG.PROC(…)`. */
const withoutSchema = (entity: EntitySummary): NameShape => {
  const dot = entity.display.indexOf(".");
  return dot > 0 ? { within: { display: entity.display.slice(0, dot) } } : {};
};

/**
 * The entries of "In this file", in file order: each object, then the members it declares. A member's name is shown
 * relative to its object, with as much of its parameter list as tells the overloads of its object apart.
 */
export function outlineEntries(objects: FileObject[]): OutlineEntry[] {
  return objects.flatMap(object => {
    const headShape: NameShape = { ...withoutSchema(object.entity), parameters: "short" };
    const head: OutlineEntry = {
      entity: object.entity, line: object.line, depth: 0, shape: headShape, name: shownNameText(object.entity, headShape),
      body: object.unitKind === "PACKAGE_BODY" || object.unitKind === "TYPE_BODY"
    };
    const detail = listParameterDetail(object.members.map(m => m.entity), { relative: true });
    return [head, ...object.members.map((m): OutlineEntry => {
      const shape: NameShape = { relative: true, parameters: detail(m.entity) };
      return { entity: m.entity, line: m.line, depth: 1, shape, name: shownNameText(m.entity, shape), body: false };
    })].sort((a, b) => a.line - b.line || a.depth - b.depth);
  });
}

/** The index of the entry the reader is in at [line]: the last that starts at or before it; -1 above the first. */
export function currentEntry(entries: OutlineEntry[], line: number) {
  let current = -1;
  entries.forEach((entry, index) => {
    if (entry.line <= line) current = index;
  });
  return current;
}

/** The kinds a routine declares inside itself, shown by name alone in the routine's own file. */
const ROUTINE_MEMBER_KINDS = ["PARAMETER", "LOCAL_VARIABLE", "LOCAL_CONSTANT", "CURSOR", "TYPE"];

/** Whether [key] ends with a routine's parameter list (`…LOAD(P_ID IN NUMBER)@BODY`): the key of a routine. */
const isRoutineKey = (key: string) => /\)(@BODY)?(#\d+)?$/.test(key);

/** [key] and its last name apart: `…LOAD(P_ID IN NUMBER)@BODY.L_CODE` → `…LOAD(P_ID IN NUMBER)@BODY`, `L_CODE`. */
function lastName(key: string): [string, string] | null {
  const dot = key.lastIndexOf(".");
  return dot > 0 ? [key.slice(0, dot), key.slice(dot + 1)] : null;
}

/**
 * The name of [entity] within the routine that declares it, when it is declared inside one: a parameter, a local
 * variable, constant, cursor or type by its name (`L_CODE`), a field of a record type declared there by the type and
 * the field (`R_PAIR.A`); null for anything declared at the package or schema level, whose qualified name stays. Its
 * path says nothing to the reader of that routine. The routine is known by its key, which ends with its parameter list.
 */
export function routineMemberName(entity: EntitySummary): string | null {
  const own = lastName(entity.key);
  if (own === null) return null;
  if (ROUTINE_MEMBER_KINDS.includes(entity.kind)) return isRoutineKey(own[0]) ? entity.name : null;
  if (entity.kind !== "RECORD_FIELD") return null;
  const type = lastName(own[0]);
  return type !== null && isRoutineKey(type[0]) ? type[1] + "." + entity.name : null;
}

/**
 * The occurrence of [indexes] (those a clicked piece of code covers) that is the name under the click: the one with an
 * entity whose span is the smallest (in `l_pair.a`, the field `A` rather than the record `L_PAIR`); null when none
 * has an entity.
 */
export function innermost(occurrences: FileOccurrence[], indexes: number[]): number | null {
  const size = (o: FileOccurrence) => (o.at[2] - o.at[0]) * 1_000_000 + (o.at[3] - o.at[1]);
  const linked = indexes.filter(i => occurrences[i].entity !== null);
  if (linked.length === 0) return null;
  return linked.reduce((best, i) => size(occurrences[i]) < size(occurrences[best]) ? i : best);
}

/** The occurrences of the file's entity [entity] (an index of [FileView.entities]), in reading order. */
export function occurrencesOf(occurrences: FileOccurrence[], entity: number): number[] {
  return occurrences.map((o, index) => ({ o, index })).filter(({ o }) => o.entity === entity)
    .sort((a, b) => a.o.at[0] - b.o.at[0] || a.o.at[1] - b.o.at[1]).map(({ index }) => index);
}
