import type {CodeAt} from "./api";
import type {CodeUnit} from "./forms-view";
import {codeUnitName} from "./forms-view";

const HTML_ESCAPES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

export function esc(text: unknown) {
  return String(text ?? "").replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
}

/**
 * [text] with each mark `\u0001<i>\u0001` replaced by `html[i]` as it is: a `$'` or `$&` in the HTML (a regular
 * expression written in code) is not a replacement pattern.
 */
export function fillMarks(text: string, html: string[]) {
  return text.replace(/\u0001(\d+)\u0001/g, (_, i: string) => html[Number(i)]);
}

/** The value of [key] in [map], put there first when it is missing. */
export function getOrPut<K, V>(map: Map<K, V>, key: K, create: () => V): V {
  let value = map.get(key);
  if (value === undefined) {
    value = create();
    map.set(key, value);
  }
  return value;
}

/** The query page with [params] (empty values left out), keeping the language. */
export function queryHref(params: Record<string, string | null | undefined>) {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== "") search.set(k, v);
  const lang = new URLSearchParams(location.search).get("lang");
  if (lang) search.set("lang", lang);
  const text = search.toString();
  return "query.html" + (text ? "?" + text : "");
}

/** `&lang=…` when the page was opened with one, so links keep the language. */
export function langSuffix() {
  const lang = new URLSearchParams(location.search).get("lang");
  return lang ? `&lang=${encodeURIComponent(lang)}` : "";
}

/** A qualifier as HTML that may break after each dot, never inside a name. */
export function qualifierHtml(text: string) {
  return esc(text).replace(/\./g, ".<wbr>");
}

/**
 * A qualified name as HTML that may break only between its parts: after a dot, a comma or an opening parenthesis (and
 * at its spaces), never inside an identifier; with the `parts` class, a part breaks only when it is longer than a line.
 */
export function partBreaksHtml(text: string) {
  return esc(text).replace(/([.,(])/g, "$1<wbr>");
}

/** A name as HTML that may break after each underscore or dot, never inside a word (`V$LOCK` stays whole). */
export function nameBreaksHtml(text: string) {
  return esc(text).replace(/([_.])/g, "$1<wbr>");
}

/**
 * Code as HTML that may break at its spaces (as any text) and after a comma, a dot, a percent sign or a run of
 * operator characters (`||`, `+`, `=`, `<`…) followed by more code; never inside a word, a number or a string literal.
 * A minus that starts a number is kept with it, even where the page breaks inside words.
 */
export function codeBreaksHtml(text: string) {
  const breakAfter = ",.%|+*/=<>-";
  const digit = (c: string | undefined) => c !== undefined && c >= "0" && c <= "9";
  let html = "";
  let inLiteral = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    const previous = text[i - 1];
    if (!inLiteral && c === "-" && digit(text[i + 1]) && !(previous !== undefined && /[\w)]/.test(previous))) {
      let end = i + 1;
      while (end < text.length && (digit(text[end]) || text[end] === ".")) end++;
      html += `<span class="nobr">${esc(text.slice(i, end))}</span>`;
      i = end - 1;
      continue;
    }
    html += esc(c);
    if (c === "'") inLiteral = !inLiteral;
    const next = text[i + 1];
    const inNumber = c === "." && digit(previous) && digit(next);
    const startsNumber = next === "-" && digit(text[i + 2]);
    if (!inLiteral && !inNumber && breakAfter.includes(c) && next !== undefined && next.trim() !== "" && (!breakAfter.includes(next) || startsNumber)) html += "<wbr>";
  }
  return html;
}

/** The impact page of [key], keeping the language. */
export function impactHref(key: string) {
  return `impact.html?key=${encodeURIComponent(key)}${langSuffix()}`;
}

export function entityHref(key: string) {
  return `entity.html?key=${encodeURIComponent(key)}${langSuffix()}`;
}

/** A table that scrolls on its own when it is wider than the page. */
export function scrollTable(inner: string, className?: string) {
  return `<div class="scroll"><table${className ? ` class="${className}"` : ""}>${inner}</table></div>`;
}

/**
 * A cell of a table that stacks on a phone (`table.stack`): [inner] is its HTML; [label], when given, says what the
 * value is once the header row is gone (`depth 1`), shown before it on a phone only.
 */
export function stackCell(inner: string, label?: string | null, className?: string) {
  const attributes = (className ? ` class="${esc(className)}"` : "") + (label ? ` data-label="${esc(label)}"` : "");
  return `<td${attributes}>${inner}</td>`;
}

/**
 * The code page of the Forms module file [path] showing [unit] (null: the module's first piece of code), its [line]
 * marked (null: none): `file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY&line=78#L78`.
 */
export function codeHref(path: string, unit: CodeUnit | null, line: number | null) {
  let query = `path=${encodeURIComponent(path)}`;
  if (unit !== null) query += `&unit=${encodeURIComponent(unit.key)}` + (unit.unitKind ? `&unitKind=${encodeURIComponent(unit.unitKind)}` : "");
  if (line !== null) query += `&line=${line}`;
  return `file.html?${query}${langSuffix()}${line !== null ? `#L${line}` : ""}`;
}

/**
 * The page of the file [path]: for a Forms module, the code page of [unit] (the code a row is in, the module's first);
 * without one, the whole file, or a Forms module's first unit, which the page finds.
 */
export function fileHref(path: string, unit?: CodeUnit | null) {
  return unit ? codeHref(path, unit, null) : `file.html?path=${encodeURIComponent(path)}${langSuffix()}`;
}

/** Where a line leads: inside Forms code ([code]), the code page of its unit at its own line; else the whole of [path] at [line]. */
export function lineHref(path: string, line: number, code?: CodeAt | null) {
  return code ? codeHref(path, code, code.line) : `file.html?path=${encodeURIComponent(path)}&line=${line}${langSuffix()}#L${line}`;
}

/** A line's tooltip: its file and number (`pkg/pack_orders.pkb:59`), inside Forms code its file, unit and own line (`demo0004_fmb.xml, PK_ACCORDION:3`). */
export function lineTitle(path: string, line: number, code?: CodeAt | null) {
  return code ? `${path}, ${codeUnitName(code.key)}:${code.line}` : `${path}:${line}`;
}

/**
 * The line number, linking the whole of [path] at [line], or inside Forms code ([code]) the code page of its unit at
 * its own line, the number shown; [title] is its tooltip (the column, in words).
 */
export function lineLink(path: string, line: number, title: string, code?: CodeAt | null) {
  return `<a class="mono" href="${lineHref(path, line, code)}" title="${esc(title)}">${code ? code.line : line}</a>`;
}

/** A literal as it would be written: strings quoted, the others as stored; null for a name, a bind or an expression. */
export function literalText(kind: string | null, value: string | null) {
  switch (kind) {
    case "STRING": return "'" + value!.replace(/'/g, "''") + "'";
    case "NUMBER": case "BOOLEAN": return value;
    case "NULL": return "NULL";
    case "DEFAULT": return "DEFAULT";
    default: return null;
  }
}
