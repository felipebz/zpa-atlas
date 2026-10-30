import type {Answer, EntitySummary} from "./api";
import {api, ApiFailure, errorMessage} from "./api";
import {showBanner} from "./banner";
import type {NameOptions} from "./entity-html";
import {entityLink, kindBadge, kindLabel, listNameOptions, nameHtml, statusBadge} from "./entity-html";
import {entityHref, esc, langSuffix, scrollTable, stackCell} from "./html";
import {formatList, formatNumber, initI18n, t} from "./i18n";
import type {Phrase} from "./impact-words";
import {distanceText, nameShape, pathEnd, pathLine, reachPhrase, summaryPhrase, triggerNote} from "./impact-words";
import type {ImpactAnswer, ImpactTab, ImpactTrigger, ImpactView, NodeGroup, TreeItem} from "./impact-model";
import {
    buildTree,
    csvText,
    groupByDepth,
    groupByOwner,
    MAX_DEPTH,
    nodeText,
    pathOf,
    pathText,
    readState,
    shownNodes,
    stateSearch,
    STOPS,
    viewOf
} from "./impact-model";
import {shownNameText} from "./names";
import {setPageTitle} from "./project";

// The impact page: the whole graph of an entity, loaded once; its options, tab and filters live in the URL.

const lang = new URLSearchParams(location.search).get("lang");
const state = readState(location.search);
const options = document.getElementById("options") as HTMLFormElement;
const groupsElement = document.getElementById("groups")!;

/** Rows a group builds when it opens, and each "show more" adds. */
const ROWS_STEP = 100;
/** A tab with at most this many nodes opens every group; a larger one opens them one by one. */
const OPEN_ALL = 100;
/** Children a level of the tree shows when it opens, and each "show more" adds. */
const TREE_STEP = 50;
/** The levels of the tree open when it is drawn: the root and the first level. */
const TREE_OPEN_LEVELS = 1;
/** From this level on, a phone stops indenting and says how far the object is instead. */
const TREE_FLAT_LEVEL = 4;

interface PageState {
  view: ImpactView | null;
  /** Only the latest load fills the page. */
  generation: number;
  /** The groups of the tab shown, by their position in the page. */
  groups: number[][];
  /** For each group, how many rows it shows. */
  built: number[];
  /** The tree tab's lines by id, their level, and how many children each shows. */
  tree: { items: TreeItem[]; levels: number[]; built: number[] } | null;
}

const page: PageState = { view: null, generation: 0, groups: [], built: [], tree: null };

function writeUrl() {
  history.replaceState(null, "", "impact.html" + stateSearch(state, lang));
}

// ---- loading ------------------------------------------------------------------------------------------------------

async function load() {
  const ran = ++page.generation;
  writeUrl();
  (options.elements.namedItem("triggers") as HTMLInputElement).checked = state.triggers;
  (options.elements.namedItem("maxDepth") as HTMLInputElement).value = String(state.maxDepth);
  const title = document.getElementById("title")!;
  if (!page.view) title.innerHTML = `<p class="muted">${esc(t("banner.loading"))}</p>`;
  document.getElementById("summary")!.innerHTML = `<p class="muted">${esc(t("banner.loading"))}</p>`;
  document.getElementById("summary")!.hidden = false;
  document.getElementById("nodes")!.hidden = true;
  let answer: Answer<ImpactAnswer>;
  try {
    answer = await api<Answer<ImpactAnswer>>("api/impact/graph", { key: state.key, maxDepth: state.maxDepth, triggers: state.triggers });
  } catch (e) {
    if (ran !== page.generation) return;
    const smaller = Math.max(1, Math.floor(state.maxDepth / 2));
    const failure = e instanceof ApiFailure ? e.body : {};
    if (!page.view) setPageTitle(t(e instanceof ApiFailure && e.status === 404 ? "page.impact.titleFailed" : "page.impact.titleError"));
    const message = failure.timeout ? t("impact.timeout", { depth: smaller })
      : failure.tooLarge ? t("impact.tooLarge", { count: failure.nodes ?? 0, limit: failure.limit ?? 0, depth: smaller })
      : t("error.message", { error: errorMessage(e) });
    // Once: in the header before any graph is shown, in the summary over the graph shown last.
    const summary = document.getElementById("summary")!;
    if (page.view) summary.innerHTML = `<p>${esc(message)}</p>`;
    else {
      title.innerHTML = `<p>${esc(message)}</p>`;
      summary.hidden = true;
    }
    return;
  }
  if (ran !== page.generation) return;
  page.view = viewOf(answer.result);
  renderHead(page.view);
  renderSummary(page.view);
  renderNodes();
}

