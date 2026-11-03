# Querying the index with SQL

The index is a plain SQLite file: any SQLite client opens it. The tables are named after the
[concepts](concepts.md): `entity`, `occurrence`, `column_place`, `coverage_note`, `source_file`, and a few more. To
see them all and their columns:

```sh
sqlite3 atlas.db .tables
sqlite3 atlas.db ".schema occurrence"
```

The schema changes between versions of Atlas, so keep these queries close to the version that wrote the index; the
version is in the `run` table.

Who writes a column:

```sql
SELECT f.path, o.start_line, o.written, c.key AS container
FROM occurrence o
JOIN entity e ON e.id = o.entity_id
JOIN source_file f ON f.id = o.file_id
LEFT JOIN entity c ON c.id = o.container_entity_id
WHERE e.key = 'db:HR.ORDERS.STATUS' AND (',' || o.roles || ',') LIKE '%,WRITE,%'
ORDER BY f.path, o.start_line;
```

The variables of fixed size that a column's value is read into:

```sql
SELECT f.path, p.start_line, t.key AS target, p.declared_type
FROM column_place p
JOIN entity c ON c.id = p.column_id
JOIN source_file f ON f.id = p.file_id
LEFT JOIN entity t ON t.id = p.target_entity_id
WHERE c.key = 'db:HR.ORDERS.STATUS' AND p.class = 'FIXED_SIZE'
ORDER BY f.path, p.start_line;
```

The names that were not found, with how often each is used:

```sql
SELECT e.key, o.reason, count(*) AS uses
FROM occurrence o
JOIN entity e ON e.id = o.entity_id
WHERE o.status = 'UNRESOLVED'
GROUP BY e.key, o.reason
ORDER BY uses DESC;
```

The files with the most coverage notes, by kind:

```sql
SELECT f.path, n.kind, count(*) AS notes
FROM coverage_note n
JOIN source_file f ON f.id = n.file_id
GROUP BY f.path, n.kind
ORDER BY notes DESC
LIMIT 20;
```
