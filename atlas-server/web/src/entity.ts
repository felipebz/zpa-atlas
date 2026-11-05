import type {
    Answer,
    ColumnPlace,
    Documentation,
    EntityDetail,
    EntitySummary,
    FormsInfo,
    KeyColumn,
    Member,
    OccurrencePage,
    Overload,
    PlaceGroup,
    PlaceGroupsPage,
    PlaceSource,
    TableKey,
    ValueGroup,
    Verdict
} from "./api";
import {api, ApiFailure, columnPlaceGroups, errorMessage} from "./api";
import {codeLineNode} from "./code-line";
import {shownLine} from "./text-search";
import type {DeclaredFact} from "./declared-view";
import {declaredFacts, memberType, parameterDefaults, returnType} from "./declared-view";
import {docFromKey, docPlace, docSummary} from "./doc-view";
import {showBanner} from "./banner";
import {loadCodes, usageRoles} from "./codes";
import {
    entityLink,
    fillHtml,
    formsNameLink,
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
import {
    entityPath,
    entityTags,
    isBodyOwn,
    localOf,
    membersGapKey,
    places,
    routineRows,
    usesGapKey
} from "./entity-view";
import {columnPlace, referencedColumn, referencingColumn, shownDeleteRule} from "./keys-view";
import {showFormsTree} from "./forms";
import {FORMS_TREE_KINDS, firstUnit, formsCrumbs, formsName} from "./forms-view";
import {kindOrder} from "./kinds";
import {
    codeBreaksHtml,
    entityHref,
    esc,
    fileHref,
    getOrPut,
    impactHref,
    lineHref,
    lineTitle,
    literalText,
    nameBreaksHtml,
    partBreaksHtml,
    queryHref,
    scrollTable,
    stackCell
} from "./html";
import {formatNumber, initI18n, t} from "./i18n";
import {
    compareNote,
    compareUnitText,
    DEFAULT_VERDICTS,
    type DetailPart,
    fileName,
    functionsCode,
    functionsText,
    groupCountsText,
    howText,
    moreText,
    optionSizedText,
    PLACE_SOURCES,
    placeDetail,
    placesTotal,
    rowAt,
    shownText,
    sizeShown,
    verdictButtons,
    VERDICTS,
    viewOf
} from "./places-view";
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
 * One fact of what the declaration says, the text as written in code: a cut text links the definition's line, where
 * the whole one is; a default taken from the spec says so; a body that differs from its spec says what the spec says.
 */
function declaredFactHtml(fact: DeclaredFact, entity: EntityDetail) {
  let html = fillHtml("entity.declared." + fact.label, { text: `<code class="decl">${esc(fact.text)}</code>` });
  const definition = entity.definitions[0];
  if (fact.cut) {
    html += definition && !fact.fromSpec
      ? ` <a href="${lineHref(definition.path, definition.span.startLine, definition.code)}" ` +
        `title="${esc(t("entity.declared.cutTitle"))}">${esc(t("entity.declared.whole"))}</a>`
      : ` <span class="muted" title="${esc(t("entity.declared.cutTitle"))}">${esc(t("entity.declared.cut"))}</span>`;
  }
  if (fact.fromSpec) html += ` <span class="muted">${esc(t("entity.declared.fromSpec"))}</span>`;
  if (fact.spec !== null) {
    html += ` <span class="muted">(${fact.spec === "" ? esc(t("entity.declared.specNone"))
      : fillHtml("entity.declared.specSays", { text: `<code class="decl">${esc(fact.spec)}</code>` })})</span>`;
  }
  return html;
}

/**
 * The header: the path from the schema, the title with its kind and tags, a routine's parameter names and a function's
 * return type, what a declaration says (type, default or initial value), where it is (the spec and the body, each half
 * linking to its page when it is another; inside Forms code, the code page at the line), its uses, and the actions. A
 * Forms object reads as in the Form Builder: its own name, under its module's title and the objects that hold it, the
 * full path as the tooltip; a module by its title, its file (its code a link away) the first fact.
 */
function headerHtml(entity: EntityDetail, direct: number) {
  const path = entityPath(entity);
  const forms = entity.forms;
  // A Forms object's path starts at its module: it has no schema.
  const crumbs = [
    ...(forms !== null ? formsCrumbs(entity).map(name => formsNameLink(name)) : [
      `<span>${esc(t("entity.schema", { schema: path.schema ?? t("entity.schemaUnknown") }))}</span>`,
      // A holder shows its name only: the link already leads to the right overload.
      ...path.ancestors.map(a => `<a href="${entityHref(a.key)}" title="${esc(a.display)}">${esc(a.name)}</a>`)
    ]),
    `<span aria-current="page">${esc(forms !== null ? formsName(entity, forms).label : path.name)}</span>`
  ];
  const parts = displayParts(entity.display, entity.name);
  const title = forms !== null ? formsName(entity, forms).label : parts.base;
  const tags = entityTags(entity).map(tag => tag === "external"
    ? `<span class="badge external">${esc(t("entity.external"))}</span>`
    : tag === "private" ? `<span class="badge">${esc(t("entity.tag.private"))}</span>` : `<span class="badge">${esc(t("entity.overload.body"))}</span>`);
  const returns = returnType(entity.kind, entity.declared);
  const signature = SUBPROGRAM_KINDS.includes(entity.kind) && parts.params !== null
    ? `<div class="ent-sig">(${esc(parts.params.map(p => p.name).join(", "))})${returns === null ? "" : ` return ${esc(returns)}`}</div>` : "";
  const declared = declaredFacts(entity.kind, entity.declared).map(f => `<span>${declaredFactHtml(f, entity)}</span>`);
  const statusOf = (p: Place) => p.partner ? null : entity.definitions.find(d => d.path === p.path && d.span.startLine === p.line)?.status ?? null;
  // The uses become the "Used by" section's total once it loads (with a package's members, or a routine's other half).
  const facts = [...places(entity).map(p => placeHtml(p, entity, statusOf(p))), `<span id="ent-uses">${esc(t("search.usages", { count: direct }))}</span>`];
  // A Forms module's file, whose code the file page shows: its first piece of code once the tree says which.
  if (forms !== null && entity.kind === "FORMS_MODULE") {
    facts.unshift(fillHtml("forms.file", {
      path: `<a class="mono" id="formsFile" href="${fileHref(forms.path)}">${esc(forms.path).replace(/\//g, "/<wbr>")}</a>`
    }));
  }
  // A body's own variable, constant or type is not part of the package's interface: said in words, first.
  if (isBodyOwn(entity)) facts.unshift(`<span class="private-note">${esc(t("entity.privateBody"))}</span>`);
  // A routine's own variable or constant: whose it is, first.
  const local = localOf(entity);
  if (local) facts.unshift(fillHtml("entity.localOf." + entity.kind, { routine: `<a class="mono" href="${entityHref(local.key)}">${esc(local.name)}</a>` }));
  // Uses the index could not record cannot be ruled out: said beside the count, so it is not read as the whole answer.
  const gap = usesGapKey(entity.usesIncomplete);
  if (gap !== null) facts.push(`<span class="uses-gap">${esc(t(gap))}</span>`);
  // A type whose attribute list may be partial says so: its contents are not the whole shape.
  const members = membersGapKey(entity.membersIncomplete);
  if (members !== null) facts.push(`<span class="uses-gap">${esc(t(members))}</span>`);
  return `<nav class="crumbs" aria-label="${esc(t("entity.path"))}">${crumbs.join(`<span class="sep" aria-hidden="true">›</span>`)}</nav>
    <div class="ent-title"><h1><span class="ename" title="${esc(entity.display)}"><span class="name">${nameBreaksHtml(title)}</span></span></h1>${kindBadge(entity.kind)}${tags.join("")}</div>
    ${signature}
    ${declared.length ? `<div class="ent-decl">${declared.join("")}</div>` : ""}
    <div class="ent-facts">${facts.map(f => `<span>${f}</span>`).join("")}</div>
    <div class="ent-actions"><a class="button" id="openImpact" href="${impactHref(entity.key)}">${esc(t("impact.open"))}</a>` +
    `<a class="button" id="openQuery" href="${queryHref({ entity: entity.key })}">${esc(t("entity.openUses"))}</a>` +
    `<button class="copy quiet" title="${esc(entity.key)}">${esc(t("entity.copyKey"))}</button></div>`;
}

/**
 * The documentation under the header: each comment as written (its line breaks and indentation kept), what it is (the
 * other half's is marked) and a link to its line (inside Forms code, its unit's own line on the code page); a long one
 * folds, with "more".
 */
function documentationHtml(docs: Documentation[]) {
  return docs.map(doc => {
    const from = docFromKey(doc);
    const said = docPlace(doc);
    const place = `<a href="${lineHref(doc.path, doc.line, doc.code)}" title="${esc(lineTitle(doc.path, doc.line, doc.code))}">` +
      `${esc(t(said.key, { line: said.line }))}</a>`;
    return `<div class="doc"><div class="doc-src">${from ? `<b>${esc(t(from))}</b> · ` : ""}${place}</div>` +
      `<pre class="doc-text folded">${esc(doc.text)}</pre><button type="button" class="doc-more quiet" aria-expanded="false" hidden>${esc(t("doc.more"))}</button></div>`;
  }).join("");
}

/** Shows "more" under each folded comment taller than the fold, and opens or closes it. */
function setupDocFolds(container: HTMLElement) {
  for (const doc of container.querySelectorAll<HTMLElement>(".doc")) {
    const text = doc.querySelector<HTMLElement>(".doc-text")!;
    const button = doc.querySelector<HTMLButtonElement>(".doc-more")!;
    button.hidden = text.scrollHeight <= text.clientHeight + 1;
    button.addEventListener("click", () => {
      const open = text.classList.toggle("folded") === false;
      button.setAttribute("aria-expanded", String(open));
      button.textContent = t(open ? "doc.less" : "doc.more");
    });
  }
}

/**
 * A routine's parameters as a table (number, name, mode, type, and the default when one of them has one); each name
 * links to the parameter entity. On a phone it stacks (`table.stack`, see app.css): each parameter a block of its own,
 * the number and the name, then the mode, the type and the default, marked as such since the header row is gone.
 */
function parametersHtml(entity: EntityDetail, params: { name: string; mode?: string; type: string }[]) {
  if (params.length === 0) return sectionHead(t("entity.parameters"), t("entity.parameters.line")) + `<p class="muted">${esc(t("entity.parameters.none"))}</p>`;
  const defaults = parameterDefaults(params, entity.children);
  const head = sectionHead(t("entity.parameters"), t(defaults ? "entity.parameters.lineDefaults" : "entity.parameters.line"));
  const byName = new Map(entity.children.map((m): [string, EntitySummary] => [m.entity.name, m.entity]));
  const unquoted = (name: string) => name.startsWith('"') && name.endsWith('"') ? name.slice(1, -1) : name;
  // The default in code, like the type beside it; what is said about it in words.
  const defaultCell = (fact: DeclaredFact | null) => {
    if (!fact) return `<td class="pdefault"></td>`;
    const cut = fact.cut ? ` <span class="note" title="${esc(t("entity.declared.cutTitle"))}">${esc(t("entity.declared.cut"))}</span>` : "";
    const from = fact.fromSpec ? ` <span class="note">${esc(t("entity.declared.fromSpec"))}</span>` : "";
    const spec = fact.spec === null ? "" : ` <span class="note">(${fact.spec === "" ? esc(t("entity.declared.specNone"))
      : fillHtml("entity.declared.specSays", { text: `<span class="mono">${codeBreaksHtml(fact.spec)}</span>` })})</span>`;
    return stackCell(`${codeBreaksHtml(fact.text)}${cut}${from}${spec}`, t("entity.parameters.default"), "mono pdefault");
  };
  return head + `<table class="params stack"><thead><tr><th class="num">${esc(t("entity.parameters.number"))}</th><th>${esc(t("entity.parameters.name"))}</th>` +
    `<th>${esc(t("entity.parameters.mode"))}</th><th>${esc(t("entity.parameters.type"))}</th>` +
    `${defaults ? `<th>${esc(t("entity.parameters.default"))}</th>` : ""}</tr></thead><tbody>${params.map((p, i) => {
      const child = byName.get(p.name) ?? byName.get(unquoted(p.name));
      // A name breaks only after an underscore, a type or default only where its code has a break point.
      const name = child ? `<a class="mono" href="${entityHref(child.key)}">${nameBreaksHtml(p.name)}</a>` : `<span class="mono">${nameBreaksHtml(p.name)}</span>`;
      // A parameter written without a mode is IN.
      const mode = p.mode ? esc(p.mode) : `<span class="muted">IN</span>`;
      const doc = child ? docSummary(entity.children.find(m => m.entity.key === child.key)?.documentation ?? []) : null;
      const docRow = !doc ? "" : `<tr class="pdoc"><td></td><td colspan="${defaults ? 4 : 3}">${esc(doc.text)}` +
        `${doc.from ? ` <span class="note">${esc(t(docFromKey(doc)!))}</span>` : ""}` +
        `${doc.more ? ` <a class="note" href="${entityHref(child!.key)}">${esc(t("doc.more"))}</a>` : ""}</td></tr>`;
      // A type breaks at the same points as a default: a dot, a percent sign, a comma (company.company_name%type).
      return `<tr${doc ? ` class="with-doc"` : ""}><td class="num">${i + 1}</td><td class="pname">${name}</td><td class="mono pmode">${mode}</td>` +
        `<td class="mono ptype">${codeBreaksHtml(p.type)}</td>` +
        `${defaults ? defaultCell(defaults[i]) : ""}</tr>${docRow}`;
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

/**
 * The member tables: routines (procedures and functions together) with a filter and an order, then one table per other
 * kind; a Forms object of the module [forms] by its own name.
 */
function contentsSections(members: Member[], forms: FormsInfo | null): { html: string; entries: SectionEntry[] } {
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
      <table class="members">${head(false)}<tbody>${list.map(m => {
        // A package variable's or constant's type, or a column's datatype, follows its name, wrapping under it on a phone.
        const type = memberType(m);
        // A body's own variable, constant or type: marked private, never the "body" badge of a spec member's other half.
        // A Forms object, inside its module: its own name.
        const name = isBodyOwn(m.entity)
          ? `${entityLink(m.entity, { ...options(m.entity), noBodyBadge: true })} <span class="badge">${esc(t("entity.tag.private"))}</span>`
          : m.entity.kind.startsWith("FORMS_") ? formsNameLink(formsName(m.entity, forms)) : entityLink(m.entity, options(m.entity));
        const gap = usesGapKey(m.usesIncomplete);
        const gapBadge = gap === null ? "" : ` <span class="badge gap" title="${esc(t(gap))}">${esc(t("entity.tag.usesIncomplete"))}</span>`;
        return `<tr><td>${name}${gapBadge}${type === null ? "" : ` <span class="mono mtype">${esc(type)}</span>`}</td>${uses(m.usages)}</tr>`;
      }).join("")}</tbody></table></section>`;
  }
  return { html, entries };
}