/**
 * The header: "Impact of NAME" (the short name, linking to the entity's page) with its kind and schema muted, what the
 * page lists, and where the walk starts from, in words.
 */
function renderHead(view: ImpactView) {
  setPageTitle(t("page.impact.titleWith", { name: view.entity.name }));
  const entity = view.entity;
  const schema = entity.display.includes(".") ? entity.display.slice(0, entity.display.indexOf(".")) : null;
  const name = `<a href="${entityHref(entity.key)}">${nameHtml(entity, { ...nameShape(entity), noBadge: true, noBodyBadge: true })}</a>`;
  document.getElementById("title")!.innerHTML = `<h1>${phraseHtml({ key: "impact.title", params: {} }, { name })}</h1>` +
    `<p class="impact-of muted">${kindBadge(entity.kind)}${schema ? ` <span>${esc(t("entity.schema", { schema }))}</span>` : ""}</p>` +
    `<p class="impact-what">${esc(t("impact.what"))}</p>`;
  // The spec and the body are two items of the list, so that it reads with one "and", before its last item.
  const parts = [kindLabel(entity.kind), ...view.included.flatMap(scope =>
    scope === "IMPLEMENTATIONS" ? [t("impact.from.SPEC"), t("impact.from.BODY")] : [t("impact.from." + scope)])];
  const included = document.getElementById("included")!;
  included.textContent = t("impact.startsFrom", { list: formatList(parts) });
  included.title = view.included.join(", ");
}

// ---- the summary --------------------------------------------------------------------------------------------------

function countLink(count: number, data: string, title?: string) {
  return `<a href="#" class="count" ${data}${title ? ` title="${esc(title)}"` : ""}>${esc(formatNumber(count))}</a>`;
}

/** [phrase] as HTML: its text escaped, the values named in [html] replaced by their HTML (links). */
function phraseHtml(phrase: Phrase, html: Record<string, string>) {
  const names = Object.keys(html);
  let text = esc(t(phrase.key, { ...phrase.params, ...Object.fromEntries(names.map((name, i) => [name, `\u0001${i}\u0001`])) }));
  names.forEach((name, i) => { text = text.replace(`\u0001${i}\u0001`, html[name]); });
  return text;
}

/**
 * The summary in words: how many objects may be affected and how many for certain, how far they are, the count at
 * each distance, and how many end in each way. Every count filters the list below; codes are in the tooltips.
 */
function renderSummary(view: ImpactView) {
  const summary = document.getElementById("summary")!;
  if (view.nodes.length === 0) {
    summary.innerHTML = `<p>${esc(t("impact.empty"))}</p>`;
    return;
  }
  const total = summaryPhrase(view.nodes.length, view.certain);
  const reach = reachPhrase(view.levels);
  const stops = view.stops.map(stop =>
    `<li title="${esc(stop.value)}">${countLink(stop.count, `data-stop="${esc(stop.value)}"`)} ${esc(t("impact.stop." + stop.value))}</li>`);
  summary.innerHTML =
    `<p class="lead">${phraseHtml(total, {
      total: countLink(view.nodes.length, 'data-show="all"'),
      certain: countLink(view.certain, "data-certain", t("impact.certainHint"))
    })}</p>` +
    `<p>${esc(t(reach.key, reach.params))}</p>` +
    `<p class="levels muted"><span>${esc(t("impact.levels"))}</span> ` +
    view.levels.map((count, i) => `<span class="level">${esc(distanceText(t, i + 1))}: ${countLink(count, `data-depth="${i + 1}"`)}</span>`).join(" ") +
    `</p><ul class="stops">${stops.join("")}</ul>`;
}

