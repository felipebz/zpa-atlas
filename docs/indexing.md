# Indexing

`zpa-atlas index` reads a folder of sources and writes one index file, a SQLite database.

```sh
zpa-atlas index --sources path/to/sources --out atlas.db --compilation-schema HR
zpa-atlas index --project hr.yaml
zpa-atlas stats atlas.db
```

| Option | What it does |
|---|---|
| `--sources`, `--out` | The folder to read, and the index file to write. |
| `--compilation-schema` | The schema of the code that does not name its owner. Without it, keys show `~` for that schema. |
| `--extensions` | The files to read. Default: `sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps`. Forms modules (`_fmb.xml`) are read whatever it says: see [Oracle Forms modules](forms.md). |
| `--encoding` | `auto` (default: each file read as UTF-8, else windows-1252), or one charset for every file. |
| `--database-version`, `--ccflags` | The values conditional compilation (`$IF`) sees, as on the database. The version defaults to the one of `--oracle-url`. |
| `--nls-length-semantics` | `BYTE` (default) or `CHAR`: how a length written without a unit (`varchar2(10)`) counts, as your code is compiled. With `--oracle-…`, the dictionary tells it for each unit. |
| `--oracle-…` | Read the Oracle dictionary: see [Using the Oracle dictionary](#using-the-oracle-dictionary). |
| `--project` | Take the options from a project file: see [Project files](#project-files). |
| `--incremental` | Walk only the files a change can affect: see [Incremental indexing](#incremental-indexing). |
| `--threads` | How many files to read at once. Default: the number of processors. |
| `--phase-times` | Print how long each phase took, and the slowest files. |

- Each run writes a new file and renames it over the old one at the end, so a server can keep serving while you index.
- `stats` prints a summary of an index.
- Exit codes: 0 success, 2 invalid usage, 3 execution failure.
- In a container: see [Running in a container](container.md).

## Using the Oracle dictionary

From the sources alone, uses the code cannot confirm stay `UNVERIFIED`. With the dictionary, Atlas confirms them and
learns what only the database knows: each table's columns and their types, the keys, the synonyms. See
[The Oracle dictionary](concepts.md#the-oracle-dictionary).

1. **Driver:** download the Oracle JDBC driver JAR (e.g. `ojdbc17.jar`) from
   [Oracle's JDBC downloads page](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html) and put it
   in the `drivers` folder of the distribution, `atlas-cli/build/install/zpa-atlas/drivers/`.
2. **Password:** put it in the environment variable `ZPA_ORACLE_PASSWORD`, or name another with
   `--oracle-password-env`. It is never an option.
3. **Schemas:** `--oracle-schemas` reads whole schemas; `--oracle-relations OWNER.TABLE` reads single tables or views.
4. **User:** a user with `SELECT ANY TABLE` gives complete lists of the foreign keys that reference each table.

```sh
export ZPA_ORACLE_PASSWORD=...
zpa-atlas index --sources path/to/sources --out atlas.db --compilation-schema HR \
  --oracle-url jdbc:oracle:thin:@//host:1521/SERVICE --oracle-user HR --oracle-schemas HR
```

## Project files

A project file keeps a codebase's options in one place, so you index it with `zpa-atlas index --project hr.yaml`:

```yaml
# the project "hr": hr.yaml
sources: ../src/hr              # required
index: ../data/hr.db            # required: the index file to write
extensions: [sql, pks, pkb]
encoding: auto
compilationSchema: HR
databaseVersion: "19"
ccflags: "debug:TRUE, level:2"
nlsLengthSemantics: BYTE
oracle:                         # optional
  url: jdbc:oracle:thin:@//db.example.com:1521/orcl
  user: atlas_reader
  passwordEnv: HR_DB_PASSWORD   # the password itself never goes in the file
  schemas: [hr, common]
  relations: [other.some_table]
tabWidth: 2                     # optional: how wide the page shows a tab, 2, 4 or 8
```

- **The project's name** is the file's name without `.yaml`.
- **Each key is an option** of `index`, in camel case: `compilationSchema` is `--compilation-schema`, and `index` is
  `--out`. `tabWidth` is for `serve`.
- **Paths** are relative to the file's folder.
- **An option on the command line wins** over the file: `--out other.db` writes elsewhere.

`serve --projects` serves a folder of project files: see [Serving several projects](serving.md#serving-several-projects).

## Incremental indexing

`index --incremental` keeps a cache beside the index (`<index file>.facts`) and, on the next run, reads again only the
files that changed and those a change can affect, such as the callers of a procedure whose signature changed. The
index it writes is the same as a full run's.

A run reads everything again when the options or the dictionary changed, or when the cache was written by another
version of Atlas. A Forms module is read again when the JAR of its [reader](forms.md#serving) changed, even if its file
did not.
