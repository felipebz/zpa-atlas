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

### Dictionary columns and keys

With the Oracle dictionary, a column's declaration says its datatype as a `CREATE TABLE` writes it, then `NOT NULL`
when the dictionary says so (`ALL_TAB_COLS.NULLABLE`): `VARCHAR2(30 CHAR)` or `CHAR(1 BYTE)` (always with the unit:
the default depends on the session), `NVARCHAR2(30)` (always characters), `RAW(16)`, `NUMBER`, `NUMBER(10)`,
`NUMBER(10,2)`, `NUMBER(*,0)`, `FLOAT(126)`, `DATE`, `TIMESTAMP(6)`, an object type as `OWNER.NAME`. A view's columns
get theirs the same way, from what the dictionary derives from the query: a column taken as is keeps its table
column's type and `NOT NULL`; an expression gets the type Oracle computes for it (`sum(amount)` and `count(*)` a bare
`NUMBER`, `'C-' || country` a `CHAR(4 BYTE)`), without `NOT NULL`.

The primary, unique and foreign keys (`ALL_CONSTRAINTS`, `ALL_CONS_COLUMNS`) are read for the relations the dictionary
reads, with the keys their foreign keys reference and the foreign keys of any table the session sees that reference
theirs. They are rows of their own, not entities: a key's name, kind, columns in order, whether it is enabled, and for
a foreign key its delete rule and the key it references. A table's page lists its keys and the foreign keys that
reference them; a column's page, the keys it is in, the column it references and the columns that reference it.

`ALL_CONSTRAINTS` shows a table's constraints only to its owner, to a user granted a privilege on it, and to a user with
`SELECT ANY TABLE` (or `READ ANY TABLE`, `INSERT ANY TABLE`, `UPDATE ANY TABLE`, `DELETE ANY TABLE`, `LOCK ANY TABLE`,
directly or through a role). The loader checks `SESSION_PRIVS` for one of them. Without one, the foreign keys of the
tables the user cannot see are not read, and the run records a `REFERENCED_BY_INCOMPLETE` coverage note
(`TABLES_NOT_VISIBLE`): the page of a table or column with a primary or unique key, and `get_entity`
(`referencedByIncomplete`), say that "Referenced by" may be incomplete, even when it is empty. Index as a user with
`SELECT ANY TABLE` for complete lists. The MCP tool `find_referencing_keys` pages through all of them; `get_entity`
cuts the list like its other lists.

A relation the code names is an entity; so is the relation at the other end of one of its keys when the dictionary
read it, so that both ends of the key can be followed. A key end the index has no entity for (a schema the dictionary
was not asked for, or a relation more than one key away from the code) is kept by name: the key, its table and its
columns, without links. Check constraints, indexes and column defaults are not read.

### Where a column's value is read into

A *place* is where a column's value is read straight into a PL/SQL target: `SELECT … INTO`, `FETCH … INTO` of an
explicit cursor whose query the index knows (declared in the same unit, or in a package spec or body), and
`RETURNING … INTO`, with or without `BULK COLLECT`, and an assignment `target := r.f` (`ASSIGNMENT`, at the line of the
`:=`) of a field of a row record: the record of a cursor `FOR` loop (over a query, or a cursor the index knows) or a
variable of a `%ROWTYPE` of a table, view or cursor. The field stands for the column a `FETCH` from that cursor or
query would read at its name, or the relation's column of that name. The select item at position *i* goes to the target at position *i*;
a single target that takes several items, or a whole row (`select * into r`), is a record that takes them field by
field; a `BULK COLLECT` target is a collection whose element takes the value. An item is the column when it is the
column itself, written bare or qualified (`code`, `o.code`, `shop.orders.code`), or a `*` over a relation whose columns
the dictionary lists. An item, cursor field or assigned value that reads one column through value functions only is a
place of that column too, with its `functions` outer first (`NVL`; `UPPER,TRIM`): `NVL(a, b)` and `COALESCE(a, b, …)`
(any argument), `NVL2(e1, e2, e3)` (`e2` or `e3`, not `e1`), `MAX(x)` and `MIN(x)` (with `DISTINCT`, `ALL`, `KEEP`,
`OVER`), `TRIM`, `LTRIM`, `RTRIM`, `UPPER`, `LOWER` (`x`), nested (`nvl(max(qty), 0)`). Through them the value that lands
may be other than the column's (`NVL`'s other argument); the class still tells only how the target is declared. A
concatenation (`||` or `CONCAT`) one of whose parts is the column's value, as is or through those functions, is a place
of that column too, with `concatenated` set: other text lands with the value, so the target needs more room than the
column. A concatenation of several columns is a place of each (`code || note`). A concatenation inside another
expression (`substr(code || 'x', 1, 5)`) is not followed and is counted by that expression's shape. A
record field typed by such a cursor field (`c%rowtype` with `nvl(code, '-') code`) stays `UNKNOWN`
(`CURSOR_FIELD_EXPRESSION`). The target is the one zpa binds the name to, as for its uses; a field reached through a
collection element (`a(i).f`, `a(i).r.f`, `a(i).f(j)`) is the field zpa binds its last name to; when that field has
no entity, as in an element that is a `%ROWTYPE` of a table, view or cursor, the collection's element type is followed to
the field, as for a `%ROWTYPE` variable. A collection the index does not know leaves the field undecided
(`FIELD_UNDECIDED`). A variable of an
anonymous block has no entity, and its own declaration says its type. A bind or host variable (`:new.code`, `:b1`) is
not a name the index follows (`TARGET_NOT_A_NAME`), nor an element of an element (`a(i)(j)`,
`a(i)(j).f`, `TARGET_SHAPE_NOT_FOLLOWED`). Places are rows of their own (`column_place`): each holds the
column, the target and the field it lands in, and how the target is declared, which no relation or role could carry.

