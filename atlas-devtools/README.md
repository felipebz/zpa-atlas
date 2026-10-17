# atlas-devtools

Tools that measure the atlas; they are not part of the published `zpa-atlas` CLI. Build the distribution with
`./gradlew :atlas-devtools:installDist` (or `distZip`). As for `zpa-atlas`, the Oracle JDBC driver JAR goes in the
`drivers` folder of the distribution, and the password is read from an environment variable
(`--oracle-password-env`, default `ZPA_ORACLE_PASSWORD`). Exit codes: 0 success, 2 invalid usage, 3 execution failure.

```sh
atlas-devtools compare-deps --db <file.db> --out <dir> \
  (--oracle-url URL --oracle-user USER --oracle-schemas A,B [--oracle-password-env VAR] | --dependencies deps.csv) \
  [--save-dependencies deps.csv] [--sources <dir>] [--default-owner X] [--builtins file]
atlas-devtools timings <file.db>
atlas-devtools same-rows <first.db> <second.db>
```

## compare-deps

Compares the index, projected to object level, with `ALL_DEPENDENCIES` and classifies every difference. It prints a
summary (counts and top-20 name lists only, safe to share) and writes `units.csv`, `matched.csv`, `db_only.csv`,
`index_only.csv` and `trigger_columns.csv` to `--out`. The match rate is `matched / (database - BUILTIN - NON_EXISTENT)`: `NON-EXISTENT` rows are
never matched, they record an object that does not exist.

The "Trigger columns" section compares `ALL_TRIGGER_COLS` with the index per trigger, column and facet, for the
triggers that are units of the index and have an `ALL_OBJECTS` row: `LIST` (`COLUMN_LIST = 'YES'` against the
`UPDATE OF` events), `IN` (usage `IN` against reads through `:new`/`:old`), `OUT` (usage `OUT` against writes through
`:new`), and `USED` for compound triggers, whose usage Oracle records as `NONE`. Names in the `WHEN` clause are left
out, as Oracle does. Each facet line also gives the counts without the triggers compiled with debug
(`ALL_PLSQL_OBJECT_SETTINGS.PLSQL_DEBUG = 'TRUE'`): Oracle then lists every column of the table as read, whatever the
trigger uses. Triggers whose stored source differs get their own line and are not counted; database triggers without
a unit are only counted. `trigger_columns.csv` has a `group` column (`COMPARED`, `SOURCE_DIFFERS`, `NO_UNIT`) and a
`debug` column: the differences of the compared triggers, and every row of the other two groups.

- With `--oracle-*` it reads `ALL_DEPENDENCIES`, `ALL_OBJECTS`, `ALL_SOURCE`, `ALL_TRIGGER_COLS` and the triggers' `ALL_PLSQL_OBJECT_SETTINGS` of the `--oracle-schemas` owners
  (`--oracle-relations` is rejected). Only the seven `ALL_DEPENDENCIES` columns that exist on 10.2 are read.
- `--save-dependencies deps.csv` also writes what was read; `--dependencies deps.csv` re-runs offline from that file.
  Format: UTF-8 CSV with the header `kind,owner,name,type,c1,c2,c3,c4` and one row per fact:
  `META,source_readable,true|false`, `OBJ,owner,name,type,status`,
  `DEP,owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link` (empty means null),
  `SRC,owner,name,type,line,text` and
  `TCOL,trigger_owner,trigger_name,column_name,table_owner,table_name,column_list,column_usage` and
  `TSET,owner,name,TRIGGER,plsql_debug` (optional: without it, no trigger counts as compiled with debug).
- `--builtins file` replaces the built-in list (one name per line, `#` comments). The default is `STANDARD`,
  `DBMS_STANDARD`, `PLITBLM` and `SYS_STUB_FOR_PURITY_ANALYSIS` (the rows Oracle 26ai adds to units) and `DUAL` (which
  the indexer drops on purpose); check it on
  10.2/11.2 with the most frequent `SYS`/`PUBLIC` rows of `db_only.csv`.

`timings` times the query API (first page with `SELF`, with `IMPLEMENTATIONS,CHILDREN`, and `entity`) for the 5
entities with the most non-definition occurrences.

`same-rows` checks that two indexes have the same rows in every table: every column, rows ordered by primary key
(`rowid` without one); the run timestamps and the acquisition time in `run.metadata_origin` are ignored. It prints one line per table and exits with 3 when anything
differs. Use it to show that a change to the indexer or the writer leaves an index unchanged: index the same corpus
with the old and the new distribution, then compare.

## Runbook: the real corpus

1. Index with metadata, with the published CLI. The facts of every file stay in memory until the write: start with
   4 GB of heap and raise it on `OutOfMemoryError`. (`compare-deps` ran a 4,000-unit synthetic corpus in 512 MB;
   `index` was not measured that way.)

   ```sh
   export JAVA_OPTS=-Xmx4g
   export ZPA_ORACLE_PASSWORD=...        # never on the command line
   zpa-atlas index --sources <dir> --out atlas.db --compilation-schema OWNER \
     --oracle-url jdbc:oracle:thin:@//host:1521/service --oracle-user USER --oracle-schemas OWNER[,OTHER]
   ```

   PowerShell:

   ```powershell
   $env:JAVA_OPTS = '-Xmx4g'
   $env:ZPA_ORACLE_PASSWORD = '...'
   zpa-atlas index --sources <dir> --out atlas.db --compilation-schema OWNER `
     --oracle-url jdbc:oracle:thin:@//host:1521/service --oracle-user USER --oracle-schemas OWNER[,OTHER]
   ```

2. Measure: `zpa-atlas stats atlas.db > stats.txt` and `atlas-devtools timings atlas.db > timings.txt`.
3. Compare, keeping the database facts for offline re-runs:

   ```sh
   atlas-devtools compare-deps --db atlas.db --out compare \
     --oracle-url ... --oracle-user USER --oracle-schemas OWNER[,OTHER] --save-dependencies deps.csv > compare.txt
   ```

   If JDBC is blocked, the same facts can be exported by another tool in the format above and passed with
   `--dependencies`. Share `stats.txt`, `timings.txt` and `compare.txt`; the CSV files hold names, paths and line numbers.

Grants. The `ALL_*` views show only what the connected user can see:
- connected as the owner of the code, everything is visible;
- as another user, the views list only the objects that user has privileges on (and `ALL_SOURCE` only code it may
  run). Invisible objects are reported as "units without ALL_OBJECTS row" and not compared, so the match rate is about
  the visible part only. Run it as the owner when possible; which system privileges widen `ALL_*` on 10.2/11.2 was not
  tested here;
- if `ALL_SOURCE` cannot be read, the summary says so and the source classes are skipped.

## Oracle integration test

An opt-in test deploys `src/integrationTest/resources/oracle-corpus` to an Oracle schema (it resets the schema before
and after), indexes it with metadata and runs `compare-deps` over JDBC, then offline from the saved file. It uses the
schema of the `dev/oracle` probe: never run both at the same time.

```sh
export ATLAS_IT_ORACLE_URL=jdbc:oracle:thin:@localhost:1521/FREEPDB1
export ATLAS_IT_ORACLE_USER=zpa_probe
export ATLAS_IT_ORACLE_PASSWORD=...
./gradlew :atlas-devtools:integrationTest
```

Without the three variables the test is reported as `SKIPPED`. It is not part of `build`.
