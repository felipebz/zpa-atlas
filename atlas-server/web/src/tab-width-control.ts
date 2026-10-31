// The page's tab width: what CSS shows code with (`--tab-width`), the project's default from the run, the reader's
// choice, and the small "Tab width: 2 · 4 · 8" control of the pages that show code.

import {esc} from "./html";
import {t} from "./i18n";
import {currentProject} from "./project";
import {browserStorage} from "./recent";
import {DEFAULT_TAB_WIDTH, storedTabWidth, storeTabWidth, TAB_WIDTHS, tabWidthOf} from "./tab-width";

let projectDefault = DEFAULT_TAB_WIDTH;
/** The reader's choice: read once from storage, then kept here, so that it holds for the page when storage refuses it. */
let choice: number | null | undefined;
const listeners: Array<() => void> = [];

function readChoice() {
  if (choice === undefined) choice = storedTabWidth(browserStorage(), currentProject());
  return choice;
}

/** The width code is shown with now. */
export function tabWidth() {
  return tabWidthOf(readChoice(), projectDefault);
}

function apply() {
  document.documentElement.style.setProperty("--tab-width", String(tabWidth()));
  for (const listener of listeners) listener();
}

/** Calls [listener] whenever the width changes. */
export function onTabWidth(listener: () => void) {
  listeners.push(listener);
}

/** The project's default, from the run's answer; the reader's choice still wins. */
export function setProjectTabWidth(width: number | undefined) {
  projectDefault = tabWidthOf(null, width);
  apply();
}

/** The reader picks [width]: kept in the browser for the project, or for this page when storage refuses it. */
export function pickTabWidth(width: number) {
  choice = width === projectDefault ? null : width;
  storeTabWidth(browserStorage(), currentProject(), width, projectDefault);
  apply();
}

/** "Tab width: 2 · 4 · 8", the width shown marked and the project's default named in its tooltip. */
export function tabWidthControl(): HTMLElement {
  const control = document.createElement("span");
  control.className = "tab-width muted";
  control.setAttribute("role", "group");
  const render = () => {
    const shown = tabWidth();
    control.setAttribute("aria-label", t("tab.width"));
    control.innerHTML = `${esc(t("tab.width"))} ` + TAB_WIDTHS.map(width =>
      `<button type="button" data-width="${width}" aria-pressed="${width === shown}"${width === projectDefault ? ` title="${esc(t("tab.default"))}"` : ""}>${width}</button>`)
      .join(` <span aria-hidden="true">·</span> `);
  };
  control.addEventListener("click", e => {
    const button = (e.target as Element).closest<HTMLButtonElement>("button[data-width]");
    if (button) pickTabWidth(Number(button.dataset.width));
  });
  onTabWidth(render);
  render();
  return control;
}

// The stored choice shows from the first paint, before the run's answer brings the project's default.
document.documentElement.style.setProperty("--tab-width", String(tabWidth()));
