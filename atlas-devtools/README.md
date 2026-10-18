# atlas-devtools

These are the tools we use to check how good an index is. They are not part of the published `zpa-atlas` CLI and are
meant for whoever develops or evaluates the atlas, not for its everyday users.

There are three commands:

- **`compare-deps`** compares an index with what Oracle itself records in `ALL_DEPENDENCIES` and explains every
  difference. It is the main way to find out what the atlas gets right and where it falls short.
- **`timings`** measures how fast the web page's queries are on a real index.
- **`same-rows`** confirms that two indexes hold exactly the same data, for example before and after a refactoring.

## Getting started

Build the distribution with `./gradlew :atlas-devtools:installDist` (or `distZip` for a zip). As with `zpa-atlas`:

- put the Oracle JDBC driver JAR in the `drivers` folder of the distribution;
- set the password in an environment variable, never on the command line. The default variable is
  `ZPA_ORACLE_PASSWORD`; `--oracle-password-env` picks another.

```sh
atlas-devtools compare-deps --db <file.db> --out <dir> \
  (--oracle-url URL --oracle-user USER --oracle-schemas A,B [--oracle-password-env VAR] | --dependencies deps.csv) \
  [--save-dependencies deps.csv] [--sources <dir>] [--default-owner X] [--builtins file]
atlas-devtools timings <file.db> [--sources <dir>]
atlas-devtools same-rows <first.db> <second.db>
```

Every command exits with 0 on success, 2 on invalid usage and 3 when something fails while running.

## compare-deps

`compare-deps` looks at the index one database object at a time (a package, a procedure, a view) and asks: does the
index see the same dependencies that Oracle recorded for that object?

It prints a summary to the screen and writes the details to CSV files in `--out`. The summary has only counts and the
20 most frequent names in each list, so it is safe to share. The CSV files are not: they hold names, paths and line
numbers.

| File | What it lists |
|---|---|
| `units.csv` | every object of the index, with its database status and whether its source matches the database |
| `matched.csv` | dependencies found on both sides |
| `db_only.csv` | dependencies Oracle recorded and the index did not find |
| `index_only.csv` | dependencies the index found and Oracle did not record |
| `trigger_columns.csv` | differences in the columns that triggers use |

### Reading the summary

The headline number is the **match rate**: the share of Oracle's dependencies the index also found. Some rows can
never be matched and are left out of it (the classes marked "expected" below). A second rate also leaves out the
dependencies whose name never appears in the unit's text, since no reading of the source can find those.

Before trusting a difference, check the **Source** section. A unit whose stored source differs from the file
(`DIFFERS`) was compiled from another version of the code, so its differences say little about the index.

**Dependencies only in the database** (`db_only.csv`), each with the first reason that applies:

