//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.atlas.cli.applicationHome
import com.felipebz.zpa.atlas.cli.oracle.OracleSettings
import com.felipebz.zpa.atlas.compare.ComparisonReport
import com.felipebz.zpa.atlas.compare.DatabaseDependencies
import com.felipebz.zpa.atlas.compare.DependencyComparison
import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import java.nio.file.Files
import java.nio.file.Path

private fun compareDeps(args: CompareArguments, environment: (String) -> String?): Int {
    if (args.oracle.oracleRelations.isNotBlank()) {
        throw CliValidationException("compare-deps does not use --oracle-relations: it reads whole owners with --oracle-schemas")
    }
    val index = Path.of(args.db)
    if (!Files.isRegularFile(index)) throw CliValidationException("Index file does not exist: ${args.db}")
    val settings = OracleSettings.from(args.oracle, environment)
    if ((settings == null) == args.dependencies.isBlank()) {
        throw CliValidationException("compare-deps needs either the --oracle-* options or --dependencies <file>")
    }
    if (settings != null && settings.schemas.isEmpty()) throw CliValidationException("compare-deps needs --oracle-schemas: the owners of the indexed code")
    if (args.saveDependencies.isNotBlank() && settings == null) throw CliValidationException("--save-dependencies needs the --oracle-* options")
    val database = if (settings != null) {
        OracleDependencyReader.read(settings, applicationHome().resolve("drivers")).also { facts ->
            if (args.saveDependencies.isNotBlank()) {
                Files.newBufferedWriter(Path.of(args.saveDependencies)).use { facts.write(it) }
            }
        }
    } else {
        val file = Path.of(args.dependencies)
        if (!Files.isRegularFile(file)) throw CliValidationException("Dependencies file does not exist: ${args.dependencies}")
        Files.newBufferedReader(file).use { DatabaseDependencies.read(it) }
    }
    val sources = args.sources.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    if (sources != null && !Files.isDirectory(sources)) throw CliValidationException("Sources folder does not exist: ${args.sources}")
    val builtins = args.builtins.takeIf { it.isNotBlank() }?.let {
        val file = Path.of(it)
        if (!Files.isRegularFile(file)) throw CliValidationException("Built-ins file does not exist: $it")
        DependencyComparison.readBuiltins(Files.readString(file))
    } ?: DependencyComparison.DEFAULT_BUILTINS
    val result = try {
        DependencyComparison(index, database, sources, args.defaultOwner.trim().ifEmpty { null }, builtins).compare()
    } catch (e: IllegalArgumentException) {
        throw CliValidationException(e.message ?: "Invalid input")
    }
    ComparisonReport.writeCsv(result, Path.of(args.out))
    print(ComparisonReport.summary(result))
    return 0
}

private fun timings(args: TimingsArguments): Int {
    if (args.database.size != 1) throw CliValidationException("timings takes exactly one index file")
    val database = Path.of(args.database.single())
    if (!Files.isRegularFile(database)) throw CliValidationException("Index file does not exist: $database")
    val index = AtlasIndex(database, null)
    index.run() // checks the schema version
    print(Timings.report(index))
    return 0
}

private fun sameRows(args: SameRowsArguments): Int {
    if (args.databases.size != 2) throw CliValidationException("same-rows takes exactly two index files")
    val (first, second) = args.databases.map { Path.of(it) }
    for (file in listOf(first, second)) if (!Files.isRegularFile(file)) throw CliValidationException("Index file does not exist: $file")
    val result = SameRows.compare(first, second)
    result.lines.forEach(::println)
    if (!result.identical) throw CliExecutionException("The indexes differ")
    return 0
}

/** Validation tools of the atlas itself (not part of the published `zpa-atlas` CLI). Exit codes as zpa-atlas. */
fun execute(args: Array<String>, environment: (String) -> String? = System::getenv): Int {
    val main = MainArguments()
    val compareArguments = CompareArguments()
    val timingsArguments = TimingsArguments()
    val sameRowsArguments = SameRowsArguments()
    val commander = JCommander.newBuilder()
        .addObject(main)
        .addCommand(compareArguments)
        .addCommand(timingsArguments)
        .addCommand(sameRowsArguments)
        .programName("atlas-devtools")
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
            "compare-deps" -> compareDeps(compareArguments, environment)
            "same-rows" -> sameRows(sameRowsArguments)
            else -> timings(timingsArguments)
        }
    } catch (exception: ParameterException) {
        System.err.println(exception.message)
        2
    } catch (exception: SchemaMismatchException) {
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
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}
