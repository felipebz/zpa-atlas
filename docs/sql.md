# Querying the index with SQL

The index is a plain SQLite file with the tables `run`, `source_file`, `source_line`, `unit`, `entity`, `relation`,
`sql_statement`, `occurrence`, `occurrence_candidate`, `call_argument`, `written_value`, `coverage_note`,
`trigger_event`, `declared_text` (a declaration's type and value as written: `datatype`, `value`, `value_cut`),
`doc_comment` (a declaration's documentation comment, one row per declaration: `entity_id`, `file_id`, `in_body`,
`shape`, `start_line`, `text`, `text_cut`; see [serving.md](serving.md#a-declarations-documentation)), `uses_gap`
(a body global whose recorded uses may be incomplete: `entity_id`, `reasons`), `members_gap` (an object type
whose listed attributes may not be all: `entity_id`, `reasons`), `dictionary_key` (a primary, unique or foreign key
the dictionary gives: `owner`, `name`, `kind`, `table_owner`, `table_name`, `relation_id`, `enabled`, `delete_rule`,
`referenced_owner`, `referenced_name`, `referenced_id`), `dictionary_key_column` (its columns in order: `key_id`,
`position`, `column_name`, `column_id`) and `column_place` (a place a column's value is read into in PL/SQL: `column_id`,
`source`, `bulk`, `target_entity_id`, `target_written`, `field`, `class`, `declared_type`, `length`, `length_unit`,
`scale`, `type_source_id`, `parameter_mode`, `functions`, `argument_parameter_id`, `argument_routine_id`, `call_count`, `call_path`, `call_line`, `reason`, and where: `file_id`, `unit_id`, `statement_id`, the span; see
[concepts.md](concepts.md#where-a-columns-value-is-read-into)). The words they use are in [concepts.md](concepts.md).
Who writes a column:

```sql
SELECT f.path, o.start_line, o.written, c.key AS container
FROM occurrence o
JOIN entity e ON e.id = o.entity_id
JOIN source_file f ON f.id = o.file_id
LEFT JOIN entity c ON c.id = o.container_entity_id
WHERE e.key = 'db:HR.COMPANY.DT_CHANGED' AND (',' || o.roles || ',') LIKE '%,WRITE,%'
ORDER BY o.id;
```

The schema can change between versions (the stored version is in `run`), so queries are not a stable API.
