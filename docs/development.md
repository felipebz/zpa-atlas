# Development

Requirements: JDK 25 (pinned in `.java-version`). `./gradlew build` builds and tests every module;
`./gradlew :atlas-cli:installDist` builds the launcher, `atlas-cli/build/install/zpa-atlas/bin/zpa-atlas`
(`zpa-atlas.bat` on Windows).

## Modules

- `atlas-core`: parsing with ZPA, extraction, the index writer.
- `atlas-query`: the read API over an index, and the dependency comparison.
- `atlas-server`: the web page and its JSON API (`/api/run`, `/api/top-packages`, `/api/codes`, `/api/entities`, `/api/entity`,
  `/api/occurrences`, `/api/source`, `/api/file`, `/api/values`, `/api/value-sites`, `/api/query`,
  `/api/query/summary`, `/api/query/groups`, `/api/impact`, `/api/impact/graph`, `/api/text`), and the MCP endpoint `/mcp` over the same calls. `/api/codes` lists the
  stored codes (reasons and parse statuses included) and reads no index; the pages label every one of them in each
  catalog, which a test checks. `/api/occurrences` takes `side=OUTSIDE|INSIDE` (in the units of the entity itself, a
  package's or type's spec and body, or elsewhere) and counts both sides in `facets.bySide`. `/api/file` answers the
  file's lines, occurrences and the entities they name, its `parseStatus`, its `objects` (each top-level unit with an
  entity, with the members it declares directly) and, with `colors=true`, `colors`: `[startLine, startCol, endLine,
  endCol, color]` spans (0 keyword, 1 literal, 2 comment, 3 documentation comment), or null when the server has no
  colorer or the lexer cannot read the text. The colors come from zpa's lexer in `atlas-core`, handed to the server by
  the `serve` command: `atlas-query` and `atlas-server` do not depend on zpa. `/api/text` answers `files` (the files
  the lines are in) and `fileLines` (each file of the page with its count of lines), and with `colors=true` each row's
  `colors`, `[startCol, endCol, color]` on its line: from the whole file, so a line inside a comment that opens above
  it is a comment; from the line alone when the file changed since indexing or cannot be read.
- `atlas-cli`: the `zpa-atlas` command.
- `atlas-devtools`: measurement tools.

## Validation tools

`atlas-devtools` is a separate distribution with the tools that measure the atlas itself (`compare-deps` against
`ALL_DEPENDENCIES`, `timings`), the runbook for the real corpus and the Oracle integration test: see
[atlas-devtools/README.md](../atlas-devtools/README.md).

## Third-party notice

`THIRD-PARTY.md` is generated from the runtime classpath of `atlas-cli` by `./gradlew :atlas-cli:thirdPartyNotice`, which every distribution build (`distZip`, `distTar`, `installDist`) runs. The license texts the distribution ships for ZPA and FLR are in [licenses/](../licenses).
