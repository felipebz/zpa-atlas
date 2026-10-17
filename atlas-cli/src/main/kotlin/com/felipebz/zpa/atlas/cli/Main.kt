//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.felipebz.zpa.atlas.cli.oracle.OracleMetadataLoader
import com.felipebz.zpa.atlas.cli.oracle.OracleSettings
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.index.SourceDecoding
import com.felipebz.zpa.atlas.index.SourceIdentifiers
import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.stats.Stats
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
    val settings = OracleSettings.from(args.oracle, environment)
    val compilationSchema = OracleSettings.compilationSchema(args)
    val dictionary = settings?.let {
        val schemas = it.schemas.toSet() + it.relations.map { relation -> relation.first }
        val writtenNames = SourceIdentifiers.scan(sources, args.extensions.split(','))
        val loaded = OracleMetadataLoader.load(it, compilationSchema, applicationHome().resolve("drivers"), writtenNames)
        AtlasDictionary.lookupOnly(loaded.result.metadata, schemas, loaded.synonyms, loaded.objects)
    }
        ?: AtlasDictionary.NONE
    val summary = Indexer().index(
        IndexOptions(
            sources = sources,
            output = Path.of(args.out),
            extensions = args.extensions.split(',').map { it.trim() },
            compilationSchema = compilationSchema,
            dictionary = dictionary,
            threads = args.threads,
            encoding = encoding
        )
    )
    System.err.println("Indexed ${summary.files} files into ${args.out}: scan ${summary.parseMillis} ms, write ${summary.writeMillis} ms, total ${summary.totalMillis} ms")
    if (summary.failedFiles > 0) System.err.println("${summary.failedFiles} file(s) failed extraction and were indexed as FAILED (see stats)")
    return 0
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
    System.err.println("Serving ${args.db} on http://${args.host}:${args.port}/ (sources: ${sources ?: "not available"})")
    com.felipebz.zpa.atlas.server.serve(AtlasIndex(database, sources), args.host, args.port)
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