function triggersHtml(entity: EntityDetail, own: boolean) {
  if (entity.triggerEvents.length === 0) return "";
  const title = own ? "trigger.events" : "trigger.firing";
  return sectionHead(t(title), t(title + ".line")) + scrollTable(entity.triggerEvents.map(e =>
    `<tr>${own ? "" : `<td>${entityLink(e.trigger)}</td>`}<td>${esc(t("trigger.timing." + e.timing))}</td><td>${esc(e.level ? t("trigger.level." + e.level) : "")}</td>
     <td class="grow">${esc(e.event)}${e.column ? " " + esc(t("trigger.of")) + " " + entityLink(e.column) : ""}</td></tr>`).join(""), "compact stack");
}

/** What the rows of both key sections show of a key: a relation's own, or a foreign key referencing one. */
type KeyLike = Pick<TableKey, "name" | "enabled" | "table" | "relation" | "deleteRule"> & { columns: KeyColumn[] };

/** A key's name, with a badge when the constraint is disabled. */
function keyNameHtml(key: KeyLike) {
  const disabled = key.enabled ? "" : ` <span class="badge warn-badge" title="${esc(t("keys.disabledTitle"))}">${esc(t("keys.disabled"))}</span>`;
  return `<span class="mono">${nameBreaksHtml(key.name)}</span>${disabled}`;
}

