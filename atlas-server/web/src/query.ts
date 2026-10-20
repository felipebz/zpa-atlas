import {
  api, Count, displayParts, entityLink, EntityRef, errorMessage, esc, formatNumber, getOrPut, initI18n, kindLabel, langSuffix, literalText, nameHtml,
  OccurrencePage, parentKeyOf, qualifierHtml, queryHref, renderChips, ROLES, roleLabel, scrollTable, showBanner, t, usesBlock
} from "./common";

/** The filters and mode of a query, as the page URL carries them. */
type QueryParams = Record<string, string>;

/** What the group answer holds for one dimension of a group; the fields a dimension does not use are null in the API. */
interface GroupValue {
  entity: EntityRef | null;
  text: string;
  unit: { kind: string; name: string } | null;
  path: string;
  valueKind: string;
}

interface GroupPage {
  groups: { count: number; values: GroupValue[] }[];
  totalGroups: number;
}

// The codes the query accepts, as the index stores them (the roles are in common.js).
const STATUSES = ["RESOLVED", "UNVERIFIED", "AMBIGUOUS", "UNRESOLVED", "EXTERNAL", "DYNAMIC", "PARTIAL_SOURCE"];
const ENTITY_KINDS = ["SCHEMA", "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "COLUMN", "SYNONYM", "SEQUENCE", "TRIGGER", "PACKAGE",
  "PROCEDURE", "FUNCTION", "PARAMETER", "TYPE", "RECORD_FIELD", "PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "EXCEPTION", "CURSOR",
  "LOCAL_VARIABLE", "LOCAL_CONSTANT", "LABEL", "FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM", "FORMS_TRIGGER", "FORMS_PROGRAM_UNIT",
  "FORMS_LIBRARY", "UNKNOWN_RELATION"];
const UNIT_KINDS = ["PACKAGE", "PACKAGE_BODY", "PROCEDURE", "FUNCTION", "TRIGGER", "TYPE", "TYPE_BODY", "VIEW", "MATERIALIZED_VIEW",
  "ANONYMOUS_BLOCK", "SQL_STATEMENT", "UNPARSED", "FORMS_TRIGGER", "FORMS_PROGRAM_UNIT"];
