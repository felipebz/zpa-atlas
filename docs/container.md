# Running in a container

The image `ghcr.io/felipebz/zpa-atlas` runs `zpa-atlas` without a JDK on the host. Its entry point is the `zpa-atlas`
command, so `docker run IMAGE index …` and `docker run IMAGE stats …` work, and with no command it runs `serve`.

| In the image          | What                                                                                            |
|-----------------------|-------------------------------------------------------------------------------------------------|
| `/data`               | The index, `/data/atlas.db` by default. A volume, owned by the image's user.                    |
| `/sources`            | The sources, mounted read-only.                                                                 |
| `/opt/zpa-atlas/drivers` | The Oracle JDBC driver, for `index` with the dictionary. Not bundled.                        |
| User                  | `atlas`, UID 10001 and GID 10001.                                                               |
| Port                  | 8080.                                                                                           |

`serve` reads every option from an environment variable too: `ZPA_ATLAS_` and the option's name, in upper case with `_`
for `-`, such as `ZPA_ATLAS_DB` for `--db`. The command line wins, and a blank variable counts as unset. The image sets
`ZPA_ATLAS_HOST=0.0.0.0`, `ZPA_ATLAS_DB=/data/atlas.db` and `ZPA_ATLAS_SOURCES=/sources`. `index` and `stats` read no
variables but `ZPA_ORACLE_PASSWORD`, so their options are given on the command line.

## Index in the container

The sources are mounted read-only, and the index is written to a folder of the host. The folder must be writable by
the user the container runs as: run it as yourself, as below, or give the folder to UID 10001
(`sudo chown 10001:10001 data`) and leave `--user` out. `--no-healthcheck` because the image's health check asks a server, and
`index` runs none: a long run would show as `unhealthy`.

```sh
mkdir -p data
docker run --rm --no-healthcheck --user "$(id -u):$(id -g)" \
  -v "$PWD/path/to/sources:/sources:ro" -v "$PWD/data:/data" \
  ghcr.io/felipebz/zpa-atlas index --sources /sources --out /data/atlas.db --compilation-schema HR
docker run --rm --no-healthcheck -v "$PWD/data:/data:ro" ghcr.io/felipebz/zpa-atlas stats /data/atlas.db
```

With the Oracle dictionary, mount the folder that holds the JDBC driver JAR (see the README) and pass the password in
`ZPA_ORACLE_PASSWORD`:

```sh
docker run --rm --no-healthcheck --user "$(id -u):$(id -g)" -e ZPA_ORACLE_PASSWORD \
  -v "$PWD/drivers:/opt/zpa-atlas/drivers:ro" \
  -v "$PWD/path/to/sources:/sources:ro" -v "$PWD/data:/data" \
  ghcr.io/felipebz/zpa-atlas index --sources /sources --out /data/atlas.db --compilation-schema HR \
  --oracle-url jdbc:oracle:thin:@//host:1521/SERVICE --oracle-user HR --oracle-schemas HR
```

## Serve with no authentication

Without the OIDC variables there is no authentication. The server binds `0.0.0.0` inside the container, so the host
decides who reaches it: publish the port on loopback only.

```sh
docker run -d --name atlas --read-only --tmpfs /tmp:exec -p 127.0.0.1:8080:8080 \
  -v "$PWD/data:/data" -v "$PWD/path/to/sources:/sources:ro" \
  ghcr.io/felipebz/zpa-atlas
```

Open http://127.0.0.1:8080. `--read-only` keeps the image's files unchanged; `/data` stays writable as a volume.
`/tmp` needs `exec`: the SQLite driver copies its native library to `/tmp` and loads it from there, and Docker mounts a
tmpfs `noexec` unless told otherwise.

## Serve with OpenID Connect

Set the options of [authentication](authentication.md) as variables. The issuer and the public URL must be `https`
off localhost, so in practice the container sits behind a TLS proxy that passes the `Host` header, and the public URL
is the proxy's address. Its host is accepted by `/mcp`; give `ZPA_ATLAS_MCP_ALLOWED_HOST` (comma-separated) for any
other name clients use.

```sh
cat > atlas.env <<'EOF'
ZPA_ATLAS_OIDC_ISSUER=https://id.example.com/realms/atlas
ZPA_ATLAS_PUBLIC_URL=https://atlas.example.com
ZPA_ATLAS_OIDC_SCOPE=atlas.read
ZPA_ATLAS_OIDC_CLIENT_ID=atlas-web
ZPA_ATLAS_OIDC_CLIENT_SECRET=...
EOF
docker run -d --name atlas --read-only --tmpfs /tmp:exec -p 127.0.0.1:8080:8080 --env-file atlas.env \
  -v "$PWD/data:/data" -v "$PWD/path/to/sources:/sources:ro" \
  ghcr.io/felipebz/zpa-atlas
```

`ZPA_ATLAS_OIDC_CLIENT_SECRET` is where `serve` looks for the page client's secret unless `--oidc-client-secret-env`
names another variable; without it, the page client is public.

The page's sessions live in memory: run one container, and know that a restart signs everyone out.

## Re-index while serving

`index` writes a new file and renames it over the old one, and the server opens the index for each request, so the
next request reads the new index, without a restart. Run `index` into the same folder while the server runs:

```sh
docker run --rm --no-healthcheck --user "$(id -u):$(id -g)" \
  -v "$PWD/path/to/sources:/sources:ro" -v "$PWD/data:/data" \
  ghcr.io/felipebz/zpa-atlas index --sources /sources --out /data/atlas.db --compilation-schema HR
curl -s http://127.0.0.1:8080/api/run
```

`run.finishedAt` changes to the new run.

## Memory

The JVM sizes its heap from the container's memory limit, 25 % of it by default. For a large index, raise the limit
and the share, or set the heap, with `JAVA_OPTS`:

```sh
docker run -d --name atlas -m 4g -e JAVA_OPTS="-XX:MaxRAMPercentage=75" -p 127.0.0.1:8080:8080 \
  -v "$PWD/data:/data" -v "$PWD/path/to/sources:/sources:ro" ghcr.io/felipebz/zpa-atlas
```

## Health

`GET /health` (or `HEAD`) answers 200 when the index opens and has the schema version the server reads, and 503
otherwise. It needs no token or session. The image's `HEALTHCHECK` calls it, on `ZPA_ATLAS_PORT` when that is set, so
`docker ps` shows the container as healthy. Change the port with `ZPA_ATLAS_PORT`, not with `--port`: the health
check reads only the variable, and with the option it keeps asking 8080 and the container never becomes healthy.

```sh
curl -i http://127.0.0.1:8080/health
```

## Published tags

A `v1.2.3` tag publishes `1.2.3`, `1.2` and `latest`; a pre-release such as `v1.0.0-rc.1` publishes `1.0.0-rc.1` and
leaves `latest` alone. A manual run of the workflow publishes the version it is given and moves `latest` to it, even
when that version is older than the current one.

## Building the image

```sh
docker build -t zpa-atlas .
docker buildx build --platform linux/amd64,linux/arm64 -t zpa-atlas .
```

The build runs Gradle on the builder's platform; only files are copied into the image of the other platform, so no
emulator is needed.
