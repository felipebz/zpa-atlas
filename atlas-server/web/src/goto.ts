import type {Answer, SearchHit} from "./api";
import {api} from "./api";
import type {NameOptions} from "./entity-html";
import {kindBadge, listNameOptions, nameHtml} from "./entity-html";
import {enterAction, firstOrObjects} from "./goto-choice";
import {entityHref, esc, qualifierHtml} from "./html";
import {t} from "./i18n";
import {groupHits, shownName} from "./names";
import {currentProject} from "./project";
import {browserStorage, openedLast} from "./recent";

// "Go to object…": a dialog with one field over the entity search, opened from the top bar's button, Ctrl+K (⌘K on a
// Mac) or `/` outside a text field. Its list reads like the search results; with an empty field, the objects opened
// last. Enter opens the chosen one, Shift+Enter the Objects page with the text, Esc closes and gives the focus back.

/** The most hits the dialog asks for: a spec and its body count twice, so fewer rows show. */
const SUGGESTIONS = 20;

interface Item {
  href: string;
  html: string;
  /** The "see all results" line: no entity of its own. */
  all?: boolean;
}

let dialog: HTMLDialogElement | null = null;
let input: HTMLInputElement;
let list: HTMLUListElement;
let items: Item[] = [];
let active = 0;
let asked = 0;
let timer: number | undefined;
/** The text the list on screen was made for: "" for the objects opened last, null before any list. */
let listedFor: string | null = null;
/** The suggestions asked for the last text, shared by the list and by an Enter pressed before they came in. */
let fetched: { q: string; hits: Promise<SearchHit[]> } | null = null;
/** Counts the Enters that wait for suggestions: a later one, or closing the dialog, drops an earlier one. */
let entering = 0;
let opener: HTMLElement | null = null;

/** Wires the top bar's button and the keys that open the dialog. */
export function setupGoto() {
  const button = document.querySelector<HTMLButtonElement>("header.top .goto");
  if (!button) return;
  const mac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  button.querySelector("kbd")!.textContent = mac ? "⌘K" : "Ctrl K";
  button.setAttribute("aria-keyshortcuts", mac ? "Meta+K" : "Control+K");
  button.addEventListener("click", () => openGoto());
  document.addEventListener("keydown", e => {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === "k") {
      e.preventDefault();
      if (dialog?.open) dialog.close();
      else openGoto();
    } else if (e.key === "/" && !e.ctrlKey && !e.metaKey && !e.altKey && !typing(e.target) && !dialog?.open) {
      e.preventDefault();
      openGoto();
    }
  });
}

/** Whether [target] takes typed text, where `/` is a character and not a shortcut. */
function typing(target: EventTarget | null) {
  return target instanceof HTMLElement && (target.isContentEditable || target.closest("input, textarea, select") !== null);
}

function build() {
  const d = document.createElement("dialog");
  d.className = "goto-dialog";
  d.setAttribute("aria-label", t("goto.label"));
  d.innerHTML = `<input type="text" role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="goto-list"
      autocomplete="off" spellcheck="false" placeholder="${esc(t("goto.placeholder"))}" aria-label="${esc(t("goto.label"))}">
    <ul id="goto-list" role="listbox" aria-label="${esc(t("goto.label"))}"></ul>
    <div class="foot"><span>${esc(t("goto.keys"))}</span><span>${esc(t("goto.close"))}</span></div>`;
  document.body.append(d);
  input = d.querySelector("input")!;
  list = d.querySelector("ul")!;
  input.addEventListener("input", () => fill());
  input.addEventListener("keydown", key);
  list.addEventListener("click", e => {
    const li = (e.target as Element).closest<HTMLElement>("li[data-index]");
    if (li) go(items[Number(li.dataset.index)].href);
  });
  // A click on the backdrop lands on the dialog itself.
  d.addEventListener("click", e => { if (e.target === d) d.close(); });
  d.addEventListener("close", () => { clearTimeout(timer); asked++; entering++; opener?.focus(); });
  return d;
}

/** Opens the dialog with an empty field, remembering where the focus was. */
export function openGoto() {
  dialog ??= build();
  opener = document.activeElement instanceof HTMLElement && document.activeElement !== document.body ? document.activeElement : null;
  input.value = "";
  fill();
  dialog.showModal();
  input.focus();
}

