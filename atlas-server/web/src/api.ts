// The answers of the API, as `atlas-query`'s `Model.kt` serializes them: one type per class the page reads, with the
// same name and fields. The server writes every field (`explicitNulls`, `encodeDefaults`), so a nullable Kotlin field
// is `T | null`, never optional; a `Long` or `Int` is a number.

/** The run an answer comes from; every API response carries it. */
export interface RunStamp {
  finishedAt: string;
  atlasVersion: string;
  zpaVersion: string;
  compilationSchema: string | null;
  metadataOrigin: string | null;
}

/** A query result with the run it was read from. */
export interface Answer<T> {
  run: RunStamp;
  result: T;
}

export interface Count {
  value: string;
  count: number;
}

export interface RunSummary {
  schemaVersion: number;
  sourcesRoot: string;
  startedAt: string;
  filesByParseStatus: Count[];
  occurrencesByStatus: Count[];
  coverageByKind: Count[];
}

export interface EntitySummary {
  key: string;
  kind: string;
  display: string;
  external: boolean;
  /** The entity's own name (`entity.name`), the end of [display]: `P_A`, `RUN_JOB`, `NOTIFY_REQ@FAR.EXAMPLE.COM`. */
  name: string;
}

export interface SearchHit {
  key: string;
  kind: string;
  display: string;
  external: boolean;
  usages: number;
  name: string;
}

/** One-based lines, zero-based columns, exclusive end column, as stored. */
export interface Span {
  startLine: number;
  startCol: number;
  endLine: number;
  endCol: number;
}

export interface Location {
  path: string;
  span: Span;
  roles: string[];
  status: string;
  written: string;
}

export interface RelationLink {
  kind: string;
  direction: string;
  entity: EntitySummary;
}

export interface Overload {
  entity: EntitySummary;
  body: boolean;
  self: boolean;
}

/** A `CONTAINS` child and its uses (occurrences that are not a definition or declaration). */
export interface Member {
  entity: EntitySummary;
  usages: number;
}

export interface UsageSummary {
  byRole: Count[];
  byStatus: Count[];
  asCandidate: number;
}

/** One event of a trigger: its [timing], level (null for compound and system triggers) and column for `UPDATE OF`. */
export interface TriggerEvent {
  trigger: EntitySummary;
  timing: string;
  level: string | null;
  event: string;
  column: EntitySummary | null;
}

export interface EntityDetail {
  key: string;
  kind: string;
  schema: string | null;
  name: string;
  display: string;
  external: boolean;
  parent: EntitySummary | null;
  children: Member[];
  relations: RelationLink[];
  definitions: Location[];
  overloads: Overload[];
  usage: UsageSummary;
  triggerEvents: TriggerEvent[];
  reads: EntitySummary[];
  views: EntitySummary[];
  baseColumns: EntitySummary[];
  viewColumns: EntitySummary[];
}

export interface UnitRef {
  kind: string;
  name: string;
}

export interface CallArgument {
  ordinal: number;
  label: string | null;
  parameter: EntitySummary | null;
  span: Span;
  valueKind: string;
  /** The literal's value, or the name or bind as written; null for `NULL` and `EXPRESSION`. */
  value: string | null;
}

/** The arguments bound to a parameter that pass the same value: [value] null for `NULL`, `BIND` and `EXPRESSION`. */
export interface ValueGroup {
  valueKind: string;
  value: string | null;
  count: number;
}

export interface OccurrenceRow {
  id: number;
  path: string;
  span: Span;
  written: string;
  roles: string[];
  status: string;
  reason: string | null;
  origin: string;
  /** The entity in scope this row hit (the occurrence entity, or the candidate); null for a query row without entity. */
  matched: EntitySummary | null;
  viaCandidate: boolean;
  viaView: boolean;
  entity: EntitySummary | null;
  container: EntitySummary | null;
  unit: UnitRef;
  statementKind: string | null;
  candidates: EntitySummary[];
  arguments: CallArgument[];
  /** For a column write: the value written, one per row of its statement. */
  values: WrittenValue[];
}

export interface WrittenValue {
  row: number;
  valueKind: string;
  value: string | null;
}

/** Counts over a set of occurrences, roles split; the occurrence query fills every list, "who uses" the first two. */
export interface Facets {
  byRole: Count[];
  byStatus: Count[];
  byOrigin: Count[];
  byEntityKind: Count[];
  byUnitKind: Count[];
  byReason: Count[];
}

export interface OccurrencePage {
  rows: OccurrenceRow[];
  nextAfter: number | null;
  total: number;
  facets: Facets;
}

export interface QuerySummary {
  total: number;
  facets: Facets;
}

/** The value of one dimension of a group; the fields the dimension does not use are null. */
export interface GroupValue {
  text: string | null;
  entity: EntitySummary | null;
  unit: UnitRef | null;
  path: string | null;
  valueKind: string | null;
}

export interface OccurrenceGroup {
  values: GroupValue[];
  count: number;
}

export interface GroupPage {
  dimensions: string[];
  groups: OccurrenceGroup[];
  totalGroups: number;
}

export interface SourceLine {
  number: number;
  text: string;
}

export interface SourceSnippet {
  path: string;
  lines: SourceLine[];
  stale: boolean;
}

/** One occurrence of a [FileView]: the span of its name, and [entity] and [candidates] as indexes into [FileView.entities]. */
export interface FileOccurrence {
  at: number[];
  roles: string[];
  status: string;
  reason: string | null;
  entity: number | null;
  candidates: number[];
}

export interface FileView {
  path: string;
  stale: boolean;
  lines: string[];
  entities: EntitySummary[];
  occurrences: FileOccurrence[];
}

// ---- requests ------------------------------------------------------------------------------------------------------

/** The JSON the API answers is whatever the caller says it is: [T] is the caller's description of the answer. */
export async function api<T>(path: string, params?: Record<string, string | number | boolean | null | undefined>): Promise<T> {
  const url = new URL(path, window.location.href);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== null && v !== undefined && v !== "") url.searchParams.set(k, String(v));
  }
  const response = await fetch(url);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || response.statusText);
  return body;
}

/** The text of an error caught from a request or a parse. */
export function errorMessage(e: unknown) {
  return e instanceof Error ? e.message : String(e);
}
