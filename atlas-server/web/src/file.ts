import type {Answer, EntityDetail, EntitySummary, FileOccurrence, FileView, FormsFile, FormsNode} from "./api";
import {api, errorMessage} from "./api";
import {showBanner} from "./banner";
import type {NameOptions} from "./entity-html";
import {fillHtml, formsNameLink, kindBadgeNode, listNameOptions, nameNodes, parseLabel, placeHtml, roleLabel, statusBadge} from "./entity-html";
import {places} from "./entity-view";
import type {OutlineEntry, Segment} from "./file-view";
import {
    currentEntry,
    innermost,
    occurrencesOf,
    outlineEntries,
    routineMemberName,
    segmentClass,
    segmentsByLine
} from "./file-view";
import {declaredLine} from "./declared-view";
import type {FormsTreeView} from "./forms";
import {renderFormsTree} from "./forms";
import type {CodeUnit} from "./forms-view";
import {codePageAsk, formsName, nodeId, sameUnit, settledCode, unitKindWord, unitNode, wantedCode} from "./forms-view";
import {codeHref, entityHref, getOrPut, langSuffix} from "./html";
import {formatNumber, initI18n, t} from "./i18n";
import {fileTitleParts, setPageTitle} from "./project";
import {widestLine} from "./tab-width";
import {onTabWidth, tabWidth, tabWidthControl} from "./tab-width-control";

// The whole of an indexed file, colored the way the analyzer reads it; every indexed name is clickable and opens a
// popover with its entity, status, places and who uses it. Beside the code, the objects the file declares. A Forms
// module shows one piece of its code at a time (a trigger, a program unit's specification or body, a record group's
// query), numbered from 1, beside the module's objects, from which another opens in its place. File text only ever
// enters the DOM as text nodes.

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
/** The pieces of code each occurrence covers (an occurrence over several lines has several), by occurrence index. */
const piecesOf = new Map<number, HTMLElement[]>();

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
    if (segment.occurrences.length) {
      piece.dataset.o = segment.occurrences.join(",");
      for (const index of segment.occurrences) getOrPut(piecesOf, index, () => []).push(piece);
    }
    code.appendChild(piece);
    at = segment.to;
  }
  if (at < text.length) code.appendChild(document.createTextNode(text.slice(at)));
  row.appendChild(code);
  return row;
}

// Lines go in blocks the browser may skip laying out while they are off screen (content-visibility).
const BLOCK = 200;

/**
 * Every line box as wide as the widest line at the tab width shown, for the sticky numbers: the number column, its gap
 * and border, then the code.
 */
function fitWidth(inner: HTMLElement) {
  inner.style.minWidth = `max(100%, calc(${widestLine(view.lines, tabWidth()) + 8}ch + 24px))`;
}

/** The box of the lines shown, fitted again when the tab width changes. */
let shownLines: HTMLElement | null = null;
onTabWidth(() => { if (shownLines) fitWidth(shownLines); });

