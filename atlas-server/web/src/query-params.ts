/** The filters and mode of a query, as the page URL carries them. */
export type QueryParams = Record<string, string>;

/** The filters behind "more filters"; the URL setting any of them opens it. */
export const MORE = ["entityKinds", "schema", "external", "candidates", "unitKinds", "containerInside", "path", "statuses", "reasons", "origins",
  "statementKinds", "valueKind", "value"];
/** The filters set in [params] that "more filters" holds. */
export const moreSet = (params: QueryParams) => MORE.filter(name => params[name] !== undefined);

/** The filters of [params]: everything but the answer's own mode. */
export function filtersOf(params: QueryParams) {
  const { mode, groupBy, ...filters } = params;
  return filters;
}

/** What only modifies another filter: `inside` an entity, `containerInside` a container, `candidates` the entity filters. */
const MODIFIERS = ["inside", "containerInside", "candidates"];

/** Whether [params] narrows the query: a modifier alone does not, so it leaves the whole index. */
export const hasFilter = (params: QueryParams) => Object.keys(filtersOf(params)).some(name => !MODIFIERS.includes(name));

/** The mode of [params] that a Run of other filters keeps: groups with their dimensions, or nothing for a list. */
export function modeOf(params: QueryParams | null): QueryParams {
  return params?.mode === "groups" ? { mode: params.mode, groupBy: params.groupBy } : {};
}

/**
 * The query of the page's [search] string: every parameter but `lang` and those with an empty value (an empty value
 * is no filter), and `groupBy` `ENTITY` for groups without dimensions.
 */
export function paramsOf(search: string): QueryParams {
  const params: QueryParams = {};
  for (const [name, value] of new URLSearchParams(search)) if (value !== "" && name !== "lang") params[name] = value;
  if (params.mode === "groups" && !params.groupBy) params.groupBy = "ENTITY";
  return params;
}