const ORIGINS = ["STATIC", "DYNAMIC_LITERAL", "FORMS_IMPLICIT", "HEURISTIC"];
const STATEMENT_KINDS = ["SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "LOCK", "DYNAMIC", "DDL"];
const VALUE_KINDS = ["STRING", "NUMBER", "NULL", "BOOLEAN", "NAME", "BIND", "EXPRESSION", "DEFAULT"];
const VALUES_WITH_TEXT = new Set(["STRING", "NUMBER", "BOOLEAN", "NAME"]);
const DIMENSIONS = ["ENTITY", "ENTITY_KIND", "CONTAINER", "UNIT", "FILE", "ROLE", "STATUS", "REASON", "ORIGIN", "STATEMENT_KIND", "VALUE"];

const code = (value: string) => value;
/** The multi-value filters picked from a list: their codes and how a code is shown. */
const PICKS: Record<string, [string[], (code: string) => string]> = {
  entityKinds: [ENTITY_KINDS, kindLabel], unitKinds: [UNIT_KINDS, code], roles: [ROLES, roleLabel],
  statuses: [STATUSES, code], origins: [ORIGINS, code], statementKinds: [STATEMENT_KINDS, code]
};
/** The text and select filters of "more filters". */
const TEXTS = ["schema", "path", "reasons", "value", "external", "valueKind"];
/** The filters behind "more filters"; the URL setting any of them opens it. */
const MORE = ["entityKinds", "schema", "external", "candidates", "unitKinds", "containerInside", "path", "statuses", "reasons", "origins",
  "statementKinds", "valueKind", "value"];
/** The facets of a list answer and the filter each one sets. */
const FACETS: [string, string][] = [["byRole", "roles"], ["byStatus", "statuses"], ["byReason", "reasons"], ["byOrigin", "origins"], ["byEntityKind", "entityKinds"],
  ["byUnitKind", "unitKinds"]];
const labelOf = (filter: string) => PICKS[filter]?.[1] ?? code;
const PAGE = 100;
const GROUPS = 200;
const SUGGESTIONS = 10;
const FACET_SHOWN = 8;

const form = document.getElementById("query") as HTMLFormElement;
const answer = document.getElementById("answer")!;
/** A form control by name (`form.name` is the form's own name, not the input). */
const field = (name: string) => form.elements.namedItem(name) as HTMLInputElement;
/** The query that ran last, as page parameters; null before the first run. */
let current: QueryParams | null = null;
/** Only the latest run fills the answer. */
let generation = 0;
/** The dimensions grouped last, offered again when the answer goes back from the list to groups. */
let lastGroupBy = "ENTITY";
/** The entity each suggest field has picked, by its parameter. */
const picks: Record<string, EntityRef | null> = { entity: null, container: null };
/** The text typed in each suggest field before it picked, put back when the pick is removed. */
const typed: Record<string, string> = { entity: "", container: "" };
/** A `name` that came in a URL beside an `entity`: shown under the picked entity, kept until removed. */
let keptName: string | null = null;

// ---- entities by key -----------------------------------------------------------------------------------------------

const entities = new Map<string, Promise<EntityRef>>();

/** The entity of [key] (key, kind, display, name, external), or a stand-in showing the key when it cannot be read. */
function entityOf(key: string) {
  return getOrPut(entities, key, () => api<{ result: EntityRef }>("api/entity", { key }).then(a => a.result, () => null)
    .then(e => e ?? { key, kind: "", display: key, name: key, external: false }));
}

// ---- the suggest fields ----------------------------------------------------------------------------------------------

function setupSuggest(box: HTMLElement) {
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  const list = box.querySelector<HTMLElement>("ul.suggestions")!;
  let timer: number | undefined;
  let hits: EntityRef[] = [];
  let active = -1;
  let asked = 0;
  const close = () => {
    clearTimeout(timer);
    asked++;
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    active = -1;
  };
  const highlight = (index: number) => {
    active = index;
    list.querySelectorAll("li").forEach((li, i) => li.classList.toggle("active", i === active));
  };
  input.addEventListener("input", () => {
    clearTimeout(timer);
    const text = input.value.trim();
    // A pattern or a key is a filter as typed, not a search.
    if (!text || text.includes("*") || text.startsWith("db:")) { close(); return; }
    timer = setTimeout(async () => {
      const ask = ++asked;
      try {
        const found = (await api<{ result: EntityRef[] }>("api/entities", { q: text, limit: SUGGESTIONS })).result;
        if (ask !== asked || input.value.trim() !== text) return;
        hits = found;
        list.innerHTML = hits.length === 0 ? `<li class="muted">${esc(t("search.empty"))}</li>` : hits.map((h, i) => {
          const parts = displayParts(h.display, h.name);
          const qualifier = parts.shortQualifier.replace(/\.$/, "");
          return `<li role="option" data-index="${i}"><span class="hit-name">${nameHtml(h, { relative: true })}</span>
            <span class="badge">${esc(kindLabel(h.kind))}</span>${qualifier ? ` <span class="muted">${qualifierHtml(qualifier)}</span>` : ""}</li>`;
        }).join("");
        list.hidden = false;
        input.setAttribute("aria-expanded", "true");
        highlight(-1);
      } catch {
        close();
      }
    }, 250);
  });
  input.addEventListener("keydown", e => {
    // Enter without a highlighted suggestion closes the list and runs the typed text (the form submits).
    if (e.key === "Enter" && active < 0) { close(); return; }
    if (list.hidden) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (hits.length) highlight(e.key === "ArrowDown" ? (active + 1) % hits.length : active <= 0 ? hits.length - 1 : active - 1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      pick(box, hits[active]);
      close();
    } else if (e.key === "Escape") {
      e.preventDefault();
      close();
    }
  });
  input.addEventListener("blur", () => setTimeout(close, 150));
  // Mouse down, not click: the input's blur would close the list first.
  list.addEventListener("mousedown", e => {
    const li = (e.target as Element).closest<HTMLElement>("li[data-index]");
    if (!li) return;
    e.preventDefault();
    pick(box, hits[Number(li.dataset.index)]);
    close();
  });
  box.querySelector(".picked button.x")!.addEventListener("click", () => {
    unpick(box, true);
    input.focus();
  });
}

/** Shows [entity] picked in [box]'s own field box; the typed text is kept for when the pick is removed. */
function pick(box: HTMLElement, entity: EntityRef) {
  const param = box.dataset.param!;
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  if (!picks[param]) typed[param] = input.value;
  picks[param] = entity;
  input.value = "";
  input.hidden = true;
  const picked = box.querySelector<HTMLElement>(".picked")!;
  picked.querySelector(".pick-name")!.innerHTML = nameHtml(entity);
  const kind = picked.querySelector<HTMLElement>(".pick-kind")!;
  kind.textContent = entity.kind ? kindLabel(entity.kind) : "";
  kind.hidden = !entity.kind;
  picked.hidden = false;
  box.querySelector(".inside")?.removeAttribute("hidden");
}

/** Takes the pick out of [box]; with [restore], the text typed before it goes back into the field. */
function unpick(box: HTMLElement, restore: boolean) {
  const param = box.dataset.param!;
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  picks[param] = null;
  box.querySelector<HTMLElement>(".picked")!.hidden = true;
  input.hidden = false;
  if (restore) input.value = typed[param];
  typed[param] = "";
  const inside = box.querySelector<HTMLElement>(".inside");
  if (inside) {
    inside.hidden = true;
    field("inside").checked = false;
  }
  if (param === "entity") setKept(null);
}

/** A `name` kept beside a picked entity, shown under it with an × of its own. */
function setKept(name: string | null) {
  keptName = name;
  const kept = suggestBox("entity").querySelector<HTMLElement>(".kept")!;
  kept.hidden = !name;
  kept.querySelector(".kept-text")!.textContent = name ? t("query.name") + ": " + name : "";
}

const suggestBox = (param: string) => form.querySelector<HTMLElement>(`.suggest[data-param="${param}"]`)!;

// ---- the form ---------------------------------------------------------------------------------------------------------

function buildForm() {
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const [codes, label] = PICKS[pick.dataset.name!];
    pick.querySelector(".opts")!.innerHTML = codes.map(c =>
      `<label class="check"><input type="checkbox" value="${esc(c)}"> ${esc(label(c))}</label>`).join("");
  }
  field("valueKind").insertAdjacentHTML("beforeend", VALUE_KINDS.map(k => `<option value="${k}">${esc(k)}</option>`).join(""));
  form.querySelectorAll<HTMLElement>(".suggest").forEach(setupSuggest);
  suggestBox("entity").querySelector(".kept button.x")!.addEventListener("click", () => setKept(null));
  form.addEventListener("change", e => {
    const pick = (e.target as Element).closest<HTMLElement>("details.pick");
    if (pick) pickSummary(pick);
    moreSummary();
  });
  form.addEventListener("input", moreSummary);
}

