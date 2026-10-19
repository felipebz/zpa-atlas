"use strict";

const key = new URLSearchParams(location.search).get("key");
const SUBPROGRAM_KINDS = ["PROCEDURE", "FUNCTION"];
const VIEWED_KINDS = ["TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "UNKNOWN_RELATION", "COLUMN"];
const state = {
  roles: new Set(), statuses: new Set(), implementations: false, children: false, views: false, after: null,
  scopes: [], direct: null, ambiguous: null, own: ""
};

function lazySource(path, line) {
  const id = "src" + Math.random().toString(36).slice(2);
  return `<a href="#" data-src="${id}" data-path="${esc(path)}" data-line="${line}">${esc(t("source.show"))}</a><div id="${id}"></div>`;
}

document.addEventListener("click", e => {
  const link = e.target.closest("a[data-src]");
  if (!link) return;
  e.preventDefault();
  sourceLine(link.dataset.path, Number(link.dataset.line), document.getElementById(link.dataset.src), 0);
  link.remove();
});

/** A list of entity links in columns, folded behind its title when longer than 20. */
function linkList(title, items) {
  if (items.length === 0) return "";
  return `<details class="section" ${items.length <= 20 ? "open" : ""}><summary>${esc(title)} (${esc(formatNumber(items.length))})</summary>
    <ul class="cols">${items.map(item => `<li>${item}</li>`).join("")}</ul></details>`;
}

/** A spec and its body (either may be missing) in their order, grouped by [keyOf]. */
function specBodyGroups(list, keyOf) {
  const groups = new Map();
  for (const item of list) {
    const k = keyOf(item);
    const parent = parentKeyOf(k);
    const group = groups.get(parent) ?? groups.set(parent, { spec: null, body: null }).get(parent);
    if (k === parent) group.spec = item;
    else group.body = item;
  }
  return [...groups.values()];
}

function bodyBadgeLink(entity) {
  return ` <a class="badge" href="${entityHref(entity.key)}">${esc(t("entity.overload.body"))}</a>`;
}

function headerHtml(entity) {
  const parts = displayParts(entity.display, entity.name);
  const external = entity.external ? ` <span class="badge external">${esc(t("entity.external"))}</span>` : "";
  const body = parts.body ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  const parent = entity.parent
    ? `<div class="muted">${esc(t("entity.parent"))}</div><div>${entityLink(entity.parent)}</div>`
    : "";
  return `<h1><span class="ename" title="${esc(entity.display)}"><span class="name">${esc(parts.base)}</span></span>${external}${body}</h1>
    <div class="facts">
      <div><span class="badge">${esc(kindLabel(entity.kind))}</span> ${esc(t("entity.schema", { schema: entity.schema ?? t("entity.schemaUnknown") }))}</div>
      ${parent}
      <div><button class="copy" title="${esc(entity.key)}">${esc(t("entity.copyKey"))}</button></div>
    </div>`;
}

/** The definitions as a stacked list that fits the sidebar; a path may break after each slash. */
function definitionsHtml(entity) {
  if (entity.definitions.length === 0) return "";
  return `<h2>${esc(t("entity.definitions"))}</h2><ul class="stacked">${entity.definitions.map(d =>
    `<li><span class="muted">${esc(d.roles.map(roleLabel).join(", "))}</span>${d.status === "RESOLVED" ? "" : " " + statusBadge(d.status)}
     <div><span class="mono">${esc(d.path).replace(/\//g, "/<wbr>")}:</span>${lineLink(d.path, d.span.startLine, d.span.startCol)} ${lazySource(d.path, d.span.startLine)}</div></li>`).join("")}</ul>`;
}

/** The parameters of a subprogram, from its signature; each name links to the parameter entity. */
function parametersHtml(entity, params) {
  if (params === null) return "";
  if (params.length === 0) return `<h2>${esc(t("entity.parameters"))}</h2><p class="muted">${esc(t("entity.parameters.none"))}</p>`;
  const byName = new Map(entity.children.map(m => [m.entity.name, m.entity]));
  const unquoted = name => name.startsWith('"') && name.endsWith('"') ? name.slice(1, -1) : name;
  return `<h2>${esc(t("entity.parameters"))}</h2><ul class="stacked">${params.map(p => {
    const child = byName.get(p.name) ?? byName.get(unquoted(p.name));
    const name = child ? `<a class="mono" href="${entityHref(child.key)}">${esc(p.name)}</a>` : `<span class="mono">${esc(p.name)}</span>`;
    const mode = p.mode ? ` <span class="badge">${esc(p.mode)}</span>` : "";
    return `<li>${name}${mode}<div class="mono muted">${esc(p.type)}</div></li>`;
  }).join("")}</ul>`;
}

function overloadsHtml(groups) {
  if (groups.length < 2) return "";
  const link = (o, label) => !o ? "" : o.self ? ` <b>${esc(t(label))}</b>` : ` <a href="${entityHref(o.entity.key)}">${esc(t(label))}</a>`;
  return `<h2>${esc(t("entity.overloads"))}</h2><ul>${groups.map(g =>
    `<li>${nameHtml((g.spec ?? g.body).entity, { relative: true, noBadge: true })}${link(g.spec, "entity.overload.spec")}${link(g.body, "entity.overload.body")}</li>`).join("")}</ul>`;
}

/**
 * The [members] by kind, each with its uses; a subprogram's spec and body are one row. The caller leaves out the
 * parameters when the parameter list shows them.
 */
function childrenHtml(members) {
  if (members.length === 0) return "";
  const byKind = new Map();
  for (const member of members) (byKind.get(member.entity.kind) ?? byKind.set(member.entity.kind, []).get(member.entity.kind)).push(member);
  // The uses column is the narrowest that fits, at the right: the same place in every kind's table.
  const row = (link, usages) => `<tr><td>${link}</td><td class="num">${esc(formatNumber(usages))}</td></tr>`;
  const head = `<tr><th>${esc(t("search.column.name"))}</th><th class="num">${esc(t("search.column.usages"))}</th></tr>`;
  return `<h2>${esc(t("entity.children"))}</h2><div class="kinds">${[...byKind].map(([kind, list]) => {
    const rows = SUBPROGRAM_KINDS.includes(kind)
      ? specBodyGroups(list, m => m.entity.key).map(g => row(
        entityLink((g.spec ?? g.body).entity, { relative: true, noBadge: true }) + (g.body ? bodyBadgeLink(g.body.entity) : ""),
        (g.spec?.usages ?? 0) + (g.body?.usages ?? 0)))
      : list.map(m => row(entityLink(m.entity, { relative: true }), m.usages));
    return `<details ${rows.length <= 30 ? "open" : ""}><summary>${esc(kindLabel(kind))} (${esc(formatNumber(rows.length))})</summary>${scrollTable(head + rows.join(""), "compact")}</details>`;
  }).join("")}</div>`;
}

function triggersHtml(entity, own) {
  if (entity.triggerEvents.length === 0) return "";
  return `<h2>${esc(t(own ? "trigger.events" : "trigger.firing"))}</h2>${scrollTable(entity.triggerEvents.map(e =>
    `<tr>${own ? "" : `<td>${entityLink(e.trigger)}</td>`}<td>${esc(t("trigger.timing." + e.timing))}</td><td>${esc(e.level ? t("trigger.level." + e.level) : "")}</td>
     <td>${esc(e.event)}${e.column ? " " + esc(t("trigger.of")) + " " + entityLink(e.column) : ""}</td></tr>`).join(""), "compact")}`;
}

function renderEntity(entity) {
  document.title = t("page.entity.titleWith", { name: entity.name });
  const set = (id, html) => { document.getElementById(id).innerHTML = html; };
  set("header", headerHtml(entity));
  set("definitions", definitionsHtml(entity));
  const params = SUBPROGRAM_KINDS.includes(entity.kind) ? displayParts(entity.display, entity.name).params ?? [] : null;
  set("parameters", parametersHtml(entity, params));
  const overloads = specBodyGroups(entity.overloads, o => o.entity.key);
  set("overloads", overloadsHtml(overloads));
  // A subprogram's parameters are in its parameter list, not repeated under its contents.
  const members = params === null ? entity.children : entity.children.filter(m => m.entity.kind !== "PARAMETER");
  set("children", childrenHtml(members));
  set("relations", linkList(t("entity.relations"), entity.relations.map(r =>
    `${esc(r.kind)} ${r.direction === "OUT" ? "→" : "←"} ${entityLink(r.entity)}`)));
  // A trigger lists its events; a relation or a column, the triggers that fire on it.
  const own = entity.kind === "TRIGGER";
  set("triggers", triggersHtml(entity, own));
  // A view's base relations, and the views over a relation: both from the SQL reads of the view queries.
  set("viewReads", linkList(t("entity.reads"), entity.reads.map(e => entityLink(e))));
  set("views", linkList(t("entity.views"), entity.views.map(e => entityLink(e))));
  // A view column's sources, and the view columns computed from a column: one hop of the stored lineage.
  set("baseColumns", linkList(t("entity.baseColumns"), entity.baseColumns.map(e => entityLink(e))));
  set("viewColumns", linkList(t("entity.viewColumns"), entity.viewColumns.map(e => entityLink(e))));

  const u = entity.usage;
  const direct = u.byStatus.reduce((n, c) => n + c.count, 0);
  state.own = u.asCandidate > 0
    ? t("usage.summary", { direct: t("usage.direct", { count: direct }), candidate: t("usage.candidate", { count: u.asCandidate }) })
    : t("usage.direct", { count: direct });

  state.implementations = [...SUBPROGRAM_KINDS, "PACKAGE"].includes(entity.kind);
  state.children = ["PACKAGE", "TYPE"].includes(entity.kind);
  state.scopes = [
    [...SUBPROGRAM_KINDS, "PACKAGE"].includes(entity.kind) && "implementations",
    entity.children.length > 0 && "children",
    VIEWED_KINDS.includes(entity.kind) && "views"
  ].filter(Boolean);

  // What a parameter is passed and what a column is written: the same groups, from different rows.
  const valuesTitle = entity.kind === "PARAMETER" ? "values.title.passed" : entity.kind === "COLUMN" ? "values.title.written" : null;
  if (valuesTitle) loadValues(valuesTitle);

  // The sidebar shows the definitions, parameters and overloads itself; the list leads to the content column.
  const sections = [
    ["children", t("entity.children"), members.length],
    ["values", valuesTitle && t(valuesTitle), valuesTitle ? null : 0],
    ["usage", t("usage.title"), direct],
    ["relations", t("entity.relations"), entity.relations.length],
    ["triggers", t(own ? "trigger.events" : "trigger.firing"), entity.triggerEvents.length],
    ["viewReads", t("entity.reads"), entity.reads.length],
    ["views", t("entity.views"), entity.views.length],
    ["baseColumns", t("entity.baseColumns"), entity.baseColumns.length],
    ["viewColumns", t("entity.viewColumns"), entity.viewColumns.length]
  ];
  // The usage section is always there, its count replaced by the scope's total once it loads; values have no count
  // until they load.
  set("sections", sections.filter(([id, , count]) => id === "usage" || count === null || count > 0).map(([id, label, count]) =>
    `<a href="#${id}" data-section="${id}"><span>${esc(label)}</span>${count === null ? "" : `<span class="count">${esc(formatNumber(count))}</span>`}</a>`).join(""));
}

document.getElementById("header").addEventListener("click", async e => {
  const button = e.target.closest("button.copy");
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

function valueLabel(group) {
  const literal = literalText(group.valueKind, group.value);
  if (literal !== null) return `<span class="mono">${esc(literal)}</span>`;
  if (group.valueKind === "NAME") return `<span class="mono">${esc(group.value)}</span>`;
  return `<span class="muted">${esc(t("values.kind." + group.valueKind))}</span>`;
}

function valueRowsHtml(groups, from, to) {
  return groups.slice(from, to).map((g, offset) => {
    const i = from + offset;
    return `<tr><td><span class="badge">${esc(g.valueKind)}</span></td><td>${valueLabel(g)}</td><td>${esc(formatNumber(g.count))}</td>
      <td><a href="#" data-group="${i}">${esc(t("values.sites"))}</a></td></tr>
      <tr hidden data-calls="${i}"><td colspan="4"><button class="more" hidden>${esc(t("usage.more"))}</button></td></tr>`;
  }).join("");
}

async function loadValues(title) {
  const target = document.getElementById("values");
  const groups = (await api("api/values", { key })).result;
  if (groups.length === 0) {
    target.innerHTML = `<h2>${esc(t(title))}</h2><p class="muted">${esc(t("values.none"))}</p>`;
    return;
  }
  target.innerHTML = `<h2>${esc(t(title))}</h2><div class="scroll"><table class="compact"><tbody>${valueRowsHtml(groups, 0, VALUES_SHOWN)}</tbody></table></div>` +
    (groups.length > VALUES_SHOWN ? `<p><a href="#" data-show-all>${esc(t("values.showAll", { count: groups.length }))}</a></p>` : "");
  const blocks = new Map();
  target.addEventListener("click", e => {
    if (e.target.closest("a[data-show-all]")) {
      e.preventDefault();
      target.querySelector("tbody").insertAdjacentHTML("beforeend", valueRowsHtml(groups, VALUES_SHOWN, groups.length));
      e.target.closest("p").remove();
      return;
    }
    const link = e.target.closest("a[data-group]");
    if (link) {
      e.preventDefault();
      const index = Number(link.dataset.group);
      const row = target.querySelector(`tr[data-calls="${index}"]`);
      row.hidden = !row.hidden;
      if (!row.hidden && !blocks.has(index)) {
        const block = usesBlock();
        blocks.set(index, block);
        row.firstElementChild.prepend(block.element);
        loadValueSites(groups[index], block, row, null);
      }
      return;
    }
    const more = e.target.closest("tr[data-calls] button.more");
    if (more) {
      const index = Number(more.closest("tr").dataset.calls);
      loadValueSites(groups[index], blocks.get(index), more.closest("tr"), more.dataset.after);
    }
  });
}

/** One page of the calls or writes that give [group]'s value, appended to its "who uses" [block]. */
async function loadValueSites(group, block, row, after) {
  const page = (await api("api/value-sites", { key, kind: group.valueKind, value: group.value, after, limit: 100 })).result;
  block.append(page.rows);
  const more = row.querySelector("button.more");
  more.hidden = page.nextAfter === null;
  if (page.nextAfter !== null) more.dataset.after = page.nextAfter;
}

// ---- "who uses" filters and pages ---------------------------------------------------------------------------------

function renderScope() {
  const toggle = name =>
    `<span class="chip ${state[name] ? "on" : ""}" data-scope="${name}">${esc(t("usage.scope." + name))}</span>`;
  document.getElementById("scope").innerHTML = state.scopes.length === 0 ? "" : esc(t("usage.scope")) + " " + state.scopes.map(toggle).join("");
}

async function loadUsages(reset) {
  if (reset) {
    state.after = null;
    state.direct = usesBlock();
    state.ambiguous = usesBlock();
    document.getElementById("direct").replaceChildren(state.direct.element);
    document.getElementById("ambiguousRows").replaceChildren(state.ambiguous.element);
    document.getElementById("ambiguous").hidden = true;
  }
  const scope = ["SELF"];
  if (state.implementations) scope.push("IMPLEMENTATIONS");
  if (state.children) scope.push("CHILDREN");
  if (state.views) scope.push("VIEWS");
  const answer = await api("api/occurrences", {
    key, scope: scope.join(","), roles: [...state.roles].join(","), status: [...state.statuses].join(","),
    candidates: true, after: state.after, limit: 100
  });
  const page = answer.result;
  renderScope();
  renderChips("roleChips", page.facets.byRole, state.roles, "role", roleLabel);
  renderChips("statusChips", page.facets.byStatus, state.statuses, "status");
  if (state.roles.size || state.statuses.size) {
    document.getElementById("statusChips").insertAdjacentHTML("beforeend", ` <a href="#" id="clear">${esc(t("filters.clear"))}</a>`);
  }
  const direct = page.rows.filter(r => !r.viaCandidate);
  const ambiguous = page.rows.filter(r => r.viaCandidate);
  if (direct.length) document.querySelector("#direct > p.muted")?.remove();
  state.direct.append(direct);
  state.ambiguous.append(ambiguous);
  if (ambiguous.length) document.getElementById("ambiguous").hidden = false;
  // Without filters the summary line already says there is no direct use.
  if (reset && direct.length === 0 && (state.roles.size || state.statuses.size)) {
    document.getElementById("direct").insertAdjacentHTML("afterbegin", `<p class="muted">${esc(t("usage.noDirect"))}</p>`);
  }
  // The summary line and the section count give the listed total; with members, implementations or views in scope it
  // is more than the entity's own uses, which the line keeps beside it.
  if (reset) {
    document.getElementById("total").textContent = scope.length > 1
      ? t("usage.scoped", { listed: t("usage.listed", { count: page.total }), own: state.own })
      : t("usage.own", { own: state.own });
    document.querySelector('#sections a[data-section="usage"] .count').textContent = formatNumber(page.total);
  }
  // The query has no filter for spec and body or for uses through views: it covers the entity and, with members, its contents.
  const open = document.getElementById("openQuery");
  open.href = queryHref({
    entity: key, inside: state.children ? "true" : "", roles: [...(state.roles.size ? state.roles : USAGE_ROLES)].join(","),
    statuses: [...state.statuses].join(",")
  });
  open.title = t(state.children ? "query.open.withContents" : "query.open.entityOnly");
  state.after = page.nextAfter;
  document.getElementById("more").hidden = page.nextAfter === null;
}

document.getElementById("usage").addEventListener("click", e => {
  if (e.target.id === "clear") {
    e.preventDefault();
    state.roles.clear();
    state.statuses.clear();
    loadUsages(true);
    return;
  }
  const chip = e.target.closest(".chip");
  if (!chip) return;
  if (chip.dataset.scope) state[chip.dataset.scope] = !state[chip.dataset.scope];
  for (const [attribute, set] of [["role", state.roles], ["status", state.statuses]]) {
    const value = chip.dataset[attribute];
    if (value) set.has(value) ? set.delete(value) : set.add(value);
  }
  loadUsages(true);
});
document.getElementById("more").addEventListener("click", () => loadUsages(false));

(async () => {
  await initI18n();
  showBanner();
  if (!key) { document.getElementById("header").textContent = t("entity.missingKey"); return; }
  try {
    const answer = await api("api/entity", { key });
    renderEntity(answer.result);
    await loadUsages(true);
  } catch (e) {
    document.getElementById("header").innerHTML = `<p>${esc(t("error.message", { error: e.message }))}</p>`;
  }
})();