The target's declared type is followed to where it ends: a `%TYPE` anchor to a variable, parameter or field, a subtype
and a type name (a record, a collection, a standalone `CREATE TYPE`) are followed to their declaration, in this file or
another. zpa's name binding does not answer the name of an anchor, so its first segment is followed with the
declaration zpa's parser attaches to it, as the index already does to tell a local anchor. The class of a place:

| Class | When |
|---|---|
| `FIXED_SIZE` | The type ends at a written length (`VARCHAR2(12)`, `CHAR(1)`, with its unit), a number's precision, or a bounded type (`PLS_INTEGER`). A character length written without `CHAR` or `BYTE` has the unit `NLS_LENGTH_SEMANTICS`: the compile-time setting decides it, and the index does not know it. |
| `UNKNOWN` | The target or its type could not be followed; `reason` says why (`TARGET_UNDECIDED`, `TYPE_NOT_DECLARED`, `ROWTYPE_COLUMNS_UNKNOWN`, …). |
| `CALLER_DECIDES` | The target is an `OUT` or `IN OUT` parameter (`parameter_mode`): the value lands in each caller's argument, which does not take the size of the parameter's type, so the caller's declaration decides. The place stays, since a caller the index does not see may pass anything; each argument a known call passes for the parameter, positional or named, is a place of its own (`OUT_ARGUMENT`, `argument_parameter_id` and `argument_routine_id`), with the functions of the place it comes from, classed as an `INTO` target. An argument that is the caller's own `OUT` parameter is followed to that routine's callers, once per parameter and column. A subprogram nested in a routine has no entity: its calls are bound in its file, to the subprogram of that name in the nearest declarations around the call (a call of an overloaded one is counted, `OUT_CALL_NOT_DECIDED`, when a column's value reaches one of its `OUT` parameters). An object type method's `OUT` parameter, whose calls the index does not record, is counted (`OUT_PARAMETER_NOT_FOLLOWED`). An argument the index does not bind (`OUT_ARGUMENT_NOT_FOLLOWED`) and a call whose routine is not decided among candidates with such a parameter (`OUT_CALL_NOT_DECIDED`) are counted. A field or element of such a parameter keeps the class its own type gives. |
| `ANCHORED_ELSEWHERE` | The type ends at another column (`orders.note%type`, a field of another `%ROWTYPE`). |
| `NO_SIZE` | The type has no size of its own: `NUMBER` without precision, a floating number (`FLOAT(n)`, `BINARY_DOUBLE`), which rounds rather than fails, `CLOB`, `DATE`, an object type. |
| `ANCHORED_TO_COLUMN` | The type ends at this column: `%TYPE` of it, a `%ROWTYPE` of its table, a cursor record whose field is it, or an anchor to something so anchored. Its type follows the column. |

