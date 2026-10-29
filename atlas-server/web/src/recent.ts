// The objects this viewer opened last, per project, kept in the browser. Storage may be missing, full or refused (a
// private window, a policy): every read and write gives up quietly, and the page goes on without the list.

/** An opened entity, as much as a link to it needs. */
export interface Opened {
  key: string;
  kind: string;
  display: string;
  name: string;
}

/** The most entities the list keeps. */
export const OPENED_LAST = 8;

/** The storage key of [project]'s list; the server without projects has its own. */
function storageKey(project: string | null) {
  return "atlas.openedLast" + (project === null ? "" : "." + project);
}

function isOpened(value: unknown): value is Opened {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.key === "string" && typeof v.kind === "string" && typeof v.display === "string" && typeof v.name === "string";
}

/** The browser's local storage, or null when the page may not use it. */
export function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** [project]'s list, the last opened first; empty when there is none or it cannot be read. */
export function openedLast(storage: Storage | null, project: string | null): Opened[] {
  try {
    const text = storage?.getItem(storageKey(project));
    if (!text) return [];
    const list: unknown = JSON.parse(text);
    return Array.isArray(list) ? list.filter(isOpened).slice(0, OPENED_LAST) : [];
  } catch {
    return [];
  }
}

/** Puts [entity] first in [project]'s list, once, keeping at most [OPENED_LAST]. */
export function recordOpened(storage: Storage | null, project: string | null, entity: Opened) {
  try {
    if (!storage) return;
    const { key, kind, display, name } = entity;
    const list = [{ key, kind, display, name }, ...openedLast(storage, project).filter(o => o.key !== key)].slice(0, OPENED_LAST);
    storage.setItem(storageKey(project), JSON.stringify(list));
  } catch {
    // Full or refused: the list stays as it was.
  }
}