document.getElementById("summary")!.addEventListener("click", e => {
  const link = (e.target as Element).closest<HTMLElement>("a.count");
  if (!link) return;
  e.preventDefault();
  const { stop, depth } = link.dataset;
  state.tab = stop === "ENTRY_POINT" ? "entries" : "all";
  state.stop = stop && stop !== "ENTRY_POINT" ? stop : null;
  state.depth = depth ? Number(depth) : null;
  state.certain = link.dataset.certain !== undefined;
  writeUrl();
  renderNodes();
  document.getElementById("nodes")!.scrollIntoView({ block: "start" });
});

// ---- the tabs and their groups ------------------------------------------------------------------------------------

function renderNodes() {
  const view = page.view!;
  const nodes = document.getElementById("nodes")!;
  nodes.hidden = view.nodes.length === 0;
  if (nodes.hidden) return;
  const entries = view.stops.find(stop => stop.value === "ENTRY_POINT")?.count ?? 0;
  for (const tab of nodes.querySelectorAll<HTMLButtonElement>("[data-tab]")) {
    tab.textContent = tab.dataset.tab === "entries" ? t("impact.tab.entries", { count: entries })
      : t(tab.dataset.tab === "tree" ? "impact.tab.tree" : "impact.tab.all", { count: view.nodes.length });
    tab.setAttribute("aria-selected", String(tab.dataset.tab === state.tab));
    tab.classList.toggle("on", tab.dataset.tab === state.tab);
  }
  const shown = shownNodes(view, state);
  renderToolbar(view, shown.length);
  page.tree = null;
  if (state.tab === "tree") {
    renderTree(view);
    return;
  }
  const groups = state.tab === "entries" ? groupByOwner(view, shown) : groupByDepth(view, shown);
  page.groups = groups.map(group => group.nodes);
  page.built = groups.map(() => 0);
  if (shown.length === 0) {
    groupsElement.innerHTML = `<p class="muted">${esc(t(state.tab === "entries" ? "impact.noEntries" : "impact.noneShown"))}</p>`;
    return;
  }
  const open = shown.length <= OPEN_ALL;
  groupsElement.innerHTML = groups.map((group, i) => {
    const label = "depth" in group ? esc(distanceText(t, group.depth)) : groupLabel(group);
    return `<details class="ngroup" data-group="${i}"${open ? " open" : ""}><summary>${label} <span class="muted">· ${esc(formatNumber(group.nodes.length))}</span></summary>` +
      `<div class="rows"></div></details>`;
  }).join("");
  if (open) for (const details of groupsElement.querySelectorAll<HTMLDetailsElement>("details.ngroup")) buildRows(details);
}

function groupLabel(group: NodeGroup) {
  if (group.files) return esc(t("impact.group.files"));
  if (group.owner) return `${nameHtml(group.owner, { ...nameShape(group.owner), noBodyBadge: true })} ${kindBadge(group.owner.kind)}`;
  return esc(t("impact.group.schema", { schema: group.schema ?? t("entity.schemaUnknown") }));
}

function renderToolbar(view: ImpactView, shown: number) {
  const toolbar = document.getElementById("toolbar")!;
  let filters = "";
  if (state.tab === "all") {
    const present = new Set(view.nodes.map(node => node.stop));
    const stopOptions = STOPS.filter(stop => present.has(stop))
      .map(stop => `<option value="${stop}"${state.stop === stop ? " selected" : ""}>${esc(t("impact.stopWord." + stop))}</option>`).join("");
    filters = `<label class="field">${esc(t("impact.filter.stop"))} <select name="stop"><option value="">${esc(t("impact.filter.any"))}</option>${stopOptions}</select></label>` +
      `<label class="check"><input type="checkbox" name="certain"${state.certain ? " checked" : ""}> ${esc(t("impact.filter.certain"))}</label>` +
      (state.depth !== null ? `<span class="chip on" data-clear="depth">${esc(distanceText(t, state.depth))} ×</span>` : "");
  }
  if (state.tab === "tree") {
    filters = `<p class="tree-about muted">${esc(t("impact.tree.about"))}</p>` +
      `<button type="button" data-tree="open">${esc(t("impact.tree.openAll"))}</button> <button type="button" data-tree="close">${esc(t("impact.tree.closeAll"))}</button>`;
  }
  toolbar.innerHTML = `${filters}<span class="muted">${esc(t("impact.shown", { count: shown }))}</span> <button type="button" data-csv>${esc(t("impact.csv"))}</button>`;
}

