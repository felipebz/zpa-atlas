const HTML_ESCAPES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

export function esc(text: unknown) {
  return String(text ?? "").replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
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

/** The line number, linking the whole of [path] at [line]; [title] is its tooltip (the column, in words). */
export function lineLink(path: string, line: number, title: string) {
  return `<a class="mono" href="file.html?path=${encodeURIComponent(path)}&line=${line}${langSuffix()}#L${line}" title="${esc(title)}">${line}</a>`;
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