function pickSummary(pick: HTMLElement) {
  const chosen = [...pick.querySelectorAll<HTMLInputElement>("input:checked")].map(i => i.value);
  const values = chosen.length ? chosen.map(labelOf(pick.dataset.name!)).join(", ") : t("query.any");
  // A boxed picker has its label above it.
  pick.querySelector("summary")!.textContent = pick.classList.contains("boxed") ? values : t("query." + pick.dataset.name) + ": " + values;
}

/** The filters set in [params] that "more filters" holds. */
const moreSet = (params: QueryParams) => MORE.filter(name => params[name] !== undefined);

function moreSummary() {
  const count = moreSet(readFilters()).length;
  document.querySelector("#more > summary")!.textContent = count ? t("query.more.count", { count }) : t("query.more");
}

/**
 * The filters the form sets, empty and default values left out. A suggest field gives its pick's key; typed, it gives
 * the dotted name (`name`, `containerName`), or the key when the text is one (`db:…`).
 */
function readFilters() {
  const params: QueryParams = {};
  const what = field("what").value.trim();
  if (picks.entity) params.entity = picks.entity.key;
  else if (what.startsWith("db:")) params.entity = what;
  else if (what) params.name = what;
  if (params.entity && field("inside").checked) params.inside = "true";
  if (params.entity && keptName) params.name = keptName;
  const within = field("within").value.trim();
  if (picks.container) params.container = picks.container.key;
  else if (within.startsWith("db:")) params.container = within;
  else if (within) params.containerName = within;
  for (const name of TEXTS) {
    const value = name === "value" ? field(name).value : field(name).value.trim();
    if (value) params[name] = value;
  }
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const chosen = [...pick.querySelectorAll<HTMLInputElement>("input:checked")].map(i => i.value);
    if (chosen.length) params[pick.dataset.name!] = chosen.join(",");
  }
  if (!field("containerInside").checked) params.containerInside = "false";
  if (!field("candidates").checked) params.candidates = "false";
  return params;
}