document.getElementById("nodes")!.addEventListener("click", e => {
  const target = e.target as Element;
  const tab = target.closest<HTMLElement>("[data-tab]");
  if (tab) {
    state.tab = (["all", "tree"].includes(tab.dataset.tab!) ? tab.dataset.tab : "entries") as ImpactTab;
    writeUrl();
    renderNodes();
    return;
  }
  if (target.closest("[data-clear=depth]")) {
    state.depth = null;
    writeUrl();
    renderNodes();
    return;
  }
  const all = target.closest<HTMLElement>("[data-tree]");
  if (all) {
    openTree(all.dataset.tree === "open");
    return;
  }
  const moreTree = target.closest<HTMLButtonElement>("button.more-tree");
  if (moreTree) {
    buildChildren(Number(moreTree.dataset.more));
    return;
  }
  if (target.closest("[data-csv]")) {
    downloadCsv();
    return;
  }
  const why = target.closest<HTMLButtonElement>("button.why");
  if (why) {
    togglePath(why);
    return;
  }
  const more = target.closest<HTMLButtonElement>("button.more-rows");
  if (more) buildRows(more.closest<HTMLDetailsElement>("details.ngroup")!);
});

document.getElementById("toolbar")!.addEventListener("change", e => {
  const input = e.target as HTMLInputElement;
  if (input.name === "stop") state.stop = input.value || null;
  if (input.name === "certain") state.certain = input.checked;
  writeUrl();
  renderNodes();
});

// A folded group builds its rows the first time it opens, and so does a level of the tree.
groupsElement.addEventListener("toggle", e => {
  const details = e.target as HTMLDetailsElement;
  if (details.matches("details.tnode")) {
    const id = Number(details.dataset.item);
    if (details.open && page.tree && page.tree.built[id] === 0) buildChildren(id);
    return;
  }
  if (details.matches("details.ngroup") && details.open && page.built[Number(details.dataset.group)] === 0) buildRows(details);
}, true);

// ---- rows ---------------------------------------------------------------------------------------------------------

/** Adds the next rows of [details]'s group, with "show more" while some are left. */
function buildRows(details: HTMLDetailsElement) {
  const group = Number(details.dataset.group);
  const nodes = page.groups[group];
  const from = page.built[group];
  const to = Math.min(nodes.length, from + ROWS_STEP);
  page.built[group] = to;
  const holder = details.querySelector(".rows")!;
  holder.querySelector("button.more-rows")?.remove();
  // Names short: without the schema, or the package the entry points tab groups them under; overloads of the group
  // told apart.
  const graph = page.view!;
  const entities = nodes.map(index => graph.nodes[index].entity).filter((e): e is number => e !== null).map(e => graph.entities[e]);
  const detail = listNameOptions(entities);
  const naming = (entity: EntitySummary, owner: number | null): NameOptions =>
    ({ ...detail(entity), ...nameShape(entity, state.tab === "entries" && owner !== null ? graph.entities[owner] : null) });
  const rows = nodes.slice(from, to).map(index => rowHtml(index, naming)).join("");
  let body = holder.querySelector("tbody");
  if (!body) {
    const stop = state.tab === "all" ? `<th>${esc(t("impact.head.stop"))}</th>` : "";
    // Fixed column widths, so the tables of every group line up.
    const columns = `<colgroup><col class="c-why"><col class="c-name"><col class="c-kind"><col class="c-depth">` +
      `${state.tab === "all" ? '<col class="c-stop">' : ""}<col class="c-notes"></colgroup>`;
    holder.innerHTML = scrollTable(`${columns}<thead><tr><th></th><th>${esc(t("impact.column.name"))}</th><th>${esc(t("impact.column.kind"))}</th>` +
      `<th>${esc(t("impact.head.distance"))}</th>${stop}<th>${esc(t("impact.column.notes"))}</th></tr></thead><tbody></tbody>`,
      state.tab === "all" ? "impact with-stop stack" : "impact stack");
    body = holder.querySelector("tbody")!;
  }
  body.insertAdjacentHTML("beforeend", rows);
  if (to < nodes.length) {
    holder.insertAdjacentHTML("beforeend", `<button type="button" class="more-rows">${esc(t("impact.more", { count: nodes.length - to }))}</button>`);
  }
}

