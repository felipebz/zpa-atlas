// ---- i18n ---------------------------------------------------------------------------------------------------------

/** A message: plain text, or one text per plural category (and exact forms such as "=0"). */
export type Message = string | Record<string, string>;
export type Catalog = Record<string, Message>;

/** Shipped languages; adding one means adding i18n/<lang>.ts and listing it here. */
const LOCALES = ["en", "pt"];
const FALLBACK = "en";
const CATALOGS: Record<string, Catalog> = {};
const CATALOG_LOADERS = import.meta.glob<{ default: Catalog }>("./i18n/*.ts");

/** ?lang=, else the browser language (or its base), else English; only shipped languages. */
const LOCALE = (() => {
  const wanted = [new URLSearchParams(location.search).get("lang"), navigator.language].filter((candidate): candidate is string => Boolean(candidate));
  for (const candidate of wanted) {
    if (LOCALES.includes(candidate)) return candidate;
    const base = candidate.split("-")[0];
    if (LOCALES.includes(base)) return base;
  }
  return FALLBACK;
})();
document.documentElement.lang = LOCALE;

const plurals = new Intl.PluralRules(LOCALE);
const numbers = new Intl.NumberFormat(LOCALE);
const dates = new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium", timeStyle: "medium" });

export function formatNumber(value: number) {
  return numbers.format(value);
}

export function formatDate(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : dates.format(date);
}

/** The values a message may fill in; `count` selects its plural form. */
export type Params = { count?: number; [name: string]: string | number | null | undefined };

/**
 * The message [key] in the chosen locale, else English, else the key itself. Placeholders {name} are filled from
 * [params] (numbers are formatted); `count` selects the exact form (`=0`) or else the plural category. The result is plain text: escape it for HTML.
 */
export function t(key: string, params?: Params) {
  params = params || {};
  let message = CATALOGS[LOCALE]?.[key] ?? CATALOGS[FALLBACK]?.[key] ?? key;
  if (typeof message === "object") {
    // An exact form ("=0") wins over the plural category (Portuguese "one" covers 0 and 1).
    const count = params.count ?? 0;
    message = message["=" + count] ?? message[plurals.select(count)] ?? message.other ?? key;
  }
  return message.replace(/\{(\w+)\}/g, (match, name) => {
    const value = params[name];
    if (value === undefined || value === null) return match;
    return typeof value === "number" ? formatNumber(value) : String(value);
  });
}

/**
 * Loads the catalogs (English always, as the fallback), fills the static `data-i18n` text, and makes the header
 * search keep the page's `?lang=`.
 */
export async function initI18n() {
  for (const lang of new Set([FALLBACK, LOCALE])) {
    if (!CATALOGS[lang]) CATALOGS[lang] = (await CATALOG_LOADERS[`./i18n/${lang}.ts`]()).default;
  }
  for (const element of document.querySelectorAll<HTMLElement>("[data-i18n]")) element.textContent = t(element.dataset.i18n!);
  for (const element of document.querySelectorAll<HTMLInputElement>("[data-i18n-placeholder]")) element.placeholder = t(element.dataset.i18nPlaceholder!);
  for (const element of document.querySelectorAll<HTMLElement>("[data-i18n-title]")) {
    element.title = t(element.dataset.i18nTitle!);
    element.setAttribute("aria-label", element.title);
  }
  const lang = new URLSearchParams(location.search).get("lang");
  for (const input of document.querySelectorAll<HTMLInputElement>("form.find input[name=lang]")) {
    if (lang) input.value = lang;
    else input.remove();
  }
  if (lang) {
    for (const link of document.querySelectorAll<HTMLAnchorElement>("a[data-keep-lang]")) link.href += (link.href.includes("?") ? "&" : "?") + "lang=" + encodeURIComponent(lang);
  }
}

// ---- API and rendering helpers ------------------------------------------------------------------------------------

/** The JSON the API answers is whatever the caller says it is: [T] is the caller's description of the answer. */
export async function api<T>(path: string, params?: Record<string, string | number | boolean | null | undefined>): Promise<T> {
  const url = new URL(path, window.location.href);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== null && v !== undefined && v !== "") url.searchParams.set(k, String(v));
  }
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || response.statusText);
  return body;
}

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

