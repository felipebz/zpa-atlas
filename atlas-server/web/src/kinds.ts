/** Each kind's letters and color group: program units, types, relations and columns, members, and the rest. */
const KIND_MARKS: Record<string, [string, string]> = {
  PACKAGE: ["K", "unit"], PROCEDURE: ["P", "unit"], FUNCTION: ["F", "unit"], TRIGGER: ["G", "unit"],
  TYPE: ["T", "type"], RECORD_FIELD: ["RF", "type"],
  SCHEMA: ["SC", "relation"], TABLE: ["TB", "relation"], VIEW: ["W", "relation"], MATERIALIZED_VIEW: ["MW", "relation"],
  OBJECT_TABLE: ["OT", "relation"], COLUMN: ["c", "relation"], SYNONYM: ["SY", "relation"], SEQUENCE: ["S", "relation"],
  PARAMETER: ["PA", "member"], PACKAGE_VARIABLE: ["V", "member"], PACKAGE_CONSTANT: ["C", "member"], EXCEPTION: ["E", "member"],
  CURSOR: ["CU", "member"], LOCAL_VARIABLE: ["LV", "member"], LOCAL_CONSTANT: ["LC", "member"], LABEL: ["L", "member"],
  FORMS_MODULE: ["FM", "other"], FORMS_BLOCK: ["FB", "other"], FORMS_ITEM: ["FI", "other"], FORMS_TRIGGER: ["FG", "other"],
  FORMS_PROGRAM_UNIT: ["FU", "other"], FORMS_LIBRARY: ["FL", "other"], UNKNOWN_RELATION: ["?", "other"]
};

/** The letters and color group of [kind]; an unknown kind gets a dot in the neutral group. */
export function kindMark(kind: string): [string, string] {
  return KIND_MARKS[kind] ?? ["·", "other"];
}
