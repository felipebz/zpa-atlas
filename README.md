# ZPA Atlas

Code-intelligence index for Oracle PL/SQL built on [ZPA](https://github.com/felipebz/zpa).

## Build

JDK 21 (`.java-version`).

```sh
./gradlew build
./gradlew :atlas-cli:installDist
```

## Usage

```sh
zpa-atlas index --sources <dir> --out <file.db> [--compilation-schema HR] [--threads N] [--encoding auto|<charset>] \
  [--oracle-url URL --oracle-user USER --oracle-schemas HR [--oracle-relations S.T] [--oracle-password-env VAR]]
zpa-atlas stats <file.db>
zpa-atlas serve --db <file.db> [--sources <dir>] [--host 127.0.0.1] [--port 8080]
```

Oracle options and exit codes are those of zpa-cli (0 success, 2 invalid usage, 3 execution failure). The JDBC driver
JAR goes in the `drivers` directory of the distribution. `serve` has no authentication: binding to anything but 127.0.0.1 exposes the index and the sources to the
network. It opens the index for each request only, so `index` can replace the file while the server runs. The output is a new SQLite file per run, written to a
temporary file and renamed at the end; a schema change means re-indexing.

### Source encoding

`--encoding auto` (the default) decides per file: bytes that decode as strict UTF-8 are UTF-8 (a leading BOM is
dropped), anything else is read as windows-1252, which covers ISO-8859-1 text plus curly quotes and the euro sign.
Accented legacy text is practically never valid UTF-8, and pure ASCII reads the same either way. Force a charset
(`--encoding ISO-8859-1`, any name Java accepts) when the files use another legacy charset (for example IBM850 or
ISO-8859-15), which cannot be told apart reliably. The charset used is recorded per file (`source_file.encoding`) and
used again by the source view and the validation tools; `stats` prints the file count per encoding.

## Validation tools

`atlas-devtools` is a separate distribution with the tools that measure the atlas itself (`compare-deps` against
`ALL_DEPENDENCIES`, `timings`), the runbook for the real corpus and the Oracle integration test: see
[atlas-devtools/README.md](atlas-devtools/README.md).
