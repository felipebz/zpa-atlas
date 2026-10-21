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
import com.felipebz.zpa.atlas.server.AuthDiscoveryException
import com.felipebz.zpa.atlas.server.AuthSettings
import com.felipebz.zpa.atlas.server.MCP_LOCAL_HOSTS
import com.felipebz.zpa.atlas.server.McpSettings
import com.felipebz.zpa.atlas.server.PageClient
import com.felipebz.zpa.atlas.stats.Stats
import com.felipebz.zpa.squid.CcValue
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.DatabaseVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

private fun index(args: IndexArguments, environment: (String) -> String?): Int {
    requireOptions(args)
    if (Files.isDirectory(Path.of(args.out))) throw CliValidationException("--out names a folder: ${args.out}; give the path of the index file to create")
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

/** What `serve` starts, once its options are checked. */
internal class ServeLaunch(val database: Path, val sources: Path?, val host: String, val port: Int, val mcp: McpSettings, val auth: AuthSettings?)

private fun launch(serve: ServeLaunch) =
    com.felipebz.zpa.atlas.server.serve(AtlasIndex(serve.database, serve.sources), serve.host, serve.port, serve.mcp, serve.auth)

private fun serve(args: ServeArguments, environment: (String) -> String?, launch: (ServeLaunch) -> Unit): Int {
    if (args.db.isBlank()) throw CliValidationException("The option is required: --db (or ${environmentName("--db")})")
    val database = Path.of(args.db)
    if (!Files.isRegularFile(database)) throw CliValidationException("Index file does not exist: ${args.db}")
    if (args.port !in 1..65535) throw CliValidationException("--port must be between 1 and 65535")
    val sources = if (args.sources.isNotBlank()) {
        Path.of(args.sources).also { if (!Files.isDirectory(it)) throw CliValidationException("Sources folder does not exist: ${args.sources}") }
    } else {
        Path.of(AtlasIndex(database, null).run().result.sourcesRoot).takeIf { Files.isDirectory(it) }
    }
    val mcpHosts = args.mcpAllowedHosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (mcpHosts.any { it.any { c -> c in "/@?#:" && !(it.startsWith("[") && it.endsWith("]")) } }) {
        throw CliValidationException("--mcp-allowed-host takes host names or addresses, without scheme or port")
    }
    val auth = authSettings(args, environment)
    System.err.println("Serving ${args.db} on http://${args.host}:${args.port}/ (sources: ${sources ?: "not available"})")
    System.err.println("MCP at http://${args.host}:${args.port}/mcp, for Host ${(MCP_LOCAL_HOSTS + mcpHosts + listOfNotNull(auth?.publicHost)).distinct().joinToString(", ")}")
    if (auth != null) {
        System.err.println("Authentication: /mcp and /api need an access token of ${auth.issuer}, for audience ${auth.expectedAudience}" +
            (auth.scope?.let { " and scope $it" } ?: ""))
        System.err.println("Protected resource metadata: ${auth.metadataUrl}")
        auth.page?.let {
            System.err.println("Page sign-in: client ${it.clientId} (${if (it.clientSecret == null) "public" else "confidential"}), redirect URI ${auth.base}/auth/callback")
        }
    }
    if (args.host !in MCP_LOCAL_HOSTS && mcpHosts.isEmpty() && auth == null) {
        System.err.println("Warning: /mcp accepts only local host names; clients on other machines need --mcp-allowed-host with the name they use")
    }
    launch(ServeLaunch(database, sources, args.host, args.port, McpSettings(allowedHosts = mcpHosts), auth))
    return 0
}

/** The authentication [args] ask for, or null without `--oidc-issuer`; the other options without it are an error. */
private fun authSettings(args: ServeArguments, environment: (String) -> String?): AuthSettings? {
    if (args.oidcIssuer.isBlank()) {
        val orphan = listOf(
            "--public-url" to args.publicUrl, "--oidc-audience" to args.oidcAudience, "--oidc-scope" to args.oidcScope,
            "--oidc-client-id" to args.oidcClientId, "--oidc-client-secret-env" to args.oidcClientSecretEnv
        ).firstOrNull { it.second.isNotBlank() }
        if (orphan != null) throw CliValidationException("${orphan.first} needs --oidc-issuer")
        return null
    }
    if (args.publicUrl.isBlank()) throw CliValidationException("--oidc-issuer needs --public-url, the server's address as clients reach it")
    val secretEnv = args.oidcClientSecretEnv.trim()
    if (secretEnv.isNotEmpty() && args.oidcClientId.isBlank()) throw CliValidationException("--oidc-client-secret-env needs --oidc-client-id")
    // A variable named on purpose must be set; the default one is optional, and without it the client is public.
    val secret = if (secretEnv.isNotEmpty()) {
        environment(secretEnv)?.takeIf(String::isNotEmpty) ?: throw CliValidationException("The environment variable $secretEnv, named by --oidc-client-secret-env, is not set")
    } else {
        environment(DEFAULT_CLIENT_SECRET_ENV)?.takeIf { it.isNotEmpty() && args.oidcClientId.isNotBlank() }
    }
    return try {
        val page = args.oidcClientId.trim().takeIf { it.isNotEmpty() }?.let { PageClient(it, secret) }
        AuthSettings(args.oidcIssuer.trim(), args.publicUrl.trim(), args.oidcAudience.trim().ifEmpty { null }, args.oidcScope.trim().ifEmpty { null }, page)
    } catch (e: IllegalArgumentException) {
        throw CliValidationException(e.message ?: "Invalid authentication options")
    }
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

/** The variable `serve` reads the page client's secret from when `--oidc-client-secret-env` names none. */
internal const val DEFAULT_CLIENT_SECRET_ENV = "ZPA_ATLAS_OIDC_CLIENT_SECRET"

/** The environment variable an option of `serve` can come from: `--oidc-issuer` is `ZPA_ATLAS_OIDC_ISSUER`. */
internal fun environmentName(option: String) = "ZPA_ATLAS_" + option.removePrefix("--").uppercase().replace('-', '_')

/** The names of the options of `serve`, the only ones the environment gives. */
internal val SERVE_OPTIONS: Set<String> = ServeArguments::class.java.declaredFields
    .mapNotNull { it.getAnnotation(com.beust.jcommander.Parameter::class.java) }.flatMap { it.names.toList() }.toSet()

fun execute(args: Array<String>, environment: (String) -> String? = System::getenv): Int = execute(args, environment, ::launch)

internal fun execute(args: Array<String>, environment: (String) -> String?, launch: (ServeLaunch) -> Unit): Int {
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
        .apply {
            // JCommander does not tell the provider which command it parses, and with a provider it no longer enforces
            // `required` on options that have an initial value: only `serve` gets one, so the variables of a server
            // never change what `index` indexes.
            if (args.firstOrNull { !it.startsWith("-") } == "serve") {
                defaultProvider { option -> if (option in SERVE_OPTIONS) environment(environmentName(option))?.takeIf { it.isNotBlank() } else null }
            }
        }
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
            "serve" -> serve(serveArguments, environment, launch)
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
    } catch (exception: AuthDiscoveryException) {
        System.err.println(exception.message)
        3
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
