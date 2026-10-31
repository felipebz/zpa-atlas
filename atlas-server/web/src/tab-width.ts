// How wide a tab shows in code, without the DOM: the project's default comes with the run, the reader's choice is kept
// in the browser per project and wins until the reader picks the default again. Storage may be missing or refused: a
// read then finds nothing and a write gives up, and the choice holds for the page only.

/** The widths a reader may pick, in spaces. */
export const TAB_WIDTHS = [2, 4, 8];

/** The width when neither the project nor the reader gives one. */
export const DEFAULT_TAB_WIDTH = 8;

/** The storage key of [project]'s choice; the server without projects has its own. */
function storageKey(project: string | null) {
  return "atlas.tabWidth" + (project === null ? "" : "." + project);
}

const valid = (width: unknown): number | null => typeof width === "number" && TAB_WIDTHS.includes(width) ? width : null;

/** The reader's choice for [project], or null when there is none, it is not a width, or storage cannot be read. */
export function storedTabWidth(storage: Storage | null, project: string | null): number | null {
  try {
    const text = storage?.getItem(storageKey(project));
    return text ? valid(Number(text)) : null;
  } catch {
    return null;
  }
}

/**
 * Keeps [width] as [project]'s choice; picking the project's default ([projectDefault]) forgets the choice, so a later
 * change of the default reaches this reader. False when storage refused it.
 */
export function storeTabWidth(storage: Storage | null, project: string | null, width: number, projectDefault: number): boolean {
  try {
    if (!storage) return false;
    if (width === projectDefault) storage.removeItem(storageKey(project));
    else storage.setItem(storageKey(project), String(width));
    return true;
  } catch {
    return false;
  }
}

/** The width shown: the reader's [choice], else the project's default, else [DEFAULT_TAB_WIDTH]. */
export function tabWidthOf(choice: number | null, projectDefault: number | null | undefined): number {
  return valid(choice) ?? valid(projectDefault) ?? DEFAULT_TAB_WIDTH;
}

/** The widest of [lines] in columns, a tab reaching the next multiple of [tabWidth]: the width of the code. */
export function widestLine(lines: string[], tabWidth: number) {
  let widest = 0;
  for (const line of lines) {
    let column = 0;
    for (const c of line) column = c === "\t" ? column + tabWidth - (column % tabWidth) : column + 1;
    widest = Math.max(widest, column);
  }
  return widest;
}
