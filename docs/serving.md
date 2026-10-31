# Serving

`zpa-atlas serve` serves an index as a web page, a JSON API and an MCP endpoint for agents.

```sh
zpa-atlas serve --projects <folder> [--default-project <name>] [--tab-width 2|4|8] [--host 127.0.0.1] [--port 8080] [--base-path /atlas] [--mcp-allowed-host <name>,...] [authentication options as below]
zpa-atlas serve --db <file.db> [--sources <dir>] [--tab-width 2|4|8] [--host 127.0.0.1] [--port 8080] [--base-path /atlas] [--mcp-allowed-host <name>,...] \
  [--oidc-issuer URL --public-url URL [--oidc-audience VALUE] [--oidc-scope SCOPE] [--oidc-client-id ID [--oidc-client-secret-env VAR]]]
```

| Option               | Default                                                                                          |
|----------------------|--------------------------------------------------------------------------------------------------|
| `--host`             | `127.0.0.1`                                                                                      |
| `--port`             | `8080`                                                                                           |
| `serve --sources`    | the folder recorded in the index                                                                 |
| `--tab-width`        | `8`: how wide the page shows a tab, 2, 4 or 8. With `--projects`, for the projects whose file gives no `tabWidth` |
| `--base-path`        | none: everything at the root. With `/atlas`, everything under `/atlas/` and nothing outside it   |
| `--mcp-allowed-host` | none: `/mcp` accepts only the `Host` names `localhost`, `127.0.0.1` and `[::1]`                  |
| `--oidc-issuer`      | none: no authentication. With it, `/mcp` and `/api` need an access token of that provider        |
| `--public-url`       | none; required with `--oidc-issuer`: the server as clients reach it, such as `https://atlas.example.com`, plus the base path when there is one |
| `--oidc-audience`    | the resource, `<public url>/mcp`: what an access token must carry in `aud`                       |
| `--oidc-scope`       | none: a scope an access token must carry, in `scope` or `scp`                                    |
| `--oidc-client-id`   | none: the page has no sign-in. With it, people sign in to the page with the provider             |
| `--oidc-client-secret-env` | `ZPA_ATLAS_OIDC_CLIENT_SECRET`, optional: unset, the page client is public. The variable holding its secret |

- Exit codes: 0 success, 2 invalid usage, 3 execution failure.
- Every option of `serve` can also come from the environment: `ZPA_ATLAS_` and the option's name in upper case, `-` as `_` (`ZPA_ATLAS_DB` for `--db`). The command line wins; a blank variable is unset. `index` and `stats` read no such variables.
- In a container: see [container.md](container.md).
- Without `--oidc-issuer`, `serve` has no authentication: binding to anything but 127.0.0.1 exposes the index and the sources to the network. With it, follow [authentication.md](authentication.md) to set up the provider, the page and the agents.
- `serve` opens the index for each request only, so `index` can replace the file while the server runs.
- Under a path, behind a reverse proxy: see [Serving under a base path](#serving-under-a-base-path).

## The page

The top bar opens **Go to object…** (`Ctrl+K`, `⌘K` on a Mac, or `/` outside a text field) and three pages:

- **Objects**, the home page: search by name or dotted name (e.g. `pack.run_job`); with no search, the objects
  opened last in this browser, the project's packages most used from outside them (by other units and by scripts, not
  by their own spec or body), and an example for each page.
