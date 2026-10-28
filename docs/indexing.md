# Indexing

`zpa-atlas index` reads a folder of sources and writes one index file.

```sh
zpa-atlas index --project <name>.yaml [any option below, which wins over the file] [--threads N] [--incremental] [--phase-times]
zpa-atlas index --sources <dir> --out <file.db> [--compilation-schema HR] [--threads N] [--incremental] [--phase-times] [--extensions sql,pks,...] [--encoding auto|<charset>] \
  [--database-version 19] [--ccflags 'name:value, ...'] \
  [--oracle-url URL --oracle-user USER --oracle-schemas HR [--oracle-relations S.T] [--oracle-password-env VAR]]
zpa-atlas stats <file.db>
```

| Option               | Default                                                                                          |
|----------------------|--------------------------------------------------------------------------------------------------|
| `--extensions`       | `sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps`                                            |
| `--encoding`         | `auto` (per file: strict UTF-8, else windows-1252), or a charset name forced for every file      |
| `--threads`          | the number of processors                                                                         |
| `--incremental`      | off. With it, `index` walks only the files a change can affect: see [Incremental indexing](#incremental-indexing) |
| `--phase-times`      | off. With it, `index` prints to stderr the time, share and peak heap of each phase, and the slowest files |
| `--database-version` | the version of the `--oracle-url` database, else none                                            |
| `--ccflags`          | none                                                                                             |

- `--compilation-schema` is the schema of the code that does not write its owner; without it, keys use `~`.
- `stats` prints counts by file parse status, entity kind, occurrence roles and status, coverage note kind, and the top
  called and written entities.
- The output is a new SQLite file per run, written to a temporary file and renamed at the end.
- A schema change means re-indexing.
- Oracle options and exit codes are those of zpa-cli: 0 success, 2 invalid usage, 3 execution failure.
- `index` and `stats` read no environment variables for their options.
- In a container: see [container.md](container.md).

## Using the Oracle dictionary

Without a database, uses that the sources alone cannot confirm stay `UNVERIFIED`. With the dictionary they become
`RESOLVED` or `UNRESOLVED`; table and view columns are known, so `select *` expands and view columns get definitions;
synonyms are followed, and views are told apart from tables.

- **Driver:** download the Oracle JDBC driver JAR (e.g. `ojdbc17.jar`, for JDK 17 and later) from
  [Oracle's JDBC downloads page](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html) and put it
  in the `drivers` directory of the distribution, `atlas-cli/build/install/zpa-atlas/drivers/`. Create the directory
  if it does not exist.
- **Password:** read from the environment variable `ZPA_ORACLE_PASSWORD`; choose another with `--oracle-password-env`.
- **Schemas:** `--oracle-schemas` reads whole schemas; `--oracle-relations S.T` reads single relations.

```sh
export ZPA_ORACLE_PASSWORD=...
zpa-atlas index --sources path/to/sources --out atlas.db --compilation-schema HR \
  --oracle-url jdbc:oracle:thin:@//host:1521/SERVICE --oracle-user HR --oracle-schemas HR
```

## Project files

A project is one codebase, indexed into one index file from its own sources with its own options. Instead of
repeating the options, describe the project in `<name>.yaml` and index it with `zpa-atlas index --project <name>.yaml`:

```yaml
# the project "hr": hr.yaml
sources: ../src/hr              # required
index: ../data/hr.db            # required: the index file `index` writes
extensions: [sql, pks, pkb]     # default: index's default list
encoding: auto
compilationSchema: HR
databaseVersion: "19"
ccflags: "debug:TRUE, level:2"
oracle:                         # optional
  url: jdbc:oracle:thin:@//db.example.com:1521/orcl
  user: atlas_reader
  passwordEnv: HR_DB_PASSWORD   # the password itself never goes in the file
  schemas: [hr, common]
  relations: [other.some_table]
```

- **The name** is the file's name without `.yaml`: lower-case letters, digits, `-` and `_`, starting with a letter or a
  digit.
- **Each key is an option** of `index`, with the same values and checks: `index` is `--out`, `compilationSchema` is
  `--compilation-schema`, `oracle.passwordEnv` is `--oracle-password-env`, and so on. `--threads`, `--incremental` and
  `--phase-times` belong to the machine and stay on the command line.
- **Paths** are relative to the file's folder. **Lists** are YAML lists or the comma-separated text the option takes.
- **An option given beside `--project` wins** over the file's key: `--out other.db` writes elsewhere.
- **Refused:** an unknown key, a value of the wrong type, a missing `sources` or `index`, and a password in the file;
  each message names the file and the key.

`serve --projects` serves a folder of these files: see [Serving several projects](serving.md#serving-several-projects).

## Incremental indexing

`index --incremental` keeps each file's facts in a cache beside the index, `<index file>.facts`, and on the next run walks
only the files a change can affect; the other files' facts come from the cache. The index it writes is the one a full
run writes, ids included. Each run prints one more line: the files walked out of the total, as changed, new, or affected
by a change elsewhere, and the files removed; or, for a full run, why.

- **A file is walked again** when its text changed, when it is new, or when a declaration other files can see changed
  (a package spec's member, a signature, a type, a standalone subprogram, an object type's attributes or methods) and
  the file writes its name, directly or through another declaration that refers to it. A change inside a package
  body, a procedure or a function that changes no declaration walks that file only.
- **Every file is walked** when the preparation of the sources failed differently, or when the schemas of the declared
  entities changed; the line says which.
- **A full run** replaces the cache when there is none, when it was written by another atlas or ZPA build, with other
  options (`--compilation-schema`, `--encoding`, `--extensions`, `--database-version`, `--ccflags`), or with another
  dictionary (the Oracle metadata, including relations the sources name for the first time), and when it is damaged or
  cut short. A run killed midway leaves the old cache or none, never a wrong one.
- **What is still done in full:** the dictionary load, the project preparation, the object type members pass and the
  writing of the whole index. The cache is read while the dictionary loads and written while the index is, and is
  rewritten on every run; it is about a twentieth of the index's size. `--phase-times` shows the plan's parts.
- `serve` never reads the cache. Without `--incremental`, `index` neither reads nor writes it.
