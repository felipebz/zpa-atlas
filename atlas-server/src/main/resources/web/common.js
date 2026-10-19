"use strict";

// ---- i18n ---------------------------------------------------------------------------------------------------------

/** Shipped languages; adding one means adding i18n/<lang>.js and listing it here. */
const LOCALES = ["en", "pt"];
const FALLBACK = "en";
const CATALOGS = {};

function registerMessages(lang, messages) {
  CATALOGS[lang] = messages;
}

/** ?lang=, else the browser language (or its base), else English; only shipped languages. */
const LOCALE = (() => {
  const wanted = [new URLSearchParams(location.search).get("lang"), navigator.language].filter(Boolean);
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

function formatNumber(value) {
  return numbers.format(value);
}

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : dates.format(date);
}

/**
 * The message [key] in the chosen locale, else English, else the key itself. Placeholders {name} are filled from
 * [params] (numbers are formatted); `count` selects the exact form (`=0`) or else the plural category. The result is plain text: escape it for HTML.
 */
function t(key, params) {
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

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = src;
    script.onload = resolve;
    script.onerror = reject;
    document.head.appendChild(script);
  });
}

/**
 * Loads the catalogs (English always, as the fallback), fills the static `data-i18n` text, and makes the header
 * search keep the page's `?lang=`.
 */
async function initI18n() {
  for (const lang of new Set([FALLBACK, LOCALE])) {
    if (!CATALOGS[lang]) await loadScript(`i18n/${lang}.js`);
  }
  for (const element of document.querySelectorAll("[data-i18n]")) element.textContent = t(element.dataset.i18n);
  for (const element of document.querySelectorAll("[data-i18n-placeholder]")) element.placeholder = t(element.dataset.i18nPlaceholder);
  const lang = new URLSearchParams(location.search).get("lang");
  for (const input of document.querySelectorAll("form.find input[name=lang]")) {
    if (lang) input.value = lang;
    else input.remove();
  }
}

// ---- API and rendering helpers ------------------------------------------------------------------------------------

async function api(path, params) {
  const url = new URL(path, window.location.href);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== null && v !== undefined && v !== "") url.searchParams.set(k, v);
  }
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || response.statusText);
  return body;
}