- **Uses**: pick an object and filter its occurrences by role and status. With no query, a "Try" list gives three
  queries of the index served (calls to a routine, a package's uses grouped by program, the writes to a table), each a
  link that opens the query run.
- **Code**: find the files and lines that hold a piece of text, grouped by file, each line colored as the file page
  colors it and aligned on its first character, the container named once above each run of lines in it. With no
  search, a "Try" list gives texts taken from the index served.

The index date in the bar opens what the index holds; when files failed to parse or were recovered, a note beside it
lists them. An entity page shows its definition, contents, relations, triggers, the views reading it, and "Used by",
filterable by role and status, with the source line. A package's or type's "Used by" lists the uses from other programs
first, grouped by calling program, and switches to the uses inside its own spec and body. Its "Impact" link opens what
a change to the entity reaches, up to the entry points (the code nothing in the index calls), grouped by package, with
the path behind each one and a CSV export.

The header of a function says its return type, and a variable's, constant's, record field's or cursor's says its type
and initial value, as written in the source (see [What a declaration says](#what-a-declaration-says)). A routine's
parameter table has a "Default" column when one of its parameters has a default.

Under the header, the entity's documentation comment ([A declaration's documentation](#a-declarations-documentation))
shows as written, its line breaks and indentation kept, with a link to its line; a long one folds after six lines,
with "more". A routine's parameter table shows each parameter's documentation in a row under it, across the table:
its first line, with "more" leading to the parameter's page when there is more.

A file page shows the whole file, colored the way zpa's own highlighter colors it (keywords, literals, comments and
documentation comments, from zpa's lexer, with the run's conditional compilation), each indexed name a link to a
popover with its status and where it is declared.

The file page and the Code page show a tab as wide as the project's `tabWidth` (or `--tab-width`, 8 by default); the
page reads it from `/api/run`, whose answer has `tabWidth` beside `run` and `result`. "Tab width: 2 · 4 · 8", in the
file page's header and beside the Code page's total, picks another for this browser and project; picking the
project's width again follows the project's from then on. Where the browser cannot keep it (some private windows),
the choice holds until the page is left. Beside the code (above it, closed, on a phone), "In this file" lists
the objects the file declares and their members; an entry moves the code to its line.

## Agents (MCP)

The server answers MCP at `/mcp` (Streamable HTTP), with read-only tools over the same answers as the web page.
`get_entity` answers what the entity's declaration says in `declared`, and each child's in its own `declared`:

| Field       | What it holds                                                                                          |
|-------------|--------------------------------------------------------------------------------------------------------|
| `type`      | A function's or cursor's return type, else the declared type (with `not null` when written).           |
| `value`     | A parameter's default, or a variable's, constant's or record field's initial value.                   |
| `valueCut`  | `true` when `value` was longer than 200 characters: it is cut and ends with `…`.                      |
| `valueFrom` | `SPEC` when a body's parameter has no default and `value` is the one its spec writes.                 |
| `specText`  | On a body that says otherwise than its spec (Oracle refuses such a pair): the spec's `type` and `value`. |

Its `documentation`, and each child's, lists the documentation comments shown on the page:

| Field      | What it holds                                                                                           |
|------------|---------------------------------------------------------------------------------------------------------|
| `text`     | The comment without its markers, separator lines or `--%` annotation lines; line breaks and indentation kept. |
| `textCut`  | `true` when the comment was longer than 4,000 characters: it is cut at a line break and ends with `…`. |
| `shape`    | Where it is: `ABOVE` the declaration, `AFTER_IS` or `AFTER_BEGIN`, or at the `END_OF_LINE`.             |
| `path`, `line` | The file and the comment's first line.                                                          |
| `from`     | Absent for the declaration's own; `SPEC` or `BODY` for the other half's.                               |

## What a declaration says

The index keeps a declaration's type and value as the source writes them, read from zpa's syntax tree: comments and
line breaks left out, spaces between two tokens made one. Nothing is resolved (`company.company_name%type` stays) or
evaluated (`sysdate + 1` stays). A value longer than 200 characters is cut and marked with `…`: the page's link
opens the file at the declaration, where the whole text is.

- **Spec and body:** each keeps its own text. A default the spec writes and the body leaves out is the body's too:
  the body's page shows it, saying it comes from the spec. When both write a type or a default and they differ
  (ignoring case), each shows its own, and the body says what the spec says. A function whose body returns another
  type than its spec's is not its body for zpa: it is listed as a private function of the package body.
- **Conditional compilation:** the text is the branch the run kept, with its `--database-version` and `--ccflags`
  ([indexing.md](indexing.md)). A declaration in a branch not kept is not in the index: no entity, no text. Without
  either option, zpa keeps the first branch of each `$IF`; with them, a condition they cannot decide (a package
  constant, say) keeps the first branch from that condition on.

## A declaration's documentation

A declaration's documentation is the first of these comment blocks, read from zpa's lexer (a block is a run of
comments with no blank line between them):

1. **Right above it**, with no blank line between: `/** */`, `/* */` and `--` alike. A comment that ends the previous
   line of code is that line's, never part of the block.
2. **For a package, a routine body or a standalone routine:** the first block after its `IS`/`AS`, then, for a routine,
   the first block after its `BEGIN`. Each counts only when a blank line follows it: a block glued to the next line
   comments that line. After a package's `IS`, a glued block is its first member's (rule 1).
3. **At the end of its own line**, after nothing but `,`, `;` or `)`: a parameter's, a variable's or a field's.

A block below a declaration is never its documentation. A block is never taken when it holds only `--%`
annotations or only separator lines (no letter or digit), has license words (copyright, license, licence, "(c)"),
comes before the file's first token, or reads as commented-out code (half its lines or more, read by zpa's lexer, end
with `;` or hold `:=`).

The text kept is the comment's words: without the markers (`--`, the opening and closing of a block comment, a
leading `*`), without separator lines and annotation lines, with its line breaks and its indentation (dedented
together, so the tables written with spaces keep their columns). A text longer than 4,000 characters is cut at a line
break and marked with `…`; the link opens the file at the comment.