/** The text of an error caught from a request or a parse. */
export function errorMessage(e: unknown) {
  return e instanceof Error ? e.message : String(e);
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

// ---- entity names ---------------------------------------------------------------------------------------------------

/** A space after each comma of a parameter list (`(P_A IN NUMBER, P_B IN DATE)`), never inside a quoted identifier. */
function spacedSignature(text: string) {
  let out = "", depth = 0, quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")") depth--;
    out += c;
    if (c === "," && !quoted && depth > 0 && text[i + 1] !== " ") out += " ";
  }
  return out;
}

/**
 * Every top-level `(...)` of [text] as its parameter names, outside quoted identifiers:
 * `PKG.PROC(P_A IN NUMBER, P_B IN DATE).` → `PKG.PROC(P_A, P_B).`, so overloads stay apart.
 */
function parameterNamesOnly(text: string) {
  let out = "", inside = "", depth = 0, quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    if (!quoted && c === "(" && depth++ === 0) continue;
    if (!quoted && c === ")" && depth > 0 && --depth === 0) {
      out += "(" + (inside.trim() === "" ? "" : splitTopLevel(inside).map(item => parameterParts(item).name).join(", ")) + ")";
      inside = "";
      continue;
    }
    if (depth > 0) inside += c;
    else out += c;
  }
  return out;
}

/** The parts of [text] split at its top-level commas, outside quoted identifiers and nested parentheses. */
function splitTopLevel(text: string) {
  const items: string[] = [];
  let current = "", depth = 0, quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")") depth--;
    if (c === "," && !quoted && depth === 0) {
      items.push(current);
      current = "";
    } else {
      current += c;
    }
  }
  items.push(current);
  return items;
}

/** One parameter of a signature, `P_A IN OUT NUMBER` or `"p a" IN DATE`: its name, mode and type. */
function parameterParts(item: string) {
  let rest = item.trim();
  let name: string;
  if (rest.startsWith('"')) {
    const close = rest.indexOf('"', 1);
    name = close < 0 ? rest : rest.slice(0, close + 1);
    rest = close < 0 ? "" : rest.slice(close + 1);
  } else {
    name = rest.split(/\s+/)[0];
    rest = rest.slice(name.length);
  }
  const tokens = rest.split(/\s+/).filter(Boolean);
  let mode: string | undefined = "";
  if (tokens[0] === "IN" && tokens[1] === "OUT") mode = tokens.splice(0, 2).join(" ");
  else if (tokens[0] === "IN" || tokens[0] === "OUT") mode = tokens.shift();
  return { name, mode, type: tokens.join(" ") };
}

/**
 * The parts of a [display] for reading, with the entity's own [name]:
 * - `qualifier`: before the name (`HR.PACK_PROCESS.`); `shortQualifier`: the same with parameter names only and
 *   without `@BODY` (`HR.PACK_PROCESS.RUN_JOB(P_A, P_B).`), `qualifierBody` telling whether it had one;
 * - `text`: the name with its signature (`RUN_JOB(P_A IN NUMBER, …)`, a key collision's `#2` kept);
 * - `base`: the name without its parameter list (`RUN_JOB#2`);
 * - `params`: the parameters (`{ name, mode, type }`), or null without a parameter list;
 * - `body`: whether it ends with `@BODY`, which is dropped from the text.
 * The name is looked for at a segment boundary only (a remote link may hold dots); without one, the whole display is
 * the text.
 */
