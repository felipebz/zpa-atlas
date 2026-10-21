# syntax=docker/dockerfile:1

# zpa-atlas: `docker run` it with an index in /data and the sources in /sources; see docs/container.md.

# The build runs on the builder's own platform: the distribution is plain JARs, and the Gradle Node plugin downloads
# Node's glibc build for the web page.
FROM --platform=$BUILDPLATFORM docker.io/bellsoft/liberica-openjdk-debian:25@sha256:213512c11989ebdcf464c475603f6381af2a447338a2a04381c14b6e786d8c94 AS build
WORKDIR /src
COPY . .
# Empty: the version of gradle.properties. A release passes its own.
ARG VERSION=
# zpa-core and zpa-oracle-metadata are snapshots: resolve the latest published build, as the Build workflow does.
RUN --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon --refresh-dependencies ${VERSION:+-Pversion=$VERSION} :atlas-cli:installDist

# The user, without running anything on the target platform: its passwd and group are the runtime image's plus one line.
COPY --from=docker.io/bellsoft/liberica-runtime-container:jre-25-musl@sha256:803455b2383747e0848a47e196bfed65ba99e96d29c8a61e713b10060e620db0 /etc/passwd /etc/group /users/
RUN echo 'atlas:x:10001:10001:zpa-atlas:/data:/sbin/nologin' >> /users/passwd \
    && echo 'atlas:x:10001:' >> /users/group \
    && mkdir -p /folders/data /folders/sources /folders/drivers

FROM docker.io/bellsoft/liberica-runtime-container:jre-25-musl@sha256:803455b2383747e0848a47e196bfed65ba99e96d29c8a61e713b10060e620db0

ARG VERSION=
ARG REVISION=
LABEL org.opencontainers.image.title="zpa-atlas" \
      org.opencontainers.image.description="Index of PL/SQL code: a web page, a JSON API and an MCP server over it" \
      org.opencontainers.image.source="https://github.com/felipebz/zpa-atlas" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.authors="Felipe Zorzo" \
      org.opencontainers.image.vendor="Felipe Zorzo" \
      maintainer="Felipe Zorzo" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

COPY --from=build /users/passwd /users/group /etc/
COPY --from=build /src/atlas-cli/build/install/zpa-atlas /opt/zpa-atlas
COPY --from=build --chown=10001:10001 /folders/data /data
COPY --from=build /folders/sources /sources
COPY --from=build /folders/drivers /opt/zpa-atlas/drivers

ENV ZPA_ATLAS_HOST=0.0.0.0 \
    ZPA_ATLAS_DB=/data/atlas.db \
    ZPA_ATLAS_SOURCES=/sources

USER 10001:10001
WORKDIR /data
# The index: kept apart from the container, and writable when the container's file system is read-only.
VOLUME ["/data"]
EXPOSE 8080

# On the port the server listens on: ZPA_ATLAS_PORT when it is set.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${ZPA_ATLAS_PORT:-8080}/health" || exit 1

# The start script ends in `exec java`: java is PID 1 and gets the stop signal.
ENTRYPOINT ["/opt/zpa-atlas/bin/zpa-atlas"]
CMD ["serve"]