/** The relation a key is on: a link to its entity, else its name, said not to be in the index. */
function keyTableHtml(key: KeyLike) {
  return key.relation ? entityLink(key.relation)
    : `<span class="mono">${codeBreaksHtml(key.table)}</span> <span class="muted">(${esc(t("keys.outside"))})</span>`;
}

/** A column of [key]: a link to its entity, else its name; [whole] names its table too (`HR.ORDERS.ORDER_ID`). */
function keyColumnHtml(key: KeyLike, column: KeyColumn, whole: boolean) {
  if (column.column) return entityLink(column.column, { relative: !whole });
  return `<span class="mono">${codeBreaksHtml(whole ? key.table + "." + column.name : column.name)}</span>`;
}

/** A key's columns in order, by name under the table the row names; [own], the page's column, marked. */
function keyColumnsHtml(key: KeyLike, own: string | null) {
  return key.columns.map(c => {
    const name = keyColumnHtml(key, c, false);
    return c.column !== null && c.column.key === own ? `<mark>${name}</mark>` : name;
  }).join(", ");
}

/** Where the page's column sits in a key of several columns (`column 2 of 2`); nothing for a key of one column. */
function columnPlaceHtml(place: { position: number; count: number } | null) {
  return place === null || place.count < 2 ? "" : ` <span class="muted">${esc(t("keys.position", place))}</span>`;
}