function esc(text) {
  return String(text ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** `&lang=…` when the page was opened with one, so links keep the language. */
function langSuffix() {
  const lang = new URLSearchParams(location.search).get("lang");
  return lang ? `&lang=${encodeURIComponent(lang)}` : "";
}

// ---- entity names ---------------------------------------------------------------------------------------------------

/** A space after each comma of a parameter list (`(P_A IN NUMBER, P_B IN DATE)`), never inside a quoted identifier. */
function spacedSignature(text) {
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
function parameterNamesOnly(text) {
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
function splitTopLevel(text) {
  const items = [];
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
function parameterParts(item) {
  let rest = item.trim();
  let name;
  if (rest.startsWith('"')) {
    const close = rest.indexOf('"', 1);
    name = close < 0 ? rest : rest.slice(0, close + 1);
    rest = close < 0 ? "" : rest.slice(close + 1);
  } else {
    name = rest.split(/\s+/)[0];
    rest = rest.slice(name.length);
  }
  const tokens = rest.split(/\s+/).filter(Boolean);
  let mode = "";
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
function displayParts(display, name) {
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
  let base = text, params = null;
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

function firstOpenParenthesis(text) {
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') quoted = !quoted;
    else if (!quoted && text[i] === "(") return i;
  }
  return -1;
}

function matchingParenthesis(text, open) {
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
function qualifierHtml(text) {
  return esc(text).replace(/\./g, ".<wbr>");
}

/**
 * An entity's name as HTML, with the full display as its tooltip: the qualifier muted with parameter names only
 * (left out with [relative], under its parent), the name, its signature, and a "body" badge for a body or a member
 * of a body (left out with [noBadge] for the entity itself, where the list already says spec or body). [signature]
 * is `"names"` (`RUN_JOB(P_A, P_B)`, the default: overloads stay apart) or `"full"` (every mode and type).
 */
function nameHtml(entity, options) {
  const parts = displayParts(entity.display, entity.name);
  const qualifier = parts.shortQualifier && !options?.relative ? `<span class="qualifier">${qualifierHtml(parts.shortQualifier)}</span>` : "";
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
function nameNodes(entity, options) {
  const holder = document.createElement("span");
  holder.innerHTML = nameHtml(entity, options);
  return holder;
}

function entityHref(key) {
  return `entity.html?key=${encodeURIComponent(key)}${langSuffix()}`;
}

function entityLink(entity, options) {
  if (!entity) return '<span class="muted">—</span>';
  const ext = entity.external ? ` <span class="badge external">${esc(t("entity.external"))}</span>` : "";
  return `<a href="${entityHref(entity.key)}">${nameHtml(entity, options)}</a>${ext}`;
}

/** The key that groups a spec with its body: the key without `@BODY`. */
function parentKeyOf(key) {
  return key.replace(/@BODY(?=#\d+$|$)/, "");
}

/** A table that scrolls on its own when it is wider than the page. */
function scrollTable(inner, className) {
  return `<div class="scroll"><table${className ? ` class="${className}"` : ""}>${inner}</table></div>`;
}

/** `line:col`, linking the whole of [path] at [line]. */
function lineLink(path, line, col) {
  return `<a class="mono" href="file.html?path=${encodeURIComponent(path)}&line=${line}${langSuffix()}#L${line}">${line}:${col}</a>`;
}

/** A literal as it would be written: strings quoted, the others as stored; null for a name, a bind or an expression. */
function literalText(kind, value) {
  switch (kind) {
    case "STRING": return "'" + value.replace(/'/g, "''") + "'";
    case "NUMBER": case "BOOLEAN": return value;
    case "NULL": return "NULL";
    case "DEFAULT": return "DEFAULT";
    default: return null;
  }
}

/** The arguments of a call as `NAME => VALUE`; past four, the rest behind a summary that opens. */
function argumentsHtml(args) {
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
function statusBadge(status, reason) {
  return `<span class="badge st-${esc(status)}">${esc(status)}${reason && reason !== status ? " · " + esc(reason) : ""}</span>`;
}

/** A catalog label (`<prefix>.<NAME>`) when the catalog has one, else the stored name. */
function storedLabel(prefix, name) {
  const key = prefix + "." + name;
  const label = t(key);
  return label === key ? name : label;
}

/** A role as shown. */
const roleLabel = role => storedLabel("role", role);

/** An entity kind as shown. */
const kindLabel = kind => storedLabel("kind", kind);

/** Fills the run line and the parse warning; returns the run summary, or null when the index is unavailable. */
async function showBanner() {
  const header = document.querySelector("header.top .run");
  const warn = document.getElementById("warn");
  try {
    const answer = await api("api/run");
    const run = answer.run;
    header.textContent = t("banner.run", {
      date: formatDate(run.finishedAt),
      schema: run.compilationSchema ?? t("banner.none")
    });
    header.title = t("banner.metadata", { metadata: run.metadataOrigin ?? t("banner.none") });
    const byStatus = Object.fromEntries(answer.result.filesByParseStatus.map(c => [c.value, c.count]));
    const problems = [];
    if (byStatus.FAILED) problems.push(t("banner.failed", { count: byStatus.FAILED }));
    if (byStatus.RECOVERED) problems.push(t("banner.recovered", { count: byStatus.RECOVERED }));
    if (problems.length) {
      warn.textContent = t("banner.warning", { problems: problems.join(t("banner.separator")) });
      warn.hidden = false;
    }
    return answer.result;
  } catch (e) {
    header.textContent = t("banner.unavailable", { error: e.message });
    return null;
  }
}

async function sourceLine(path, line, target, context) {
  try {
    const answer = await api("api/source", { path, line, context: context ?? 0 });
    const s = answer.result;
    target.innerHTML = `<pre class="src">${s.lines.map(l =>
      `<span class="${l.number === line ? "hl" : ""}">${String(l.number).padStart(5)}  ${esc(l.text)}</span>`).join("\n")}</pre>` +
      (s.stale ? `<div class="muted">${esc(t("source.stale"))}</div>` : "");
  } catch (e) {
    target.innerHTML = `<span class="muted">${esc(t("source.unavailable", { error: e.message }))}</span>`;
  }
}