export function displayParts(display: string, name?: string) {
  let text = display;
  let body = false;
  const bodyMatch = /@BODY(#\d+)?$/.exec(text);
  if (bodyMatch) {
    text = text.slice(0, bodyMatch.index) + (bodyMatch[1] ?? "");
    body = true;
  }
  let qualifier = "";
  if (name) {
    for (let at = text.lastIndexOf(name); at >= 0; at = at === 0 ? -1 : text.lastIndexOf(name, at - 1)) {
      const rest = text.slice(at + name.length);
      if ((at === 0 || text[at - 1] === ".") && (rest === "" || rest.startsWith("(") || rest.startsWith("#"))) {
        qualifier = text.slice(0, at);
        text = text.slice(at);
        break;
      }
    }
  }
  let base = text, params: ReturnType<typeof parameterParts>[] | null = null;
  const open = firstOpenParenthesis(text);
  if (open >= 0) {
    const close = matchingParenthesis(text, open);
    const inside = text.slice(open + 1, close);
    base = text.slice(0, open) + text.slice(close + 1);
    params = inside.trim() === "" ? [] : splitTopLevel(inside).map(parameterParts);
  }
  const qualifierBody = /@BODY(?=[#.]|$)/.test(qualifier);
  const shortQualifier = parameterNamesOnly(qualifier.replace(/@BODY(?=[#.]|$)/g, ""));
  return { qualifier, shortQualifier, qualifierBody, text: spacedSignature(text), base, params, body };
}

function firstOpenParenthesis(text: string) {
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') quoted = !quoted;
    else if (!quoted && text[i] === "(") return i;
  }
  return -1;
}

function matchingParenthesis(text: string, open: number) {
  let depth = 0, quoted = false;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")" && --depth === 0) return i;
  }
  return text.length;
}

/** A qualifier as HTML that may break after each dot, never inside a name. */
export function qualifierHtml(text: string) {
  return esc(text).replace(/\./g, ".<wbr>");
}

/** An entity as every list shows it. */
export interface EntityRef {
  key: string;
  kind: string;
  display: string;
  name: string;
  external?: boolean;
}

export interface NameOptions {
  relative?: boolean;
  within?: EntityRef;
  noBadge?: boolean;
  signature?: string;
}

/**
 * An entity's name as HTML, with the full display as its tooltip: the qualifier muted with parameter names only
 * (left out with [relative], under its parent; with [within], an entity, only the part below that entity, or none for
 * the entity itself or its body), the name, its signature, and a "body" badge for a body or a member of a body (left
 * out with [noBadge] for the entity itself, where the list already says spec or body). [signature] is `"names"`
 * (`RUN_JOB(P_A, P_B)`, the default: overloads stay apart) or `"full"` (every mode and type).
 */
export function nameHtml(entity: EntityRef, options?: NameOptions) {
  const parts = displayParts(entity.display, entity.name);
  let shortQualifier = parts.shortQualifier;
  if (options?.within) {
    const withoutBody = (text: string) => text.replace(/@BODY(?=[#.]|$)/g, "");
    const base = withoutBody(options.within.display);
    const qualifier = withoutBody(parts.qualifier);
    if (withoutBody(entity.display) === base) shortQualifier = "";
    else if (qualifier.startsWith(base + ".")) shortQualifier = parameterNamesOnly(qualifier.slice(base.length + 1));
  }
  const qualifier = shortQualifier && !options?.relative ? `<span class="qualifier">${qualifierHtml(shortQualifier)}</span>` : "";
  let text;
  if (options?.signature === "full" || parts.params === null) {
    text = options?.signature === "full" ? parts.text : parts.base;
  } else {
    // A key collision's `#2` stays after the parameter list, where the display has it.
    const collision = /#\d+$/.exec(parts.base);
    const bare = collision ? parts.base.slice(0, collision.index) : parts.base;
    text = `${bare}(${parts.params.map(p => p.name).join(", ")})${collision ? collision[0] : ""}`;
  }
  const body = (parts.body && !options?.noBadge) || (parts.qualifierBody && !options?.relative);
  const badge = body ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<span class="ename" title="${esc(entity.display)}">${qualifier}<span class="name">${esc(text)}</span>${badge}</span>`;
}

/** The same rendering as DOM nodes, for pages that build their DOM from text nodes. */
export function nameNodes(entity: EntityRef, options?: NameOptions) {
  const holder = document.createElement("span");
  holder.innerHTML = nameHtml(entity, options);
  return holder;
}

export function entityHref(key: string) {
  return `entity.html?key=${encodeURIComponent(key)}${langSuffix()}`;
}

export function entityLink(entity: EntityRef | null | undefined, options?: NameOptions) {
  if (!entity) return '<span class="muted">—</span>';
  const ext = entity.external ? ` <span class="badge external">${esc(t("entity.external"))}</span>` : "";
  return `<a href="${entityHref(entity.key)}">${nameHtml(entity, options)}</a>${ext}`;
}

/** The key that groups a spec with its body: the key without `@BODY`. */
export function parentKeyOf(key: string) {
  return key.replace(/@BODY(?=#\d+$|$)/, "");
}

/** A table that scrolls on its own when it is wider than the page. */
export function scrollTable(inner: string, className?: string) {
  return `<div class="scroll"><table${className ? ` class="${className}"` : ""}>${inner}</table></div>`;
}

/** `line:col`, linking the whole of [path] at [line]. */
export function lineLink(path: string, line: number, col: number) {
  return `<a class="mono" href="file.html?path=${encodeURIComponent(path)}&line=${line}${langSuffix()}#L${line}">${line}:${col}</a>`;
}

/** A literal as it would be written: strings quoted, the others as stored; null for a name, a bind or an expression. */
export function literalText(kind: string, value: string) {
  switch (kind) {
    case "STRING": return "'" + value.replace(/'/g, "''") + "'";
    case "NUMBER": case "BOOLEAN": return value;
    case "NULL": return "NULL";
    case "DEFAULT": return "DEFAULT";
    default: return null;
  }
}

/** An argument of a call, as the index records it. */
export interface CallArgument {
  ordinal: number;
  label: string | null;
  parameter: EntityRef | null;
  valueKind: string;
  value: string;
}

/** The arguments of a call as `NAME => VALUE`; past four, the rest behind a summary that opens. */
function argumentsHtml(args: CallArgument[] | null | undefined) {
  if (!args || args.length === 0) return "";
  const items = args.map(a => {
    const name = a.label ?? a.parameter?.name ?? "#" + a.ordinal;
    const value = literalText(a.valueKind, a.value) ?? (a.valueKind === "NAME" || a.valueKind === "BIND" ? a.value : "…");
    return esc(name) + " =&gt; " + esc(value);
  });
  if (items.length <= 4) return `<div class="args mono muted">(${items.join(", ")})</div>`;
  return `<details class="args mono muted"><summary>(${items.slice(0, 4).join(", ")}, … +${esc(formatNumber(items.length - 4))})</summary>(${items.join(", ")})</details>`;
}

/** Status and reason codes are data, shown as they are; the reason is omitted when it repeats the status. */
export function statusBadge(status: string, reason?: string | null) {
  return `<span class="badge st-${esc(status)}">${esc(status)}${reason && reason !== status ? " · " + esc(reason) : ""}</span>`;
}

/** A catalog label (`<prefix>.<NAME>`) when the catalog has one, else the stored name. */
function storedLabel(prefix: string, name: string) {
  const key = prefix + "." + name;
  const label = t(key);
  return label === key ? name : label;
}

/** Every role the index stores. */
export const ROLES = ["DEFINITION", "DECLARATION", "READ", "WRITE", "CALL", "SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE", "SQL_MERGE",
  "SQL_LOCK", "TYPE_USE", "RAISE", "HANDLE", "IMPLICIT", "DDL_CREATE", "DDL_ALTER", "DDL_DROP", "DDL_TRUNCATE", "DDL_ANALYZE"];

/** The roles "who uses" lists by default: every role but where the entity is defined or declared. */
export const USAGE_ROLES = ROLES.filter(r => r !== "DEFINITION" && r !== "DECLARATION");

/** A role as shown. */
export const roleLabel = (role: string) => storedLabel("role", role);

/** An entity kind as shown. */
export const kindLabel = (kind: string) => storedLabel("kind", kind);

/** What a count query answers: a value and how many rows have it. */
export interface Count {
  value: string;
  count: number;
}

export interface RunSummary {
  filesByParseStatus: Count[];
  occurrencesByStatus: Count[];
}

/** Fills the run line and the parse warning; returns the run summary, or null when the index is unavailable. */
export async function showBanner() {
  const header = document.querySelector<HTMLElement>("header.top .run")!;
  const warn = document.getElementById("warn")!;
  try {
    const answer = await api<{
      run: { finishedAt: string; compilationSchema: string | null; metadataOrigin: string | null };
      result: RunSummary;
    }>("api/run");
    const run = answer.run;
    header.textContent = t("banner.run", {
      date: formatDate(run.finishedAt),
      schema: run.compilationSchema ?? t("banner.none")
    });
    header.title = t("banner.metadata", { metadata: run.metadataOrigin ?? t("banner.none") });
    const byStatus: Record<string, number> = Object.fromEntries(answer.result.filesByParseStatus.map(c => [c.value, c.count]));
    const problems = [];
    if (byStatus.FAILED) problems.push(t("banner.failed", { count: byStatus.FAILED }));
    if (byStatus.RECOVERED) problems.push(t("banner.recovered", { count: byStatus.RECOVERED }));
    if (problems.length) {
      warn.textContent = t("banner.warning", { problems: problems.join(t("banner.separator")) });
      warn.hidden = false;
    }
    return answer.result;
  } catch (e) {
    header.textContent = t("banner.unavailable", { error: errorMessage(e) });
    return null;
  }
}

export async function sourceLine(path: string, line: number, target: HTMLElement, context?: number) {
  try {
    const answer = await api<{ result: { lines: { number: number; text: string }[]; stale: boolean } }>("api/source", { path, line, context: context ?? 0 });
    const s = answer.result;
    target.innerHTML = `<pre class="src">${s.lines.map(l =>
      `<span class="${l.number === line ? "hl" : ""}">${String(l.number).padStart(5)}  ${esc(l.text)}</span>`).join("\n")}</pre>` +
      (s.stale ? `<div class="muted">${esc(t("source.stale"))}</div>` : "");
  } catch (e) {
    target.innerHTML = `<span class="muted">${esc(t("source.unavailable", { error: errorMessage(e) }))}</span>`;
  }
}

// ---- "who uses" rows, grouped by the program they are in ----------------------------------------------------------

/**
 * A "who uses" table: every row under a header naming the program it is in (its container, else its unit) and its
 * file. The groups and their columns are shared across [append] calls, so later pages fill the same groups. With
 * `options.entity`, each row also names the entity it hit.
 */
/** A row of "who uses": one occurrence, with the entity it hit. */
export interface OccurrenceRow {
  path: string;
  span: { startLine: number; startCol: number };
  written: string;
  roles: string[];
  status: string;
  reason: string | null;
  statementKind: string | null;
  unit: { kind: string; name: string };
  container: EntityRef | null;
  matched: EntityRef | null;
  viaView: boolean;
  viaCandidate: boolean;
  candidates: EntityRef[];
  arguments: CallArgument[];
  values: { valueKind: string; value: string }[];
}

/** A page of occurrences with the counts of the whole matching set. */
export interface OccurrencePage {
  rows: OccurrenceRow[];
  nextAfter: number | null;
  total: number;
  facets: Record<string, Count[]>;
}

export interface UsesBlock {
  element: HTMLDivElement;
  append(rows: OccurrenceRow[]): void;
}

export function usesBlock(options?: { entity?: boolean }): UsesBlock {
  const element = document.createElement("div");
  element.className = "scroll";
  element.hidden = true;
  element.innerHTML = `<table class="uses"><colgroup><col class="c-at"><col class="c-what"><col class="c-role"><col class="c-status"><col class="c-toggle"></colgroup></table>`;
  const table = element.firstElementChild!;
  const groups = new Map<string, HTMLTableSectionElement>();
  return {
    element,
    append(rows) {
      for (const row of rows) {
        const groupKey = row.path + "|" + (row.container?.key ?? row.unit.kind + " " + row.unit.name);
        let tbody = groups.get(groupKey);
        if (!tbody) {
          tbody = document.createElement("tbody");
          tbody.innerHTML = groupHeaderHtml(row);
          table.appendChild(tbody);
          groups.set(groupKey, tbody);
        }
        tbody.insertAdjacentHTML("beforeend", useRowHtml(row, options));
      }
      if (rows.length) element.hidden = false;
    }
  };
}

function groupHeaderHtml(row: OccurrenceRow) {
  const program = row.container ? entityLink(row.container) : `<span class="muted">${esc(row.unit.kind)} ${esc(row.unit.name)}</span>`;
  return `<tr class="group"><th colspan="5">${program} <span class="muted">·</span>
    <a class="mono muted" href="file.html?path=${encodeURIComponent(row.path)}${langSuffix()}">${esc(row.path)}</a></th></tr>`;
}

function useRowHtml(row: OccurrenceRow, options?: { entity?: boolean }) {
  // A column write with literal values: `= 'S'`, one per row of a multi-row insert.
  const literals = row.values.map(v => literalText(v.valueKind, v.value)).filter(v => v !== null);
  const written = literals.length === 0 ? "" : `<div class="muted mono">= ${esc(literals.join(", "))}</div>`;
  // The view column or view the row was reached through.
  const via = row.viaView ? `<div class="muted">${esc(t("usage.via"))} ${entityLink(row.matched)}</div>` : "";
  const hit = options?.entity && !row.viaCandidate ? `<div>${entityLink(row.matched)}</div>` : "";
  const others = row.viaCandidate
    ? `<div class="muted">${esc(t("usage.candidates"))} ${row.candidates.map(c => entityLink(c)).join(", ")}</div>` : "";
  const statement = row.statementKind && !row.roles.includes("SQL_" + row.statementKind)
    ? ` <span class="muted">${esc(row.statementKind)}</span>` : "";
  return `<tr data-path="${esc(row.path)}" data-line="${row.span.startLine}"><td>${lineLink(row.path, row.span.startLine, row.span.startCol)}</td>
    <td><span class="mono">${esc(row.written)}</span>${hit}${argumentsHtml(row.arguments)}${written}${via}${others}</td>
    <td>${esc(row.roles.map(roleLabel).join(", "))}${statement}</td>
    <td>${row.status === "RESOLVED" ? "" : statusBadge(row.status, row.reason)}</td>
    <td><button class="snip" title="${esc(t("source.show"))}">▸</button></td></tr>`;
}

// The source around a "who uses" row, opened under it and closed by the same button.
document.addEventListener("click", e => {
  const button = (e.target as Element).closest("button.snip");
  if (!button) return;
  const row = button.closest("tr")!;
  const next = row.nextElementSibling;
  if (next?.classList.contains("snippet")) {
    next.remove();
    button.textContent = "▸";
    return;
  }
  row.insertAdjacentHTML("afterend", `<tr class="snippet"><td colspan="5"><div></div></td></tr>`);
  sourceLine(row.dataset.path!, Number(row.dataset.line), row.nextElementSibling!.querySelector("div")!, 2);
  button.textContent = "▾";
});

/**
 * Facet chips into the element [id]: one per counted value, `data-<attribute>` holding the value, the [selected]
 * ones on.
 */
export function renderChips(id: string, counts: Count[], selected: Set<string>, attribute: string, label?: (value: string) => string) {
  const target = document.getElementById(id)!;
  // A single value filters nothing; it shows only when it is selected.
  if (counts.length <= 1 && selected.size === 0) {
    target.innerHTML = "";
    return;
  }
  // A selected value stays visible (count 0) even when the other filter leaves it out of the facet.
  const listed = new Set(counts.map(c => c.value));
  counts = counts.concat([...selected].filter(v => !listed.has(v)).map(v => ({ value: v, count: 0 })));
  target.innerHTML = counts.map(c =>
    `<span class="chip ${selected.has(c.value) ? "on" : ""}" data-${attribute}="${esc(c.value)}">${esc(label ? label(c.value) : c.value)} (${esc(formatNumber(c.count))})</span>`).join("");
}