function writeForm(params: QueryParams) {
  for (const param of ["entity", "container"]) {
    const box = suggestBox(param);
    unpick(box, false);
    field(param === "entity" ? "what" : "within").value = (param === "entity" ? (params.entity ? "" : params.name) : params.containerName) ?? "";
    if (params[param]) {
      const key = params[param];
      pick(box, { key, kind: "", display: key, name: key, external: false });
      entityOf(key).then(e => { if (picks[param]?.key === key) pick(box, e); });
    }
  }
  field("inside").checked = params.inside === "true";
  setKept(params.entity && params.name ? params.name : null);
  for (const name of TEXTS) field(name).value = params[name] ?? "";
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const chosen = new Set((params[pick.dataset.name!] ?? "").split(",").filter(Boolean));
    for (const input of pick.querySelectorAll<HTMLInputElement>("input")) input.checked = chosen.has(input.value);
    pickSummary(pick);
  }
  field("containerInside").checked = params.containerInside !== "false";
  field("candidates").checked = params.candidates !== "false";
  (document.getElementById("more") as HTMLDetailsElement).open = moreSet(params).length > 0;
  moreSummary();
}

// ---- running ------------------------------------------------------------------------------------------------------------

/** The filters of [params]: everything but the answer's own mode. */
function filtersOf(params: QueryParams) {
  const { mode, groupBy, ...filters } = params;
  return filters;
}

/** The filters [params] sets: none means the whole index. */
const hasFilter = (params: QueryParams) => Object.keys(filtersOf(params)).length > 0;

/** Runs the query of [params]; the page URL becomes the query. The form stays as it is, the record of the query. */
function run(params: QueryParams) {
  current = params;
  history.replaceState(null, "", queryHref(params));
  const ran = ++generation;
  answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(t("banner.loading"))}</p></div>`;
  const done = params.mode === "groups" ? runGroups(params, ran) : runList(params, ran);
  done.catch(e => {
    if (ran === generation) answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p></div>`;
  });
}

/** Empties the answer and the URL's query; nothing runs. */
function clearAnswer(params?: QueryParams) {
  current = null;
  generation++;
  answer.innerHTML = "";
  history.replaceState(null, "", queryHref(params ?? {}));
}

/**
 * Writes [params] into the form and runs it, for a change made on the answer (a facet). A change that leaves no
 * filter does not run the whole index: only Run does that, when the user asks for it.
 */