A number's size is given as a column's `NUMBER(p,s)` is compared with it: `length` is the digits it holds before the
point, `p - s` (none below zero), in `DIGITS`, and `scale` is `s`. `NUMBER(10,2)` holds 8 digits before the point, scale
2; `NUMBER(p)` has scale 0; `NUMBER(*,s)` has precision 38; `INTEGER`, `INT`, `SMALLINT`, `DECIMAL` and `NUMERIC`
without a precision are `NUMBER(38,0)`; a negative scale rounds to the left of the point and holds that many more
digits (`NUMBER(5,-2)`: 7, scale -2).

`type_source_id` is the entity whose declaration decides the type, when it is not the target itself: the column an
anchor ends at, or the variable, field or type that writes the size. A column's page lists its places by class (in the
order above), then by file and line, with those of the view columns computed from it, marked as read through the view; a target's page lists
the columns it receives. The MCP tool `find_column_places` pages through them all; `get_entity` of a column counts
them by class (`placesByClass`). What is not followed is counted by shape in `COLUMN_VALUE_NOT_FOLLOWED` coverage notes:
an item that is another expression of one column (`EXPRESSION_NVL`, `_NVL2`, `_UPPER`, `_TRIM`, `_SUBSTR`, `_MAX_MIN`, `_TO_CHAR`,
`_CONCATENATION`, `_OTHER`), a dynamic `INTO` (`DYNAMIC_INTO`), an assigned field of a row record whose column is not
known (`RECORD_ASSIGNMENT`: the cursor's field is an expression, or the cursor or `%ROWTYPE` is not known), an assigned
expression of such a field (`RECORD_ASSIGNMENT_EXPRESSION_UPPER`, …, by the same shapes), a column passed to a routine
whose value the index does not follow (below), and a `FETCH` from a cursor whose query is not known
(`FETCH_CURSOR_NOT_KNOWN`).

A column passed to a routine's `IN` parameter (in static SQL, anywhere in the statement, even a `WHERE`; or a field of
a row record passed in PL/SQL), as is or through value functions, lands in the parameter, which never limits it, even
declared `%TYPE`. Each target the routine writes the parameter into (an assignment of it, as is or through value
functions, or a `SELECT … INTO` or `RETURNING … INTO` of it, a record taking
it as its field as it would take a column) is a place of the column (`IN_ARGUMENT`), classed as any target, once per column and target however many
calls pass it: `argument_parameter_id` and `argument_routine_id` name the parameter the column was passed to,
`call_count` how many calls pass it, `call_path` and `call_line` one of them. A parameter passed on to another routine's
`IN` parameter is followed there, once per parameter and column; a target that is an `OUT` parameter is followed to its
callers. A parameter passed to a subprogram nested in the routine is followed there too, by where it is declared in
the file. A parameter only read (compared, in a condition) gives nothing. What is not followed is counted: a routine
among candidates (`ARGUMENT_ROUTINE_UNDECIDED`), one outside the index (`ARGUMENT_ROUTINE_OUTSIDE`: qualified by a
package the sources do not have, resolved by the dictionary only, without a body in the sources, or with a body that is
not PL/SQL: a Java or C call spec, `AGGREGATE USING`, `PIPELINED … USING`), an argument the call
does not tie to a parameter (`ARGUMENT_NOT_TIED`), an object type's constructor (`ARGUMENT_CONSTRUCTOR`), an overloaded nested subprogram (`ARGUMENT_LOCAL_SUBPROGRAM`), the parameter named in SQL on a table whose columns are not known, so a column may take the name
(`ARGUMENT_COLUMN_POSSIBLE`), the parameter selected by a cursor (`ARGUMENT_FETCHED`) or
into targets that do not match the items (`ARGUMENT_INTO_NOT_FOLLOWED`), a
record field whose column is not known (`ARGUMENT_COLUMN_NOT_KNOWN_*`, by why: `_EXPRESSION_CONCATENATION`, … for a
cursor item that is an expression, by the same shapes as `EXPRESSION_*`; `_ROWTYPE` for a `%ROWTYPE` whose relation or
cursor is not known; `_CURSOR` for a `%ROWTYPE` of a cursor whose query is not known; `_STAR` for a cursor's `*` over a
relation whose columns are not known; `_OTHER` for anything else), and the parameter returned (`ARGUMENT_RETURNED`)
or written within an expression (`ARGUMENT_EXPRESSION_*`, by its shape as for `EXPRESSION_*`, with `_CASE` for a `CASE`
and `_LENGTH` for `LENGTH` and its kin), each once per column. An unqualified name the index does not
resolve is taken for a built-in function (`upper`, `count`) and not counted.

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
