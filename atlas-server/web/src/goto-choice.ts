// What Enter in "Go to object…" does. The list on screen belongs to the text it was asked for, which may be older than
// the text the field holds now (a paste, or a key typed before the suggestions came in): Enter acts on the field's text
// and never opens an entry listed for another one.

/** The list on screen: the text it was listed for (null before any), the place each entry opens, and the chosen one. */
export interface Listed {
  text: string | null;
  hrefs: string[];
  active: number;
}

/** Enter's outcome: open [href] now, or wait for the suggestions of [text] and open the first (or the Objects page). */
export type EnterAction = { open: string } | { wait: string } | null;

/** What Enter does when the field holds [text] and [listed] is on screen. */
export function enterAction(text: string, listed: Listed): EnterAction {
  if (listed.text === text) {
    const href = listed.hrefs[listed.active];
    return href !== undefined ? { open: href } : text ? { wait: text } : null;
  }
  return text ? { wait: text } : null;
}

/** Where Enter goes once the suggestions of a text are in: the first entity, else the Objects page with the text. */
export function firstOrObjects(entityHrefs: string[], objectsHref: string) {
  return entityHrefs[0] ?? objectsHref;
}
