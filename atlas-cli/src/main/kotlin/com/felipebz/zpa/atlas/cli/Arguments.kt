package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

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

    @Parameter(names = ["--threads"], description = "Number of files parsed concurrently")
    var threads: Int = Runtime.getRuntime().availableProcessors()

    @Parameter(names = ["--oracle-url"], description = "JDBC URL of an Oracle database to read schema metadata from (enables Oracle metadata acquisition)")
    var oracleUrl: String = ""

    @Parameter(names = ["--oracle-user"], description = "Database user for --oracle-url")
    var oracleUser: String = ""

    @Parameter(names = ["--oracle-password-env"], description = "Name of the environment variable holding the database password")
    var oraclePasswordEnv: String = "ZPA_ORACLE_PASSWORD"

    @Parameter(names = ["--oracle-schemas"], description = "Comma-separated schemas to read completely (exact dictionary names, e.g. HR)")
    var oracleSchemas: String = ""

    @Parameter(names = ["--oracle-relations"], description = "Comma-separated SCHEMA.RELATION names to read individually (exact dictionary names)")
    var oracleRelations: String = ""

    @Parameter(names = ["--compilation-schema"], description = "Schema in which analyzed units without an explicit schema compile (exact dictionary name)")
    var compilationSchema: String = ""
}

@Parameters(commandNames = ["stats"], commandDescription = "Print a summary of an index database")
class StatsArguments {
    @Parameter(description = "<file.db>", required = true)
    var database: List<String> = ArrayList()
}
