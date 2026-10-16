# ZPA Atlas

Code-intelligence index for Oracle PL/SQL built on [ZPA](https://github.com/felipebz/zpa).

## Build

JDK 21 (`.java-version`). Run Gradle through javm:

```sh
javm exec ./gradlew build
javm exec ./gradlew :atlas-cli:installDist
```

## Usage

```sh
zpa-atlas index --sources <dir> --out <file.db> [--compilation-schema HR] [--threads N] \
  [--oracle-url URL --oracle-user USER --oracle-schemas HR [--oracle-relations S.T] [--oracle-password-env VAR]]
zpa-atlas stats <file.db>
```

Oracle options and exit codes are those of zpa-cli (0 success, 2 invalid usage, 3 execution failure). The JDBC driver
JAR goes in the `drivers` directory of the distribution. The output is a new SQLite file per run, written to a
temporary file and renamed at the end; a schema change means re-indexing.
