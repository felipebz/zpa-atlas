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

## Validation tools

`atlas-devtools` is a separate distribution with the tools that measure the atlas itself (`compare-deps` against
`ALL_DEPENDENCIES`, `timings`), the runbook for the real corpus and the Oracle integration test: see
[atlas-devtools/README.md](atlas-devtools/README.md).

## License

ZPA Atlas is licensed under the [Apache License, Version 2.0](LICENSE).

It depends on [ZPA](https://github.com/felipebz/zpa) and [FLR](https://github.com/felipebz/flr), which are licensed under the GNU Lesser General Public License, version 3 or later. The binary distribution lists every bundled library and its license in `THIRD-PARTY.md`.

`THIRD-PARTY.md` is generated from the runtime classpath of `atlas-cli` by `./gradlew :atlas-cli:thirdPartyNotice`, which every distribution build (`distZip`, `distTar`, `installDist`) runs. The license texts the distribution ships for ZPA and FLR are in [licenses/](licenses).
