# Development

Requirements: JDK 25 (pinned in `.java-version`). `./gradlew build` builds and tests every module;
`./gradlew :atlas-cli:installDist` builds the launcher, `atlas-cli/build/install/zpa-atlas/bin/zpa-atlas`
(`zpa-atlas.bat` on Windows).

## Modules

- `atlas-core`: parsing with ZPA, extraction, the index writer.
- `atlas-query`: the read API over an index, and the dependency comparison.
- `atlas-server`: the web page and its JSON API (`/api/run`, `/api/codes`, `/api/entities`, `/api/entity`,
  `/api/occurrences`, `/api/source`, `/api/file`, `/api/values`, `/api/value-sites`, `/api/query`,
  `/api/query/summary`, `/api/query/groups`, `/api/impact`, `/api/impact/graph`, `/api/text`), and the MCP endpoint `/mcp` over the same calls. `/api/codes` lists the
  stored codes and reads no index.
- `atlas-cli`: the `zpa-atlas` command.
- `atlas-devtools`: measurement tools.

## Validation tools

`atlas-devtools` is a separate distribution with the tools that measure the atlas itself (`compare-deps` against
`ALL_DEPENDENCIES`, `timings`), the runbook for the real corpus and the Oracle integration test: see
[atlas-devtools/README.md](../atlas-devtools/README.md).

## Third-party notice

`THIRD-PARTY.md` is generated from the runtime classpath of `atlas-cli` by `./gradlew :atlas-cli:thirdPartyNotice`, which every distribution build (`distZip`, `distTar`, `installDist`) runs. The license texts the distribution ships for ZPA and FLR are in [licenses/](../licenses).
