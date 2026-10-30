// What the impact page computes from a graph, without the DOM: the answer's types, the page state in its URL, the
// path of a node, the groups of nodes, the tree of first uses and the CSV.

import type { Count, EntitySummary } from "./api";

/** One event of a trigger node: as `TriggerEvent` without the trigger. */
export interface ImpactTriggerEvent {
  timing: string;
  level: string | null;
  event: string;
  column: EntitySummary | null;
}

/** The table a trigger node is on, and the events that fire it. */
export interface ImpactTrigger {
  table: EntitySummary | null;
  events: ImpactTriggerEvent[];
}

/**
 * One use on a shortest path to a node: [node] the node it uses (an index into the nodes), null on the first hop,
 * where [uses] (an index into the entities) is the entity asked for or what the first hop matched.
 */
export interface ImpactViewVia {
  node: number | null;
  uses: number;
  path: number;
  line: number;
  status: string;
  fires: string | null;
}

/** A node: [entity] an index into the entities, or for a file node [path] into the paths. */
export interface ImpactViewNode {
  kind: string;
  entity: number | null;
  path: number | null;
  uses: number | null;
  firstLine: number | null;
  depth: number;
  certain: boolean;
  stop: string;
  /** The package or type the node belongs to (an index into the entities); null for standalone code and files. */
  owner: number | null;
  schema: string | null;
  via: ImpactViewVia;
  trigger: ImpactTrigger | null;
}

/** An entity of the graph as the answer writes it: [display] null when it is the key without `db:`. */
export interface ImpactEntity {
  key: string;
  kind: string;
  display: string | null;
  external: boolean;
  name: string;
}

/** `/api/impact/graph`: the whole graph, each entity and path written once. */
export interface ImpactAnswer {
  entity: EntitySummary;
  included: string[];
  maxDepth: number;
  followTriggers: boolean;
  levels: number[];
  stops: Count[];
  certain: number;
  entities: ImpactEntity[];
  paths: string[];
  nodes: ImpactViewNode[];
}

/** [ImpactAnswer] with every entity's display filled in: what the page reads. */
export interface ImpactView {
  entity: EntitySummary;
  included: string[];
  maxDepth: number;
  followTriggers: boolean;
  levels: number[];
  stops: Count[];
  certain: number;
  entities: EntitySummary[];
  paths: string[];
  nodes: ImpactViewNode[];
}

/** The stops in the walk's order, which the page keeps. */
export const STOPS = ["FOLLOWED", "ENTRY_POINT", "TRIGGER", "PACKAGE_LEVEL", "DEPTH_LIMIT", "NO_CONTAINER"];

/** [answer] as the page reads it: a display left out is the key without `db:`. */
export function viewOf(answer: ImpactAnswer): ImpactView {
  return { ...answer, entities: answer.entities.map(e => ({ ...e, display: e.display ?? e.key.replace(/^db:/, "") })) };
}

// ---- the page state, as its URL holds it ----------------------------------------------------------------------------

export const DEFAULT_DEPTH = 10;
export const MAX_DEPTH = 50;

export type ImpactTab = "entries" | "all" | "tree";

/** The graph asked for, the tab shown, and the filters of "All nodes". */
export interface ImpactState {
  key: string | null;
  triggers: boolean;
  maxDepth: number;
  tab: ImpactTab;
  stop: string | null;
  depth: number | null;
  certain: boolean;
}

function positive(text: string | null, max: number) {
  if (text === null || !/^\d+$/.test(text)) return null;
  const value = Number(text);
  return value >= 1 && value <= max ? value : null;
}

/** The state of the page's [search] string; a value out of range is its default. */
export function readState(search: string): ImpactState {
  const params = new URLSearchParams(search);
  const stop = params.get("stop");
  return {
    key: params.get("key") || null,
    triggers: params.get("triggers") !== "false",
    maxDepth: positive(params.get("maxDepth"), MAX_DEPTH) ?? DEFAULT_DEPTH,
    tab: params.get("tab") === "all" ? "all" : params.get("tab") === "tree" ? "tree" : "entries",
    stop: stop && STOPS.includes(stop) ? stop : null,
    depth: positive(params.get("depth"), MAX_DEPTH),
    certain: params.get("certain") === "true"
  };
}

