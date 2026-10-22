//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.beust.jcommander.ParametersDelegate

/** The Oracle connection options, shared by every command that reads the database (same names and rules as zpa-cli). */
class OracleOptions {
    @Parameter(names = ["--oracle-url"], description = "JDBC URL of an Oracle database to read schema metadata from (enables Oracle metadata acquisition)")
    var oracleUrl: String = ""

    @Parameter(names = ["--oracle-user"], description = "Database user for --oracle-url")
    var oracleUser: String = ""

    @Parameter(names = ["--oracle-password-env"], description = "Name of the environment variable holding the database password")
    var oraclePasswordEnv: String = "ZPA_ORACLE_PASSWORD"

    @Parameter(names = ["--oracle-schemas"], description = "Comma-separated schemas to read completely (case-insensitive, e.g. hr; \"Name\" for an exact case)")
    var oracleSchemas: String = ""

    @Parameter(names = ["--oracle-relations"], description = "Comma-separated SCHEMA.RELATION names to read individually (case-insensitive; \"Name\" for an exact case)")
    var oracleRelations: String = ""
}

class MainArguments {
    @Parameter(names = ["--help", "-h"], help = true, description = "Display help information")
    var help: Boolean = false
}

@Parameters(commandNames = ["index"], commandDescription = "Index a source tree into a new SQLite database")
class IndexArguments {
    @Parameter(
        names = ["--project"],
        description = "Project file <name>.yaml whose keys give the options below (but --threads); an option given beside it wins over its key"
    )
    var project: String = ""

    @Parameter(names = ["--sources"], description = "Folder with files", required = true)
    var sources: String = ""

    @Parameter(names = ["--out"], description = "Index database to create (replaced atomically)", required = true)
    var out: String = ""

    @Parameter(names = ["--extensions"], description = "Extensions to analyze")
    var extensions: String = "sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps"

    @Parameter(names = ["--encoding"], description = "Source encoding: auto (per file, strict UTF-8 else windows-1252) or a charset name forced for every file")
    var encoding: String = "auto"

    @Parameter(names = ["--threads"], description = "Number of files parsed concurrently")
    var threads: Int = Runtime.getRuntime().availableProcessors()

    @ParametersDelegate
    val oracle = OracleOptions()

    @Parameter(names = ["--compilation-schema"], description = "Schema in which analyzed units without an explicit schema compile (case-insensitive; \"Name\" for an exact case)")
    var compilationSchema: String = ""

    @Parameter(names = ["--database-version"], description = "Target database version for DBMS_DB_VERSION in conditional compilation, e.g. 19 or 11.2 (default: the version of --oracle-url, else none)")
    var databaseVersion: String = ""

    @Parameter(names = ["--ccflags"], description = "PLSQL_CCFLAGS for conditional compilation, e.g. 'debug:TRUE, level:2'")
    var ccflags: String = ""
}

@Parameters(commandNames = ["stats"], commandDescription = "Print a summary of an index database")
class StatsArguments {
    @Parameter(description = "<file.db>", required = true)
    var database: List<String> = ArrayList()
}

@Parameters(
    commandNames = ["serve"],
    commandDescription = "Serve the web UI, the JSON API and the MCP endpoint (/mcp) of an index. Without --oidc-issuer there is no " +
        "authentication: binding to an address other than 127.0.0.1 exposes the index and the source files to the network. With it, " +
        "/mcp and /api accept only access tokens of that OpenID Connect provider. Every option can also come from the environment " +
        "variable named next to it (ZPA_ATLAS_ and the option's name); the command line wins, a blank variable is unset"
)
class ServeArguments {
    @Parameter(names = ["--db"], description = "Index database to serve [env ZPA_ATLAS_DB]", required = true)
    var db: String = ""

    @Parameter(names = ["--sources"], description = "Folder of the indexed sources (default: the folder recorded in the index, when it exists) [env ZPA_ATLAS_SOURCES]")
    var sources: String = ""

    @Parameter(names = ["--host"], description = "Address to bind; anything but 127.0.0.1 exposes the index to the network [env ZPA_ATLAS_HOST]")
    var host: String = "127.0.0.1"

    @Parameter(names = ["--port"], description = "Port to listen on [env ZPA_ATLAS_PORT]")
    var port: Int = 8080

    @Parameter(
        names = ["--base-path"],
        description = "The path to serve everything under, such as /atlas, behind a reverse proxy that passes the path unchanged " +
            "(default: the root). Nothing outside it is answered [env ZPA_ATLAS_BASE_PATH]"
    )
    var basePath: String = ""

    @Parameter(
        names = ["--mcp-allowed-host"],
        description = "A host name /mcp accepts besides localhost, 127.0.0.1 and [::1]: the name or address clients on other machines " +
            "use to reach this server (comma-separated or repeated). /mcp refuses any other Host, against DNS rebinding [env ZPA_ATLAS_MCP_ALLOWED_HOST]"
    )
    var mcpAllowedHosts: List<String> = ArrayList()

    @Parameter(
        names = ["--oidc-issuer"],
        description = "Issuer URL of an OpenID Connect provider: turns authentication on. /mcp and /api then need an access token of that provider " +
            "(the server reads its discovery document at startup and refuses to start if it cannot); needs --public-url [env ZPA_ATLAS_OIDC_ISSUER]"
    )
    var oidcIssuer: String = ""

    @Parameter(
        names = ["--public-url"],
        description = "The server's address as clients reach it, such as https://atlas.example.com (no path but --base-path, as in " +
            "https://atlas.example.com/atlas; http only for localhost). " +
            "Required with --oidc-issuer: the resource is this plus /mcp, and its host is accepted by /mcp [env ZPA_ATLAS_PUBLIC_URL]"
    )
    var publicUrl: String = ""

    @Parameter(
        names = ["--oidc-audience"],
        description = "The value an access token must carry in aud (default: the resource, <public url>/mcp). Never the id of a client that signs users in [env ZPA_ATLAS_OIDC_AUDIENCE]"
    )
    var oidcAudience: String = ""

    @Parameter(
        names = ["--oidc-scope"],
        description = "A scope an access token must carry, in scope or scp (default: none required); a token without it gets 403 [env ZPA_ATLAS_OIDC_SCOPE]"
    )
    var oidcScope: String = ""

    @Parameter(
        names = ["--oidc-client-id"],
        description = "The page's client id at the provider: turns the page sign-in on (redirect URI <public url>/auth/callback). Needs --oidc-issuer; never the audience [env ZPA_ATLAS_OIDC_CLIENT_ID]"
    )
    var oidcClientId: String = ""

    @Parameter(
        names = ["--oidc-client-secret-env"],
        description = "Name of the environment variable holding the page client's secret (default: ZPA_ATLAS_OIDC_CLIENT_SECRET; when the " +
            "default is unset the client is public, with PKCE; a variable named here must be set) [env ZPA_ATLAS_OIDC_CLIENT_SECRET_ENV]"
    )
    var oidcClientSecretEnv: String = ""
}