function rerun(params: QueryParams) {
  writeForm(params);
  if (hasFilter(params)) run(params);
  else clearAnswer(params);
}

// ---- the mode, above the answer ---------------------------------------------------------------------------------------

function modeBar(params: QueryParams) {
  const grouped = params.mode === "groups";
  if (grouped) lastGroupBy = params.groupBy;
  const chosen = (params.groupBy || lastGroupBy).split(",");
  const selects = [0, 1, 2].map(i => `<select name="groupBy" aria-label="${esc(t("query.mode.groups"))}">` +
    (i === 0 ? "" : `<option value="">${esc(t("query.none"))}</option>`) +
    DIMENSIONS.map(d => `<option value="${d}" ${chosen[i] === d ? "selected" : ""}>${esc(t("query.dimension." + d))}</option>`).join("") +
    `</select>`).join(" ");
  return `<div class="mode">
    <label class="check"><input type="radio" name="view" value="" ${grouped ? "" : "checked"}> ${esc(t("query.mode.list"))}</label>
    <label class="check"><input type="radio" name="view" value="groups" ${grouped ? "checked" : ""}> ${esc(t("query.mode.groups"))}</label>
    <span class="dims" ${grouped ? "" : "hidden"}>${selects}</span></div>`;
}

answer.addEventListener("change", e => {
  if ((e.target as HTMLInputElement).name !== "view" && (e.target as HTMLInputElement).name !== "groupBy") return;
  const bar = (e.target as Element).closest(".mode")!;
  const params = filtersOf(current!);
  if (bar.querySelector<HTMLInputElement>("input[name=view]:checked")!.value === "groups") {
    params.mode = "groups";
    params.groupBy = [...new Set([...bar.querySelectorAll<HTMLSelectElement>("select[name=groupBy]")].map(s => s.value).filter(Boolean))].join(",");
  }
  run(params);
});

// ---- list mode ------------------------------------------------------------------------------------------------------------

async function runList(params: QueryParams, ran: number) {
  const filters = filtersOf(params);
  // The rows come first; the total and the facets, which count the whole set, follow in a request of their own.
  const summary = api<{ result: { total: number; facets: Record<string, Count[]> } }>("api/query/summary", filters);
  summary.catch(() => {});
  const page = (await api<{ result: OccurrencePage }>("api/query", { ...filters, summary: false, limit: PAGE })).result;
  if (ran !== generation) return;
  answer.innerHTML = `<div class="card">${modeBar(params)}<div id="facets"></div><p id="total" class="muted">${esc(t("banner.loading"))}</p>
    <div id="rows"></div><button id="more-rows" hidden>${esc(t("usage.more"))}</button></div>`;
  const block = usesBlock({ entity: true });
  document.getElementById("rows")!.append(block.element);
  let after = page.nextAfter;
  block.append(page.rows);
  const more = document.getElementById("more-rows") as HTMLButtonElement;
  more.hidden = after === null;
  more.addEventListener("click", async () => {
    more.disabled = true;
    try {
      const next = (await api<{ result: OccurrencePage }>("api/query", { ...filters, summary: false, after, limit: PAGE })).result;
      block.append(next.rows);
      after = next.nextAfter;
      more.hidden = after === null;
    } catch (e) {
      more.insertAdjacentHTML("afterend", `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`);
    } finally {
      more.disabled = false;
    }
  });
  summary.then(s => {
    if (ran !== generation) return;
    const { total, facets } = s.result;
    document.getElementById("total")!.textContent = t("query.total", { count: total });
    document.getElementById("facets")!.innerHTML = FACETS.map(([, filter]) =>
      `<div class="facet"><span class="muted">${esc(t("query." + filter))}:</span> <span id="facet-${filter}"></span></div>`).join("");
    for (const [facet, filter] of FACETS) {
      const selected = new Set((params[filter] ?? "").split(",").filter(Boolean));
      renderFacet(filter, facets[facet], selected, false);
      const target = document.getElementById("facet-" + filter)!;
      if (!target.innerHTML) target.parentElement!.remove();
    }
  }, e => {
    if (ran === generation) document.getElementById("total")!.textContent = t("error.message", { error: errorMessage(e) });
  });
}

