# Concepts

The words the web page, `stats`, the MCP tools and the index tables use.

## Entity

A named thing with a stable key, such as `db:HR.COMPANY.DT_CHANGED`. `~` is the placeholder schema when
no schema is known.

## Occurrence

One place in the source where an entity is defined or used. It has roles and a status.

### Roles

`DEFINITION`, `DECLARATION`, `READ`, `WRITE`, `CALL`, `SQL_SELECT`, `SQL_INSERT`, `SQL_UPDATE`,
`SQL_DELETE`, `SQL_MERGE`, `SQL_LOCK`, `TYPE_USE`, `RAISE`, `HANDLE`, `IMPLICIT`, and for the object a DDL
statement acts on (`TRUNCATE TABLE`, `ANALYZE TABLE`, `DROP`, `ALTER` and `CREATE` of tables, views, materialized
views, sequences, synonyms, packages, types and standalone subprograms) `DDL_CREATE`, `DDL_ALTER`, `DDL_DROP`,
`DDL_TRUNCATE`, `DDL_ANALYZE`. DDL names the object itself, never through a synonym: a name of another kind is
`UNRESOLVED` with reason `WRONG_KIND`.

### Statuses

| Status           | Meaning                                                                      |
|------------------|------------------------------------------------------------------------------|
| `RESOLVED`       | Found, in the sources or the dictionary.                                     |
| `UNVERIFIED`     | Not checked, e.g. no dictionary or unknown schema.                           |
| `AMBIGUOUS`      | Several candidates, e.g. overloads.                                          |
| `UNRESOLVED`     | Named but not found. The reason says why, e.g. `NOT_IN_DICTIONARY`. With `LOCAL_SUBPROGRAM` the name is not missing: it calls a subprogram nested in the caller's own unit, or a method of the type whose body holds the call, which the index does not list as an object. |
| `EXTERNAL`       | Outside what the index can check, e.g. through a database link (`DB_LINK`). |
| `DYNAMIC`        | A dynamic statement. The reason says what became of its SQL text, below.     |
| `PARTIAL_SOURCE` | Inside a region the parser had to recover.                                   |

Every reason the indexer writes is in one declared list, served with the other codes by `/api/codes`; the pages show
statuses and reasons in words, with the code as the tooltip.

### Origin

`STATIC` for code, `DYNAMIC_LITERAL` for a name inside the SQL text of a dynamic statement
(`EXECUTE IMMEDIATE`, `OPEN … FOR`, `DBMS_SQL.PARSE`). That text is indexed like code, at its place inside the
literal; a part built at run time (a variable, a call) is a hole and never becomes a name. Oracle records no
dependency for dynamic SQL, so these occurrences give no dependency in `compare-deps`.

### Dynamic SQL

The reason of a `DYNAMIC` occurrence:

| Reason                 | Meaning                                                                     |
|------------------------|-----------------------------------------------------------------------------|
| `PARSED`               | The text is fully known and parsed.                                         |
| `PARSED_WITH_HOLES`    | Parsed, with parts built at run time.                                       |
| `PARSED_HOLES_DROPPED` | Parsed only once the parts built at run time were left out: may be partial. |
| `NOT_PARSED`           | The text has a string literal but was not parsed: not one statement, or a dynamic statement inside another dynamic text. |
| `EXPRESSION`           | No string literal: nothing to read (a call, a variable of a package, …).   |

A text written as a plain variable (`EXECUTE IMMEDIATE v_sql`) is read only when it can be only one text: a local
variable of character type, written once in the code (its default value, a constant, or one `:=`), and nowhere
else. Its reason starts with `VARIABLE_`:

| Reason                          | Meaning                                                                       |
|---------------------------------|-------------------------------------------------------------------------------|
| `VARIABLE_PARSED`, `VARIABLE_PARSED_WITH_HOLES`, `VARIABLE_PARSED_HOLES_DROPPED` | The variable's one value, parsed as above: its names are indexed at their place in that value. |
| `VARIABLE_NOT_PARSED`           | The one value has a string literal but was not parsed.                        |
| `VARIABLE_NO_LITERAL`           | The one value has no string literal (a call, another variable).               |
| `VARIABLE_SEVERAL_WRITES`       | Written more than once, or built on its own value (`v := v \|\| '…'`).          |
| `VARIABLE_VALUE_NOT_IN_CODE`    | Written once, by an `INTO` or as the actual of an `OUT` or `IN OUT` formal.   |
| `VARIABLE_NEVER_WRITTEN`        | No value is ever written to it.                                               |
| `VARIABLE_UNSEEN_WRITE`         | It may be written where the index cannot tell: an actual of a call it does not resolve, or a block it cannot fully see (conditional compilation, recovered parse, substitution). |
| `VARIABLE_NOT_CHARACTER`        | Not of a character type (`VARCHAR2`, `CHAR`, `CLOB` and their kin).          |
| `VARIABLE_NOT_LOCAL`            | A parameter or a package variable, not a variable of a block or subprogram.   |
| `VARIABLE_UNRESOLVED`           | The name binds to nothing the analysis can prove.                             |

## Unit

one compiled object or script, such as a package body, trigger, view or anonymous block.

## Coverage notes

Each file can carry coverage notes: what the index could not see or decide in it, by kind. `stats` counts them.
