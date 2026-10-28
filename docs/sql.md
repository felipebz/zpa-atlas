# Querying the index with SQL

The index is a plain SQLite file with the tables `run`, `source_file`, `source_line`, `unit`, `entity`, `relation`,
`sql_statement`, `occurrence`, `occurrence_candidate`, `call_argument`, `written_value`, `coverage_note` and
`trigger_event`. The words they use are in [concepts.md](concepts.md). Who writes a column:

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