function fileHref(path: string, line: number) {
  return `file.html?path=${encodeURIComponent(path)}&line=${line}${langSuffix()}#L${line}`;
}

function nodeNameHtml(view: ImpactView, index: number, options?: NameOptions) {
  const node = view.nodes[index];
  if (node.entity !== null) return entityLink(view.entities[node.entity], options);
  const path = view.paths[node.path!];
  return `<a class="mono" href="${fileHref(path, node.firstLine!)}">${esc(path)}</a>`;
}

/** A trigger's note as the CSV writes it: its codes and the table's full name, for tools. */
function triggerText(trigger: ImpactTrigger) {
  const events = trigger.events.map(event =>
    [event.timing.replace("_", " "), event.level, event.event + (event.column ? " OF " + event.column.name : "")].filter(Boolean).join(" "));
  return t("impact.trigger", { table: trigger.table?.display ?? "—", events: events.join(", ") || "—" });
}

function rowHtml(index: number, naming: (entity: EntitySummary, owner: number | null) => NameOptions) {
  const view = page.view!;
  const node = view.nodes[index];
  const entity = node.entity !== null ? view.entities[node.entity] : null;
  const notes: string[] = [];
  if (!node.certain) notes.push(`<span class="badge uncertain" title="${esc(t("impact.notCertainHint"))}">${esc(t("impact.notCertain"))}</span>`);
  if (node.trigger) notes.push(esc(triggerNote(t, node.trigger)));
  if (node.uses !== null) notes.push(esc(t("impact.fileUses", { count: node.uses })));
  const stop = state.tab === "all" ? `<td title="${esc(node.stop)}">${esc(t("impact.stopWord." + node.stop))}</td>` : "";
  return `<tr data-node="${index}"><td><button type="button" class="why" aria-expanded="false">${esc(t("impact.why"))}</button></td>` +
    `<td class="grow">${nodeNameHtml(view, index, entity ? naming(entity, node.owner) : undefined)}</td>` +
    `<td>${node.kind === "FILE" ? esc(t("impact.kind.FILE")) : kindBadge(node.kind)}</td>` +
    `${stackCell(esc(distanceText(t, node.depth)))}${stop}<td>${notes.join(" ")}</td></tr>`;
}

/**
 * Unfolds the path of a row's node below it, one line per step back to the object the page starts from, each read
 * like a "Used by" row: the short name, then "line 7" linking the file (its path in the tooltip), the status in words
 * and what the step fires; a second click folds it.
 */
function togglePath(button: HTMLButtonElement) {
  const row = button.closest("tr")!;
  const open = row.nextElementSibling?.classList.contains("path");
  button.setAttribute("aria-expanded", String(!open));
  if (open) {
    row.nextElementSibling!.remove();
    return;
  }
  const view = page.view!;
  const { hops, target } = pathOf(view, Number(row.dataset.node));
  const lines = hops.map(hop => {
    const line = pathLine(t, view, hop);
    const name = line.entity ? entityLink(line.entity, nameShape(line.entity)) : `<span class="mono">${esc(line.name)}</span>`;
    const notes = [`<a href="${fileHref(line.path, line.line)}" title="${esc(line.path)}">${esc(line.place)}</a>`,
      `<span class="muted mono">${esc(line.path)}</span>`];
    if (line.fires) notes.push(esc(line.fires));
    if (line.status) notes.push(statusBadge(hop.status));
    return `<li>${name} <span class="muted">·</span> ${notes.join(" ")}</li>`;
  });
  lines.push(`<li>${entityLink(target, nameShape(target))} <span class="muted">(${esc(pathEnd(t, view, target))})</span></li>`);
  row.insertAdjacentHTML("afterend", `<tr class="path"><td class="pad"></td><td class="whole" colspan="${row.children.length - 1}">` +
    `<p class="muted">${esc(t("impact.path.title"))}</p><ol class="hops">${lines.join("")}</ol></td></tr>`);
}