/** The search string of [state], defaults left out, [lang] kept: what [readState] reads back. */
export function stateSearch(state: ImpactState, lang?: string | null) {
  const params = new URLSearchParams();
  if (state.key) params.set("key", state.key);
  if (!state.triggers) params.set("triggers", "false");
  if (state.maxDepth !== DEFAULT_DEPTH) params.set("maxDepth", String(state.maxDepth));
  if (state.tab === "tree") params.set("tab", "tree");
  if (state.tab === "all") {
    params.set("tab", "all");
    if (state.stop) params.set("stop", state.stop);
    if (state.depth !== null) params.set("depth", String(state.depth));
    if (state.certain) params.set("certain", "true");
  }
  if (lang) params.set("lang", lang);
  const text = params.toString();
  return text ? "?" + text : "";
}

/** The nodes [state]'s tab shows, by index in the graph's order: the tree shows every node, without filters. */
export function shownNodes(view: ImpactView, state: ImpactState) {
  const shown: number[] = [];
  view.nodes.forEach((node, index) => {
    if (state.tab === "tree") {
      shown.push(index);
      return;
    }
    if (state.tab === "entries" ? node.stop !== "ENTRY_POINT"
      : (state.stop !== null && node.stop !== state.stop) || (state.depth !== null && node.depth !== state.depth) || (state.certain && !node.certain)) return;
    shown.push(index);
  });
  return shown;
}

// ---- the path of a node -------------------------------------------------------------------------------------------

/** One hop of a path: [node] (an index) uses the next hop's node, or the target, at [path] line [line]. */
export interface Hop {
  node: number;
  path: string;
  line: number;
  status: string;
  fires: string | null;
}

/**
 * The path from node [index] back to the entity: one hop per node, the node itself first, each using the next; the
 * [target] is what the last hop uses (the entity asked for, or what the first hop matched: a member, a view).
 */
export function pathOf(view: ImpactView, index: number): { hops: Hop[]; target: EntitySummary } {
  const hops: Hop[] = [];
  const seen = new Set<number>();
  let at: number | null = index;
  let last = view.nodes[index];
  while (at !== null && !seen.has(at)) {
    seen.add(at);
    last = view.nodes[at];
    hops.push({ node: at, path: view.paths[last.via.path], line: last.via.line, status: last.via.status, fires: last.via.fires });
    at = last.via.node;
  }
  return { hops, target: view.entities[last.via.uses] };
}

/** A node's name for text: its entity's display, or a file's path. */
export function nodeText(view: ImpactView, index: number) {
  const node = view.nodes[index];
  return node.entity !== null ? view.entities[node.entity].display : view.paths[node.path!];
}

/** [pathOf] as one line of text, the hops joined by " < ": `name (file:line, fires INSERT, UNVERIFIED) < … < target`. */
export function pathText(view: ImpactView, index: number, fires: (events: string) => string) {
  const { hops, target } = pathOf(view, index);
  const parts = hops.map(hop => {
    const notes = [`${hop.path}:${hop.line}`];
    if (hop.fires) notes.push(fires(hop.fires));
    if (hop.status !== "RESOLVED") notes.push(hop.status);
    return `${nodeText(view, hop.node)} (${notes.join(", ")})`;
  });
  return [...parts, target.display].join(" < ");
}

// ---- groups -------------------------------------------------------------------------------------------------------

/** A group of nodes: of a package or type ([owner]), of the standalone code of a [schema], or the script files. */
export interface NodeGroup {
  id: string;
  owner: EntitySummary | null;
  schema: string | null;
  files: boolean;
  nodes: number[];
}

/**
 * [indexes] grouped by the package or type each node belongs to, or by its schema for standalone code; script files
 * are a group of their own, last. Groups are in the order of their names; nodes keep the graph's order.
 */
export function groupByOwner(view: ImpactView, indexes: number[]): NodeGroup[] {
  const groups = new Map<string, NodeGroup>();
  for (const index of indexes) {
    const node = view.nodes[index];
    const owner = node.owner !== null ? view.entities[node.owner] : null;
    const id = node.kind === "FILE" ? "files" : owner ? "owner:" + owner.key : "schema:" + (node.schema ?? "");
    let group = groups.get(id);
    if (!group) {
      group = { id, owner, schema: owner ? null : node.schema, files: node.kind === "FILE", nodes: [] };
      groups.set(id, group);
    }
    group.nodes.push(index);
  }
  const label = (group: NodeGroup) => group.owner?.display ?? group.schema ?? "";
  return [...groups.values()].sort((a, b) => Number(a.files) - Number(b.files) || label(a).localeCompare(label(b)) || a.id.localeCompare(b.id));
}

