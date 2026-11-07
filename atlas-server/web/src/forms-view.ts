// What the pages of Forms objects show, without the DOM: names and paths as a Forms developer knows them, the module's
// objects as a tree that opens, closes and filters by name, as the Form Builder's Object Navigator does, which piece
// of code the code page shows, and what a data block and an item make Forms query and write with no code.

import type {
    CodeAt,
    EntityDetail,
    EntitySummary,
    FormsBlock,
    FormsDataSource,
    FormsGlobalModule,
    FormsGroup,
    FormsGroupKind,
    FormsInfo,
    FormsItem,
    FormsNode,
    FormsTree
} from "./api";

/** The kinds whose page shows their objects as a tree (`api/forms-tree`). */
export const FORMS_TREE_KINDS = ["FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM"];
/** The kinds that are code, which the code page shows (`api/forms-file`): a trigger, a program unit, a record group's query. */
export const FORMS_CODE_KINDS = ["FORMS_TRIGGER", "FORMS_PROGRAM_UNIT", "FORMS_RECORD_GROUP"];

/** A Forms object as a page inside its module names it: the page it links to, the name it shows, its tooltip. */
export interface FormsName {
  key: string;
  label: string;
  title: string;
}

/**
 * How a page inside the module [forms] names a Forms object: by its own name (`ID1`), the module by its title (its
 * internal name, `RELEASE_LOCKS`); the full path (`DEMO0004/V$LOCK/ID1`) is the tooltip. A global, which belongs to no
 * module, as its code writes it (`:GLOBAL.ROUTE_USER`).
 */
export function formsName(entity: Pick<EntitySummary, "key" | "kind" | "name" | "display">, forms: FormsInfo | null): FormsName {
  const label = entity.kind === "FORMS_GLOBAL" ? entity.display : forms !== null && forms.module.key === entity.key ? forms.title : entity.name;
  return { key: entity.key, label, title: entity.display };
}

/**
 * What the code page's popover says of an item or a parameter, which the module defines and no code declares: the
 * catalog key of its kind (an item's own, `formsItemKind.TEXT_ITEM`) and its path (`DATA_DESK/EVENTS/CODE`), which
 * links to its page; null for anything else (a global belongs to no module).
 */
export function formsDefinition(entity: Pick<EntityDetail, "kind" | "display" | "formsItem">): { kindKey: string; path: string } | null {
  if (entity.kind === "FORMS_PARAMETER") return { kindKey: "popover.formsParameter", path: entity.display };
  if (entity.kind !== "FORMS_ITEM") return null;
  return { kindKey: entity.formsItem ? "formsItemKind." + entity.formsItem.kind : "kind.FORMS_ITEM", path: entity.display };
}

/**
 * A row of a Forms global's "Forms that use it": the module and its file, how many of its uses set the global and how
 * many read it, each with the query page's filters listing those uses (null when there are none).
 */
export interface GlobalUseRow {
  module: EntitySummary;
  path: string;
  writes: number;
  reads: number;
  writesQuery: Record<string, string> | null;
  readsQuery: Record<string, string> | null;
}

/** The rows of the global [key]'s "Forms that use it", one per module of [uses], in the answer's order. */
export function globalUseRows(key: string, uses: FormsGlobalModule[]): GlobalUseRow[] {
  const query = (count: number, roles: string, path: string) => count === 0 ? null : { entity: key, roles, path };
  return uses.map(u => ({ module: u.module, path: u.path, writes: u.writes, reads: u.reads,
    writesQuery: query(u.writes, "WRITE", u.path), readsQuery: query(u.reads, "READ", u.path) }));
}

/**
 * The crumbs above a Forms object's title: the module by its title, then the other objects that hold it, each by its
 * own name, outermost first; nothing for the module itself.
 */
export function formsCrumbs(entity: Pick<EntityDetail, "ancestors" | "forms">): FormsName[] {
  return entity.ancestors.map(a => formsName(a, entity.forms));
}