function key(e: KeyboardEvent) {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    if (items.length) mark(Math.min(Math.max(active + (e.key === "ArrowDown" ? 1 : -1), 0), items.length - 1));
  } else if (e.key === "Enter") {
    e.preventDefault();
    const q = input.value.trim();
    if (e.shiftKey) { if (q) go(objectsHref(q)); return; }
    const action = enterAction(q, { text: listedFor, hrefs: items.map(item => item.href), active });
    if (action && "open" in action) go(action.open);
    else if (action) enterWhenListed(action.wait);
  }
}

/** Opens the first suggestion of [q], or the Objects page with it, once its suggestions are in. */
async function enterWhenListed(q: string) {
  const enter = ++entering;
  const groups = groupHits(await hitsFor(q));
  if (enter !== entering || !dialog?.open) return;
  go(firstOrObjects(groups.map(g => entityHref((g.spec ?? g.body)!.key)), objectsHref(q)));
}

/** The suggestions of [q]: the ones already asked for, or a new request. */
function hitsFor(q: string) {
  if (fetched?.q !== q) {
    fetched = { q, hits: api<Answer<SearchHit[]>>("api/entities", { q, limit: SUGGESTIONS }).then(answer => answer.result, () => []) };
  }
  return fetched.hits;
}

/** The Objects page searching [q], keeping the language. */
function objectsHref(q: string) {
  const params = new URLSearchParams({ q });
  const lang = new URLSearchParams(location.search).get("lang");
  if (lang) params.set("lang", lang);
  return "./?" + params;
}

function go(href: string) {
  opener = null;
  dialog?.close();
  location.assign(href);
}

/** An entry of the list: its kind as a square, its short name, and the program it sits in. */
function rowHtml(entity: { key: string; kind: string; display: string; name: string; external: boolean }, options: NameOptions | undefined, bodyOnly: boolean) {
  const container = shownName(entity.display, entity.name, options).qualifier.replace(/\.$/, "");
  return `${kindBadge(entity.kind, true)}<span class="goto-text"><span class="goto-name">${nameHtml(entity, { ...options, relative: true, noBadge: !bodyOnly })}</span>` +
    ` <span class="goto-in">${qualifierHtml(container)}</span></span>`;
}

function fill() {
  clearTimeout(timer);
  const q = input.value.trim();
  const ask = ++asked;
  if (!q) {
    const recent = openedLast(browserStorage(), currentProject());
    const options = listNameOptions(recent.map(o => ({ ...o, external: false })));
    items = recent.map(o => ({ href: entityHref(o.key), html: rowHtml({ ...o, external: false }, options({ ...o, external: false }), /@BODY(#\d+)?$/.test(o.key)) }));
    render("", recent.length ? `<li class="goto-head" role="presentation">${esc(t("home.recent"))}</li>` : "");
    return;
  }
  timer = window.setTimeout(async () => {
    const hits = await hitsFor(q);
    if (ask !== asked) return;
    const groups = groupHits(hits);
    const options = listNameOptions(groups.map(g => (g.spec ?? g.body)!));
    items = groups.map(g => {
      const h = (g.spec ?? g.body)!;
      return { href: entityHref(h.key), html: rowHtml(h, options(h), g.spec === null) };
    });
    items.push({ href: objectsHref(q), html: `<span class="goto-all">${esc(t("goto.all", { q }))}</span>`, all: true });
    render(q, groups.length ? "" : `<li class="goto-none" role="presentation">${esc(t("goto.none", { q }))}</li>`);
  }, 150);
}

/** Shows [items], listed for the text [text], after [head]. */
function render(text: string, head: string) {
  listedFor = text;
  list.innerHTML = head + items.map((item, i) =>
    `<li role="option" id="goto-${i}" data-index="${i}"${item.all ? ' class="all"' : ""}>${item.html}</li>`).join("");
  mark(0);
}

function mark(index: number) {
  active = index;
  list.querySelectorAll<HTMLElement>("li[data-index]").forEach(li => li.setAttribute("aria-selected", String(Number(li.dataset.index) === index)));
  if (items.length) {
    input.setAttribute("aria-activedescendant", "goto-" + index);
    document.getElementById("goto-" + index)?.scrollIntoView({ block: "nearest" });
  } else {
    input.removeAttribute("aria-activedescendant");
  }
}
