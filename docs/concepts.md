# Concepts

Atlas reads your PL/SQL and SQL sources and records two things: the *entities* the code defines or names, and the
*occurrences*, the places in the code that use them. Every [answer](answers.md) is built from these. This page explains
what they mean and how far to trust them.

## Entities

An entity is anything with a name you can look up: a table, a column, a view, a sequence, a synonym, a trigger, a
package, a routine, a parameter, a variable, a type or a record field. Each one has a page and a key, such as
`db:HR.ORDERS.STATUS`. When neither the code nor `--compilation-schema` says the schema, the key has `~` in its place.

A few keys carry more than the name:

- A routine's key has its signature, so overloads stay apart: `db:HR.PKG.RUN(P_ID IN NUMBER)`.
- What a package body declares for itself, invisible from outside the package, ends with `@BODY`.
- A routine's own variables are entities too, under the routine's key. A search by name alone leaves them out: search
  for `routine.variable`.
- An object of an Oracle Forms module is keyed `forms:` and its path, starting with the module's file name, such as
  `forms:ORDERS/ORDERS/STATUS/WHEN-VALIDATE-ITEM` in `orders_fmb.xml`: see [Oracle Forms modules](forms.md).

## Occurrences

An occurrence is one place in the source where an entity is defined or used. Its *role* says how: called, read,
written, selected, inserted, updated, deleted, used as a type, created or dropped by DDL, and so on. Its *status* says
how sure Atlas is that the name means that entity:

| Status | Meaning | What to make of it |
|---|---|---|
| `RESOLVED` | Found, in the sources or in the Oracle dictionary. | Reliable. |
| `UNVERIFIED` | Plausible, but not checked: there was no dictionary, or the schema was not read. | Usually right. Index with the dictionary to confirm it. |
| `AMBIGUOUS` | Several candidates, such as overloads Atlas cannot tell apart. | Check the call. |
| `UNRESOLVED` | Named, but not found. | Something is missing, or lives outside the sources and the dictionary. The reason says which. |
| `EXTERNAL` | Beyond what Atlas can check, such as a name reached through a database link. | Check it by hand. |
| `DYNAMIC` | Inside dynamic SQL. | See below. |
| `PARTIAL_SOURCE` | In code the parser had to recover. | The file has a parse error; the page lists those files. |

### Dynamic SQL

Atlas reads the SQL of `EXECUTE IMMEDIATE`, `OPEN … FOR` and `DBMS_SQL.PARSE` when its text is in the code, and indexes
the names it finds there like any others. A part built at run time is a hole: Atlas never guesses what it holds.

Oracle records no dependency for dynamic SQL, so these uses are missing from `ALL_DEPENDENCIES`. Atlas lists them with
the rest.

## The Oracle dictionary

From the sources alone, Atlas knows what the code says, not what the database holds. With the dictionary (see
[Indexing](indexing.md#using-the-oracle-dictionary)), names are confirmed or refused, `select *` is expanded into the
table's columns, synonyms are followed, and views are told apart from tables. The pages then also show each column's
type and each table's keys.

## Coverage notes

What Atlas could not see or decide in a file is not dropped: it is recorded as a *coverage note* on that file, with its
kind and reason. `zpa-atlas stats` counts them by kind, and the `coverage_note` table lists them file by file (see
[Querying the index with SQL](sql.md)).