/** A foreign key's delete rule as SQL writes it, when other than `NO ACTION`. */
function deleteRuleHtml(key: KeyLike & { kind?: string }) {
  const rule = shownDeleteRule(key);
  return rule === null ? "" : ` <span class="mono">ON DELETE ${esc(rule)}</span>`;
}

/**
 * Where a foreign key points. On a relation's page: the referenced key's table and columns, and its name; on a
 * column's page ([own]), the column this one references, at its position in the referenced key. When the dictionary
 * did not give the referenced key, its name and a note saying so.
 */
function referenceHtml(fk: TableKey, own: string | null) {
  const target = fk.references;
  if (!target) return `→ <span class="mono">${codeBreaksHtml(fk.referencedName ?? "")}</span> <span class="muted">(${esc(t("keys.notGiven"))})</span>${deleteRuleHtml(fk)}`;
  const targetName = ` <span class="mono muted">${nameBreaksHtml(target.name)}</span>`;
  const column = own === null ? null : referencedColumn(fk, own);
  if (column) return `→ ${fillHtml("keys.references", { column: keyColumnHtml(target, column, true) })}${targetName}${deleteRuleHtml(fk)}`;
  return `→ ${keyTableHtml(target)} (${keyColumnsHtml(target, null)})${targetName}${deleteRuleHtml(fk)}`;
}

/**
 * A relation's keys, or the keys a column is a column of: per key its name, its kind, its columns in order (the page's
 * column marked, with its position) and, for a foreign key, what it references. A row wraps on a phone (`table.stack`).
 */
function keysHtml(entity: EntityDetail, own: string | null) {
  if (entity.keys.length === 0) return "";
  return sectionHead(t("entity.keys"), t(own === null ? "entity.keys.line" : "entity.keys.lineColumn")) + scrollTable(entity.keys.map(k =>
    `<tr><td>${keyNameHtml(k)}</td><td>${esc(t("keys.kind." + k.kind))}</td>` +
    `<td class="grow">(${keyColumnsHtml(k, own)})${own === null ? "" : columnPlaceHtml(columnPlace(k, own))}</td>` +
    `<td class="grow">${k.kind === "FOREIGN_KEY" ? referenceHtml(k, own) : ""}</td></tr>`).join(""), "compact stack keys");
}

/**
 * The foreign keys that reference a relation's keys, or a column ([own]): per key its table and
 * columns (on a column's page, the column that references this one, with its position), its name, the key it
 * references and its delete rule. When the loading user may not have seen every table, a note says there may be more,
 * even over an empty list; a relation or column without a key it could be referenced by shows nothing.
 */
function referencedByHtml(entity: EntityDetail, own: string | null) {
  const gap = entity.referencedByIncomplete?.[0] ?? null;
  if (entity.referencedBy.length === 0 && gap === null) return "";
  const note = gap === null ? "" : `<p class="uses-gap">${esc(t("keys.incomplete." + gap))}</p>`;
  const head = sectionHead(t("entity.referencedBy"), t(own === null ? "entity.referencedBy.line" : "entity.referencedBy.lineColumn")) + note;
  if (entity.referencedBy.length === 0) return head + `<p class="muted">${esc(t("keys.noneSeen"))}</p>`;
  return head + scrollTable(entity.referencedBy.map(fk => {
    const column = own === null ? null : referencingColumn(fk, entity.name);
    const columns = column ? keyColumnHtml(fk, column.column, false) + columnPlaceHtml(column) : `(${keyColumnsHtml(fk, null)})`;
    return `<tr><td>${keyTableHtml(fk)}</td><td class="grow">${columns}</td><td>${keyNameHtml(fk)}</td>` +
      `<td>→ <span class="mono">${codeBreaksHtml(fk.references)}</span>${deleteRuleHtml(fk)}</td></tr>`;
  }).join(""), "compact stack keys");
}

