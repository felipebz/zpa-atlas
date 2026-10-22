// The pure parts of the text search page: its parameters in the URL, and where a line matches the text.

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
