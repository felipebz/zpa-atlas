import type {Answer, EntitySummary, GroupPage, PackageUses, QuerySummary} from "./api";
import {api} from "./api";
import {loadCodes, usageRoles} from "./codes";
import {roleLabel} from "./entity-html";
import {esc, queryHref} from "./html";
import {t} from "./i18n";
import {shownNameText} from "./names";
import type {QueryParams} from "./query-params";
import {filtersOf} from "./query-params";

// ---- "Try": three queries of the index served, each a link whose URL is the query ----------------------------------

/** One example: the query, how it reads (object, with its members or not, roles, grouping) and its count of rows. */
interface Example {
  params: QueryParams;
  object: string;
  members?: boolean;
  roles: string;
  grouping: string | null;
  rows: number;
}

const WRITES = ["SQL_INSERT", "SQL_UPDATE", "SQL_DELETE", "SQL_MERGE"];
const ROUTINES = ["PROCEDURE", "FUNCTION"];
/** What a write's table can be: one the dictionary describes, or one the code alone names. */
const TABLES = ["TABLE", "OBJECT_TABLE", "UNKNOWN_RELATION"];

/** A name without its schema: `PKG.GET_STR`, the full display in the link's tooltip. */
const shortName = (entity: EntitySummary) => {
  const dot = entity.display.indexOf(".");
  return shownNameText(entity, dot > 0 ? { within: { display: entity.display.slice(0, dot) } } : undefined);
};

/** The entity of the largest group of [filters] grouped by entity; null when nothing matches. */
async function topEntity(filters: QueryParams) {
  const page = (await api<Answer<GroupPage>>("api/query/groups", { ...filters, groupBy: "ENTITY", limit: 1 })).result;
  return page.groups[0]?.values[0]?.entity ?? null;
}

const rowCount = async (params: QueryParams) =>
  (await api<Answer<QuerySummary>>("api/query/summary", filtersOf(params))).result.total;

/**
 * The examples, each a different kind of query: the calls to the package routine most used from outside its package
 * (else the most called routine), the uses of the package most used from outside grouped by program, the writes to
 * the most written table. One whose object the index lacks, or that finds no row, is left out.
 */
async function examples(): Promise<Example[]> {
  const codes = await loadCodes();
  const packages = api<Answer<PackageUses[]>>("api/top-packages").then(a => a.result);
  const calls = async (): Promise<Example | null> => {
    const members = (await packages).filter(p => p.topMember && ROUTINES.includes(p.topMember.kind)).sort((a, b) => b.topMemberUses - a.topMemberUses);
    const routine = members[0]?.topMember ?? await topEntity({ roles: "CALL", entityKinds: ROUTINES.join(","), external: "false" });
    if (!routine) return null;
    const params = { entity: routine.key, roles: "CALL" };
    return { params, object: shortName(routine), roles: roleLabel("CALL"), grouping: null, rows: await rowCount(params) };
  };
  const programs = async (): Promise<Example | null> => {
    const top = (await packages)[0];
    if (!top) return null;
    const params = { entity: top.entity.key, inside: "true", roles: usageRoles(codes).join(","), mode: "groups", groupBy: "CONTAINER" };
    return {
      params, object: shortName(top.entity), members: true, roles: t("query.try.everyUse"),
      grouping: t("query.try.by", { dimension: t("query.dimension.CONTAINER").toLowerCase() }), rows: await rowCount(params)
    };
  };
  const writes = async (): Promise<Example | null> => {
    // A table the code alone names is external (no declaration in the project): every table counts.
    const table = await topEntity({ roles: WRITES.join(","), entityKinds: TABLES.join(",") });
    if (!table) return null;
    const params = { entity: table.key, roles: WRITES.join(",") };
    return { params, object: shortName(table), roles: WRITES.map(roleLabel).join(", "), grouping: null, rows: await rowCount(params) };
  };
  const found = await Promise.all([calls(), programs(), writes()].map(p => p.catch(() => null)));
  return found.filter((e): e is Example => e !== null && e.rows > 0);
}

let loading: Promise<Example[]> | null = null;

/** Fills [target] with the examples, read once per page, while [wanted] (no query ran meanwhile); hidden when there is none. */
export async function showExamples(target: HTMLElement, wanted: () => boolean) {
  loading ??= examples();
  const list = await loading.catch(() => []);
  if (!wanted()) return;
  if (list.length === 0) {
    target.hidden = true;
    return;
  }
  const item = (e: Example) => {
    const object = `<span class="mono">${esc(e.object)}</span>` + (e.members ? " " + esc(t("query.try.members")) : "");
    const parts = [object, esc(e.roles), e.grouping ? esc(e.grouping) : "",
      `<span class="muted">${esc(t("query.try.rows", { count: e.rows }))}</span>`].filter(Boolean);
    return `<li><a href="${queryHref(e.params)}">${parts.join(" · ")}</a></li>`;
  };
  target.innerHTML = `<h2>${esc(t("query.try"))}</h2><ul class="try">${list.map(item).join("")}</ul>`;
  target.hidden = false;
}
