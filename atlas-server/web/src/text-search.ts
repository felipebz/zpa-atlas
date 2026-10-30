import type {EntitySummary} from "./api";
import {CODE_COLORS} from "./api";

// The pure parts of the text search page: its parameters in the URL, where a line matches the text, how a line is
// shown (aligned, colored, marked) and where its file's group and its container go.

/** The fewest characters the server searches: one trigram. */
export const MIN_TEXT = 3;

/** A search as the page URL holds it; a container is a key (`container`) or a typed dotted name (`containerName`). */
export interface TextParams {
  text?: string;
  path?: string;
  container?: string;
  containerName?: string;
}

const NAMES = ["text", "path", "container", "containerName"] as const;

/** The search in [search] (a location's `?…`), empty values left out. */
export function textParamsOf(search: string): TextParams {
  const query = new URLSearchParams(search);
  const params: TextParams = {};
  for (const name of NAMES) {
    const value = query.get(name);
    if (value) params[name] = value;
  }
  return params;
}

/** The page URL of [params], keeping [lang] when given. */
export function textHref(params: TextParams, lang: string | null): string {
  const query = new URLSearchParams();
  for (const name of NAMES) if (params[name]) query.set(name, params[name]!);
  if (lang) query.set("lang", lang);
  const text = query.toString();
  return "text.html" + (text ? "?" + text : "");
}

/** Whether [text] is long enough to search: the server counts characters, not UTF-16 units. */
export function longEnough(text: string): boolean {
  return [...text].length >= MIN_TEXT;
}

/**
 * Each code unit of [text] folded on its own, so that positions in the folded text are positions in [text]: a
 * character whose lower case has another length (`İ`) is kept as it is.
 */
function fold(text: string): string {
  let folded = "";
  for (const c of text) {
    const lower = c.toLowerCase();
    folded += lower.length === c.length ? lower : c;
  }
  return folded;
}

/**
 * Where [line] contains [text], compared without case as the server's trigram index folds it, as `[start, end)`
 * ranges of `line`, without overlaps; null when the line came back but the text cannot be placed in it, so the page
 * shows it unmarked rather than mark the wrong place.
 */
export function matchesIn(line: string, text: string): Array<[number, number]> | null {
  if (!text) return null;
  const haystack = fold(line);
  const needle = fold(text);
  const found: Array<[number, number]> = [];
  for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length)) {
    found.push([at, at + needle.length]);
  }
  return found.length ? found : null;
}

/** A piece of a shown line: from, to (columns of the shown text), its color (an index of [CODE_COLORS]) and the match. */
export interface LinePiece {
  from: number;
  to: number;
  color: number | null;
  mark: boolean;
}

/**
 * A found line as shown: without its leading whitespace, so every line starts at the same column, cut into pieces at
 * every edge of a color ([colors], `[startCol, endCol, color]` on the whole line) and of a match ([matches]). A match
 * or a color that starts in the left-out whitespace starts at the shown text's first character.
 */
export function shownLine(line: string, matches: Array<[number, number]> | null, colors: number[][] | null | undefined) {
  const indent = line.length - line.trimStart().length;
  const text = line.slice(indent);
  const shift = (at: number) => Math.min(Math.max(at - indent, 0), text.length);
  const marks = (matches ?? []).map(([from, to]) => [shift(from), shift(to)] as const).filter(([from, to]) => to > from);
  const colored = (colors ?? []).filter(c => c[2] < CODE_COLORS.length).map(c => [shift(c[0]), shift(c[1]), c[2]] as const).filter(([from, to]) => to > from);
  const edges = [...new Set([0, text.length, ...marks.flat(), ...colored.flatMap(c => [c[0], c[1]])])].sort((a, b) => a - b);
  const pieces: LinePiece[] = [];
  for (let i = 0; i + 1 < edges.length; i++) {
    const [from, to] = [edges[i], edges[i + 1]];
    const covers = (c: readonly number[]) => c[0] <= from && c[1] >= to;
    pieces.push({ from, to, color: colored.find(covers)?.[2] ?? null, mark: marks.some(covers) });
  }
  return { text, pieces };
}

/** Where a row goes: under a new file header or the one before, and the container shown above it, if any. */
export interface RowPlace {
  newGroup: boolean;
  /** The container to show above the row: the first row of a file, or one whose container is not the row before's. */
  container: EntitySummary | null;
}

/**
 * Places the rows of one page after those already shown: [shown] holds, for each file already headed, the container
 * key of its last row (null when it had none), and is updated. A file whose rows continue from the page before keeps
 * its header; a run of rows in one container shows the container once.
 */
export function placeRows(rows: { path: string; entity: EntitySummary | null }[], shown: Map<string, string | null>): RowPlace[] {
  return rows.map(row => {
    const newGroup = !shown.has(row.path);
    const key = row.entity?.key ?? null;
    const container = row.entity && (newGroup || shown.get(row.path) !== key) ? row.entity : null;
    shown.set(row.path, key);
    return { newGroup, container };
  });
}
