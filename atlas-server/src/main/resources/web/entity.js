"use strict";

const key = new URLSearchParams(location.search).get("key");
const state = { roles: new Set(), statuses: new Set(), implementations: false, children: false, views: false, after: null, groups: {} };

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

function renderEntity(entity) {
  document.title = t("page.entity.titleWith", { name: entity.display });
  document.getElementById("header").innerHTML =
    `<h1>${esc(entity.display)} ${entity.external ? `<span class="badge external">${esc(t("entity.external"))}</span>` : ""}</h1>
     <p><span class="badge">${esc(kindLabel(entity.kind))}</span> ${esc(t("entity.schema", { schema: entity.schema ?? t("entity.schemaUnknown") }))}
     ${entity.parent ? " · " + esc(t("entity.parent")) + " " + entityLink(entity.parent) : ""}</p>`;
  document.getElementById("definitions").innerHTML = entity.definitions.length === 0 ? "" :
    `<h2>${esc(t("entity.definitions"))}</h2><table>${entity.definitions.map(d =>
      `<tr><td>${esc(d.roles.map(roleLabel).join(", "))}</td><td class="mono">${esc(d.path)}:${d.span.startLine}:${d.span.startCol}</td>
       <td>${statusBadge(d.status)}</td><td>${openFileLink(d.path, d.span.startLine)} · ${lazySource(d.path, d.span.startLine)}</td></tr>`).join("")}</table>`;
  document.getElementById("overloads").innerHTML = entity.overloads.length <= 1 ? "" :
    `<h2>${esc(t("entity.overloads"))}</h2><ul>${entity.overloads.map(o =>
      `<li>${o.self ? "<b>" + esc(o.entity.display) + "</b>" : entityLink(o.entity)} <span class="badge">${esc(t(o.body ? "entity.overload.body" : "entity.overload.spec"))}</span></li>`).join("")}</ul>`;
  document.getElementById("relations").innerHTML = entity.relations.length === 0 ? "" :
    `<h2>${esc(t("entity.relations"))}</h2><ul>${entity.relations.map(r =>
      `<li>${esc(r.kind)} ${r.direction === "OUT" ? "→" : "←"} ${entityLink(r.entity)}</li>`).join("")}</ul>`;
  // A trigger lists its events; a relation or a column, the triggers that fire on it.
  const own = entity.kind === "TRIGGER";
  document.getElementById("triggers").innerHTML = entity.triggerEvents.length === 0 ? "" :
    `<h2>${esc(t(own ? "trigger.events" : "trigger.firing"))}</h2><table>${entity.triggerEvents.map(e =>
      `<tr>${own ? "" : `<td>${entityLink(e.trigger)}</td>`}<td>${esc(t("trigger.timing." + e.timing))}</td><td>${esc(e.level ? t("trigger.level." + e.level) : "")}</td>
       <td>${esc(e.event)}${e.column ? " " + esc(t("trigger.of")) + " " + entityLink(e.column) : ""}</td></tr>`).join("")}</table>`;
  // A view's base relations, and the views over a relation: both from the SQL reads of the view queries.
  const links = (list, title) => list.length === 0 ? "" :
    `<h2>${esc(t(title))}</h2><ul>${list.map(e => `<li>${entityLink(e)}</li>`).join("")}</ul>`;
  document.getElementById("viewReads").innerHTML = links(entity.reads, "entity.reads");
  document.getElementById("views").innerHTML = links(entity.views, "entity.views");
  // A view column's sources, and the view columns computed from a column: one hop of the stored lineage.
  document.getElementById("baseColumns").innerHTML = links(entity.baseColumns, "entity.baseColumns");
  document.getElementById("viewColumns").innerHTML = links(entity.viewColumns, "entity.viewColumns");
  const byKind = {};
  for (const child of entity.children) (byKind[child.kind] ??= []).push(child);
  document.getElementById("children").innerHTML = entity.children.length === 0 ? "" :
    `<h2>${esc(t("entity.children"))}</h2>${Object.entries(byKind).map(([kind, list]) =>
      `<details ${list.length <= 30 ? "open" : ""}><summary>${esc(kindLabel(kind))} (${esc(formatNumber(list.length))})</summary><ul>${list.map(c =>
        `<li>${entityLink(c)}</li>`).join("")}</ul></details>`).join("")}`;
  const u = entity.usage;
  document.getElementById("total").textContent = t("usage.summary", {
    direct: t("usage.direct", { count: u.byStatus.reduce((n, c) => n + c.count, 0) }),
    candidate: t("usage.candidate", { count: u.asCandidate })
  });
  state.implementations = ["PROCEDURE", "FUNCTION", "PACKAGE"].includes(entity.kind);
}

