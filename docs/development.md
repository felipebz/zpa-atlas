# Development

Requirements: JDK 25 (pinned in `.java-version`). `./gradlew build` builds and tests every module;
`./gradlew :atlas-cli:installDist` builds the launcher, `atlas-cli/build/install/zpa-atlas/bin/zpa-atlas`
(`zpa-atlas.bat` on Windows).

## Modules

- `atlas-core`: parsing with ZPA, what is extracted from the code, and the index writer.
- `atlas-query`: reading an index, for the server and the tools.
- `atlas-server`: the web page (`web/`), its JSON API under `/api`, and the MCP endpoint `/mcp`.
- `atlas-cli`: the `zpa-atlas` command.
- `atlas-devtools`: tools that measure Atlas itself, such as the comparison with `ALL_DEPENDENCIES`: see
  [atlas-devtools/README.md](../atlas-devtools/README.md).

## Documentation

The documents in `docs/` explain the concepts and what to do with the answers, for the people who use the page and
run the server. They do not list what Atlas covers or how it decides each case: the tests say that, and the coverage
notes say it for each index. The agents read the tools' own descriptions, not these documents.
