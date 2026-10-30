// How a catalog message becomes text, without the page: what `t` does once it has the message and the locale.

/** A message: plain text, or one text per plural category (and exact forms such as "=0"). */
export type Message = string | Record<string, string>;
export type Catalog = Record<string, Message>;

/** The values a message may fill in; `count` selects its plural form. */
export type Params = { count?: number; [name: string]: string | number | null | undefined };

/** A translator: the text of a catalog key with its values filled in. */
export type Say = (key: string, params?: Params) => string;

/**
 * [message] as text: `count` selects the exact form (`=0`) or else the plural category; placeholders {name} are filled
 * from [params], numbers formatted. A placeholder without a value stays as written; [key] stands for a missing form.
 */
export function formatMessage(message: Message, key: string, params: Params, plurals: Intl.PluralRules, numbers: Intl.NumberFormat) {
  if (typeof message === "object") {
    // An exact form ("=0") wins over the plural category (Portuguese "one" covers 0 and 1).
    const count = params.count ?? 0;
    message = message["=" + count] ?? message[plurals.select(count)] ?? message.other ?? key;
  }
  return message.replace(/\{(\w+)\}/g, (match, name) => {
    const value = params[name];
    if (value === undefined || value === null) return match;
    return typeof value === "number" ? numbers.format(value) : String(value);
  });
}

/** A translator over [catalog] in [locale], for code that runs without the page (tests). */
export function sayIn(catalog: Catalog, locale: string): Say {
  const plurals = new Intl.PluralRules(locale);
  const numbers = new Intl.NumberFormat(locale);
  return (key, params) => formatMessage(catalog[key] ?? key, key, params ?? {}, plurals, numbers);
}
