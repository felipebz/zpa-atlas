import type {Answer, EntityDetail, EntitySummary, FileOccurrence, FileView} from "./api";
import {api, errorMessage} from "./api";
import {showBanner} from "./banner";
import type {NameOptions} from "./entity-html";
import {kindBadgeNode, listNameOptions, nameNodes, parseLabel, placeHtml, roleLabel, statusBadge} from "./entity-html";
import {places} from "./entity-view";
import type {OutlineEntry, Segment} from "./file-view";
import {currentEntry, outlineEntries, segmentClass, segmentsByLine} from "./file-view";
import {entityHref, getOrPut, langSuffix} from "./html";
import {formatNumber, initI18n, t} from "./i18n";
import {fileTitleParts, setPageTitle} from "./project";

// The whole of an indexed file, colored the way the analyzer reads it; every indexed name is clickable and opens a
// popover with its entity, status, places and who uses it. Beside the code, the objects the file declares. File text
// only ever enters the DOM as text nodes.

const params = new URLSearchParams(location.search);
const path = params.get("path");
const wantedLine = Number(params.get("line")) || null;
/** The height of a line of code, in pixels; the stylesheet's `.file-src` line height. */
const LINE_HEIGHT = 18;
/** Below this width "In this file" is a closed list above the code. */
const NARROW = window.matchMedia("(max-width: 700px)");

let view!: FileView;
let entries: OutlineEntry[] = [];
const details = new Map<string, Promise<EntityDetail>>();

/** The entity detail of [key], fetched once. */
function detail(key: string) {
  return getOrPut(details, key, () => api<Answer<EntityDetail>>("api/entity", { key }).then(a => a.result));
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string | null, text?: string | null) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

/** An element holding trusted HTML built by the page's own helpers (which escape every value). */
function html(markup: string, tag: "span" | "div" = "span") {
  const holder = el(tag);
  holder.innerHTML = markup;
  return holder;
}

function renderLine(number: number, text: string, segments: Segment[] | undefined) {
  const row = el("div", "ln");
  row.id = "L" + number;
  const anchor = el("a", "num", String(number));
  anchor.href = "#L" + number;
  row.appendChild(anchor);
  const code = el("span", "code");
  let at = 0;
  for (const segment of segments ?? []) {
    if (segment.from > at) code.appendChild(document.createTextNode(text.slice(at, segment.from)));
    const piece = el("span", segmentClass(segment, view.occurrences), text.slice(segment.from, segment.to));
    if (segment.occurrences.length) piece.dataset.o = segment.occurrences.join(",");
    code.appendChild(piece);
    at = segment.to;
  }
  if (at < text.length) code.appendChild(document.createTextNode(text.slice(at)));
  row.appendChild(code);
  return row;
}

// Lines go in blocks the browser may skip laying out while they are off screen (content-visibility).
const BLOCK = 200;

/** The widest line in columns, tabs at every eighth: the code's width, so line numbers can stay in view across all of it. */
function widestLine(lines: string[]) {
  let widest = 0;
  for (const line of lines) {
    let column = 0;
    for (const c of line) column = c === "\t" ? column + 8 - (column % 8) : column + 1;
    widest = Math.max(widest, column);
  }
  return widest;
}

function renderFile() {
  const segments = segmentsByLine(view.lines, view.occurrences, view.colors);
  const source = el("div", "file-src mono");
  const inner = el("div", "file-lines");
  // The number column, its gap and border, then the code: every line box as wide as the widest, for the sticky numbers.
  inner.style.minWidth = `max(100%, calc(${widestLine(view.lines) + 8}ch + 24px))`;
  for (let start = 0; start < view.lines.length; start += BLOCK) {
    const block = el("div", "block");
    block.style.containIntrinsicHeight = `auto ${Math.min(BLOCK, view.lines.length - start) * LINE_HEIGHT}px`;
    for (let i = start; i < Math.min(start + BLOCK, view.lines.length); i++) block.appendChild(renderLine(i + 1, view.lines[i], segments.get(i + 1)));
    inner.appendChild(block);
  }
  source.appendChild(inner);
  document.getElementById("file")!.replaceChildren(source);
}

function highlight(line: number) {
  document.querySelectorAll(".ln.hl").forEach(n => n.classList.remove("hl"));
  const row = document.getElementById("L" + line);
  if (!row) return;
  row.classList.add("hl");
  row.scrollIntoView({ block: "center" });
}