function renderScope() {
  const toggle = (name, label) =>
    `<span class="chip ${state[name] ? "on" : ""}" data-scope="${name}">${esc(t(label))}</span>`;
  document.getElementById("scope").innerHTML = esc(t("usage.scope")) + " " +
    toggle("implementations", "usage.scope.implementations") + toggle("children", "usage.scope.children") +
    toggle("views", "usage.scope.views");
}

function renderChips(id, counts, selected, attribute, label) {
  // A selected value stays visible (count 0) even when the other filter leaves it out of the facet.
  const listed = new Set(counts.map(c => c.value));
  counts = counts.concat([...selected].filter(v => !listed.has(v)).map(v => ({ value: v, count: 0 })));
  document.getElementById(id).innerHTML = counts.map(c =>
    `<span class="chip ${selected.has(c.value) ? "on" : ""}" data-${attribute}="${esc(c.value)}">${esc(label ? label(c.value) : c.value)} (${esc(formatNumber(c.count))})</span>`).join("");
}

function rowHtml(row) {
  const args = row.arguments.length === 0 ? "" : `<div class="muted mono">(${row.arguments.map(a =>
    (a.label ? esc(a.label) + " => " : "") + (a.parameter ? "→" + esc(a.parameter.display.split(".").pop()) : "?")).join(", ")})</div>`;
  const others = row.viaCandidate ? `<div class="muted">${esc(t("usage.candidates"))} ${row.candidates.map(entityLink).join(", ")}</div>` : "";
  // The view column or view the row was reached through.
  const via = row.viaView ? `<div class="muted">${esc(t("usage.via"))} ${entityLink(row.matched)}</div>` : "";
  return `<tr><td class="mono">${row.span.startLine}:${row.span.startCol}</td><td>${entityLink(row.container)}
    <div class="muted">${esc(row.unit.kind)} ${esc(row.unit.name)}</div></td>
    <td class="mono">${esc(row.written)}${args}${via}</td><td>${esc(row.roles.map(roleLabel).join(", "))}${row.statementKind ? '<div class="muted">' + esc(row.statementKind) + "</div>" : ""}</td>
    <td>${statusBadge(row.status, row.reason)}${others}</td><td>${openFileLink(row.path, row.span.startLine)} · ${lazySource(row.path, row.span.startLine)}</td></tr>`;
}

function appendRows(rows) {
  for (const row of rows) {
    const target = row.viaCandidate ? "ambiguousRows" : "direct";
    const groupKey = target + "|" + row.path;
    let tbody = state.groups[groupKey];
    if (!row.viaCandidate) document.querySelector("#direct > p.muted")?.remove();
    if (!tbody) {
      document.getElementById(target).insertAdjacentHTML("beforeend",
        `<h3 class="file mono">${esc(row.path)}</h3><table><tbody></tbody></table>`);
      tbody = document.getElementById(target).lastElementChild.querySelector("tbody");
      state.groups[groupKey] = tbody;
    }
    tbody.insertAdjacentHTML("beforeend", rowHtml(row));
    if (row.viaCandidate) document.getElementById("ambiguous").hidden = false;
  }
}

async function loadUsages(reset) {
  if (reset) {
    state.after = null;
    state.groups = {};
    document.getElementById("direct").innerHTML = "";
    document.getElementById("ambiguousRows").innerHTML = "";
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
  appendRows(page.rows);
  if (reset && !page.rows.some(r => !r.viaCandidate)) {
    document.getElementById("direct").innerHTML = `<p class="muted">${esc(t("usage.noDirect"))}</p>`;
  }
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