/**
 * A facet row: its values by count, descending; past [FACET_SHOWN], a "+k" chip that shows the rest ([all]). The
 * selected values are always shown.
 */
function renderFacet(filter: string, counts: Count[], selected: Set<string>, all: boolean) {
  const sorted = [...counts].sort((a, b) => b.count - a.count || (a.value < b.value ? -1 : 1));
  const shown = all ? sorted : sorted.filter((c, i) => i < FACET_SHOWN || selected.has(c.value));
  renderChips("facet-" + filter, shown, selected, "value", labelOf(filter));
  const hidden = sorted.length - shown.length;
  if (hidden > 0) {
    const target = document.getElementById("facet-" + filter)!;
    target.insertAdjacentHTML("beforeend", `<button type="button" class="chip more" title="${esc(t("query.facet.more"))}">+${esc(formatNumber(hidden))}</button>`);
    target.querySelector("button.more")!.addEventListener("click", () => renderFacet(filter, counts, selected, true));
  }
}

// A facet chip adds its value to the filter, or takes it out.
answer.addEventListener("click", e => {
  const chip = (e.target as Element).closest<HTMLElement>(".chip");
  const facet = chip?.closest("[id^=facet-]");
  if (!chip || !facet || !chip.dataset.value) return;
  const filter = facet.id.slice("facet-".length);
  const params = { ...current };
  const chosen = new Set((params[filter] ?? "").split(",").filter(Boolean));
  if (chosen.has(chip.dataset.value)) chosen.delete(chip.dataset.value);
  else chosen.add(chip.dataset.value);
  if (chosen.size) params[filter] = [...chosen].join(",");
  else delete params[filter];
  rerun(params);
});

// ---- group mode ----------------------------------------------------------------------------------------------------------

async function runGroups(params: QueryParams, ran: number) {
  const dimensions = params.groupBy.split(",");
  const [page, container] = await Promise.all([
    api<{ result: GroupPage }>("api/query/groups", { ...filtersOf(params), groupBy: params.groupBy, limit: GROUPS }).then(a => a.result),
    containerOf(params)
  ]);
  if (ran !== generation) return;
  if (page.groups.length === 0) {
    answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(t("query.total", { count: 0 }))}</p></div>`;
    return;
  }
  const head = `<thead><tr><th class="num">${esc(t("query.count"))}</th>${dimensions.map(d => `<th>${esc(t("query.dimension." + d))}</th>`).join("")}</tr></thead>`;
  const rows = page.groups.map(g => `<tr><td class="num"><a href="${listHref(params, dimensions, g)}">${esc(formatNumber(g.count))}</a></td>
    ${g.values.map((v, i) => `<td>${groupCell(dimensions[i], v, container)}</td>`).join("")}</tr>`).join("");
  const shown = page.groups.length < page.totalGroups
    ? t("query.groups.limited", { shown: page.groups.length, count: page.totalGroups })
    : t("query.groups.total", { count: page.totalGroups });
  answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(shown)}</p>${scrollTable(head + `<tbody>${rows}</tbody>`, "groups")}</div>`;
}

/**
 * The one entity the query is inside, to shorten names against: the picked container, or the only entity a typed
 * container name (without `*`) names; null otherwise.
 */
async function containerOf(params: QueryParams): Promise<EntityRef | null> {
  if (params.container) return entityOf(params.container);
  const typedName = params.containerName;
  if (!typedName || typedName.includes("*")) return null;
  try {
    const last = typedName.split(".").pop()?.toUpperCase();
    const hits = (await api<{ result: EntityRef[] }>("api/entities", { q: typedName, limit: 20 })).result.filter(h => h.name.toUpperCase() === last);
    const roots = new Set(hits.map(h => parentKeyOf(h.key)));
    return roots.size === 1 ? hits.find(h => h.key === [...roots][0]) ?? hits[0] : null;
  } catch {
    return null;
  }
}

