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

## Forms readers

A reader turns one export format of Oracle Forms modules into the model Atlas indexes
(`com.felipebz.zpa.atlas.forms.FormsModule`, in `atlas-core`); the `frmf2xml` reader is built in. A reader implements
`com.felipebz.zpa.atlas.forms.FormsModuleReader`:

- `fileNameEndings`: the endings of the file names it reads, such as `_fmb.json`;
- `skippedFileNameEndings`: files of its format that hold no module to index, counted in a coverage note;
- `read(fileName, bytes)`: the module, or null when the file holds none;
- `moduleName(fileName)`: the module's identity, the name the form is called by (`CALL_FORM`, `OPEN_FORM`). By default
  it is the file name without the reader's ending, in upper case; a reader whose files are named otherwise overrides
  it. A file whose name is blank is not indexed.

List the class in `META-INF/services/com.felipebz.zpa.atlas.forms.FormsModuleReader` in its JAR, and put the JAR in the
`readers` folder of the distribution (`atlas-cli/build/install/zpa-atlas/readers/`). Two readers that claim the same
file names stop `index` and `serve` at start.

## Documentation

The documents in `docs/` explain the concepts and what to do with the answers, for the people who use the page and
run the server. They do not list what Atlas covers or how it decides each case: the tests say that, and the coverage
notes say it for each index. The agents read the tools' own descriptions, not these documents.
