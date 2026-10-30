// ---- i18n ---------------------------------------------------------------------------------------------------------

import type {Catalog, Params} from "./i18n-format";
import {formatMessage} from "./i18n-format";

export type { Catalog, Message } from "./i18n-format";

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
const lists = new Intl.ListFormat(LOCALE, { type: "conjunction" });
const dates = new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium", timeStyle: "medium" });
const shortDates = new Intl.DateTimeFormat(LOCALE, { dateStyle: "short", timeStyle: "short" });

export function formatNumber(value: number) {
  return numbers.format(value);
}

export function formatDate(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : dates.format(date);
}

/** A date and time in few characters, for the top bar. */
export function formatShortDate(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : shortDates.format(date);
}

export type { Params };

/**
 * The message [key] in the chosen locale, else English, else the key itself. Placeholders {name} are filled from
 * [params] (numbers are formatted); `count` selects the exact form (`=0`) or else the plural category. The result is plain text: escape it for HTML.
 */
export function t(key: string, params?: Params) {
  return formatMessage(CATALOGS[LOCALE]?.[key] ?? CATALOGS[FALLBACK]?.[key] ?? key, key, params ?? {}, plurals, numbers);
}

/** [items] as one list in the chosen locale: `a, b, and c`. */
export function formatList(items: string[]) {
  return lists.format(items);
}

/**
 * Loads the catalogs (English always, as the fallback), fills the static `data-i18n` text, and makes the links marked
 * `data-keep-lang` keep the page's `?lang=`.
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
  if (lang) {
    for (const link of document.querySelectorAll<HTMLAnchorElement>("a[data-keep-lang]")) link.href += (link.href.includes("?") ? "&" : "?") + "lang=" + encodeURIComponent(lang);
  }
}
