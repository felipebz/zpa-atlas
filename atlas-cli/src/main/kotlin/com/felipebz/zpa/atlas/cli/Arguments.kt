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
    commandDescription = "Serve the web UI and the JSON API of an index (no authentication: binding to an address other " +
        "than 127.0.0.1 exposes the index and the source files to the network)"
)
class ServeArguments {
    @Parameter(names = ["--db"], description = "Index database to serve", required = true)
    var db: String = ""

    @Parameter(names = ["--sources"], description = "Folder of the indexed sources (default: the folder recorded in the index, when it exists)")
    var sources: String = ""

    @Parameter(names = ["--host"], description = "Address to bind; anything but 127.0.0.1 exposes the index to the network")
    var host: String = "127.0.0.1"

    @Parameter(names = ["--port"], description = "Port to listen on")
    var port: Int = 8080
}
