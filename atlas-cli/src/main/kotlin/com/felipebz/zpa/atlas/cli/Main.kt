//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.felipebz.zpa.atlas.cli.oracle.LoadedMetadata
import com.felipebz.zpa.atlas.cli.oracle.OracleMetadataLoader
import com.felipebz.zpa.atlas.cli.oracle.OracleSettings
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.ConditionalCompilationText
import com.felipebz.zpa.atlas.index.DatabaseVersionOrigin
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.index.SourceDecoding
import com.felipebz.zpa.atlas.index.SourceIdentifiers
import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.server.MCP_LOCAL_HOSTS
import com.felipebz.zpa.atlas.server.McpSettings
import com.felipebz.zpa.atlas.stats.Stats
import com.felipebz.zpa.squid.CcValue
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.DatabaseVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

private fun index(args: IndexArguments, environment: (String) -> String?): Int {
    val sources = Path.of(args.sources)
    if (!Files.isDirectory(sources)) throw CliValidationException("Sources folder does not exist or is not a directory: ${args.sources}")
    if (args.threads < 1) throw CliValidationException("--threads must be at least 1")
    val encoding = try {
        SourceDecoding.validate(args.encoding.trim())
    } catch (e: IllegalArgumentException) {
        throw CliValidationException(e.message ?: "Unknown encoding")
    }
    val explicitVersion = args.databaseVersion.takeIf { it.isNotBlank() }?.let {
        validated("--database-version") { ConditionalCompilationSettings.parseVersion(it) }
    }
    val flags = validated("--ccflags") { ConditionalCompilationSettings.parseFlags(args.ccflags) }
    val settings = OracleSettings.from(args.oracle, environment)
    val compilationSchema = OracleSettings.compilationSchema(args)
    val oracle = settings?.let {
        val writtenNames = SourceIdentifiers.scan(sources, args.extensions.split(','))
        it to OracleMetadataLoader.load(it, compilationSchema, applicationHome().resolve("drivers"), writtenNames)
    }
    val dictionary = oracle?.let { (oracleSettings, loaded) ->
        val schemas = oracleSettings.schemas.toSet() + oracleSettings.relations.map { relation -> relation.first }
        AtlasDictionary.lookupOnly(loaded.result.metadata, schemas, loaded.synonyms, loaded.objects)
    }
        ?: AtlasDictionary.NONE
    val (conditionalCompilation, databaseVersionOrigin) = conditionalCompilation(explicitVersion, flags, oracle)
    if (conditionalCompilation.isActive) System.err.println(ConditionalCompilationText.describe(conditionalCompilation, databaseVersionOrigin))
    val summary = Indexer().index(
        IndexOptions(
            sources = sources,
            output = Path.of(args.out),
            extensions = args.extensions.split(',').map { it.trim() },
            compilationSchema = compilationSchema,
            dictionary = dictionary,
            threads = args.threads,
            encoding = encoding,
            conditionalCompilation = conditionalCompilation,
            databaseVersionOrigin = databaseVersionOrigin
        )
    )
    System.err.println("Indexed ${summary.files} files into ${args.out}: scan ${summary.parseMillis} ms, write ${summary.writeMillis} ms, total ${summary.totalMillis} ms")
    if (summary.failedFiles > 0) System.err.println("${summary.failedFiles} file(s) failed extraction and were indexed as FAILED (see stats)")
    return 0
}

/** The result of [parse], or a [CliValidationException] naming [option] with the parser's message. */
private fun <T> validated(option: String, parse: () -> T): T = try {
    parse()
} catch (e: IllegalArgumentException) {
    throw CliValidationException("$option: ${e.message}")
}

/**
 * The conditional compilation settings of a run and the origin of their database version. An explicit
 * `--database-version` wins over the version of the [oracle] database, with a warning when they differ; without one,
 * the database's version is used, with a warning when it only comes from the JDBC driver. Neither gives no version.
 */