| Class | Meaning |
|---|---|
| `BUILTIN` | expected: a built-in name such as `STANDARD` (see [built-in names](#built-in-names)) |
| `REMOTE` | the dependency goes through a database link |
| `NON_EXISTENT` | expected: Oracle's record of an object that does not exist |
| `MVIEW_CONTAINER` | expected: a materialized view depending on its own storage table |
| `SOURCE_DIFFERS` | the unit's stored source differs from the file |
| `TRANSITIVE` | the name is not in the text; Oracle reached it through another object (the path is shown) |
| `NAME_NOT_IN_TEXT` | the name is not in the text, and no path explains it |
| `UNRESOLVED_IN_INDEX` | the index saw the name but could not resolve it |
| `RESOLVED_ELSEWHERE` | the index resolved the name to a different object: most likely a bug worth looking at |
| `GUESSED_ELSEWHERE` | the index guessed a different object, without being sure |
| `NOT_EXTRACTED` | none of the above: the index did not see this use at all |

**Dependencies only in the index** (`index_only.csv`):

| Class | Meaning |
|---|---|
| `VIEW_REMOTE` | a view reading through a database link. Oracle never records a view's dependency on a remote object, so this is not a gap in the index. |
| `SOURCE_DIFFERS` | the unit's stored source differs from the file |
| `UNIT_INVALID` | the database object is invalid, so Oracle's dependencies for it may be out of date |
| `BY_NAME_ONLY` | the index knows only the name of the target, not its owner |
| `INDEX_ONLY` | none of the above |

Two more sections complete the picture:

- **Trigger columns** compares the columns each trigger uses (its `UPDATE OF` list, what it reads and writes through
  `:new` and `:old`) with `ALL_TRIGGER_COLS`. Triggers compiled with debug are also counted apart, because Oracle then
  reports every column of the table as read.
- **Conditional compilation** checks that the index was built for the same database version and the same
  `PLSQL_CCFLAGS` as the units in the database, and warns when they differ.

### Working offline

Reading the database takes a while and needs a connection. With `--save-dependencies deps.csv`, `compare-deps` also
saves what it read; later runs can use `--dependencies deps.csv` instead of `--oracle-*`, without touching the
database. If JDBC is blocked, another tool can export the same facts in that format (see
[the file format](#the-dependencies-file) below).

### Built-in names

Some dependencies are not worth comparing. By default `compare-deps` treats these as built-ins:

- `STANDARD`, `DBMS_STANDARD`, `PLITBLM` and `SYS_STUB_FOR_PURITY_ANALYSIS`, which Oracle adds to units;
- `DUAL`, which the index leaves out on purpose.

The first four come from Oracle 26ai. On an older database, look at the most frequent `SYS` and `PUBLIC` rows of `db_only.csv` and, if needed, pass your own list with
`--builtins file` (one name per line, `#` starts a comment).

### Permissions

The `ALL_*` views show only what the connected user can see:

- **connected as the owner of the code**, everything is visible. Prefer this whenever possible;
- **as another user**, Oracle lists only the objects that user has privileges on. Objects it cannot see appear as
  "units without ALL_OBJECTS row" and are not compared, so the match rate covers only the visible part;
- if `ALL_SOURCE` cannot be read, the summary says so and skips the source checks.

## timings

`timings` runs the queries behind the web page on a real index and prints how long they took:

- "who uses" and the entity page for the 5 most used entities;
- "who uses" through views for the column with the most view columns over it;
- the file view for the file with the most occurrences, with the size of the answer before and after gzip.

The file view reads the sources from `--sources`, or by default from the folder recorded in the index.

## same-rows

`same-rows` compares two indexes table by table and prints one line per table. It exits with 3 when anything differs.
Timestamps are ignored, so the same corpus indexed twice compares equal.

Use it to show that a change to the indexer leaves its output unchanged: index the same corpus with the old and the
new version, then compare the two files.

## Running on a real corpus

1. **Index with metadata**, using the published CLI. Indexing keeps every file's facts in memory until the end, so
   start with 4 GB of heap and raise it if you get an `OutOfMemoryError`.

   ```sh
   export JAVA_OPTS=-Xmx4g
   export ZPA_ORACLE_PASSWORD=...        # never on the command line
   zpa-atlas index --sources <dir> --out atlas.db --compilation-schema OWNER \
     --oracle-url jdbc:oracle:thin:@//host:1521/service --oracle-user USER --oracle-schemas OWNER[,OTHER]
   ```

   On PowerShell:

   ```powershell
   $env:JAVA_OPTS = '-Xmx4g'
   $env:ZPA_ORACLE_PASSWORD = '...'
   zpa-atlas index --sources <dir> --out atlas.db --compilation-schema OWNER `
     --oracle-url jdbc:oracle:thin:@//host:1521/service --oracle-user USER --oracle-schemas OWNER[,OTHER]
   ```

2. **Measure:**

   ```sh
   zpa-atlas stats atlas.db > stats.txt
   atlas-devtools timings atlas.db > timings.txt
   ```

3. **Compare**, saving what was read from the database for later offline runs:

   ```sh
   atlas-devtools compare-deps --db atlas.db --out compare \
     --oracle-url ... --oracle-user USER --oracle-schemas OWNER[,OTHER] --save-dependencies deps.csv > compare.txt
   ```

4. **Share** `stats.txt`, `timings.txt` and `compare.txt`. Keep the CSV files to yourself: they contain names, paths and
   line numbers.

## Oracle integration test

An opt-in test runs `compare-deps` end to end against a real Oracle schema. It deploys a small corpus
(`src/integrationTest/resources/oracle-corpus`), indexes it with metadata and compares it twice: once over JDBC and once
offline from the saved file. It resets the schema before and after.

It uses the same schema as the `dev/oracle` probe, so never run the two at the same time.

```sh
export ATLAS_IT_ORACLE_URL=jdbc:oracle:thin:@localhost:1521/FREEPDB1
export ATLAS_IT_ORACLE_USER=zpa_probe
export ATLAS_IT_ORACLE_PASSWORD=...
./gradlew :atlas-devtools:integrationTest
```

Without the three variables, the test is reported as skipped. It is not part of `build`.

## Reference

### The dependencies file

The file written by `--save-dependencies` and read by `--dependencies` is UTF-8 CSV with the header
`kind,owner,name,type,c1,c2,c3,c4` and one row per fact. An empty value means null.

| Kind | Columns after `kind` | Notes |
|---|---|---|
| `META` | `source_readable,true\|false` | |
| `META` | `ccflags_readable,true\|false` | optional; without it the flags are not checked |
| `META` | `database_version,23.0` and `database_version_origin,DATABASE\|JDBC` | optional |
| `OBJ` | `owner,name,type,status` | one per object (`ALL_OBJECTS`) |
| `DEP` | `owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link` | one per dependency (`ALL_DEPENDENCIES`) |
| `SRC` | `owner,name,type,line,text` | stored source (`ALL_SOURCE`; for views, `ALL_VIEWS.TEXT` with type `VIEW`) |
| `TCOL` | `trigger_owner,trigger_name,column_name,table_owner,table_name,column_list,column_usage` | `ALL_TRIGGER_COLS` |
| `TSET` | `owner,name,TRIGGER,plsql_debug` | optional; without it no trigger counts as compiled with debug |
| `CCF` | `owner,name,type,plsql_ccflags` | optional; empty means compiled without flags |

A file without `VIEW` source rows leaves views without stored source, so their text is not compared.