function renderEntity(entity: EntityDetail) {
  setPageTitle(t("page.entity.titleWith", { name: formsName(entity, entity.forms).label }));
  recordOpened(browserStorage(), currentProject(), entity);
  const set = (id: string, html: string) => { document.getElementById(id)!.innerHTML = html; };
  const u = entity.usage;
  const direct = u.byStatus.reduce((n, c) => n + c.count, 0);
  set("header", headerHtml(entity, direct));
  const documentation = document.getElementById("documentation")!;
  documentation.innerHTML = documentationHtml(entity.documentation);
  setupDocFolds(documentation);
  const params = SUBPROGRAM_KINDS.includes(entity.kind) ? displayParts(entity.display, entity.name).params ?? [] : null;
  set("parameters", params === null ? "" : parametersHtml(entity, params));
  const overloads = specBodyGroups(entity.overloads, o => o.entity.key);
  set("overloads", overloadsHtml(overloads));
  // A subprogram's parameters are in its parameter table, not repeated under its contents; a Forms module's, block's
  // or item's objects are in its tree.
  const tree = FORMS_TREE_KINDS.includes(entity.kind);
  const members = params !== null ? entity.children.filter(m => m.entity.kind !== "PARAMETER")
    : tree ? entity.children.filter(m => !m.entity.kind.startsWith("FORMS_")) : entity.children;
  const contents = contentsSections(members, entity.forms);
  set("contents", contents.html);
  // The objects of a Forms module, block or item; a module's file then leads to its first piece of code.
  if (tree) {
    void showFormsTree(document.getElementById("forms")!, entity.key).then(shown => {
      const file = document.getElementById("formsFile") as HTMLAnchorElement | null;
      const unit = shown && firstUnit(shown);
      if (file && unit && entity.forms) file.href = fileHref(entity.forms.path, unit);
    });
  }
  // Inside its module a Forms object is named by its own name, the full path as the tooltip.
  const inModule = (e: EntitySummary) => entity.forms !== null && e.key.startsWith(entity.forms.module.key + "/");
  set("relations", linkList(t("entity.relations"), t("entity.relations.line"), entity.relations.map(r =>
    `${esc(r.kind)} ${r.direction === "OUT" ? "→" : "←"} ${inModule(r.entity) ? formsNameLink(formsName(r.entity, entity.forms)) : entityLink(r.entity)}`)));
  // A trigger lists its events; a relation or a column, the triggers that fire on it.
  const own = entity.kind === "TRIGGER";
  set("triggers", triggersHtml(entity, own));
  // A view's base relations, and the views over a relation: both from the SQL reads of the view queries.
  set("viewReads", linkList(t("entity.reads"), t("entity.reads.line"), entity.reads.map(e => entityLink(e))));
  set("views", linkList(t("entity.views"), t("entity.views.line"), entity.views.map(e => entityLink(e))));
  // A view column's sources, and the view columns computed from a column: one hop of the stored lineage.
  set("baseColumns", linkList(t("entity.baseColumns"), t("entity.baseColumns.line"), entity.baseColumns.map(e => entityLink(e))));
  set("viewColumns", linkList(t("entity.viewColumns"), t("entity.viewColumns.line"), entity.viewColumns.map(e => entityLink(e))));
  // A PL/SQL target: the columns whose value is read into it.
  set("receives", linkList(t("receives.title"), t("receives.line"), entity.receives.map(e => entityLink(e))));
  // The dictionary's keys: a relation's own and those referencing it; a column's, those it is in and those referencing it.
  const column = entity.kind === "COLUMN" ? entity.key : null;
  set("keys", keysHtml(entity, column));
  set("referencedBy", referencedByHtml(entity, column));
  document.getElementById("usage")!.hidden = false;
  // Under the total too: the list below may not be every use.
  const usesGap = document.getElementById("usesGap")!;
  const gapKey = usesGapKey(entity.usesIncomplete);
  usesGap.hidden = gapKey === null;
  usesGap.textContent = gapKey === null ? "" : t(gapKey);

  state.own = u.asCandidate > 0
    ? t("usage.summary", { direct: t("usage.direct", { count: direct }), candidate: t("usage.candidate", { count: u.asCandidate }) })
    : t("usage.direct", { count: direct });

  // A parameter follows the same parameter of its routine's spec or body: its uses are on the body's side.
  state.implementations = [...SUBPROGRAM_KINDS, "PACKAGE", "PARAMETER"].includes(entity.kind);
  state.children = ["PACKAGE", "TYPE"].includes(entity.kind);
  // A package or a type lists the uses from other programs first; its own spec and body are the other side.
  state.kind = entity.kind;
  state.display = entity.display;
  state.side = ["PACKAGE", "TYPE"].includes(entity.kind) ? "OUTSIDE" : null;
  state.scopes = [
    [...SUBPROGRAM_KINDS, "PACKAGE", "PARAMETER"].includes(entity.kind) && "implementations",
    entity.children.length > 0 && "children",
    VIEWED_KINDS.includes(entity.kind) && "views"
  ].filter((scope): scope is ScopeName => Boolean(scope));

  // What a parameter is passed and what a column is written: the same groups, from different rows.
  const valuesTitle = entity.kind === "PARAMETER" ? "values.title.passed" : entity.kind === "COLUMN" ? "values.title.written" : null;
  if (valuesTitle) loadValues(valuesTitle);
  // Where a column's value is read into in PL/SQL, compared with the column's size: the places where it lands alone
  // count for the section, those where it is one part of a concatenation join the list when the reader asks.
  const placeCount = entity.kind === "COLUMN" ? placesTotal(entity.placesByClass) : 0;
  const joinedCount = entity.kind === "COLUMN" ? entity.concatenatedPlaces : 0;
  if (placeCount + joinedCount > 0) setupPlaces(placeCount === 0);

  // The list leads to every section of the content column; the usage count becomes the scope's total once it loads,
  // and values have no count until they load.
  const sections: SectionEntry[] = [
    { id: "forms", label: t("forms.objects"), count: tree ? null : -1 },
    ...contents.entries,
    { id: "parameters", label: t("entity.parameters"), count: params?.length ?? -1 },
    { id: "overloads", label: t("entity.overloads"), count: overloads.length >= 2 ? overloads.length : -1 },
    { id: "values", label: valuesTitle ? t(valuesTitle) : "", count: valuesTitle ? null : -1 },
    { id: "places", label: t("places.title"), count: placeCount + joinedCount > 0 ? placeCount : -1 },
    { id: "receives", label: t("receives.title"), count: entity.receives.length || -1 },
    { id: "usage", label: t("usage.title"), count: direct },
    { id: "relations", label: t("entity.relations"), count: entity.relations.length || -1 },
    { id: "triggers", label: t(own ? "trigger.events" : "trigger.firing"), count: entity.triggerEvents.length || -1 },
    { id: "viewReads", label: t("entity.reads"), count: entity.reads.length || -1 },
    { id: "views", label: t("entity.views"), count: entity.views.length || -1 },
    { id: "baseColumns", label: t("entity.baseColumns"), count: entity.baseColumns.length || -1 },
    { id: "viewColumns", label: t("entity.viewColumns"), count: entity.viewColumns.length || -1 },
    { id: "keys", label: t("entity.keys"), count: entity.keys.length || -1 },
    { id: "referencedBy", label: t("entity.referencedBy"), count: entity.referencedBy.length || (entity.referencedByIncomplete ? 0 : -1) }
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
    return `<tr><td><span class="badge" title="${esc(g.valueKind)}">${esc(valueKindLabel(g.valueKind))}</span></td>${stackCell(valueLabel(g), null, "grow")}` +
      `${stackCell(esc(formatNumber(g.count)), t("values.count"))}<td><a href="#" data-group="${i}">${esc(t("values.sites"))}</a></td></tr>
      <tr hidden data-calls="${i}"><td colspan="4" class="whole"><button class="more" hidden>${esc(t("usage.more"))}</button></td></tr>`;
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
  target.innerHTML = head + `<div class="scroll"><table class="compact stack"><tbody>${valueRowsHtml(groups, 0, VALUES_SHOWN)}</tbody></table></div>` +
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

// ---- the places a column's value is read into ------------------------------------------------------------------------

/** Routines per page of places. */
const PLACE_GROUPS = 15;
/** How long a keystroke in the size or the words waits for the next before the places are asked again, in milliseconds. */
const PLACES_TYPING = 300;

/**
 * What the places are asked for (the verdicts listed, the size compared, null for the column's, the words, how the
 * value arrives, and whether concatenated places join), and what the list shows: the first page of the last answer
 * (the size compared and the column's unit), each row's place by its `data-place`, the next page's offset. [asked]
 * counts the questions, so that an answer to an older one is dropped.
 */
const placeState: {
  verdicts: Set<Verdict>; size: number | null; text: string; source: PlaceSource | null; concatenated: boolean;
  page: PlaceGroupsPage | null; rows: ColumnPlace[]; next: number | null; asked: number; typing: number;
} = {
  verdicts: new Set(DEFAULT_VERDICTS), size: null, text: "", source: null, concatenated: false,
  page: null, rows: [], next: null, asked: 0, typing: 0
};

/** A view column read in place of the page's column: the view (a link when its key is known) and the column, linked. */
function throughViewHtml(read: EntitySummary) {
  const { view, viewKey, column } = viewOf(read);
  const viewName = `<span class="mono parts">${partBreaksHtml(view)}</span>`;
  return fillHtml("places.through", {
    view: viewKey === null ? viewName : `<a href="${entityHref(viewKey)}">${viewName}</a>`,
    column: `<a class="mono parts" href="${entityHref(read.key)}">${partBreaksHtml(column)}</a>`
  });
}

/**
 * The section's frame, filled once: the verdict buttons, the note on sizes without a written unit, the size compared,
 * the filters (words, how the value arrives, the concatenated places), the line of counts, the groups and "more".
 */
function placesFrameHtml() {
  const sources = [`<option value="">${esc(t("places.source.any"))}</option>`,
    ...PLACE_SOURCES.map(s => `<option value="${s}">${esc(t("places.source." + s))}</option>`)].join("");
  return sectionHead(t("places.title"), t("places.line")) +
    `<div class="place-verdicts" role="group" aria-label="${esc(t("places.verdicts"))}"></div>` +
    `<p class="place-option muted" hidden></p>` +
    `<div class="place-compare"><label>${esc(t("places.compare"))} <input type="number" class="place-size" min="1" step="1" inputmode="numeric"></label>` +
    ` <span class="place-unit"></span> <span class="place-note muted"></span></div>` +
    `<div class="tools place-filters"><input type="search" class="filter place-text" placeholder="${esc(t("places.filter.text"))}" aria-label="${esc(t("places.filter.text"))}">` +
    `<select class="place-source" aria-label="${esc(t("places.filter.source"))}">${sources}</select>` +
    `<label class="place-joined"><input type="checkbox"> <span></span></label></div>` +
    `<p class="place-shown muted"></p><div class="place-groups"></div>` +
    `<button type="button" class="more-rows place-more" hidden></button>`;
}

/** The verdict buttons, each with its count, the ones listed pressed; SMALLER, CALLER_DECIDES and UNKNOWN in their colors. */
function verdictButtonsHtml(page: PlaceGroupsPage) {
  return verdictButtons(t, page.counts, page.size).map(b =>
    `<button type="button" class="v-${esc(b.verdict)}" data-verdict="${esc(b.verdict)}" aria-pressed="${placeState.verdicts.has(b.verdict)}" ` +
    `title="${esc(b.title)}">${esc(b.label)} <span class="count">${esc(formatNumber(b.count))}</span></button>`).join("");
}

/** What the answer says above the list: the verdict counts, the default unit's note, the size compared, the concatenated count and the line of counts. */
function renderPlacesHead(holder: HTMLElement, page: PlaceGroupsPage) {
  const verdicts = holder.querySelector<HTMLElement>(".place-verdicts")!;
  // A pressed button keeps the focus across the new answer.
  const focused = (document.activeElement as HTMLElement | null)?.dataset?.verdict;
  verdicts.innerHTML = verdictButtonsHtml(page);
  if (focused) verdicts.querySelector<HTMLElement>(`button[data-verdict="${focused}"]`)?.focus();
  const option = holder.querySelector<HTMLElement>(".place-option")!;
  const optionText = optionSizedText(t, page);
  option.hidden = optionText === null;
  option.textContent = optionText ?? "";
  holder.querySelector<HTMLInputElement>(".place-size")!.placeholder = page.column.length === null ? "" : String(page.column.length);
  holder.querySelector(".place-unit")!.textContent = compareUnitText(t, page.column.unit);
  const note = compareNote(t, page);
  holder.querySelector(".place-note")!.innerHTML = esc(note.text) +
    (note.back === null ? "" : ` <button type="button" class="place-back">${esc(note.back)}</button>`);
  holder.querySelector(".place-joined span")!.textContent = t("places.filter.concatenated", { count: page.concatenatedCount });
  holder.querySelector(".place-shown")!.textContent = shownText(t, page);
}

/**
 * One place, in four parts: its size against the size compared (a bar, the number, and the unit when it is not the
 * column's; `%TYPE`, the parameter's mode or `?` for the verdicts that have none); the target as written (a link when
 * it has an entity) with its field; how the value arrives, with the functions it goes through, "+ other text" and
 * "via view"; and its line, linking to the file (for a parameter, the call that passes the column). Focusable: its detail opens under it.
 */
function placeRowHtml(p: ColumnPlace, index: number, page: PlaceGroupsPage, groupPath: string) {
  const shown = sizeShown(t, p, page.size, page.column.unit);
  const bar = shown.fill === null ? "" : `<span class="place-bar"><i style="width:${Math.round(shown.fill * 100)}%"></i></span>`;
  const size = `<span class="place-sz">${bar}<span class="place-n">${esc(shown.text)}</span></span>`;
  const written = `<span class="mono">${codeBreaksHtml(p.targetWritten)}</span>`;
  const target = (p.target ? `<a href="${entityHref(p.target.key)}" title="${esc(p.target.display)}">${written}</a>` : written) +
    (p.field ? `<span class="mono">.${nameBreaksHtml(p.field)}</span>` : "");
  const functions = functionsCode(p);
  const how = esc(howText(t, p)) +
    (functions === null ? "" : ` <code class="place-fn" title="${esc(functionsText(t, p))}">${esc(functions)}</code>`) +
    (p.concatenated ? ` <span class="place-mark">${esc(t("places.otherText"))}</span>` : "") +
    (p.viaView ? ` <span class="place-mark">${esc(t("places.viaView"))}</span>` : "");
  // A parameter's line is the call that passes the column; the target's is in the detail.
  const line = rowAt(t, p, groupPath);
  const at = `<a href="${lineHref(line.path, line.line, line.code)}" title="${esc(line.path)}">${esc(line.text)}</a>`;
  return `<div class="place-row v-${esc(p.verdict)}" data-place="${index}" tabindex="0" aria-expanded="false" aria-controls="place-detail-${index}">` +
    `${size}<span class="place-target">${target}</span><span class="place-how">${how}</span><span class="place-at">${at}</span>` +
    (p.shown?.text ? `<span class="place-code uline"></span>` : "") + `</div>`;
}

/** What a place's row leaves out, under it when opened ([placeDetail]): each line's parts as HTML. */
function placeDetailHtml(p: ColumnPlace, index: number) {
  const part = (piece: DetailPart): string => {
    if ("text" in piece) return esc(piece.text);
    if ("code" in piece) return `<code class="decl">${codeBreaksHtml(piece.code)}</code>`;
    if ("hint" in piece) return `<abbr class="hint" title="${esc(piece.title)}">${esc(piece.hint)}</abbr>`;
    if ("entity" in piece) return `<a class="mono" href="${entityHref(piece.entity.key)}" title="${esc(piece.entity.display)}">${partBreaksHtml(piece.label)}</a>`;
    if ("view" in piece) return throughViewHtml(piece.view);
    return placeHtml({ half: null, path: piece.path, line: piece.line, col: 0, partner: null, file: true, code: piece.codeAt }, p.target ?? p.read, null);
  };
  const lines = placeDetail(t, p).map(line => `<div${line.className ? ` class="${line.className}"` : ""}${line.title ? ` title="${esc(line.title)}"` : ""}>` +
    line.parts.map(part).join("") + `</div>`);
  return `<div class="place-detail" id="place-detail-${index}">${lines.join("")}</div>`;
}

/**
 * One routine's places: a header that folds them (the routine's short name with its parameters when overloads need
 * them, then quiet its package and its file; the full name and the path as the tooltip; its counts on the right), and
 * a row per place in line order.
 */
function placeGroupHtml(g: PlaceGroup, page: PlaceGroupsPage) {
  const rows = g.rows.map(p => placeRowHtml(p, placeState.rows.push(p) - 1, page, g.path)).join("");
  const title = g.container === null ? g.path : `${g.container.display}\n${g.path}`;
  const params = g.parameters === null ? "" : `<span class="params">${partBreaksHtml(g.parameters)}</span>`;
  // An anonymous block or a script is named by its file already.
  const file = g.container === null ? "" : `<span class="place-file">${esc(fileName(g.path))}</span>`;
  const pack = g.packageName === null ? "" : `<span class="place-pkg">${nameBreaksHtml(g.packageName)}</span>`;
  const where = pack + (pack && file ? `<span class="place-sep" aria-hidden="true"> · </span>` : "") + file;
  return `<div class="place-grp"><div class="place-grp-h" role="button" tabindex="0" aria-expanded="true" title="${esc(title)}">` +
    `<span class="place-grp-name"><span class="mono">${nameBreaksHtml(g.routine)}</span>${params}` +
    (where ? ` <span class="place-where">${where}</span>` : "") + `</span>` +
    `<span class="grp-n muted">${esc(groupCountsText(t, g))}</span></div><div class="place-rows">${rows}</div></div>`;
}

/**
 * Asks for the places with what the reader chose and shows them: from the start ([more] false: the head and the first
 * groups, replacing the list), or the next groups after those shown. An answer to an older question is dropped.
 */
async function loadPlaceGroups(more: boolean) {
  const holder = document.getElementById("places")!;
  const groups = holder.querySelector<HTMLElement>(".place-groups")!;
  const moreButton = holder.querySelector<HTMLButtonElement>(".place-more")!;
  const ask = more ? placeState.asked : ++placeState.asked;
  moreButton.disabled = true;
  let page: PlaceGroupsPage | null;
  try {
    page = await columnPlaceGroups(key, {
      size: placeState.size, verdicts: VERDICTS.filter(v => placeState.verdicts.has(v)), text: placeState.text, source: placeState.source,
      concatenated: placeState.concatenated, offset: more ? placeState.next ?? 0 : 0, limit: PLACE_GROUPS
    });
  } catch (e) {
    if (ask !== placeState.asked) return;
    const message = `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
    if (more) groups.insertAdjacentHTML("beforeend", message);
    else groups.innerHTML = message;
    moreButton.disabled = false;
    return;
  }
  if (ask !== placeState.asked) return;
  moreButton.disabled = false;
  if (page === null) {
    groups.innerHTML = `<p class="muted">${esc(t("places.none"))}</p>`;
    moreButton.hidden = true;
    return;
  }
  if (!more) {
    placeState.page = page;
    placeState.rows = [];
    renderPlacesHead(holder, page);
    groups.innerHTML = page.groups.length === 0 ? `<p class="muted">${esc(t("places.empty"))}</p>` : "";
  }
  const first = placeState.rows.length;
  groups.insertAdjacentHTML("beforeend", page.groups.map(g => placeGroupHtml(g, placeState.page!)).join(""));
  // Each row's line of code, as "Used by" shows it: colored, the piece it reads marked.
  for (let index = first; index < placeState.rows.length; index++) {
    const shown = placeState.rows[index].shown;
    const holder = groups.querySelector<HTMLElement>(`.place-row[data-place="${index}"] .place-code`);
    if (!holder || !shown?.text) continue;
    holder.append(codeLineNode(shownLine(shown.text, [[shown.from, shown.to ?? shown.text.length]], shown.colors)));
    if (shown.stale) holder.insertAdjacentHTML("beforeend", ` <span class="badge warn-badge">${esc(t("text.stale"))}</span>`);
  }
  placeState.next = page.nextOffset;
  moreButton.hidden = page.nextOffset === null;
  moreButton.textContent = moreText(t, page.groupsLeft, PLACE_GROUPS);
}

/** Asks for the places again after a pause in the typing. */
function loadPlacesSoon() {
  window.clearTimeout(placeState.typing);
  placeState.typing = window.setTimeout(() => loadPlaceGroups(false), PLACES_TYPING);
}

/** Opens or closes a place's detail, made the first time it opens. */
function togglePlace(row: HTMLElement) {
  const open = row.getAttribute("aria-expanded") !== "true";
  row.setAttribute("aria-expanded", String(open));
  const index = Number(row.dataset.place);
  const detail = document.getElementById("place-detail-" + index);
  if (detail) detail.hidden = !open;
  else row.insertAdjacentHTML("afterend", placeDetailHtml(placeState.rows[index], index));
}

/** Folds or unfolds a routine's places. */
function toggleGroup(head: HTMLElement) {
  head.setAttribute("aria-expanded", String(head.getAttribute("aria-expanded") !== "true"));
}

/**
 * The places the page's column is read into, compared with a size: the frame once, its controls each asking again
 * (the size and the words after a pause in the typing), then the first groups. A column whose value is only ever
 * one part of a concatenation ([joinedOnly]) lists those places from the start.
 */
function setupPlaces(joinedOnly: boolean) {
  const holder = document.getElementById("places")!;
  holder.innerHTML = placesFrameHtml();
  placeState.concatenated = joinedOnly;
  holder.querySelector<HTMLInputElement>(".place-joined input")!.checked = joinedOnly;
  holder.addEventListener("click", e => {
    const target = e.target as Element;
    const verdict = target.closest<HTMLButtonElement>("button[data-verdict]");
    if (verdict) {
      const name = verdict.dataset.verdict as Verdict;
      const shown = (placeState.page?.counts ?? []).map(c => c.name);
      // At least one of the verdicts shown stays listed: an empty list asks for nothing.
      if (placeState.verdicts.has(name) && !shown.some(v => v !== name && placeState.verdicts.has(v))) return;
      if (!placeState.verdicts.delete(name)) placeState.verdicts.add(name);
      verdict.setAttribute("aria-pressed", String(placeState.verdicts.has(name)));
      loadPlaceGroups(false);
      return;
    }
    if (target.closest("button.place-back")) {
      window.clearTimeout(placeState.typing);
      holder.querySelector<HTMLInputElement>(".place-size")!.value = "";
      placeState.size = null;
      loadPlaceGroups(false);
      return;
    }
    if (target.closest("button.place-more")) {
      loadPlaceGroups(true);
      return;
    }
    if (target.closest("a, button, input, select, label")) return;
    const head = target.closest<HTMLElement>(".place-grp-h");
    if (head) {
      toggleGroup(head);
      return;
    }
    const row = target.closest<HTMLElement>(".place-row");
    if (row) togglePlace(row);
  });
  holder.addEventListener("keydown", e => {
    if (e.key !== "Enter" && e.key !== " ") return;
    const target = e.target as HTMLElement;
    if (target.matches(".place-row")) togglePlace(target);
    else if (target.matches(".place-grp-h")) toggleGroup(target);
    else return;
    e.preventDefault();
  });
  holder.addEventListener("input", e => {
    const target = e.target as HTMLInputElement;
    if (target.matches(".place-size")) {
      const text = target.value.trim();
      const size = Number(text);
      if (text !== "" && !(Number.isInteger(size) && size > 0)) return;
      placeState.size = text === "" ? null : size;
      loadPlacesSoon();
    } else if (target.matches(".place-text")) {
      placeState.text = target.value.trim();
      loadPlacesSoon();
    }
  });
  holder.addEventListener("change", e => {
    const target = e.target as HTMLInputElement | HTMLSelectElement;
    if (target.matches(".place-source")) placeState.source = (target.value || null) as PlaceSource | null;
    else if (target.matches(".place-joined input")) placeState.concatenated = (target as HTMLInputElement).checked;
    else return;
    loadPlaceGroups(false);
  });
  loadPlaceGroups(false);
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