/** [indexes] grouped by depth, shallowest first. */
export function groupByDepth(view: ImpactView, indexes: number[]) {
  const groups = new Map<number, number[]>();
  for (const index of indexes) {
    const depth = view.nodes[index].depth;
    let group = groups.get(depth);
    if (!group) groups.set(depth, group = []);
    group.push(index);
  }
  return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(([depth, nodes]) => ({ depth, nodes }));
}

// ---- the tree of first uses ----------------------------------------------------------------------------------------

/**
 * One line of the tree: a [node] (an index into the nodes), or a [part] of the entity the first steps use (an index
 * into the entities: a member, the spec or body). [below] counts the nodes under it; [loop] marks a node whose chain of
 * first uses loops back, placed under the root instead of under itself.
 */
export interface TreeItem {
  node: number | null;
  part: number | null;
  children: TreeItem[];
  below: number;
  loop: boolean;
}

/**
 * The nodes as a tree: each under the node it was first reached through (`via.node`). A first step goes under the
 * part of the entity it uses (`via.uses`), or straight under the root when it uses the entity itself. A chain that
 * loops back is cut at its lowest node, which goes under the root once. Every node appears exactly once; each level is
 * in the order of [nameOf] (a node's shown name), the parts in the order of their names.
 */
export function buildTree(view: ImpactView, nameOf: (index: number) => string = index => nodeText(view, index)): TreeItem {
  const count = view.nodes.length;
  const parent = view.nodes.map(node => node.via.node !== null && node.via.node >= 0 && node.via.node < count ? node.via.node : null);
  const loop = new Array<boolean>(count).fill(false);
  // Cut every loop: walk up from each node; a node met twice on one walk closes a loop.
  const settled = new Array<boolean>(count).fill(false);
  for (let start = 0; start < count; start++) {
    const walk: number[] = [];
    const onWalk = new Set<number>();
    let at: number | null = start;
    while (at !== null && !settled[at] && !onWalk.has(at)) {
      onWalk.add(at);
      walk.push(at);
      at = parent[at];
    }
    if (at !== null && onWalk.has(at)) {
      const cycle = walk.slice(walk.indexOf(at));
      const cut = Math.min(...cycle);
      parent[cut] = null;
      loop[cut] = true;
    }
    walk.forEach(index => { settled[index] = true; });
  }
  const item = (node: number | null, part: number | null): TreeItem => ({ node, part, children: [], below: 0, loop: node !== null && loop[node] });
  const items = view.nodes.map((_, index) => item(index, null));
  const root = item(null, null);
  const parts = new Map<number, TreeItem>();
  view.nodes.forEach((node, index) => {
    const up = parent[index];
    if (up !== null) {
      items[up].children.push(items[index]);
      return;
    }
    const uses = node.via.uses;
    if (loop[index] || !view.entities[uses] || view.entities[uses].key === view.entity.key) {
      root.children.push(items[index]);
      return;
    }
    let part = parts.get(uses);
    if (!part) parts.set(uses, part = item(null, uses));
    part.children.push(items[index]);
  });
  root.children.push(...parts.values());
  const names = new Map<number, string>();
  const nameOfItem = (tree: TreeItem) => tree.part !== null ? view.entities[tree.part].display
    : names.get(tree.node!) ?? (names.set(tree.node!, nameOf(tree.node!)), names.get(tree.node!)!);
  const order = (a: TreeItem, b: TreeItem) => nameOfItem(a).localeCompare(nameOfItem(b)) || (a.node ?? -1) - (b.node ?? -1) || (a.part ?? -1) - (b.part ?? -1);
  // Children sorted and counted, iteratively: a deep chain must not overflow the stack.
  const visit: TreeItem[] = [root];
  const post: TreeItem[] = [];
  while (visit.length) {
    const tree = visit.pop()!;
    post.push(tree);
    tree.children.sort(order);
    visit.push(...tree.children);
  }
  for (let i = post.length - 1; i >= 0; i--) post[i].below = post[i].children.reduce((sum, child) => sum + (child.node !== null ? 1 : 0) + child.below, 0);
  return root;
}

// ---- CSV ----------------------------------------------------------------------------------------------------------

/** A CSV field: quoted when it holds a comma, a quote, a line break or leading or trailing space; quotes doubled. */
export function csvField(value: string | number) {
  const text = String(value);
  return /[",\r\n]|^\s|\s$/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** [rows] as CSV text: fields joined by commas, records by CRLF (RFC 4180). */
export function csvText(rows: (string | number)[][]) {
  return rows.map(row => row.map(csvField).join(",")).join("\r\n") + "\r\n";
}
