# Concepts

The words the web page, `stats`, the MCP tools and the index tables use.

## Entity

A named thing with a stable key, such as `db:HR.COMPANY.DT_CHANGED`. `~` is the placeholder schema when
no schema is known.

A package's variables and constants are entities, those of its spec and those its body declares for itself, outside
any subprogram. A body's own are private to the body and end with `@BODY` (`db:HR.PKG.G_TOTAL@BODY`); their uses
inside the body are the ones zpa's name resolution binds to them. Where zpa cannot decide some names of the body (a
`$IF`, a name the spec declares too), the page and the MCP answers say that the uses may be incomplete
(`usesIncomplete`): the count is then a lower bound.

A routine's own variables and constants (`LOCAL_VARIABLE`, `LOCAL_CONSTANT`), and the types it declares with their
fields, are entities too: of a package body's subprogram, a standalone subprogram, a trigger, or a type body's method
(those hang on the type, keyed with the method's name and signature, `db:HR.TY_BOX.AREA(P_SCALE IN NUMBER) RETURN NUMBER.L_SIDE`).
Their key is the routine's, then the name, as a parameter's (`db:HR.PKG.RUN(P_A IN NUMBER)@BODY.L_TOTAL`); one in a
nested subprogram has the subprogram's name and signature between (`….HELPER(P_N IN NUMBER).L_H`), so overloads keep
their own locals whatever their order, and a second of the same name in one routine gets `#2`. Their uses are found as the body
globals', with the same note when zpa could not decide some names of the routine. A plain name search leaves them out
(`bind_json.keylist` finds one), and they add nothing to a package's "Used by" or "Impact". An anonymous block's or a
script's declarations, loop indexes, and local cursors and exceptions are not entities.

The parameters of package subprograms, standalone subprograms and package cursors are entities too, with their uses
found the same way. A use in a package body is the body parameter's (`…@BODY.P_ID`); the spec parameter's "who uses"
follows it there. Parameters of nested subprograms and of object type methods are not entities.

The fields of a package spec's record types are entities with uses: each name in a chain that zpa says is the field
(`g_rec.code`, `g_rows(1).items`), with the roles of the value the chain starts from; after a function call, only read.

The types a package body declares for itself, outside any subprogram, are entities too, like its variables
(`db:HR.PKG.T_ROW@BODY`), with a use where a declaration names them; a body record type's fields are its children
(`db:HR.PKG.T_ROW@BODY.CODE`), with uses found as for the spec's. A body type with the name of a spec type is a
second entity: where zpa cannot tell which one a name means, neither gets the use.

The attributes a standalone object type's `CREATE TYPE` writes are `ATTRIBUTE` entities, children of the type
(`db:HR.TY_POINT.X`), found and used the same way; an attribute a subtype inherits is its supertype's. An attribute an
`ALTER TYPE` adds has no entity: the type's page says its attributes may not be all.

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
