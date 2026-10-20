import { api, EntityRef, errorMessage, getOrPut, initI18n, kindLabel, langSuffix, nameNodes, roleLabel, showBanner, t } from "./common";

// The whole of an indexed file; every indexed name is clickable and opens a popover with its entity, status,
// definitions and who uses it. File text only ever enters the DOM as text nodes.

const params = new URLSearchParams(location.search);
const path = params.get("path");
const wantedLine = Number(params.get("line")) || null;
const LINKED_STATUSES = new Set(["RESOLVED"]);

/** A name span of the file, with what the index knows about it; `entity` and `candidates` index [FileView.entities]. */
interface FileOccurrence {
  at: [number, number, number, number];
  roles: string[];
  status: string;
  reason: string | null;
  entity: number | null;
  candidates: number[];
}

interface FileView {
  lines: string[];
  occurrences: FileOccurrence[];
  entities: EntityRef[];
  stale: boolean;
}

/** What the popover reads of an entity page. */
interface EntityPage {
  relations: { kind: string; entity: EntityRef }[];
  definitions: { path: string; span: { startLine: number }; roles: string[] }[];
}

/** The pieces of a line covered by name spans: from, to (columns) and the occurrences that cover them. */
type Piece = [number, number, number[]];

let view!: FileView;
const details = new Map<string, Promise<EntityPage>>();

/** The entity detail of [key], fetched once. */
function detail(key: string) {
  return getOrPut(details, key, () => api<{ result: EntityPage }>("api/entity", { key }).then(a => a.result));
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string | null, text?: string | null) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

/** Whether an occurrence leads somewhere: an entity or ambiguous candidates. */
const linkable = (o: FileOccurrence) => o.entity !== null || o.candidates.length > 0;

/**
 * Pieces of every line covered by name spans: a span over several lines is cut per line, then each line is split at
 * every span edge, and each piece lists the occurrences that cover it (a qualified read covers a column definition).
 */
function piecesByLine(lines: string[], occurrences: FileOccurrence[]) {
  const cuts = new Map<number, [number, number, number][]>();
  occurrences.forEach((o, index) => {
    const [startLine, startCol, endLine, endCol] = o.at;
    for (let line = startLine; line <= endLine && line <= lines.length; line++) {
      const from = line === startLine ? startCol : 0;
      const to = line === endLine ? endCol : lines[line - 1].length;
      if (to > from) getOrPut(cuts, line, () => []).push([from, to, index]);
    }
  });
  const result = new Map<number, Piece[]>();
  for (const [line, list] of cuts) {
    const edges = [...new Set(list.flatMap(c => [c[0], c[1]]))].sort((a, b) => a - b);
    const pieces: Piece[] = [];
    for (let i = 0; i + 1 < edges.length; i++) {
      const covering = list.filter(c => c[0] <= edges[i] && c[1] >= edges[i + 1]).map(c => c[2]);
      if (covering.length) pieces.push([edges[i], edges[i + 1], covering]);
    }
    result.set(line, pieces);
  }
  return result;
}

/** One piece's class: a link, a quiet link when none of its linked occurrences is RESOLVED, else only a marker. */
function pieceClass(covering: number[]) {
  const linked = covering.map(i => view.occurrences[i]).filter(linkable);
  if (linked.length === 0) return "occ none";
  return linked.some(o => LINKED_STATUSES.has(o.status)) ? "occ link" : "occ link quiet";
}

function renderLine(number: number, text: string, pieces: Piece[] | undefined) {
  const row = el("div", "ln");
  row.id = "L" + number;
  const anchor = el("a", "num", String(number));
  anchor.href = "#L" + number;
  row.appendChild(anchor);
  const code = el("span", "code");
  let at = 0;
  for (const [from, to, covering] of pieces ?? []) {
    if (from > at) code.appendChild(document.createTextNode(text.slice(at, from)));
    const piece = el("span", pieceClass(covering), text.slice(from, to));
    piece.dataset.o = covering.join(",");
    code.appendChild(piece);
    at = to;
  }
  if (at < text.length) code.appendChild(document.createTextNode(text.slice(at)));
  row.appendChild(code);
  return row;
}

// Lines go in blocks the browser may skip laying out while they are off screen (content-visibility).
const BLOCK = 200;

