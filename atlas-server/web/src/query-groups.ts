import { api } from "./api";
import type { Answer, EntitySummary, GroupPage, GroupValue, OccurrenceGroup, SearchHit } from "./api";
import { entityLink, kindLabel, roleLabel } from "./entity-html";
import { esc, langSuffix, literalText, queryHref, scrollTable } from "./html";
import { formatNumber, t } from "./i18n";
import { parentKeyOf } from "./names";
import { modeBar } from "./query-mode";
import { filtersOf } from "./query-params";
import type { QueryParams } from "./query-params";
import { answer, state } from "./query-state";
import { entityOf } from "./query-suggest";

const GROUPS = 200;
/** The value kinds a value filter matches by their text. */
const VALUES_WITH_TEXT = new Set(["STRING", "NUMBER", "BOOLEAN", "NAME"]);

// ---- group mode ----------------------------------------------------------------------------------------------------------

export async function runGroups(params: QueryParams, ran: number) {
  const dimensions = params.groupBy.split(",");
  const [page, container] = await Promise.all([
    api<Answer<GroupPage>>("api/query/groups", { ...filtersOf(params), groupBy: params.groupBy, limit: GROUPS }).then(a => a.result),
    containerOf(params)
  ]);
  if (ran !== state.generation) return;
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
async function containerOf(params: QueryParams): Promise<EntitySummary | null> {
  if (params.container) return entityOf(params.container);
  const typedName = params.containerName;
  if (!typedName || typedName.includes("*")) return null;
  try {
    const last = typedName.split(".").pop()?.toUpperCase();
    const hits = (await api<Answer<SearchHit[]>>("api/entities", { q: typedName, limit: 20 })).result.filter(h => h.name.toUpperCase() === last);
    const roots = new Set(hits.map(h => parentKeyOf(h.key)));
    return roots.size === 1 ? hits.find(h => h.key === [...roots][0]) ?? hits[0] : null;
  } catch {
    return null;
  }
}

/** A group's value; an entity inside the query's [container] without the qualifier the container gives. */
function groupCell(dimension: string, v: GroupValue, container: EntitySummary | null) {
  const none = `<span class="muted">${esc(t("query.none"))}</span>`;
  switch (dimension) {
    case "ENTITY": case "CONTAINER": return v.entity ? entityLink(v.entity, container ? { within: container } : undefined) : none;
    case "ENTITY_KIND": return v.text ? esc(kindLabel(v.text)) : none;
    case "ROLE": return esc(roleLabel(v.text!));
    case "UNIT": return v.unit ? `${esc(v.unit.kind)} <span class="mono">${esc(v.unit.name)}</span><div>${fileLink(v.path!)}</div>` : none;
    case "FILE": return fileLink(v.path!);
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
function listHref(params: QueryParams, dimensions: string[], group: OccurrenceGroup) {
  const list = filtersOf(params);
  group.values.forEach((v, i) => {
    switch (dimensions[i]) {
      case "ENTITY": if (v.entity) { list.entity = v.entity.key; delete list.inside; delete list.name; } break;
      case "ENTITY_KIND": if (v.text) list.entityKinds = v.text; break;
      case "CONTAINER": if (v.entity) { list.container = v.entity.key; list.containerInside = "false"; } break;
      case "UNIT": if (v.unit) { list.path = v.path!; list.unitKinds = v.unit.kind; } break;
      case "FILE": list.path = v.path!; break;
      case "ROLE": list.roles = v.text!; break;
      case "STATUS": list.statuses = v.text!; break;
      case "REASON": if (v.text) list.reasons = v.text; break;
      case "ORIGIN": list.origins = v.text!; break;
      case "STATEMENT_KIND": if (v.text) list.statementKinds = v.text; break;
      case "VALUE":
        list.valueKind = v.valueKind!;
        if (VALUES_WITH_TEXT.has(v.valueKind!) && v.text !== null) list.value = v.text;
        else delete list.value;
        break;
    }
  });
  return queryHref(list);
}
