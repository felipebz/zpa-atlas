# Serving

`zpa-atlas serve` serves an index as a web page, a JSON API and an MCP endpoint for agents.

```sh
zpa-atlas serve --db <file.db> [--sources <dir>] [--port 8080]
zpa-atlas serve --projects <folder> [--default-project <name>]
```

| Option | What it does |
|---|---|
| `--db` | The index to serve. |
| `--sources` | Where the page reads the code from. Default: the folder recorded in the index. |
| `--projects` | A folder of project files, served together: see [Serving several projects](#serving-several-projects). |
| `--host`, `--port` | Where to listen. Default: `127.0.0.1`, `8080`. |
| `--tab-width` | How wide the page shows a tab: 2, 4 or 8 (default). A project file's `tabWidth` wins. |
| `--base-path` | Serve everything under a path, such as `/atlas`: see [Serving under a base path](#serving-under-a-base-path). |
| `--mcp-allowed-host` | More host names `/mcp` accepts, besides `localhost`, `127.0.0.1` and `[::1]`. |
| `--oidc-…`, `--public-url` | Authentication; without them there is none. See [Authentication](authentication.md). |

- Every option can also come from the environment: `ZPA_ATLAS_` and the option's name in upper case, with `_` for `-`
  (`ZPA_ATLAS_DB` for `--db`). The command line wins.
- **Without authentication, anyone who reaches the port reads the index and the sources.** Keep the default host,
  `127.0.0.1`, or set up [authentication](authentication.md).
- Exit codes: 0 success, 2 invalid usage, 3 execution failure.
- In a container: see [Running in a container](container.md).

Open http://127.0.0.1:8080 for the page; [What Atlas answers](answers.md) shows what it holds.

## Agents (MCP)

The server answers MCP at `/mcp` (Streamable HTTP), with read-only tools that give the same answers as the page. Add
`http://127.0.0.1:8080/mcp` to your MCP client as an HTTP server; each tool describes itself to the agent.

`/mcp` accepts only requests addressed to a known host name, to guard against DNS rebinding. When clients reach the
server by another name, give it with `--mcp-allowed-host`. With authentication, the agents sign in too: see
[Connect an agent](authentication.md#3-connect-an-agent).

## Serving under a base path

Several applications can share one host behind a reverse proxy, each under its own path. `--base-path /atlas` serves
everything under `/atlas/`: the pages, `/atlas/api/…`, `/atlas/mcp` and `/atlas/health`.

**The proxy must pass the path unchanged:** the server expects `/atlas/api/run`, not `/api/run`. With nginx:

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

Pass the `Host` header, and name the host with `--mcp-allowed-host` (or with `--public-url`, when authentication is
on). With authentication, `--public-url` includes the base path, such as `https://atlas.example.com/atlas`; see
[Run the server](authentication.md#2-run-the-server).

## Serving several projects

`serve --projects <folder>` serves every [project file](indexing.md#project-files) in the folder, each with its own
index and sources. The folder is read at startup: adding or removing a project takes a restart, but re-indexing one
does not.

| URL | What |
|---|---|
| `/` | The list of projects, with the date of each index. |
| `/p/<name>/` | The project's pages, with a picker to switch project. |
| `/mcp` | One MCP endpoint for every project: each tool takes the project's name. |
| `/health` | 200 when the index of every indexed project opens. |

- A project not indexed yet is listed, and its pages answer once `index --project` has written its index.
- `--default-project <name>` lets agents leave the project out; every answer then says which project it read, since a
  fork shares most names with its origin and a wrong one would look right.
- With authentication, one sign-in covers every project.