// ---- the tree ----------------------------------------------------------------------------------------------------

/**
 * The tree tab: the object the page starts from, the parts of it the first steps use, and each object under the one it
 * was first reached through. Levels are built when they first open.
 */
function renderTree(view: ImpactView) {
  const named = view.nodes.map(node => node.entity !== null ? view.entities[node.entity] : null);
  const detail = listNameOptions(named.filter((e): e is EntitySummary => e !== null));
  treeNaming = (entity: EntitySummary) => ({ ...detail(entity), ...nameShape(entity) });
  const root = buildTree(view, index => named[index] ? shownNameText(named[index]!, nameShape(named[index]!)) : view.paths[view.nodes[index].path!]);
  page.tree = { items: [], levels: [], built: [] };
  groupsElement.innerHTML = `<ul class="tree">${treeItemHtml(root, 0)}</ul>`;
  buildChildren(0);
}

let treeNaming: (entity: EntitySummary) => NameOptions = entity => nameShape(entity);

/** One line of the tree, as a list item; a line with children is a `<details>` the whole line opens. */
function treeItemHtml(item: TreeItem, level: number) {
  const tree = page.tree!;
  const id = tree.items.length;
  tree.items.push(item);
  tree.levels.push(level);
  tree.built.push(0);
  const line = treeLineHtml(item, level);
  if (!item.children.length) return `<li data-level="${level}"><div class="tree-line">${line}</div></li>`;
  const open = level <= TREE_OPEN_LEVELS ? " open" : "";
  return `<li data-level="${level}"><details class="tnode" data-item="${id}"${open}><summary class="tree-line">${line}</summary>` +
    `<ul data-children="${id}"></ul></details></li>`;
}

/**
 * A line: the kind square and the short name; for an object, "line N" linking the file at the use, the status of an
 * unresolved step, "not certain" when an earlier step was not resolved, what a trigger step fires; how many objects
 * sit below. On a phone, a deep line says how far the object is, since it is no longer indented.
 */
function treeLineHtml(item: TreeItem, level: number) {
  const view = page.view!;
  const below = item.below ? ` <span class="muted">· ${esc(t("impact.tree.below", { count: item.below }))}</span>` : "";
  if (item.node === null) {
    const entity = item.part !== null ? view.entities[item.part] : view.entity;
    const within = item.part !== null && entity.display.startsWith(view.entity.display + ".") ? view.entity : null;
    return `${kindBadge(entity.kind, true)} ${entityLink(entity, nameShape(entity, within))}${below}`;
  }
  const node = view.nodes[item.node];
  const hop = { node: item.node, path: view.paths[node.via.path], line: node.via.line, status: node.via.status, fires: node.via.fires };
  const words = pathLine(t, view, hop);
  const name = node.entity !== null
    ? `${kindBadge(node.kind, true)} ${entityLink(view.entities[node.entity], treeNaming(view.entities[node.entity]))}`
    : `<span class="badge">${esc(t("impact.kind.FILE"))}</span> ${nodeNameHtml(view, item.node)}`;
  const notes = [`<a href="${fileHref(words.path, words.line)}" title="${esc(words.path)}">${esc(words.place)}</a>`];
  if (words.status) notes.push(statusBadge(hop.status));
  else if (!node.certain) notes.push(`<span class="badge uncertain" title="${esc(t("impact.notCertainHint"))}">${esc(t("impact.notCertain"))}</span>`);
  if (words.fires) notes.push(esc(words.fires));
  if (item.loop) notes.push(`<span class="muted">${esc(t("impact.tree.loop"))}</span>`);
  const steps = level >= TREE_FLAT_LEVEL ? ` <span class="steps muted">· ${esc(distanceText(t, node.depth))}</span>` : "";
  return `${name} <span class="muted">·</span> ${notes.join(" ")}${below}${steps}`;
}

