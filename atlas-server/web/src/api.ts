// The answers of the API, as `atlas-query`'s `Model.kt` serializes them: one type per class the page reads, with the
// same name and fields. The server writes every field (`explicitNulls`, `encodeDefaults`), so a nullable Kotlin field
// is `T | null`, never optional; a `Long` or `Int` is a number.

import {signInFor, signInWorked} from "./session";

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
  /** The files whose parse failed or was recovered, by path, cut at a cap; [parseProblemTotal] counts them all. */
  parseProblems: FileParse[];
  parseProblemTotal: number;
}

export interface FileParse {
  path: string;
  parseStatus: string;
}

/** A package of the project with its uses (its own and its members'), and its member of most uses. */
export interface PackageUses {
  entity: EntitySummary;
  uses: number;
  topMember: EntitySummary | null;
  topMemberUses: number;
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
  /** The kind of the unit it sits in: `PACKAGE` (a spec), `PACKAGE_BODY`, `TYPE`, `TYPE_BODY`…; null outside any unit. */
  unitKind: string | null;
}

/** A routine's other half: its body (`IN`) or, for a body, its spec (`OUT`), with its definitions. */
export interface Partner {
  entity: EntitySummary;
  direction: string;
  definitions: Location[];
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
  /** The entities that hold it, outermost first, its parent last. */
  ancestors: EntitySummary[];
  children: Member[];
  relations: RelationLink[];
  definitions: Location[];
  overloads: Overload[];
  partners: Partner[];
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

/** A stored code, and whether only a later phase of the indexer produces it. */
export interface Code {
  code: string;
  reserved: boolean;
}

/** Every code the index stores, by enum, in the writer's order (`/api/codes`, which has no run stamp). */
export interface Codes {
  roles: Code[];
  statuses: Code[];
  entityKinds: Code[];
  unitKinds: Code[];
  origins: Code[];
  statementKinds: Code[];
  valueKinds: Code[];
  groupDimensions: Code[];
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

/** One line a text search found: [id] is what `after` takes; [stale] is null when the server cannot read the file. */
export interface TextHit {
  id: number;
  path: string;
  line: number;
  text: string;
  entity: EntitySummary | null;
  stale: boolean | null;
}

export interface TextPage {
  rows: TextHit[];
  nextAfter: number | null;
  total: number;
  wholeIndex: boolean;
}

// ---- requests ------------------------------------------------------------------------------------------------------

/** The JSON the API answers is whatever the caller says it is: [T] is the caller's description of the answer. */
export async function api<T>(path: string, params?: Record<string, string | number | boolean | null | undefined>): Promise<T> {
  const url = new URL(path, window.location.href);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== null && v !== undefined && v !== "") url.searchParams.set(k, String(v));
  }
  const response = await fetch(url);
  if (response.status === 401 && await signInFor(response.status)) return new Promise<T>(() => {});
  const body = await response.json();
  if (!response.ok) throw new ApiFailure(body.error || response.statusText, response.status, body);
  signInWorked();
  return body;
}

/** A request the API refused: its message, HTTP status and error body. */
export class ApiFailure extends Error {
  constructor(message: string, readonly status: number, readonly body: { error?: string; timeout?: boolean; tooLarge?: boolean; nodes?: number; limit?: number }) {
    super(message);
  }
}

/** The text of an error caught from a request or a parse. */
export function errorMessage(e: unknown) {
  return e instanceof Error ? e.message : String(e);
}