function renderFile(lines: string[], occurrences: FileOccurrence[]) {
  const pieces = piecesByLine(lines, occurrences);
  const target = document.getElementById("file")!;
  const pre = el("div", "file-src mono");
  for (let start = 0; start < lines.length; start += BLOCK) {
    const block = el("div", "block");
    block.style.containIntrinsicSize = `auto ${Math.min(BLOCK, lines.length - start) * 18}px`;
    for (let i = start; i < Math.min(start + BLOCK, lines.length); i++) block.appendChild(renderLine(i + 1, lines[i], pieces.get(i + 1)));
    pre.appendChild(block);
  }
  target.replaceChildren(pre);
}

function highlight(line: number) {
  document.querySelectorAll(".ln.hl").forEach(n => n.classList.remove("hl"));
  const row = document.getElementById("L" + line);
  if (!row) return;
  row.classList.add("hl");
  row.scrollIntoView({ block: "center" });
}

// ---- popover ------------------------------------------------------------------------------------------------------

function fileHref(target: string, line: number) {
  return `file.html?path=${encodeURIComponent(target)}&line=${line}${langSuffix()}`;
}

function entityHeader(entity: EntityRef) {
  const head = el("div", "entity");
  head.appendChild(el("span", "badge", kindLabel(entity.kind)));
  const name = el("b");
  name.appendChild(nameNodes(entity));
  head.appendChild(name);
  if (entity.external) head.appendChild(el("span", "badge external", t("entity.external")));
  return head;
}

/** The definitions of [entity] and of its IMPLEMENTS partner (a spec and a body give two links), loaded on demand. */
async function definitionLinks(entity: EntityRef, target: HTMLElement) {
  target.replaceChildren(el("span", "muted", t("popover.loading")));
  try {
    const own = await detail(entity.key);
    const partners = own.relations.filter(r => r.kind === "IMPLEMENTS").map(r => r.entity.key);
    const all = [own, ...await Promise.all(partners.map(detail))].flatMap(d => d.definitions);
    if (all.length === 0) {
      target.replaceChildren(el("span", "muted", t("popover.noDefinition")));
      return;
    }
    const list = el("ul");
    for (const d of all) {
      const item = el("li");
      const link = el("a", "mono", `${d.path}:${d.span.startLine}`);
      link.href = fileHref(d.path, d.span.startLine);
      item.append(el("span", "muted", d.roles.map(roleLabel).join(", ") + " "), link);
      list.appendChild(item);
    }
    target.replaceChildren(list);
  } catch (e) {
    target.replaceChildren(el("span", "muted", t("error.message", { error: errorMessage(e) })));
  }
}

/** An entity with its two ways out: its definitions and who uses it. */
function entityBlock(entity: EntityRef) {
  const block = el("div", "target");
  block.appendChild(entityHeader(entity));
  const definitions = el("div", "definitions");
  const uses = el("a", null, t("popover.usedBy"));
  uses.href = `entity.html?key=${encodeURIComponent(entity.key)}${langSuffix()}`;
  const actions = el("div", "actions");
  actions.appendChild(uses);
  block.append(el("div", "label", t("popover.definition")), definitions, actions);
  definitionLinks(entity, definitions);
  return block;
}

function occurrenceBlock(o: FileOccurrence) {
  const block = el("div", "occurrence");
  const status = el("div");
  status.appendChild(el("span", "badge st-" + o.status, o.reason && o.reason !== o.status ? `${o.status} · ${o.reason}` : o.status));
  if (o.roles.length) status.appendChild(el("span", "muted", o.roles.map(roleLabel).join(", ")));
  block.appendChild(status);
  if (o.entity !== null) {
    block.appendChild(entityBlock(view.entities[o.entity]));
  } else if (o.candidates.length) {
    block.appendChild(el("div", "muted", t("popover.candidates")));
    for (const index of o.candidates) block.appendChild(entityBlock(view.entities[index]));
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
  document.title = t("page.file.titleWith", { path });
  try {
    view = (await api<{ result: FileView }>("api/file", { path })).result;
  } catch (e) {
    header.textContent = t("source.unavailable", { error: errorMessage(e) });
    return;
  }
  header.replaceChildren(el("h1", "mono", path),
    el("p", "muted", `${t("file.lines", { count: view.lines.length })} · ${t("file.occurrences", { count: view.occurrences.length })}`));
  if (view.stale) {
    const stale = document.getElementById("stale")!;
    stale.textContent = t("source.stale");
    stale.hidden = false;
  }
  renderFile(view.lines, view.occurrences);
  const hash = /^#L(\d+)$/.exec(location.hash);
  const line = hash ? Number(hash[1]) : wantedLine;
  if (line) highlight(line);
})();
