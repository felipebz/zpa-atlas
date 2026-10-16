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

/** Loads the catalogs (English always, as the fallback) and fills the static `data-i18n` text. */
async function initI18n() {
  for (const lang of new Set([FALLBACK, LOCALE])) {
    if (!CATALOGS[lang]) await loadScript(`i18n/${lang}.js`);
  }
  for (const element of document.querySelectorAll("[data-i18n]")) element.textContent = t(element.dataset.i18n);
  for (const element of document.querySelectorAll("[data-i18n-placeholder]")) element.placeholder = t(element.dataset.i18nPlaceholder);
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

function entityLink(entity) {
  if (!entity) return '<span class="muted">—</span>';
  const ext = entity.external ? ` <span class="badge external">${esc(t("entity.external"))}</span>` : "";
  const lang = new URLSearchParams(location.search).get("lang");
  const suffix = lang ? `&lang=${encodeURIComponent(lang)}` : "";
  return `<a href="entity.html?key=${encodeURIComponent(entity.key)}${suffix}">${esc(entity.display)}</a>${ext}`;
}

/** Status and reason codes are data, shown as they are; the reason is omitted when it repeats the status. */
function statusBadge(status, reason) {
  return `<span class="badge st-${esc(status)}">${esc(status)}${reason && reason !== status ? " · " + esc(reason) : ""}</span>`;
}

async function showBanner() {
  const header = document.querySelector("header.top .run");
  const warn = document.getElementById("warn");
  try {
    const answer = await api("api/run");
    const run = answer.run;
    header.textContent = t("banner.run", {
      date: formatDate(run.finishedAt),
      schema: run.compilationSchema ?? t("banner.none"),
      metadata: run.metadataOrigin ?? t("banner.none")
    });
    const byStatus = Object.fromEntries(answer.result.filesByParseStatus.map(c => [c.value, c.count]));
    const problems = [];
    if (byStatus.FAILED) problems.push(t("banner.failed", { count: byStatus.FAILED }));
    if (byStatus.RECOVERED) problems.push(t("banner.recovered", { count: byStatus.RECOVERED }));
    if (problems.length) {
      warn.textContent = t("banner.warning", { problems: problems.join(t("banner.separator")) });
      warn.hidden = false;
    }
  } catch (e) {
    header.textContent = t("banner.unavailable", { error: e.message });
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