/** Adds the next children of line [id], with "show N more" while some are left. */
function buildChildren(id: number) {
  const tree = page.tree!;
  const item = tree.items[id];
  const list = groupsElement.querySelector<HTMLUListElement>(`ul[data-children="${id}"]`);
  if (!list) return;
  const from = tree.built[id];
  const to = Math.min(item.children.length, from + TREE_STEP);
  tree.built[id] = to;
  list.querySelector(":scope > li.more")?.remove();
  const html = item.children.slice(from, to).map(child => treeItemHtml(child, tree.levels[id] + 1)).join("");
  list.insertAdjacentHTML("beforeend", html);
  if (to < item.children.length) {
    list.insertAdjacentHTML("beforeend", `<li class="more"><button type="button" class="more-tree" data-more="${id}">` +
      `${esc(t("impact.more", { count: item.children.length - to }))}</button></li>`);
  }
  // A line drawn open (the first levels) builds its own children too.
  const end = tree.items.length;
  for (let next = end - (to - from); next < end; next++) {
    if (tree.levels[next] <= TREE_OPEN_LEVELS && tree.items[next].children.length && tree.built[next] === 0) buildChildren(next);
  }
}

/** "Open all" opens every level, building each once; "Close all" folds all but the root. */
function openTree(open: boolean) {
  if (!page.tree) return;
  for (;;) {
    const folded = [...groupsElement.querySelectorAll<HTMLDetailsElement>("details.tnode")].filter(d => d.open !== open && (open || d.dataset.item !== "0"));
    if (!folded.length) return;
    for (const details of folded) {
      const id = Number(details.dataset.item);
      if (open && page.tree.built[id] === 0) buildChildren(id);
      details.open = open;
    }
    if (!open) return;
  }
}

// ---- CSV ----------------------------------------------------------------------------------------------------------

/** The rows the tab shows, every group whole, as a CSV file the browser downloads. */
function downloadCsv() {
  const view = page.view!;
  const shown = shownNodes(view, state);
  const fires = (events: string) => t("impact.fires", { events });
  const header = [t("impact.column.name"), t("impact.column.key"), t("impact.column.kind"), t("impact.column.depth"), t("impact.column.stop"),
    t("impact.column.certain"), t("impact.column.trigger"), t("impact.column.path")];
  const groupOf = new Map<number, string>();
  if (state.tab === "entries") {
    header.unshift(t("impact.column.group"));
    for (const group of groupByOwner(view, shown)) {
      const label = group.files ? t("impact.group.files") : group.owner?.display ?? t("impact.group.schema", { schema: group.schema ?? t("entity.schemaUnknown") });
      for (const index of group.nodes) groupOf.set(index, label);
    }
  }
  const order = state.tab === "entries" ? [...groupOf.keys()] : shown;
  const rows = order.map(index => {
    const node = view.nodes[index];
    const row = [nodeText(view, index), node.entity !== null ? view.entities[node.entity].key : "",
      node.kind === "FILE" ? t("impact.kind.FILE") : kindLabel(node.kind), node.depth, t("impact.stopShort." + node.stop),
      t(node.certain ? "impact.yes" : "impact.no"), node.trigger ? triggerText(node.trigger) : "", pathText(view, index, fires)];
    if (state.tab === "entries") row.unshift(groupOf.get(index)!);
    return row;
  });
  const blob = new Blob(["\uFEFF" + csvText([header, ...rows])], { type: "text/csv;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `impact-${view.entity.name}-${state.tab === "entries" ? "entry-points" : "nodes"}.csv`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 0);
}

// ---- the options --------------------------------------------------------------------------------------------------

options.addEventListener("submit", e => {
  e.preventDefault();
  const depth = (options.elements.namedItem("maxDepth") as HTMLInputElement).valueAsNumber;
  state.maxDepth = Number.isInteger(depth) ? Math.min(MAX_DEPTH, Math.max(1, depth)) : state.maxDepth;
  state.triggers = (options.elements.namedItem("triggers") as HTMLInputElement).checked;
  load();
});
options.addEventListener("change", e => {
  if ((e.target as HTMLInputElement).name === "triggers") options.requestSubmit();
});

(async () => {
  await initI18n();
  showBanner();
  if (!state.key) {
    setPageTitle(t("page.impact.titleMissing"));
    document.getElementById("title")!.textContent = t("entity.missingKey");
    return;
  }
  await load();
})();