/** The own name of the Forms code unit [key] (`forms:DEMO0004/STEUERBLOCK/POST-QUERY#2` → `POST-QUERY`), as a place names it. */
export function codeUnitName(key: string) {
  return key.slice(key.lastIndexOf("/") + 1).replace(/#\d+$/, "");
}

// ---- the code page -------------------------------------------------------------------------------------------------

/** A piece of Forms code as the code page names it: its entity's key and, for a package's half, its kind. */
export type CodeUnit = Pick<CodeAt, "key" | "unitKind">;

/** Whether [a] and [b] are the same piece of code (a package's specification is not its body). */
export function sameUnit(a: CodeUnit, b: CodeUnit) {
  return a.key === b.key && (a.unitKind ?? null) === (b.unitKind ?? null);
}

/**
 * Which of [lines] around [line] a snippet shows: inside Forms code only those of [line]'s unit, numbered as the
 * code page numbers them (the lines around it in the module's text belong to other code); all of them elsewhere.
 */
export function unitLines<L extends { number: number; code?: CodeUnit | null }>(lines: L[], line: number): L[] {
  const unit = lines.find(l => l.number === line)?.code ?? null;
  return unit === null ? lines : lines.filter(l => l.code != null && sameUnit(l.code, unit));
}

/** The piece of code [node] is; null for an object that is not code (the module, a block, an item, a library). */
export function nodeUnit(node: FormsNode): CodeUnit | null {
  return FORMS_CODE_KINDS.includes(node.entity.kind) ? { key: node.entity.key, unitKind: node.unitKind ?? null } : null;
}

/** The first node of [groups] [keep] keeps, in the tree's order: depth first, each node before the groups under it. */
function firstNode(groups: FormsGroup[], keep: (node: FormsNode) => boolean): FormsNode | null {
  for (const group of groups) {
    for (const node of group.nodes) {
      if (keep(node)) return node;
      const under = firstNode(node.groups, keep);
      if (under !== null) return under;
    }
  }
  return null;
}

/** The node of [tree] that is the piece of code [unit] (a package's half, its own node); null when the tree has none. */
export function unitNode(tree: FormsTree, unit: CodeUnit): FormsNode | null {
  return firstNode(tree.groups, node => {
    const own = nodeUnit(node);
    return own !== null && sameUnit(own, unit);
  });
}

/**
 * The first piece of code of [tree], in the order the server opens a module's code page in (a trigger, a program
 * unit's half, a record group); null when the module has no code.
 */
export function firstUnit(tree: FormsTree): CodeUnit | null {
  const node = firstNode(tree.groups, node => nodeUnit(node) !== null);
  return node === null ? null : nodeUnit(node);
}

/** The catalog key of the word after a package's half (`spec`, `body`), which tells its two nodes apart; null for other code. */
export function unitKindWord(unitKind: string | null | undefined) {
  return unitKind === "PACKAGE" ? "entity.overload.spec" : unitKind === "PACKAGE_BODY" ? "entity.overload.body" : null;
}

/**
 * What the code page's address asks for: the piece of code `unit` and `unitKind` name (null: the module's first) and
 * the line to mark, `#L5` before `line=5` (null: none). A line without a unit is the module text's, which the page no
 * longer shows: none is marked.
 */
export function wantedCode(search: string, hash: string): { unit: CodeUnit | null; line: number | null } {
  const params = new URLSearchParams(search);
  const key = params.get("unit");
  if (!key) return { unit: null, line: null };
  const marked = /^#L(\d+)$/.exec(hash);
  return { unit: { key, unitKind: params.get("unitKind") || null }, line: marked ? Number(marked[1]) : Number(params.get("line")) || null };
}

/**
 * Where the code page's address should say it is once it shows [shown], asked for [wanted]: null when the address
 * already names it; else [shown], the line kept when its unit is the one asked for (a package's other half, asked
 * without its kind), none when the server showed the first piece of code instead.
 */
export function settledCode(wanted: { unit: CodeUnit | null; line: number | null }, shown: CodeUnit): { unit: CodeUnit; line: number | null } | null {
  if (wanted.unit !== null && sameUnit(wanted.unit, shown)) return null;
  return { unit: shown, line: wanted.unit !== null && wanted.unit.key === shown.key ? wanted.line : null };
}

/** A request of the code page: the API asked and its parameters. */
export interface CodePageAsk {
  api: "api/forms-file" | "api/file";
  params: Record<string, string | boolean | null | undefined>;
}

/**
 * What the code page of [path] asks, so that no request fails: with [unit] named, or once `api/file` said the file is
 * a Forms module ([fileKind]), the module's code page (`api/forms-file`, its first unit without [unit]); else the
 * file itself (`api/file`).
 */
export function codePageAsk(path: string, unit: CodeUnit | null, fileKind?: string): CodePageAsk {
  return unit !== null || fileKind === "FORMS_MODULE"
    ? { api: "api/forms-file", params: { path, key: unit?.key, unitKind: unit?.unitKind, colors: true } }
    : { api: "api/file", params: { path, colors: true } };
}

// ---- a data block's and an item's data ------------------------------------------------------------------------------

/**
 * A block's data source or DML target as its page shows it: its relation to link ([entity]), else its name as written
 * ([written]); [typeKey], the catalog key of its type, said beside it unless it is a table.
 */
export interface DataSourceView {
  entity: EntitySummary | null;
  written: string;
  typeKey: string | null;
}

/** One of a block's operations as its page shows it: the catalog keys of the operation and of its state, and the trigger that replaces it. */
export interface OperationView {
  operationKey: string;
  stateKey: string;
  trigger: EntitySummary | null;
}

/** What a data block's page shows of its data: its source and DML target, its four operations, its WHERE and ORDER BY (null for none). */
export interface BlockView {
  source: DataSourceView;
  target: DataSourceView;
  operations: OperationView[];
  where: string | null;
  orderBy: string | null;
}

function dataSourceView(source: FormsDataSource): DataSourceView {
  return { entity: source.entity ?? null, written: source.name, typeKey: source.type === "TABLE" ? null : "formsDataType." + source.type };
}

/**
 * What the page of a data block shows of [block]: the relation it queries and the one it writes, each a link (its
 * name as written when the block is not on a table, with its type), each of its four operations done by Forms with
 * no code, replaced by the block's trigger or the module's, not allowed or not followed by the index, and its WHERE
 * and ORDER BY.
 */
export function blockView(block: FormsBlock): BlockView {
  return {
    source: dataSourceView(block.source),
    target: dataSourceView(block.target),
    operations: block.operations.map(o => {
      const trigger = o.state === "REPLACED" ? o.trigger ?? null : null;
      // A module's trigger has one slash in its key (`forms:DEMO0004/ON-INSERT`), a block's or an item's more.
      const state = trigger !== null && trigger.key.indexOf("/") === trigger.key.lastIndexOf("/") ? "REPLACED_BY_MODULE" : o.state;
      return { operationKey: "formsOperation." + o.operation, stateKey: "formsOperationState." + state, trigger };
    }),
    where: block.where.trim() === "" ? null : block.where,
    orderBy: block.orderBy.trim() === "" ? null : block.orderBy
  };
}

/**
 * What an item's page shows of its data: its kind's catalog key, whether it is a [database] item, its [column] (the
 * block's source's, when the item stands for one) and the DML target's when another relation's, and, with a column,
 * what Forms does with it, each a catalog key and yes or no.
 */
export interface ItemView {
  kindKey: string;
  database: boolean;
  column: EntitySummary | null;
  targetColumn: EntitySummary | null;
  done: { key: string; yes: boolean }[];
}

/** What the page of an item shows of [item] ([ItemView]). */
export function itemView(item: FormsItem): ItemView {
  const column = item.column ?? null;
  return {
    kindKey: "formsItemKind." + item.kind,
    database: item.database,
    column,
    targetColumn: item.targetColumn ?? null,
    done: column === null ? [] : [
      { key: "forms.data.read", yes: item.read },
      { key: "forms.data.insertWrite", yes: item.insertWrite },
      { key: "forms.data.updateWrite", yes: item.updateWrite }
    ]
  };
}

// ---- the tree ------------------------------------------------------------------------------------------------------

/** The id of the group [kind] under the node (or root) [parent], as the tree's open state names it. */
export function groupId(parent: string, kind: FormsGroupKind) {
  return `${parent}|${kind}`;
}

/** The id of the object [key] (a package's half [unitKind]), as the tree's open state names it: a package's two nodes have two. */
export function nodeId(key: string, unitKind?: string | null) {
  return unitKind ? `${key}|${unitKind}` : key;
}

/** A line of the tree as shown: a group heading, or an object (which opens when it holds groups). */
export type FormsRow =
  | { type: "group"; id: string; depth: number; kind: FormsGroupKind; count: number; open: boolean }
  | { type: "node"; id: string; depth: number; entity: EntitySummary; unitKind: string | null; openable: boolean; open: boolean };

/** What a filter keeps: the groups and objects shown (by id), and those it opens. */
export interface FormsFilter {
  shown: Set<string>;
  open: Set<string>;
}

/** A group or a node with the groups under it, walked the same way whichever it is. */
interface Branch {
  key: string;
  groups: FormsTree["groups"];
}

/** Calls [visit] for every group and node under [branch], outermost first, in the tree's order. */
function walk(branch: Branch, visit: (id: string, parent: string | null, groupKind: FormsGroupKind | null, entity: EntitySummary | null) => void) {
  for (const group of branch.groups) {
    const id = groupId(branch.key, group.kind);
    visit(id, branch.key, group.kind, null);
    for (const node of group.nodes) {
      const key = nodeId(node.entity.key, node.unitKind);
      visit(key, id, null, node.entity);
      walk({ key, groups: node.groups }, visit);
    }
  }
}

const rootOf = (tree: FormsTree): Branch => ({ key: tree.root.key, groups: tree.groups });

/** Every group and every object that holds groups: what opens and closes, in the tree's order. */
export function openableIds(tree: FormsTree): string[] {
  const ids: string[] = [];
  const holders = new Set<string>();
  walk(rootOf(tree), (id, parent, kind) => {
    if (kind !== null) {
      ids.push(id);
      if (parent !== null) holders.add(parent);
    }
  });
  // An object opens when a group hangs under it: its groups' parents, in the tree's order.
  walk(rootOf(tree), (id, _, kind) => { if (kind === null && holders.has(id)) ids.push(id); });
  return ids;
}

/** The first open state: every group open, every object (a block, an item with triggers) closed. */
export function initialOpen(tree: FormsTree): Set<string> {
  const open = new Set<string>();
  walk(rootOf(tree), (id, _, kind) => { if (kind !== null) open.add(id); });
  return open;
}

/** "Expand all": every group and object open. */
export function allOpen(tree: FormsTree): Set<string> {
  return new Set(openableIds(tree));
}

/** The group or object that holds each group and object of [tree], by id; null for the root's groups. */
function parentsOf(tree: FormsTree) {
  const parents = new Map<string, string | null>();
  walk(rootOf(tree), (id, parent) => parents.set(id, parent === tree.root.key ? null : parent));
  return parents;
}

/** [open] with the groups and objects that hold [id] opened too, so that it shows. */
export function openTo(tree: FormsTree, open: Set<string>, id: string): Set<string> {
  const parents = parentsOf(tree);
  const opened = new Set(open);
  for (let up = parents.get(id) ?? null; up !== null; up = parents.get(up) ?? null) opened.add(up);
  return opened;
}

/**
 * What a filter by [text] keeps: each object whose name contains it (ignoring case), the groups and objects leading to
 * it (opened, so it shows) and what it holds (shown, its objects closed and its groups open, as at first); null for a
 * blank text, which filters nothing.
 */
export function filterTree(tree: FormsTree, text: string): FormsFilter | null {
  const wanted = text.trim().toLowerCase();
  if (wanted === "") return null;
  const parents = parentsOf(tree);
  const matches: string[] = [];
  walk(rootOf(tree), (id, _, __, entity) => {
    if (entity !== null && entity.name.toLowerCase().includes(wanted)) matches.push(id);
  });
  const shown = new Set<string>();
  const open = new Set<string>();
  for (const match of matches) {
    shown.add(match);
    for (let up = parents.get(match) ?? null; up !== null; up = parents.get(up) ?? null) {
      shown.add(up);
      open.add(up);
    }
  }
  // What a kept object holds stays reachable: its groups and objects show once it is opened, its groups open.
  const under = new Set(matches);
  walk(rootOf(tree), (id, parent, kind) => {
    if (parent === null || !under.has(parent)) return;
    under.add(id);
    shown.add(id);
    if (kind !== null) open.add(id);
  });
  return { shown, open };
}

/**
 * The lines shown, in order: each group and object under an open one (the root's groups always), with [filter] only
 * those it keeps.
 */
export function visibleRows(tree: FormsTree, open: Set<string>, filter: FormsFilter | null): FormsRow[] {
  const rows: FormsRow[] = [];
  const kept = (id: string) => filter === null || filter.shown.has(id);
  const add = (branch: Branch, depth: number) => {
    for (const group of branch.groups) {
      const id = groupId(branch.key, group.kind);
      if (!kept(id)) continue;
      rows.push({ type: "group", id, depth, kind: group.kind, count: group.nodes.length, open: open.has(id) });
      if (!open.has(id)) continue;
      for (const node of group.nodes) {
        const key = nodeId(node.entity.key, node.unitKind);
        if (!kept(key)) continue;
        const openable = node.groups.length > 0;
        rows.push({ type: "node", id: key, depth: depth + 1, entity: node.entity, unitKind: node.unitKind ?? null, openable, open: openable && open.has(key) });
        if (openable && open.has(key)) add({ key, groups: node.groups }, depth + 2);
      }
    }
  };
  add(rootOf(tree), 0);
  return rows;
}