/** The lines of [view] into the page, in place of any shown before. */
function renderFile() {
  piecesOf.clear();
  const segments = segmentsByLine(view.lines, view.occurrences, view.colors);
  const source = el("div", "file-src mono");
  const inner = el("div", "file-lines");
  fitWidth(inner);
  shownLines = inner;
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

/** The file's path as the page's title: its folder muted, then its name. */
function fileTitle(filePath: string) {
  const { name, folder } = fileTitleParts(filePath);
  const title = el("h1", "file-title");
  if (folder) title.appendChild(el("span", "folder", folder + "/"));
  title.appendChild(el("span", "name", name));
  return title;
}

/** What the header says of the code shown, into [facts]: its lines, its parse status unless it parsed, and [tabs], the tab width. */
function codeFacts(facts: HTMLElement, code: FileView, tabs: HTMLElement) {
  facts.append(t("file.lines", { count: code.lines.length }));
  if (code.parseStatus !== "OK") {
    facts.append(" · ");
    const parse = el("span", "badge warn-badge", parseLabel(code.parseStatus));
    parse.title = code.parseStatus;
    facts.appendChild(parse);
  }
  facts.append(" · ", tabs);
}

function renderHeader() {
  const facts = el("p", "facts muted");
  codeFacts(facts, view, tabWidthControl());
  document.getElementById("header")!.replaceChildren(fileTitle(view.path), facts);
}

/** Says the code shown changed since it was indexed, or nothing when it did not. */
function showStale(stale: boolean) {
  const warning = document.getElementById("stale")!;
  warning.textContent = stale ? t("source.stale") : "";
  warning.hidden = !stale;
}

// ---- "In this file" -----------------------------------------------------------------------------------------------

/** The outline open beside the code, and closed above it on a narrow screen. */
function fitOutline(outline: HTMLDetailsElement) {
  const fit = () => { outline.open = !NARROW.matches; };
  fit();
  NARROW.addEventListener("change", fit);
}

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
  fitOutline(outline);
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

// ---- a Forms module: one piece of its code beside its objects -------------------------------------------------------

/** A Forms module's page: its module file's path, its tree beside the code, and the piece of code shown (null: none yet, or the module has none). */
let forms: { path: string; tree: FormsTreeView; shown: CodeUnit | null } | null = null;
/** The tab width control of a Forms module's header, kept from one piece of code to the next. */
let formsTabs: HTMLElement | null = null;
/** How many pieces of code were asked for: only the last one asked shows. */
let asked = 0;

/** The code page of the Forms module file [filePath] showing [unit] (null: its first piece of code). */
async function formsFile(filePath: string, unit: CodeUnit | null) {
  const ask = codePageAsk(filePath, unit, "FORMS_MODULE");
  return (await api<Answer<FormsFile>>(ask.api, ask.params)).result;
}

/**
 * The header of a Forms module's page: the module file's path, the piece of code shown ([node], its kind, its own
 * name linking to its page, the half of a package), then the module, linking to its page, and what the code says of
 * its lines.
 */
function renderFormsHeader(file: FormsFile, node: FormsNode | null) {
  const parts: HTMLElement[] = [fileTitle(file.forms.path)];
  if (node !== null) {
    const unit = el("p", "file-unit");
    unit.append(kindBadgeNode(node.entity.kind), html(formsNameLink(formsName(node.entity, file.forms))));
    const word = unitKindWord(node.unitKind);
    if (word !== null) unit.appendChild(el("span", "muted", t(word)));
    parts.push(unit);
  }
  const facts = el("p", "facts muted");
  facts.appendChild(html(fillHtml("file.module", { module: formsNameLink(formsName(file.forms.module, file.forms)) })));
  if (file.code !== null) {
    facts.append(" · ");
    formsTabs ??= tabWidthControl();
    codeFacts(facts, file.code.view, formsTabs);
  }
  parts.push(facts);
  document.getElementById("header")!.replaceChildren(...parts);
}

/** The module's objects as the outline, a piece of code linking to its code page and opening in place when chosen. */
function renderFormsOutline(file: FormsFile): FormsTreeView {
  const outline = document.getElementById("outline") as HTMLDetailsElement;
  outline.querySelector("summary")!.textContent = t("forms.objects");
  const pane = el("div", "fpane");
  outline.appendChild(pane);
  const filePath = file.forms.path;
  const tree = renderFormsTree(pane, file.tree, {
    href: unit => codeHref(filePath, unit, null),
    choose: unit => {
      history.pushState(null, "", codeHref(filePath, unit, null));
      if (NARROW.matches) outline.open = false;
      void openCode({ unit, line: null });
    }
  });
  outline.hidden = file.tree.groups.length === 0;
  fitOutline(outline);
  return tree;
}

/**
 * Shows the piece of code [file] brings, asked for as [wanted]: the header, the code, its node marked and its branch
 * open, and the line asked for marked (else the top of the page); the address then names the piece shown.
 */
function showCode(file: FormsFile, wanted: { unit: CodeUnit | null; line: number | null }) {
  closePopover();
  const code = file.code;
  const shown = code === null ? null : { key: code.key, unitKind: code.unitKind };
  forms!.shown = shown;
  const node = shown === null ? null : unitNode(file.tree, shown);
  const { name } = fileTitleParts(file.forms.path);
  setPageTitle(node !== null ? t("page.file.titleCode", { unit: node.entity.name, name }) : t("page.file.titleRoot", { name }));
  renderFormsHeader(file, node);
  showStale(code?.view.stale ?? false);
  forms!.tree.mark(shown === null ? null : nodeId(shown.key, shown.unitKind));
  if (code === null || shown === null) {
    shownLines = null;
    document.getElementById("file")!.replaceChildren(el("p", "muted", t("file.noCode")));
    return;
  }
  view = code.view;
  renderFile();
  const settled = settledCode(wanted, shown);
  if (settled !== null) history.replaceState(null, "", codeHref(file.forms.path, settled.unit, settled.line));
  const line = settled !== null ? settled.line : wanted.line;
  if (line) highlight(line);
  else window.scrollTo(0, 0);
}

/** Asks for the piece of code [wanted] names and shows it, unless another was asked for meanwhile. */
async function openCode(wanted: { unit: CodeUnit | null; line: number | null }) {
  const ask = ++asked;
  try {
    const file = await formsFile(forms!.path, wanted.unit);
    if (ask === asked) showCode(file, wanted);
  } catch (e) {
    if (ask === asked) document.getElementById("file")!.replaceChildren(el("p", "muted", t("source.unavailable", { error: errorMessage(e) })));
  }
}

// ---- popover ------------------------------------------------------------------------------------------------------

/**
 * The popover's header: the kind and the name, qualified as the entity page shows it; a routine's parameter or local
 * (variable, constant, cursor, type) by its name alone, since its routine is the one being read.
 */
function entityHeader(entity: EntitySummary, options?: NameOptions) {
  const head = el("div", "entity");
  head.appendChild(kindBadgeNode(entity.kind));
  const name = el("b");
  const member = routineMemberName(entity);
  if (member !== null) {
    name.textContent = member;
    name.title = entity.display;
  } else {
    name.appendChild(nameNodes(entity, options));
  }
  head.appendChild(name);
  if (entity.external) head.appendChild(el("span", "badge external", t("entity.external")));
  return head;
}

/** Where [entity] is, as its page's header says it (`Declared in the spec, pkg/a.pks, line 1`), loaded on demand. */
async function definitionLinks(entity: EntitySummary, target: HTMLElement, declared: HTMLElement) {
  target.replaceChildren(el("span", "muted", t("popover.loading")));
  try {
    const own = await detail(entity.key);
    const line = declaredLine(own.kind, own.declared);
    if (line !== null) {
      declared.textContent = line;
      declared.hidden = false;
    }
    // In a popover the file is not the page's: every place names its file.
    const all = places(own).map(p => ({ ...p, file: true }));
    if (all.length === 0) {
      target.replaceChildren(el("span", "muted", t("popover.noDefinition")));
      return;
    }
    const list = el("ul");
    for (const place of all) {
      const item = el("li");
      item.appendChild(html(placeHtml(place, own, null)));
      list.appendChild(item);
    }
    target.replaceChildren(list);
  } catch (e) {
    target.replaceChildren(el("span", "muted", t("error.message", { error: errorMessage(e) })));
  }
}

/**
 * An entity with its declared type, its uses in this file when [uses] gives them (stepped through from the clicked
 * one), and its two ways out: its places and who uses it.
 */
function entityBlock(entity: EntitySummary, options?: NameOptions, usesLine?: HTMLElement) {
  const block = el("div", "target");
  block.appendChild(entityHeader(entity, options));
  const declared = el("div", "declared mono");
  declared.hidden = true;
  block.appendChild(declared);
  if (usesLine) block.appendChild(usesLine);
  const definitions = el("div", "definitions");
  // The link reads as the entity page's section it opens.
  const uses = el("a", null, t("usage.title"));
  uses.href = `entity.html?key=${encodeURIComponent(entity.key)}${langSuffix()}`;
  const actions = el("div", "actions");
  actions.appendChild(uses);
  block.append(definitions, actions);
  // Once its places are in, the popover is as wide as they make it: placed again, it keeps its margins.
  definitionLinks(entity, definitions, declared).then(() => { if (anchor) placePopover(anchor); });
  return block;
}

// ---- the uses of the popover's entity in the file ------------------------------------------------------------------

/** The uses of the open popover's entity: its occurrences in reading order and the current one; null when none is marked. */
let uses: { order: number[]; current: number; position: HTMLElement } | null = null;

function setMarked(index: number, mark: boolean, current: boolean) {
  for (const piece of piecesOf.get(index) ?? []) {
    piece.classList.toggle("in-use", mark);
    piece.classList.toggle("in-use-current", mark && current);
  }
}

/** Marks every occurrence of the file's entity [entity], [clicked] as the current one; the popover's line that steps through them. */
function markUses(entity: number, clicked: number) {
  const order = occurrencesOf(view.occurrences, entity);
  const current = Math.max(0, order.indexOf(clicked));
  const line = el("div", "file-uses");
  line.appendChild(el("span", null, t("popover.uses", { count: order.length })));
  const position = el("span", "muted");
  const step = (delta: number, label: string, text: string) => {
    const button = el("button", "step", text);
    button.type = "button";
    button.title = label;
    button.setAttribute("aria-label", label);
    button.disabled = order.length < 2;
    button.addEventListener("click", () => stepUse(delta));
    return button;
  };
  line.append(step(-1, t("popover.previous"), "‹"), position, step(1, t("popover.next"), "›"));
  uses = { order, current, position };
  order.forEach((index, i) => setMarked(index, true, i === current));
  showPosition();
  return line;
}

function showPosition() {
  if (uses) uses.position.textContent = t("popover.usePosition", { position: uses.current + 1, count: uses.order.length });
}

/** Moves the current use [delta] steps, round the file, scrolls to it and brings the popover under it. */
function stepUse(delta: number) {
  if (!uses) return;
  setMarked(uses.order[uses.current], true, false);
  uses.current = (uses.current + delta + uses.order.length) % uses.order.length;
  setMarked(uses.order[uses.current], true, true);
  showPosition();
  const piece = piecesOf.get(uses.order[uses.current])?.[0];
  if (!piece) return;
  piece.scrollIntoView({ block: "center" });
  placePopover(piece);
}

function clearUses() {
  if (!uses) return;
  for (const index of uses.order) setMarked(index, false, false);
  uses = null;
}

/** The occurrence whose uses are marked, [marked]: the one of the name under the click. */
function occurrenceBlock(o: FileOccurrence, index: number, marked: number | null) {
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
    block.appendChild(entityBlock(view.entities[o.entity], undefined, index === marked ? markUses(o.entity, index) : undefined));
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
  clearUses();
  const indexes = piece.dataset.o!.split(",").map(Number);
  // A piece may lie under several names (`l_pair.a` is the record's and the field's): the innermost is the one clicked.
  const marked = innermost(view.occurrences, indexes);
  popover.replaceChildren(...indexes.map(i => occurrenceBlock(view.occurrences[i], i, marked)));
  popover.hidden = false;
  placePopover(piece);
}

/** The piece of code the open popover is under. */
let anchor: HTMLElement | null = null;

/**
 * The popover under [piece], inside the page's width with a margin on both sides: on a narrow screen across it, else
 * starting under the piece and moved left as far as its width needs.
 */
function placePopover(piece: HTMLElement) {
  const popover = document.getElementById("popover")!;
  anchor = piece;
  const box = piece.getBoundingClientRect();
  const width = document.documentElement.clientWidth;
  if (NARROW.matches) {
    popover.style.width = `${width - 2 * MARGIN}px`;
    popover.style.left = `${window.scrollX + MARGIN}px`;
  } else {
    popover.style.width = "";
    popover.style.left = `${Math.max(MARGIN, Math.min(box.left + window.scrollX, window.scrollX + width - popover.offsetWidth - MARGIN))}px`;
  }
  popover.style.top = `${box.bottom + window.scrollY + 4}px`;
}

/** The popover's least distance from the page's edges, in pixels. */
const MARGIN = 8;

function closePopover() {
  document.getElementById("popover")!.hidden = true;
  anchor = null;
  clearUses();
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
// Back and forward on a Forms module's page: the piece of code the address names, at its line; a line of the piece
// shown only marks the line.
window.addEventListener("popstate", () => {
  if (forms === null) return;
  const wanted = wantedCode(location.search, location.hash);
  if (wanted.unit !== null && forms.shown !== null && sameUnit(wanted.unit, forms.shown)) {
    if (wanted.line) highlight(wanted.line);
    return;
  }
  void openCode(wanted);
});

(async () => {
  await initI18n();
  showBanner();
  const header = document.getElementById("header")!;
  if (!path) { header.textContent = t("file.missingPath"); return; }
  const { name, folder } = fileTitleParts(path);
  setPageTitle(folder ? t("page.file.titleWith", { name, folder }) : t("page.file.titleRoot", { name }));
  // A Forms module shows one piece of its code, never its whole text: an address naming a unit asks for its module's
  // code page alone; any other asks for the file, which says when it is a module all the same (a bookmark, an old link).
  const wanted = wantedCode(location.search, location.hash);
  let file: FileView | null = null;
  let module: FormsFile | null = null;
  try {
    const ask = codePageAsk(path, wanted.unit);
    if (ask.api === "api/file") file = (await api<Answer<FileView>>(ask.api, ask.params)).result;
    if (file === null || file.kind === "FORMS_MODULE") module = await formsFile(path, wanted.unit);
  } catch (e) {
    header.textContent = t("source.unavailable", { error: errorMessage(e) });
    return;
  }
  if (module !== null) {
    forms = { path: module.forms.path, tree: renderFormsOutline(module), shown: null };
    showCode(module, wanted);
    return;
  }
  view = file!;
  renderHeader();
  showStale(view.stale);
  renderFile();
  renderOutline();
  const hash = /^#L(\d+)$/.exec(location.hash);
  const line = hash ? Number(hash[1]) : wantedLine;
  if (line) highlight(line);
})();
