import type {Answer, EntityDetail, EntitySummary, Member, OccurrencePage, Overload, ValueGroup} from "./api";
import {api, ApiFailure, errorMessage} from "./api";
import {showBanner} from "./banner";
import {loadCodes, usageRoles} from "./codes";
import {
    entityLink,
    kindBadge,
    kindLabel,
    listNameOptions,
    nameHtml,
    placeHtml,
    roleLabel,
    specBodyLinks,
    statusLabel,
    valueKindLabel
} from "./entity-html";
import type {Place, RoutineOrder, RoutineRow} from "./entity-view";
import {entityPath, entityTags, places, routineRows} from "./entity-view";
import {kindOrder} from "./kinds";
import {entityHref, esc, getOrPut, impactHref, literalText, queryHref, scrollTable} from "./html";
import {formatNumber, initI18n, t} from "./i18n";
import {displayParts, parentKeyOf} from "./names";
import {currentProject, setPageTitle} from "./project";
import {browserStorage, recordOpened} from "./recent";
import type {UsesBlock} from "./uses";
import {renderChips, setupUseRows, usesBlock} from "./uses";
import type {UsesContext} from "./uses-view";

/** A spec and its body, either of which may be missing. */
interface SpecBody<T> {
  spec: T | null;
  body: T | null;
}

/** A section of the content column, as the list on the left names it; a null count is not shown. */
interface SectionEntry {
  id: string;
  label: string;
  count: number | null;
}

type ScopeName = "implementations" | "children" | "views";
/** Where the uses of a package or type are listed from: other programs, or its own spec and body. */
type Side = "OUTSIDE" | "INSIDE";

setupUseRows();