private fun conditionalCompilation(
    explicitVersion: DatabaseVersion?,
    flags: Map<String, CcValue>,
    oracle: Pair<OracleSettings, LoadedMetadata>?
): Pair<ConditionalCompilationSettings, DatabaseVersionOrigin?> {
    val origin = explicitVersion?.let { DatabaseVersionOrigin.OPTION }
    val (settings, loaded) = oracle ?: return ConditionalCompilationSettings(flags, explicitVersion) to origin
    val detected = loaded.databaseVersion
    val detectedText = ConditionalCompilationText.version(detected.version)
    if (explicitVersion != null) {
        if (detected.version != explicitVersion) {
            val explicitText = ConditionalCompilationText.version(explicitVersion)
            val source = if (detected.origin == DatabaseVersionOrigin.JDBC) "the JDBC driver" else "DBMS_DB_VERSION"
            System.err.println("Warning: --database-version $explicitText differs from the version of the database, $detectedText (from $source); using $explicitText")
        }
        return ConditionalCompilationSettings(flags, explicitVersion) to origin
    }
    detected.failure?.let { failure ->
        System.err.println("Warning: DBMS_DB_VERSION could not be read (${OracleMetadataLoader.redact(failure.message, settings)}); using the JDBC " +
            "driver's database version $detectedText. The JDBC minor version can differ from DBMS_DB_VERSION.RELEASE (on 23ai and later " +
            "it is the release update, e.g. 23.26 against 23.0): set --database-version if it does")
    }
    return ConditionalCompilationSettings(flags, detected.version) to detected.origin
}

private fun serve(args: ServeArguments): Int {
    val database = Path.of(args.db)
    if (!Files.isRegularFile(database)) throw CliValidationException("Index file does not exist: ${args.db}")
    if (args.port !in 1..65535) throw CliValidationException("--port must be between 1 and 65535")
    val sources = if (args.sources.isNotBlank()) {
        Path.of(args.sources).also { if (!Files.isDirectory(it)) throw CliValidationException("Sources folder does not exist: ${args.sources}") }
    } else {
        Path.of(AtlasIndex(database, null).run().result.sourcesRoot).takeIf { Files.isDirectory(it) }
    }
    val mcpHosts = args.mcpAllowedHosts.map { it.trim() }.filter { it.isNotEmpty() }
    if (mcpHosts.any { it.any { c -> c in "/@?#:" && !(it.startsWith("[") && it.endsWith("]")) } }) {
        throw CliValidationException("--mcp-allowed-host takes host names or addresses, without scheme or port")
    }
    System.err.println("Serving ${args.db} on http://${args.host}:${args.port}/ (sources: ${sources ?: "not available"})")
    System.err.println("MCP at http://${args.host}:${args.port}/mcp, for Host ${(MCP_LOCAL_HOSTS + mcpHosts).distinct().joinToString(", ")}")
    if (args.host !in MCP_LOCAL_HOSTS && mcpHosts.isEmpty()) {
        System.err.println("Warning: /mcp accepts only local host names; clients on other machines need --mcp-allowed-host with the name they use")
    }
    com.felipebz.zpa.atlas.server.serve(AtlasIndex(database, sources), args.host, args.port, McpSettings(allowedHosts = mcpHosts))
    return 0
}

private fun stats(args: StatsArguments): Int {
    if (args.database.size != 1) throw CliValidationException("stats takes exactly one index file")
    val database = Path.of(args.database.single())
    if (!Files.isRegularFile(database)) throw CliValidationException("Index file does not exist: $database")
    AtlasIndex(database, null).run() // refuses an index of another schema version
    print(Stats.report(database))
    return 0
}

fun applicationHome(): Path {
    val codePath = Path.of(Indexer::class.java.protectionDomain.codeSource.location.toURI())
    return if (codePath.extension == "jar" && codePath.parent.name == "lib") codePath.parent.parent.toAbsolutePath() else Path.of(".")
}

fun execute(args: Array<String>, environment: (String) -> String? = System::getenv): Int {
    val main = MainArguments()
    val indexArguments = IndexArguments()
    val statsArguments = StatsArguments()
    val serveArguments = ServeArguments()
    val commander = JCommander.newBuilder()
        .addObject(main)
        .addCommand(indexArguments)
        .addCommand(statsArguments)
        .addCommand(serveArguments)
        .programName("zpa-atlas")
        .build()
    return try {
        commander.parse(*args)
        if (main.help || commander.parsedCommand == null) {
            val usage = StringBuilder()
            commander.usage(usage)
            println(usage)
            return if (main.help) 0 else 2
        }
        when (commander.parsedCommand) {
            "index" -> index(indexArguments, environment)
            "serve" -> serve(serveArguments)
            else -> stats(statsArguments)
        }
    } catch (exception: ParameterException) {
        System.err.println(exception.message)
        val usage = StringBuilder()
        commander.usage(usage)
        System.err.println(usage)
        2
    } catch (exception: com.felipebz.zpa.atlas.query.SchemaMismatchException) {
        System.err.println(exception.message)
        2
    } catch (exception: CliValidationException) {
        System.err.println(exception.message)
        2
    } catch (exception: CliExecutionException) {
        System.err.println(exception.message)
        3
    } catch (exception: Exception) {
        System.err.println("Execution failed: ${exception.message}")
        exception.printStackTrace(System.err)
        3
    }
}

fun main(args: Array<String>) {
    val exitCode = execute(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
