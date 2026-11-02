import type {KeyColumn, ReferencingColumn, ReferencingKey, TableKey} from "./api";

/** The delete rule a foreign key shows: null for `NO ACTION` (the default, left unsaid) and for other kinds of key. */
export function shownDeleteRule(key: { kind?: string; deleteRule: string | null }): string | null {
  return (key.kind ?? "FOREIGN_KEY") === "FOREIGN_KEY" && key.deleteRule && key.deleteRule !== "NO ACTION" ? key.deleteRule : null;
}

/** Where the column of entity [columnKey] sits in [key]: its 1-based position and the key's count of columns; null when it is not one of them. */
export function columnPlace(key: TableKey, columnKey: string): { position: number; count: number } | null {
  const index = key.columns.findIndex(c => c.column?.key === columnKey);
  return index < 0 ? null : { position: index + 1, count: key.columns.length };
}

/**
 * On the page of a column of foreign key [fk], the column it references: the referenced key's column at the same
 * position. Null when the column is not one of the key's, or the dictionary did not give the referenced key.
 */
export function referencedColumn(fk: TableKey, columnKey: string): KeyColumn | null {
  const place = columnPlace(fk, columnKey);
  return place === null ? null : fk.references?.columns[place.position - 1] ?? null;
}

/**
 * On the page of the column [columnName], the column of the referencing foreign key [fk] that references it, with its
 * position in [fk]; null when none does.
 */
export function referencingColumn(fk: ReferencingKey, columnName: string): { column: ReferencingColumn; position: number; count: number } | null {
  const index = fk.columns.findIndex(c => c.references === columnName);
  return index < 0 ? null : { column: fk.columns[index], position: index + 1, count: fk.columns.length };
}