const key = new URLSearchParams(location.search).get("key") ?? "";
const SUBPROGRAM_KINDS = ["PROCEDURE", "FUNCTION"];
const VIEWED_KINDS = ["TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "UNKNOWN_RELATION", "COLUMN"];
const state: {
  roles: Set<string>; statuses: Set<string>; implementations: boolean; children: boolean; views: boolean; after: number | null;
  scopes: ScopeName[]; direct: UsesBlock | null; ambiguous: UsesBlock | null; own: string;
  /** The side listed, for a package or a type (null for other entities, which have no switch). */
  side: Side | null; kind: string; display: string;
} = {
  roles: new Set(), statuses: new Set(), implementations: false, children: false, views: false, after: null,
  scopes: [], direct: null, ambiguous: null, own: "", side: null, kind: "", display: ""
};

/** A section's heading and the line saying what it holds. */
function sectionHead(title: string, line: string) {
  return `<h2>${esc(title)}</h2><p class="sec-line">${esc(line)}</p>`;
}

/** A list of entity links in columns under its heading. */
function linkList(title: string, line: string, items: string[]) {
  if (items.length === 0) return "";
  return sectionHead(title, line) + `<ul class="cols">${items.map(item => `<li>${item}</li>`).join("")}</ul>`;
}

/** A spec and its body (either may be missing) in their order, grouped by [keyOf]. */
function specBodyGroups<T>(list: T[], keyOf: (item: T) => string) {
  const groups = new Map<string, SpecBody<T>>();
  for (const item of list) {
    const k = keyOf(item);
    const parent = parentKeyOf(k);
    const group = getOrPut(groups, parent, () => ({ spec: null, body: null }));
    if (k === parent) group.spec = item;
    else group.body = item;
  }
  return [...groups.values()];
}

/**
 * The header: the path from the schema, the title with its kind and tags, a routine's parameter names, where it is
 * (the spec and the body, each half linking to its page when it is another), its uses, and the actions.
 */
function headerHtml(entity: EntityDetail, direct: number) {
  const path = entityPath(entity);
  const crumbs = [
    `<span>${esc(t("entity.schema", { schema: path.schema ?? t("entity.schemaUnknown") }))}</span>`,
    // A holder shows its name only: the link already leads to the right overload.
    ...path.ancestors.map(a => `<a href="${entityHref(a.key)}" title="${esc(a.display)}">${esc(a.name)}</a>`),
    `<span aria-current="page">${esc(path.name)}</span>`
  ];
  const parts = displayParts(entity.display, entity.name);
  const tags = entityTags(entity).map(tag => tag === "external"
    ? `<span class="badge external">${esc(t("entity.external"))}</span>`
    : tag === "private" ? `<span class="badge">${esc(t("entity.tag.private"))}</span>` : `<span class="badge">${esc(t("entity.overload.body"))}</span>`);
  const signature = SUBPROGRAM_KINDS.includes(entity.kind) && parts.params !== null
    ? `<div class="ent-sig">(${esc(parts.params.map(p => p.name).join(", "))})</div>` : "";
  const statusOf = (p: Place) => p.partner ? null : entity.definitions.find(d => d.path === p.path && d.span.startLine === p.line)?.status ?? null;
  // The uses become the "Used by" section's total once it loads (with a package's members, or a routine's other half).
  const facts = [...places(entity).map(p => placeHtml(p, entity.kind, statusOf(p))), `<span id="ent-uses">${esc(t("search.usages", { count: direct }))}</span>`];
  return `<nav class="crumbs" aria-label="${esc(t("entity.path"))}">${crumbs.join(`<span class="sep" aria-hidden="true">›</span>`)}</nav>
    <div class="ent-title"><h1><span class="ename" title="${esc(entity.display)}"><span class="name">${esc(parts.base)}</span></span></h1>${kindBadge(entity.kind)}${tags.join("")}</div>
    ${signature}
    <div class="ent-facts">${facts.map(f => `<span>${f}</span>`).join("")}</div>
    <div class="ent-actions"><a class="button" id="openImpact" href="${impactHref(entity.key)}">${esc(t("impact.open"))}</a>` +
    `<a class="button" id="openQuery" href="${queryHref({ entity: entity.key })}">${esc(t("entity.openUses"))}</a>` +
    `<button class="copy quiet" title="${esc(entity.key)}">${esc(t("entity.copyKey"))}</button></div>`;
}

/** A routine's parameters as a table (number, name, mode, type); each name links to the parameter entity. */
function parametersHtml(entity: EntityDetail, params: { name: string; mode?: string; type: string }[]) {
  const head = sectionHead(t("entity.parameters"), t("entity.parameters.line"));
  if (params.length === 0) return head + `<p class="muted">${esc(t("entity.parameters.none"))}</p>`;
  const byName = new Map(entity.children.map((m): [string, EntitySummary] => [m.entity.name, m.entity]));
  const unquoted = (name: string) => name.startsWith('"') && name.endsWith('"') ? name.slice(1, -1) : name;
  return head + `<table class="params"><thead><tr><th class="num">${esc(t("entity.parameters.number"))}</th><th>${esc(t("entity.parameters.name"))}</th>` +
    `<th>${esc(t("entity.parameters.mode"))}</th><th>${esc(t("entity.parameters.type"))}</th></tr></thead><tbody>${params.map((p, i) => {
      const child = byName.get(p.name) ?? byName.get(unquoted(p.name));
      const name = child ? `<a class="mono" href="${entityHref(child.key)}">${esc(p.name)}</a>` : `<span class="mono">${esc(p.name)}</span>`;
      // A parameter written without a mode is IN.
      const mode = p.mode ? esc(p.mode) : `<span class="muted">IN</span>`;
      return `<tr><td class="num">${i + 1}</td><td>${name}</td><td class="mono">${mode}</td><td class="mono ptype">${esc(p.type)}</td></tr>`;
    }).join("")}</tbody></table>`;
}

/** The overloads with every parameter name, and their types where the names alone read the same. */
function overloadsHtml(groups: SpecBody<Overload>[]) {
  if (groups.length < 2) return "";
  const link = (o: Overload | null, label: string) => !o ? "" : o.self ? ` <b>${esc(t(label))}</b>` : ` <a href="${entityHref(o.entity.key)}">${esc(t(label))}</a>`;
  const options = listNameOptions(groups.map(g => (g.spec ?? g.body)!.entity), { relative: true, noBadge: true, parameters: "names" });
  return sectionHead(t("entity.overloads"), t("entity.overloads.line")) + `<ul>${groups.map(g => {
    const shown = (g.spec ?? g.body)!.entity;
    return `<li>${nameHtml(shown, options(shown))}${link(g.spec, "entity.overload.spec")}${link(g.body, "entity.overload.body")}</li>`;
  }).join("")}</ul>`;
}

/** A row of the routines table: a routine's spec and body as one, its uses the sum of both. */
interface RoutineEntry extends RoutineRow {
  html: string;
}

let routines: RoutineEntry[] = [];
let routineOrder: RoutineOrder = "az";
/** Whether the header shows the first load's total of "Used by" yet; later loads, with filters, leave it. */
let headerCounted = false;

function routineTableRows() {
  const shown = routineRows(routines, document.querySelector<HTMLInputElement>("#sec-routines .filter")?.value ?? "", routineOrder);
  return shown.length === 0 ? `<tr><td colspan="3" class="muted">${esc(t("entity.routines.none"))}</td></tr>` : shown.map(r => r.html).join("");
}

/** The member tables: routines (procedures and functions together) with a filter and an order, then one table per other kind. */
function contentsSections(members: Member[]): { html: string; entries: SectionEntry[] } {
  const byKind = new Map<string, Member[]>();
  for (const member of members) getOrPut(byKind, member.entity.kind, () => []).push(member);
  const uses = (n: number) => `<td class="num">${esc(formatNumber(n))}</td>`;
  const head = (kindColumn: boolean) => `<thead><tr>${kindColumn ? `<th class="c-kind"></th>` : ""}<th>${esc(t("search.column.name"))}</th><th class="num">${esc(t("search.column.usages"))}</th></tr></thead>`;
  const entries: SectionEntry[] = [];
  let html = "";
  const routineMembers = members.filter(m => SUBPROGRAM_KINDS.includes(m.entity.kind));
  if (routineMembers.length) {
    const options = listNameOptions(routineMembers.map(m => m.entity), { relative: true });
    routines = specBodyGroups(routineMembers, m => m.entity.key).map(g => {
      const shown = (g.spec ?? g.body)!.entity;
      const usages = (g.spec?.usages ?? 0) + (g.body?.usages ?? 0);
      return {
        name: shown.name, key: shown.key, usages,
        html: `<tr><td class="c-kind">${kindBadge(shown.kind, true)}</td><td>${entityLink(shown, { ...options(shown), noBadge: true })}` +
          `${specBodyLinks(g.spec?.entity ?? null, g.body?.entity ?? null)}</td>${uses(usages)}</tr>`
      };
    });
    entries.push({ id: "sec-routines", label: t("entity.routines"), count: routines.length });
    html += `<section id="sec-routines" class="sec">${sectionHead(t("entity.routines"), t("entity.routines.line"))}
      <div class="tools"><input type="search" class="filter" placeholder="${esc(t("entity.routines.filter"))}" aria-label="${esc(t("entity.routines.filter"))}">
      <span class="seg" role="group" aria-label="${esc(t("entity.sort"))}"><button type="button" data-order="az" aria-pressed="true">${esc(t("entity.sort.az"))}</button>` +
      `<button type="button" data-order="uses" aria-pressed="false">${esc(t("entity.sort.uses"))}</button></span></div>
      <table class="members">${head(true)}<tbody>${routineTableRows()}</tbody></table></section>`;
  }
  const kinds = [...byKind].filter(([kind]) => !SUBPROGRAM_KINDS.includes(kind))
    .sort(([a], [b]) => kindOrder(a) - kindOrder(b) || (a < b ? -1 : a > b ? 1 : 0));
  for (const [kind, list] of kinds) {
    const title = t("entity.group." + kind) === "entity.group." + kind ? kindLabel(kind) : t("entity.group." + kind);
    const options = listNameOptions(list.map(m => m.entity), { relative: true });
    entries.push({ id: "sec-" + kind, label: title, count: list.length });
    html += `<section id="sec-${esc(kind)}" class="sec">${sectionHead(title, t("entity.group.line"))}
      <table class="members">${head(false)}<tbody>${list.map(m => `<tr><td>${entityLink(m.entity, options(m.entity))}</td>${uses(m.usages)}</tr>`).join("")}</tbody></table></section>`;
  }
  return { html, entries };
}

function triggersHtml(entity: EntityDetail, own: boolean) {
  if (entity.triggerEvents.length === 0) return "";
  const title = own ? "trigger.events" : "trigger.firing";
  return sectionHead(t(title), t(title + ".line")) + scrollTable(entity.triggerEvents.map(e =>
    `<tr>${own ? "" : `<td>${entityLink(e.trigger)}</td>`}<td>${esc(t("trigger.timing." + e.timing))}</td><td>${esc(e.level ? t("trigger.level." + e.level) : "")}</td>
     <td>${esc(e.event)}${e.column ? " " + esc(t("trigger.of")) + " " + entityLink(e.column) : ""}</td></tr>`).join(""), "compact");
}

function renderEntity(entity: EntityDetail) {
  setPageTitle(t("page.entity.titleWith", { name: entity.name }));
  recordOpened(browserStorage(), currentProject(), entity);
  const set = (id: string, html: string) => { document.getElementById(id)!.innerHTML = html; };
  const u = entity.usage;
  const direct = u.byStatus.reduce((n, c) => n + c.count, 0);
  set("header", headerHtml(entity, direct));
  const params = SUBPROGRAM_KINDS.includes(entity.kind) ? displayParts(entity.display, entity.name).params ?? [] : null;
  set("parameters", params === null ? "" : parametersHtml(entity, params));
  const overloads = specBodyGroups(entity.overloads, o => o.entity.key);
  set("overloads", overloadsHtml(overloads));
  // A subprogram's parameters are in its parameter table, not repeated under its contents.
  const members = params === null ? entity.children : entity.children.filter(m => m.entity.kind !== "PARAMETER");
  const contents = contentsSections(members);
  set("contents", contents.html);
  set("relations", linkList(t("entity.relations"), t("entity.relations.line"), entity.relations.map(r =>
    `${esc(r.kind)} ${r.direction === "OUT" ? "→" : "←"} ${entityLink(r.entity)}`)));
  // A trigger lists its events; a relation or a column, the triggers that fire on it.
  const own = entity.kind === "TRIGGER";
  set("triggers", triggersHtml(entity, own));
  // A view's base relations, and the views over a relation: both from the SQL reads of the view queries.
  set("viewReads", linkList(t("entity.reads"), t("entity.reads.line"), entity.reads.map(e => entityLink(e))));
  set("views", linkList(t("entity.views"), t("entity.views.line"), entity.views.map(e => entityLink(e))));
  // A view column's sources, and the view columns computed from a column: one hop of the stored lineage.
  set("baseColumns", linkList(t("entity.baseColumns"), t("entity.baseColumns.line"), entity.baseColumns.map(e => entityLink(e))));
  set("viewColumns", linkList(t("entity.viewColumns"), t("entity.viewColumns.line"), entity.viewColumns.map(e => entityLink(e))));
  document.getElementById("usage")!.hidden = false;

  state.own = u.asCandidate > 0
    ? t("usage.summary", { direct: t("usage.direct", { count: direct }), candidate: t("usage.candidate", { count: u.asCandidate }) })
    : t("usage.direct", { count: direct });

  state.implementations = [...SUBPROGRAM_KINDS, "PACKAGE"].includes(entity.kind);
  state.children = ["PACKAGE", "TYPE"].includes(entity.kind);
  // A package or a type lists the uses from other programs first; its own spec and body are the other side.
  state.kind = entity.kind;
  state.display = entity.display;
  state.side = ["PACKAGE", "TYPE"].includes(entity.kind) ? "OUTSIDE" : null;
  state.scopes = [
    [...SUBPROGRAM_KINDS, "PACKAGE"].includes(entity.kind) && "implementations",
    entity.children.length > 0 && "children",
    VIEWED_KINDS.includes(entity.kind) && "views"
  ].filter((scope): scope is ScopeName => Boolean(scope));

  // What a parameter is passed and what a column is written: the same groups, from different rows.
  const valuesTitle = entity.kind === "PARAMETER" ? "values.title.passed" : entity.kind === "COLUMN" ? "values.title.written" : null;
  if (valuesTitle) loadValues(valuesTitle);

  // The list leads to every section of the content column; the usage count becomes the scope's total once it loads,
  // and values have no count until they load.
  const sections: SectionEntry[] = [
    ...contents.entries,
    { id: "parameters", label: t("entity.parameters"), count: params?.length ?? -1 },
    { id: "overloads", label: t("entity.overloads"), count: overloads.length >= 2 ? overloads.length : -1 },
    { id: "values", label: valuesTitle ? t(valuesTitle) : "", count: valuesTitle ? null : -1 },
    { id: "usage", label: t("usage.title"), count: direct },
    { id: "relations", label: t("entity.relations"), count: entity.relations.length || -1 },
    { id: "triggers", label: t(own ? "trigger.events" : "trigger.firing"), count: entity.triggerEvents.length || -1 },
    { id: "viewReads", label: t("entity.reads"), count: entity.reads.length || -1 },
    { id: "views", label: t("entity.views"), count: entity.views.length || -1 },
    { id: "baseColumns", label: t("entity.baseColumns"), count: entity.baseColumns.length || -1 },
    { id: "viewColumns", label: t("entity.viewColumns"), count: entity.viewColumns.length || -1 }
  ].filter(s => s.count === null || s.count >= 0);
  set("sections", sections.map(s =>
    `<a href="#${s.id}" data-section="${s.id}"><span>${esc(s.label)}</span>${s.count === null ? "" : `<span class="count">${esc(formatNumber(s.count))}</span>`}</a>`).join(""));
  followSections();
}

/** Marks, in the list of sections, the section at the top of the screen as one scrolls. */
function followSections() {
  const links = new Map([...document.querySelectorAll<HTMLAnchorElement>("#sections a")].map(a => [a.dataset.section!, a]));
  const sections = [...links.keys()].map(id => document.getElementById(id)).filter((s): s is HTMLElement => s !== null);
  const mark = () => {
    const top = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--stick-top")) || 0;
    let current = sections[0];
    for (const section of sections) if (section.getBoundingClientRect().top - top <= 80) current = section;
    for (const [id, link] of links) {
      if (id === current?.id) link.setAttribute("aria-current", "true");
      else link.removeAttribute("aria-current");
    }
  };
  document.addEventListener("scroll", mark, { passive: true });
  mark();
}

document.getElementById("contents")!.addEventListener("input", e => {
  if (!(e.target as Element).matches("#sec-routines .filter")) return;
  document.querySelector("#sec-routines tbody")!.innerHTML = routineTableRows();
});

document.getElementById("contents")!.addEventListener("click", e => {
  const button = (e.target as Element).closest<HTMLButtonElement>("#sec-routines button[data-order]");
  if (!button) return;
  routineOrder = button.dataset.order as RoutineOrder;
  for (const b of document.querySelectorAll("#sec-routines button[data-order]")) b.setAttribute("aria-pressed", String(b === button));
  document.querySelector("#sec-routines tbody")!.innerHTML = routineTableRows();
});

document.getElementById("header")!.addEventListener("click", async e => {
  const button = (e.target as Element).closest<HTMLButtonElement>("button.copy");
  if (!button) return;
  if (!navigator.clipboard) {
    prompt(t("entity.copyKey"), key);
    return;
  }
  try {
    await navigator.clipboard.writeText(key);
  } catch {
    prompt(t("entity.copyKey"), key);
    return;
  }
  button.textContent = t("entity.copied");
  setTimeout(() => { button.textContent = t("entity.copyKey"); }, 1500);
});

// ---- values passed to a parameter or written to a column ----------------------------------------------------------

const VALUES_SHOWN = 20;

function valueLabel(group: ValueGroup) {
  const literal = literalText(group.valueKind, group.value);
  if (literal !== null) return `<span class="mono">${esc(literal)}</span>`;
  if (group.valueKind === "NAME") return `<span class="mono">${esc(group.value)}</span>`;
  return `<span class="muted">${esc(t("values.kind." + group.valueKind))}</span>`;
}

function valueRowsHtml(groups: ValueGroup[], from: number, to: number) {
  return groups.slice(from, to).map((g, offset) => {
    const i = from + offset;
    return `<tr><td><span class="badge" title="${esc(g.valueKind)}">${esc(valueKindLabel(g.valueKind))}</span></td><td>${valueLabel(g)}</td><td>${esc(formatNumber(g.count))}</td>
      <td><a href="#" data-group="${i}">${esc(t("values.sites"))}</a></td></tr>
      <tr hidden data-calls="${i}"><td colspan="4"><button class="more" hidden>${esc(t("usage.more"))}</button></td></tr>`;
  }).join("");
}

async function loadValues(title: string) {
  const target = document.getElementById("values")!;
  const head = sectionHead(t(title), t(title.replace(".title.", ".line.")));
  const groups = (await api<Answer<ValueGroup[]>>("api/values", { key })).result;
  if (groups.length === 0) {
    target.innerHTML = head + `<p class="muted">${esc(t("values.none"))}</p>`;
    return;
  }
  target.innerHTML = head + `<div class="scroll"><table class="compact"><tbody>${valueRowsHtml(groups, 0, VALUES_SHOWN)}</tbody></table></div>` +
    (groups.length > VALUES_SHOWN ? `<p><a href="#" data-show-all>${esc(t("values.showAll", { count: groups.length }))}</a></p>` : "");
  const blocks = new Map<number, UsesBlock>();
  target.addEventListener("click", e => {
    if ((e.target as Element).closest("a[data-show-all]")) {
      e.preventDefault();
      target.querySelector("tbody")!.insertAdjacentHTML("beforeend", valueRowsHtml(groups, VALUES_SHOWN, groups.length));
      (e.target as Element).closest("p")!.remove();
      return;
    }
    const link = (e.target as Element).closest<HTMLAnchorElement>("a[data-group]");
    if (link) {
      e.preventDefault();
      const index = Number(link.dataset.group);
      const row = target.querySelector<HTMLTableRowElement>(`tr[data-calls="${index}"]`)!;
      row.hidden = !row.hidden;
      if (!row.hidden && !blocks.has(index)) {
        const block = usesBlock();
        blocks.set(index, block);
        row.firstElementChild!.prepend(block.element);
        loadValueSites(groups[index], block, row, null);
      }
      return;
    }
    const more = (e.target as Element).closest<HTMLButtonElement>("tr[data-calls] button.more");
    if (more) {
      const index = Number(more.closest("tr")!.dataset.calls);
      loadValueSites(groups[index], blocks.get(index)!, more.closest("tr")!, more.dataset.after);
    }
  });
}

/** One page of the calls or writes that give [group]'s value, appended to its "who uses" [block]. */
async function loadValueSites(group: ValueGroup, block: UsesBlock, row: Element, after: string | null | undefined) {
  const page = (await api<Answer<OccurrencePage>>("api/value-sites", { key, kind: group.valueKind, value: group.value, after, limit: 100 })).result;
  block.append(page.rows);
  const more = row.querySelector<HTMLButtonElement>("button.more")!;
  more.hidden = page.nextAfter === null;
  if (page.nextAfter !== null) more.dataset.after = String(page.nextAfter);
}

// ---- "who uses" filters and pages ---------------------------------------------------------------------------------

function renderScope() {
  const toggle = (name: ScopeName) =>
    `<span class="chip ${state[name] ? "on" : ""}" data-scope="${name}">${esc(t("usage.scope." + name))}</span>`;
  document.getElementById("scope")!.innerHTML = state.scopes.length === 0 ? "" : esc(t("usage.scope")) + " " + state.scopes.map(toggle).join("");
}

/** The count of [side] in a page's `bySide` facet. */
const sideCount = (page: OccurrencePage, side: Side) => page.facets.bySide?.find(c => c.value === side)?.count ?? 0;

/**
 * The switch between the uses from outside and those inside the package or type, each with its count; none when
 * neither side has a use.
 */
function renderSide(page: OccurrencePage) {
  const target = document.getElementById("side")!;
  if (!state.side || sideCount(page, "OUTSIDE") + sideCount(page, "INSIDE") === 0) {
    target.innerHTML = "";
    return;
  }
  const inside = state.kind === "TYPE" ? "usage.side.insideType" : "usage.side.insidePackage";
  const button = (side: Side, label: string) =>
    `<button type="button" data-side="${side}" aria-pressed="${state.side === side}">${esc(t(label, { count: sideCount(page, side) }))}</button>`;
  target.innerHTML = `<span class="seg" role="group" aria-label="${esc(t("usage.side"))}">${button("OUTSIDE", "usage.side.outside")}${button("INSIDE", inside)}</span>`;
}

/**
 * How the rows read: a package or a type groups the uses from outside by calling program, each row naming the member
 * it hits and the routine it is in, and its own uses by calling routine; other entities group by calling routine.
 */
const usesContext = (): UsesContext => ({
  grouping: state.side === "OUTSIDE" ? "program" : "routine", page: { key, kind: state.kind, display: state.display }
});

async function loadUsages(reset: boolean) {
  if (reset) {
    state.after = null;
    state.direct = usesBlock(usesContext());
    state.ambiguous = usesBlock(usesContext());
    document.getElementById("direct")!.replaceChildren(state.direct.element);
    document.getElementById("ambiguousRows")!.replaceChildren(state.ambiguous.element);
    document.getElementById("ambiguous")!.hidden = true;
  }
  const scope = ["SELF"];
  if (state.implementations) scope.push("IMPLEMENTATIONS");
  if (state.children) scope.push("CHILDREN");
  if (state.views) scope.push("VIEWS");
  const answer = await api<Answer<OccurrencePage>>("api/occurrences", {
    key, scope: scope.join(","), roles: [...state.roles].join(","), status: [...state.statuses].join(","),
    candidates: true, side: state.side, after: state.after, limit: 100
  });
  const page = answer.result;
  renderScope();
  renderSide(page);
  renderChips("roleChips", page.facets.byRole, state.roles, "role", roleLabel);
  renderChips("statusChips", page.facets.byStatus, state.statuses, "status", statusLabel);
  if (state.roles.size || state.statuses.size) {
    document.getElementById("statusChips")!.insertAdjacentHTML("beforeend", ` <a href="#" id="clear">${esc(t("filters.clear"))}</a>`);
  }
  const direct = page.rows.filter(r => !r.viaCandidate);
  const ambiguous = page.rows.filter(r => r.viaCandidate);
  if (direct.length) document.querySelector("#direct > p.muted")?.remove();
  state.direct!.append(direct);
  state.ambiguous!.append(ambiguous);
  if (ambiguous.length) document.getElementById("ambiguous")!.hidden = false;
  // Without filters the summary line already says there is no direct use; one side can still be empty while the other is not.
  const otherSide = state.side !== null && sideCount(page, "OUTSIDE") + sideCount(page, "INSIDE") > 0;
  if (reset && direct.length === 0 && (state.roles.size || state.statuses.size || otherSide)) {
    document.getElementById("direct")!.insertAdjacentHTML("afterbegin", `<p class="muted">${esc(t("usage.noDirect"))}</p>`);
  }
  // The summary line and the section count give the listed total; with members, implementations or views in scope it
  // is more than the entity's own uses, which the line keeps beside it. With the switch, the section counts both sides.
  if (reset) {
    const bothSides = state.side ? sideCount(page, "OUTSIDE") + sideCount(page, "INSIDE") : page.total;
    document.getElementById("total")!.textContent = scope.length > 1
      ? t("usage.scoped", { listed: t("usage.listed", { count: page.total }), own: state.own })
      : t("usage.own", { own: state.own });
    document.querySelector('#sections a[data-section="usage"] .count')!.textContent = formatNumber(bothSides);
    // The header's count is a fact about the object: the total of the first, unfiltered load, kept while filters change;
    // a package or a type names both numbers.
    if (!headerCounted) {
      document.getElementById("ent-uses")!.textContent = state.side && bothSides > 0
        ? t("usage.sides", { count: bothSides, outside: sideCount(page, "OUTSIDE") })
        : t("search.usages", { count: bothSides });
      headerCounted = true;
    }
  }
  // The query has no filter for spec and body or for uses through views: it covers the entity and, with members, its contents.
  const open = document.getElementById("openQuery") as HTMLAnchorElement;
  try {
    const roles = state.roles.size ? [...state.roles] : usageRoles(await loadCodes());
    open.href = queryHref({
      entity: key, inside: state.children ? "true" : "", roles: roles.join(","), statuses: [...state.statuses].join(",")
    });
    open.title = t(state.children ? "query.open.withContents" : "query.open.entityOnly");
  } catch (e) {
    // Without the code lists the link cannot name the default roles: it shows why instead.
    open.removeAttribute("href");
    open.textContent = t("error.message", { error: errorMessage(e) });
  }
  state.after = page.nextAfter;
  document.getElementById("more")!.hidden = page.nextAfter === null;
}

document.getElementById("usage")!.addEventListener("click", e => {
  if ((e.target as HTMLElement).id === "clear") {
    e.preventDefault();
    state.roles.clear();
    state.statuses.clear();
    loadUsages(true);
    return;
  }
  const side = (e.target as Element).closest<HTMLElement>("button[data-side]");
  if (side) {
    if (side.dataset.side !== state.side) {
      state.side = side.dataset.side as Side;
      loadUsages(true);
    }
    return;
  }
  const chip = (e.target as Element).closest<HTMLElement>(".chip");
  if (!chip) return;
  if (chip.dataset.scope) state[chip.dataset.scope as ScopeName] = !state[chip.dataset.scope as ScopeName];
  for (const [attribute, set] of [["role", state.roles], ["status", state.statuses]] satisfies [string, Set<string>][]) {
    const value = chip.dataset[attribute];
    if (value) set.has(value) ? set.delete(value) : set.add(value);
  }
  loadUsages(true);
});
document.getElementById("more")!.addEventListener("click", () => loadUsages(false));

(async () => {
  await initI18n();
  showBanner();
  if (!key) {
    setPageTitle(t("page.entity.titleMissing"));
    document.getElementById("header")!.textContent = t("entity.missingKey");
    return;
  }
  // The roles "open in query" names by default come from the code lists, read beside the entity.
  loadCodes().catch(() => {});
  try {
    const answer = await api<Answer<EntityDetail>>("api/entity", { key });
    renderEntity(answer.result);
    await loadUsages(true);
  } catch (e) {
    setPageTitle(t(e instanceof ApiFailure && e.status === 404 ? "page.entity.titleFailed" : "page.entity.titleError"));
    document.getElementById("header")!.innerHTML = `<p>${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
  }
})();
