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

/** The filters [params] sets: none means the whole index. */
export const hasFilter = (params: QueryParams) => Object.keys(filtersOf(params)).length > 0;