**Spec and body:** each half keeps its own. A page shows its own half's comment, then the other half's, marked "From
the spec" or "From the body", when it says something else; when its own half has none, it shows the other half's.
Texts that differ only in case, spacing or line breaks are shown once. A package's spec and body are its two halves,
as are a routine's spec and its body, and a body's parameter and the spec's parameter of the same name.

## Serving under a base path

Several applications can share one host, each under its own path, behind one reverse proxy. `--base-path /atlas` (or
`ZPA_ATLAS_BASE_PATH=/atlas`) serves everything under `/atlas/`: the pages, `/atlas/api/…`, `/atlas/mcp`,
`/atlas/health`, and, with authentication, the sign-in routes and the metadata document. Any other path answers 404.
`/atlas` and `/atlas/` are the same base path, and it may have several segments (`/tools/atlas`). It must be a plain
path: a leading `/`, no `.` or `..` segment, no `//`, `%`, `?`, `#`, backslash, space or control character.

**The proxy passes the path unchanged:** the server receives `/atlas/api/run`, not `/api/run`. A proxy that strips the
prefix is not supported. The bare `/atlas` redirects to `/atlas/`, keeping the query string: the pages link relatively
and need the trailing slash. With nginx:

```nginx
# proxy_pass without a path: the request's path reaches the server as it came
location = /atlas {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $http_host;
}
location /atlas/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $http_host;
    proxy_http_version 1.1;
    # /atlas/mcp streams events: pass them as they come, and keep a quiet stream open
    proxy_buffering off;
    proxy_read_timeout 1h;
}
```

The server checks the `Host` header of `/mcp` requests: pass it, and name the host with `--mcp-allowed-host`, or with
`--public-url` when authentication is on. With authentication, `--public-url` carries the base path, such as
`https://atlas.example.com/atlas`; see [authentication.md](authentication.md#2-run-the-server).

## Serving several projects

`serve --projects <folder>` (or `ZPA_ATLAS_PROJECTS`) serves every project file directly in the folder, each from the
index and sources its file names; their other keys are checked and otherwise unused. The folder is read once, at
startup: adding or removing a project takes a restart.

| URL (under the base path) | What |
|---|---|
| `/` and `/api/projects` | The list of projects: name, date of the index, file count, or "not indexed yet" |
| `/p/<name>/` | The project's pages, with a picker to switch project |
| `/p/<name>/api/…` | The API of `/api` over the project's index; an unknown project answers 404 |
| `/health` | 200 when the index of every project that has one opens; one line per project |
| `/mcp` | The MCP endpoint over every project, picked by each tool's `project` argument |

- **Refused at startup:** a project file the reader refuses (every such file is listed), a folder without project files,
  and two projects with the same index file.
- **Warned at startup:** a project whose index does not exist yet (its API answers 503 until `index --project` writes
  it, without a restart), a project whose sources folder is not on this machine (served without sources), and a `.yml`
  file (not read: rename it to `.yaml`).
- **`--db` and `--sources`** beside `--projects` on the command line are refused. From the environment they are
  ignored, with a startup line: the container image sets them.
- **`--default-project <name>`** (or `ZPA_ATLAS_DEFAULT_PROJECT`, only with `--projects`) makes `project` optional
  on every tool: a call without it reads that project, and its answer starts with `"project": "<name>"` (an error ends
  with a sentence saying so). A fork shares most names with its origin, so an answer read from the wrong project would
  look right: saying which one it read lets the agent notice. `list_projects` and the list page mark the default.
- **The MCP endpoint** stays at `/mcp`, one for every project. Every tool takes a `project` argument, the project's
  name: required with several projects (a call without it is refused with the names), optional with one. The tool
  `list_projects` gives each project's name, state, index date and file count, and the instructions name the projects.
  A project not indexed yet is refused, as its API answers 503.
- **With authentication,** one gate covers `/mcp`, every project's API and the list, with the same token: a token reads
  every project. Sign-in stays at the base path.
