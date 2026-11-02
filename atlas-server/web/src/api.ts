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

/** `/api/run`: the run and its counts, and the width the page shows a tab with by default (2, 4 or 8). */
export interface RunAnswer extends Answer<RunSummary> {
  tabWidth: number;
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

/** A `CONTAINS` child, its uses (occurrences that are not a definition or declaration), what its declaration says and its documentation. */
export interface Member {
  entity: EntitySummary;
  usages: number;
  declared: Declared | null;
  documentation: Documentation[];
  /** zpa's reasons why its uses may be incomplete; null when nothing says so. */
  usesIncomplete: string[] | null;
}

/**
 * A declaration's documentation comment: its text (markers, separator and annotation lines left out, line breaks
 * kept), where it is, and `from` `SPEC` or `BODY` when it is the other half's (null when it is the declaration's own).
 */
export interface Documentation {
  text: string;
  textCut: boolean;
  /** `ABOVE`, `AFTER_IS`, `AFTER_BEGIN` or `END_OF_LINE`. */
  shape: string;
  path: string;
  line: number;
  from: string | null;
}

/** A declaration's type and value as the index keeps them: written, not resolved or evaluated. */
export interface DeclaredText {
  type: string | null;
  value: string | null;
  /** The value was too long and ends with `…`: the whole text is in the file. */
  valueCut: boolean;
}

/**
 * What a declaration says: a function's or cursor's return type, else its type; a parameter's default, or a variable's,
 * constant's or field's initial value.
 */
export interface Declared extends DeclaredText {
  /** `SPEC` when the value is the default the spec writes and this body's parameter leaves out. */
  valueFrom: string | null;
  /** On a body that says otherwise than its spec: what the spec says. */
  specText: DeclaredText | null;
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

/** A column of a key, in key order; [column] is null when the index has no entity for it. */
export interface KeyColumn {
  name: string;
  column: EntitySummary | null;
}

/**
 * A primary, unique or foreign key the dictionary gave, on the relation [table] (`OWNER.TABLE`, always set; [relation]
 * is its entity, null when the index has none). A foreign key's [deleteRule] (`NO ACTION`, `CASCADE`, `SET NULL`) and
 * [referencedName] (`OWNER.NAME` of the key it references) are set; [references] is that key (whose own [references]
 * is null), null when the dictionary did not give it. A column at position i of a foreign key references the column
 * at position i of [references]. Other kinds of key have the three null.
 */
export interface TableKey {
  owner: string;
  name: string;
  kind: "PRIMARY_KEY" | "UNIQUE" | "FOREIGN_KEY";
  table: string;
  relation: EntitySummary | null;
  columns: KeyColumn[];
  /** The constraint's status is `ENABLED`. */
  enabled: boolean;
  deleteRule: string | null;
  referencedName: string | null;
  references: TableKey | null;
}

/** A column of a referencing foreign key: its entity, null when the index has none, and the column it [references] at the same position. */
export interface ReferencingColumn {
  name: string;
  column: EntitySummary | null;
  references: string | null;
}

/** A foreign key that references a key of the page's relation or column; [references] names that key (`OWNER.NAME`), one of the page's keys. */
export interface ReferencingKey {
  owner: string;
  name: string;
  table: string;
  relation: EntitySummary | null;
  columns: ReferencingColumn[];
  enabled: boolean;
  deleteRule: string | null;
  references: string;
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
  /** A relation's own keys (primary, then unique, then foreign, by name); a column's, the keys it is a column of. */
  keys: TableKey[];
  /** The foreign keys whose referenced key is one of a relation's keys (its own included), or, for a column, contains it. */
  referencedBy: ReferencingKey[];
  /** Why [referencedBy] may not be all of them (`TABLES_NOT_VISIBLE`); null when nothing says so. */
  referencedByIncomplete: string[] | null;
  declared: Declared | null;
  /** Its own documentation first, then the other half's when it says something else; the other half's alone when it has none. */
  documentation: Documentation[];
  /**
   * zpa's reasons (CONDITIONAL_COMPILATION, …) why uses the index did not record cannot be ruled out; null when nothing
   * says so. Body globals and parameters have it; a parameter also gets its spec's or body's, whose uses it shows.
   */
  usesIncomplete: string[] | null;
  /** A standalone object type's: why its listed attributes may not be all (NOT_EXTRACTED, ALTER_TYPE); null when complete. */
  membersIncomplete: string[] | null;
  /**
   * A column's places (where PL/SQL reads its value into a target) counted by class, in the order FIXED_SIZE, UNKNOWN,
   * ANCHORED_ELSEWHERE, NO_SIZE, ANCHORED_TO_COLUMN when present; empty for any other entity.
   */
  placesByClass: Count[];
  /** For a variable, parameter, field or other PL/SQL target: the columns whose value is read into it, by key. */
  receives: EntitySummary[];
}

/** The unit an occurrence is in; [entity] is its package, routine, view, trigger or type, null for a script. */
export interface UnitRef {
  kind: string;
  name: string;
  entity?: EntitySummary | null;
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

/** How a place's target is declared, in the order the backend sorts places in. */
export type PlaceClass = "FIXED_SIZE" | "UNKNOWN" | "CALLER_DECIDES" | "ANCHORED_ELSEWHERE" | "NO_SIZE" | "ANCHORED_TO_COLUMN";

/** A place a column's value is read into in PL/SQL (`SELECT … INTO`, `FETCH … INTO`, `RETURNING … INTO`) and how its target is declared. */
export interface ColumnPlace {
  /** The column read: the page's column, or a view column computed from it ([viaView]). */
  read: EntitySummary;
  viaView: boolean;
  source: "SELECT_INTO" | "FETCH" | "RETURNING";
  /** `BULK COLLECT INTO`. */
  bulk: boolean;
  /** Where the target is written: the file relative to the sources, one-based line, zero-based column. */
  path: string;
  line: number;
  column: number;
  /** The routine around it. */
  container: EntitySummary | null;
  /** The target as written: `l_code`, `l_pair.code`, `r_ord`. */
  targetWritten: string;
  /** The target's entity; null for a variable of an anonymous block. */
  target: EntitySummary | null;
  /** The field of a record target that takes the column (`CODE` of `r_ord`). */
  field: string | null;
  placeClass: PlaceClass;
  /** The target's (or field's) declaration as written: `varchar2(12)`, `orders.code%type`, `t_code`. */
  declaredType: string | null;
  /** For FIXED_SIZE, when known; for a number, the digits it holds before the point. */
  length: number | null;
  /** `CHAR`, `BYTE`, `DIGITS`, or `NLS_LENGTH_SEMANTICS` when no unit is written (the compile-time setting decides). */
  unit: string | null;
  /** A number's scale: with [length], what to compare with the column's `NUMBER(p,s)`. */
  scale: number | null;
  /** The entity whose declaration decides the type: the column an anchor ends at, or the variable, field or type that writes the size. */
  typeSource: EntitySummary | null;
  /** `OUT` or `IN OUT` for a parameter target (CALLER_DECIDES): each caller's argument decides its type and size. */
  parameterMode: string | null;
  /** For UNKNOWN, why: TARGET_UNDECIDED, DECLARATION_UNKNOWN, ANCHOR_UNRESOLVED, … */
  reason: string | null;
}

/** A page of a column's places (`api/places`): [byClass] counts all of them, [nextOffset] is null on the last page. */
export interface ColumnPlacePage {
  total: number;
  byClass: Count[];
  offset: number;
  rows: ColumnPlace[];
  nextOffset: number | null;
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
  /** "Who uses": the rows by side (`OUTSIDE`, `INSIDE`), under the role and status filters. */
  bySide?: Count[] | null;
}

export interface OccurrencePage {
  rows: OccurrenceRow[];
  nextAfter: number | null;
  total: number;
  facets: Facets;
  /** The entities in scope whose uses may be incomplete, with zpa's reasons; null when none. */
  usesIncomplete: { key: string; reasons: string[] }[] | null;
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
  reasons: Code[];
  parseStatuses: Code[];
}

export interface SourceLine {
  number: number;
  text: string;
}

export interface SourceSnippet {
  path: string;
  lines: SourceLine[];
  stale: boolean;
  /** With `colors=true`: one list per line, each `[startCol, endCol, color]`; null when unreadable. */
  colors?: number[][][] | null;
}

/** The line an occurrence starts on, as the server reads its file now (`api/occurrence-lines`). */
export interface OccurrenceLine {
  id: number;
  /** Null when the file cannot be read or no longer has the line. */
  text: string | null;
  /** The file changed since indexing: the occurrence's span may no longer be at its place. Null when unreadable. */
  stale: boolean | null;
  colors: number[][] | null;
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
  /** How the indexer's parse of the file went: `OK`, `RECOVERED` or `FAILED`. */
  parseStatus: string;
  objects: FileObject[];
  /** With `colors=true`: `[startLine, startCol, endLine, endCol, color]` (see [CODE_COLORS]); null when not read. */
  colors: number[][] | null;
}

/** An object the file declares at its top level, with the members it declares directly. */
export interface FileObject {
  unitKind: string;
  entity: EntitySummary;
  line: number;
  members: { entity: EntitySummary; line: number }[];
}

/** The colors of [FileView.colors], by their number: the analyzer's highlighter's kinds of text. */
export const CODE_COLORS = ["keyword", "literal", "comment", "doc"] as const;

/** One line a text search found: [id] is what `after` takes; [stale] is null when the server cannot read the file. */
export interface TextHit {
  id: number;
  path: string;
  line: number;
  text: string;
  entity: EntitySummary | null;
  stale: boolean | null;
  /** With `colors=true`: `[startCol, endCol, color]` on the line (see [CODE_COLORS]); null when not read. */
  colors?: number[][] | null;
}

export interface TextPage {
  rows: TextHit[];
  nextAfter: number | null;
  total: number;
  wholeIndex: boolean;
  /** The files the lines are in. */
  files: number;
  /** Each file of [rows] with its count of lines found, later pages included. */
  fileLines: Record<string, number>;
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

/** One page of the places the column [key]'s value is read into, from [offset]. */
export async function columnPlaces(key: string, offset: number, limit = 100): Promise<ColumnPlacePage> {
  return (await api<Answer<ColumnPlacePage>>("api/places", { key, offset, limit })).result;
}
