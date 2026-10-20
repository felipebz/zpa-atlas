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