// ---- the header ---------------------------------------------------------------------------------------------------

function renderHeader() {
  const { name, folder } = fileTitleParts(view.path);
  const title = el("h1", "file-title");
  if (folder) title.appendChild(el("span", "folder", folder + "/"));
  title.appendChild(el("span", "name", name));
  const facts = el("p", "facts muted", t("file.lines", { count: view.lines.length }));
  if (view.parseStatus !== "OK") {
    facts.append(" · ");
    const parse = el("span", "badge warn-badge", parseLabel(view.parseStatus));
    parse.title = view.parseStatus;
    facts.appendChild(parse);
  }
  document.getElementById("header")!.replaceChildren(title, facts);
}

// ---- "In this file" -----------------------------------------------------------------------------------------------

function renderOutline() {
  const outline = document.getElementById("outline") as HTMLDetailsElement;
  entries = outlineEntries(view.objects);
  if (entries.length === 0) return;
  outline.querySelector("summary")!.textContent = t("file.inThisFile", { count: entries.length });
  const list = el("ol");
  entries.forEach((entry, index) => {
    const item = el("li", "d" + entry.depth);
    const go = el("a", "go");
    go.href = "#L" + entry.line;
    go.dataset.index = String(index);
    go.title = entry.entity.display;
    const name = nameNodes(entry.entity, { ...entry.shape, noBodyBadge: true });
    name.className = "name";
    go.append(kindBadgeNode(entry.entity.kind, true), name);
    if (entry.body) go.appendChild(el("span", "muted", t("entity.overload.body")));
    go.appendChild(el("span", "line muted", formatNumber(entry.line)));
    const open = el("a", "open", "↗");
    open.href = entityHref(entry.entity.key);
    open.title = t("file.openObject", { name: entry.entity.display });
    open.setAttribute("aria-label", open.title);
    item.append(go, open);
    list.appendChild(item);
  });
  outline.appendChild(list);
  outline.hidden = false;
  const fit = () => { outline.open = !NARROW.matches; };
  fit();
  NARROW.addEventListener("change", fit);
  list.addEventListener("click", e => {
    const go = (e.target as Element).closest<HTMLAnchorElement>("a.go");
    if (!go) return;
    e.preventDefault();
    const line = entries[Number(go.dataset.index)].line;
    history.pushState(null, "", "#L" + line);
    if (NARROW.matches) outline.open = false;
    highlight(line);
  });
  window.addEventListener("scroll", () => requestAnimationFrame(markCurrent), { passive: true });
  markCurrent();
}

/**
 * Marks the entry the reader is in: while the marked line is on screen, the entry it is in (a line link or an entry
 * moves the code there); else the entry of the line at the middle of the screen.
 */
function markCurrent() {
  const first = document.getElementById("L1");
  if (!first || entries.length === 0) return;
  const marked = document.querySelector(".ln.hl");
  const shown = marked?.getBoundingClientRect();
  const line = marked && shown && shown.bottom > 0 && shown.top < window.innerHeight
    ? Number(marked.id.slice(1))
    : Math.max(1, Math.floor((window.innerHeight / 2 - first.getBoundingClientRect().top) / LINE_HEIGHT) + 1);
  const index = currentEntry(entries, line);
  const outline = document.getElementById("outline")!;
  const previous = outline.querySelector("a.go[aria-current]");
  const next = index < 0 ? null : outline.querySelector<HTMLElement>(`a.go[data-index="${index}"]`);
  if (previous === next) return;
  previous?.removeAttribute("aria-current");
  if (!next) return;
  next.setAttribute("aria-current", "location");
  // The list scrolls itself to keep the mark in view; the page does not move.
  const list = outline.querySelector("ol")!;
  const box = next.getBoundingClientRect(), frame = list.getBoundingClientRect();
  if (box.top < frame.top || box.bottom > frame.bottom) list.scrollTop += box.top - frame.top - frame.height / 3;
}

// ---- popover ------------------------------------------------------------------------------------------------------

function entityHeader(entity: EntitySummary, options?: NameOptions) {
  const head = el("div", "entity");
  head.appendChild(kindBadgeNode(entity.kind));
  const name = el("b");
  name.appendChild(nameNodes(entity, options));
  head.appendChild(name);
  if (entity.external) head.appendChild(el("span", "badge external", t("entity.external")));
  return head;
}