/** A group's value; an entity inside the query's [container] without the qualifier the container gives. */
function groupCell(dimension: string, v: GroupValue, container: EntityRef | null) {
  const none = `<span class="muted">${esc(t("query.none"))}</span>`;
  switch (dimension) {
    case "ENTITY": case "CONTAINER": return v.entity ? entityLink(v.entity, container ? { within: container } : undefined) : none;
    case "ENTITY_KIND": return v.text ? esc(kindLabel(v.text)) : none;
    case "ROLE": return esc(roleLabel(v.text));
    case "UNIT": return v.unit ? `${esc(v.unit.kind)} <span class="mono">${esc(v.unit.name)}</span><div>${fileLink(v.path)}</div>` : none;
    case "FILE": return fileLink(v.path);
    case "VALUE": {
      const literal = literalText(v.valueKind, v.text);
      const shown = literal ?? (v.valueKind === "NAME" ? v.text : null);
      return `<span class="badge">${esc(v.valueKind)}</span>` +
        (shown !== null ? `<span class="mono">${esc(shown)}</span>` : `<span class="muted">${esc(t("values.kind." + v.valueKind))}</span>`);
    }
    default: return v.text ? esc(v.text) : none;
  }
}

function fileLink(path: string) {
  return `<a class="mono muted" href="file.html?path=${encodeURIComponent(path)}${langSuffix()}">${esc(path)}</a>`;
}

/** List mode for one group: the query with the group's values as filters (a value it has none of adds nothing). */
function listHref(params: QueryParams, dimensions: string[], group: { values: GroupValue[] }) {
  const list = filtersOf(params);
  group.values.forEach((v, i) => {
    switch (dimensions[i]) {
      case "ENTITY": if (v.entity) { list.entity = v.entity.key; delete list.inside; delete list.name; } break;
      case "ENTITY_KIND": if (v.text) list.entityKinds = v.text; break;
      case "CONTAINER": if (v.entity) { list.container = v.entity.key; list.containerInside = "false"; } break;
      case "UNIT": if (v.unit) { list.path = v.path; list.unitKinds = v.unit.kind; } break;
      case "FILE": list.path = v.path; break;
      case "ROLE": list.roles = v.text; break;
      case "STATUS": list.statuses = v.text; break;
      case "REASON": if (v.text) list.reasons = v.text; break;
      case "ORIGIN": list.origins = v.text; break;
      case "STATEMENT_KIND": if (v.text) list.statementKinds = v.text; break;
      case "VALUE":
        list.valueKind = v.valueKind;
        if (VALUES_WITH_TEXT.has(v.valueKind) && v.text !== null) list.value = v.text;
        else delete list.value;
        break;
    }
  });
  return queryHref(list);
}

// ---- the page ----------------------------------------------------------------------------------------------------------------

form.addEventListener("submit", e => {
  e.preventDefault();
  // Run is the user's choice: an empty form runs the whole set.
  const params = readFilters();
  if (current?.mode) {
    params.mode = current.mode;
    params.groupBy = current.groupBy;
  }
  run(params);
});

document.getElementById("reset")!.addEventListener("click", e => {
  e.preventDefault();
  writeForm({});
  clearAnswer({});
});

(async () => {
  await initI18n();
  buildForm();
  showBanner();
  const initial = Object.fromEntries(new URLSearchParams(location.search));
  delete initial.lang;
  if (initial.mode === "groups" && !initial.groupBy) initial.groupBy = "ENTITY";
  writeForm(initial);
  // A URL with filters runs; one without (or with only the mode) never runs the whole index by itself.
  if (hasFilter(initial)) run(initial);
})();