/** Where [entity] is, as its page's header says it (`Declared in the spec, pkg/a.pks, line 1`), loaded on demand. */
async function definitionLinks(entity: EntitySummary, target: HTMLElement) {
  target.replaceChildren(el("span", "muted", t("popover.loading")));
  try {
    const own = await detail(entity.key);
    // In a popover the file is not the page's: every place names its file.
    const all = places(own).map(p => ({ ...p, file: true }));
    if (all.length === 0) {
      target.replaceChildren(el("span", "muted", t("popover.noDefinition")));
      return;
    }
    const list = el("ul");
    for (const place of all) {
      const item = el("li");
      item.appendChild(html(placeHtml(place, own.kind, null)));
      list.appendChild(item);
    }
    target.replaceChildren(list);
  } catch (e) {
    target.replaceChildren(el("span", "muted", t("error.message", { error: errorMessage(e) })));
  }
}

/** An entity with its two ways out: its places and who uses it. */
function entityBlock(entity: EntitySummary, options?: NameOptions) {
  const block = el("div", "target");
  block.appendChild(entityHeader(entity, options));
  const definitions = el("div", "definitions");
  // The link reads as the entity page's section it opens.
  const uses = el("a", null, t("usage.title"));
  uses.href = `entity.html?key=${encodeURIComponent(entity.key)}${langSuffix()}`;
  const actions = el("div", "actions");
  actions.appendChild(uses);
  block.append(definitions, actions);
  definitionLinks(entity, definitions);
  return block;
}

function occurrenceBlock(o: FileOccurrence) {
  const block = el("div", "occurrence");
  const status = el("div");
  status.appendChild(html(statusBadge(o.status, o.reason)));
  if (o.roles.length) {
    const roles = el("span", "muted", o.roles.map(roleLabel).join(", "));
    roles.title = o.roles.join(", ");
    status.appendChild(roles);
  }
  block.appendChild(status);
  if (o.entity !== null) {
    block.appendChild(entityBlock(view.entities[o.entity]));
  } else if (o.candidates.length) {
    block.appendChild(el("div", "muted", t("popover.candidates")));
    // Candidates are often overloads: their parameter lists show as much as tells them apart.
    const candidates = o.candidates.map(index => view.entities[index]);
    const options = listNameOptions(candidates);
    for (const candidate of candidates) block.appendChild(entityBlock(candidate, options(candidate)));
  } else {
    block.appendChild(el("div", "muted", t("popover.noEntity")));
  }
  return block;
}

function openPopover(piece: HTMLElement) {
  const popover = document.getElementById("popover")!;
  const occurrences = piece.dataset.o!.split(",").map(Number).map(i => view.occurrences[i]);
  popover.replaceChildren(...occurrences.map(occurrenceBlock));
  popover.hidden = false;
  const box = piece.getBoundingClientRect();
  popover.style.left = `${Math.max(8, Math.min(box.left + window.scrollX, window.scrollX + document.documentElement.clientWidth - popover.offsetWidth - 8))}px`;
  popover.style.top = `${box.bottom + window.scrollY + 4}px`;
}

function closePopover() {
  document.getElementById("popover")!.hidden = true;
}

document.addEventListener("click", e => {
  const piece = (e.target as Element).closest<HTMLElement>(".occ");
  if (piece) { openPopover(piece); return; }
  if (!(e.target as Element).closest("#popover")) closePopover();
});
document.addEventListener("keydown", e => { if (e.key === "Escape") closePopover(); });
window.addEventListener("hashchange", () => {
  const match = /^#L(\d+)$/.exec(location.hash);
  if (match) highlight(Number(match[1]));
});

(async () => {
  await initI18n();
  showBanner();
  const header = document.getElementById("header")!;
  if (!path) { header.textContent = t("file.missingPath"); return; }
  const { name, folder } = fileTitleParts(path);
  setPageTitle(folder ? t("page.file.titleWith", { name, folder }) : t("page.file.titleRoot", { name }));
  try {
    view = (await api<Answer<FileView>>("api/file", { path, colors: true })).result;
  } catch (e) {
    header.textContent = t("source.unavailable", { error: errorMessage(e) });
    return;
  }
  renderHeader();
  if (view.stale) {
    const stale = document.getElementById("stale")!;
    stale.textContent = t("source.stale");
    stale.hidden = false;
  }
  renderFile();
  renderOutline();
  const hash = /^#L(\d+)$/.exec(location.hash);
  const line = hash ? Number(hash[1]) : wantedLine;
  if (line) highlight(line);
})();
