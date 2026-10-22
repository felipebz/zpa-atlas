//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalContext
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.DeclarationCatalog
import com.felipebz.zpa.atlas.extract.FileExtractor
import com.felipebz.zpa.atlas.extract.ObjectTypeMembers
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.atlas.model.CoverageFact
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.writer.AtlasWriter
import com.felipebz.zpa.atlas.writer.RunInfo
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.project.ProjectDeclarationExtractor
import com.felipebz.zpa.project.ProjectIndexPreparation
import com.felipebz.zpa.project.ProjectSource
import com.felipebz.zpa.project.ProjectSourceReader
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.Properties
import java.util.TreeMap
import java.util.concurrent.Executors
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

data class IndexOptions(
    val sources: Path,
    val output: Path,
    val extensions: List<String> = DEFAULT_EXTENSIONS,
    /** Exact dictionary name of the schema unqualified units compile in, or null. */
    val compilationSchema: String? = null,
    val dictionary: AtlasDictionary = AtlasDictionary.NONE,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    /** `auto` (per file: strict UTF-8, else windows-1252) or a charset name forced for every file. */
    val encoding: String = SourceDecoding.AUTO,
    /**
     * How the sources are compiled (`PLSQL_CCFLAGS`, target database version): the branch of each `$IF` that is kept.
     * Both lexing passes (declarations and AST) get this same value, so they agree on which branch exists.
     */
    val conditionalCompilation: ConditionalCompilationSettings = ConditionalCompilationSettings.NONE,
    /** Where [conditionalCompilation]'s database version comes from; null without one. */
    val databaseVersionOrigin: DatabaseVersionOrigin? = null
) {
    companion object {
        /** The default of zpa-cli `--extensions`. */
        val DEFAULT_EXTENSIONS = "sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps".split(',')
    }
}

data class IndexSummary(val files: Int, val failedFiles: Int, val parseMillis: Long, val writeMillis: Long, val totalMillis: Long)

/**
 * Drives ZPA as zpa-cli does: project preparation once over every source, then one [AstScanner] walk per file with
 * a new [FileExtractor]. Files are scanned on [IndexOptions.threads] threads (the walk itself runs under the
 * scanner's global lock); each file's facts stay in memory and are written by one [AtlasWriter] at the end.
 *
 * A file whose walk throws is indexed as `FAILED` (its declarations only, with an `EXTRACTION_FAILED` note); the
 * facts it gathered before failing are discarded and the run continues.
 */
class Indexer internal constructor(
    private val clock: () -> Instant,
    /** The visitors run for a file; tests add a failing one. */
    private val visitors: (path: String, extractor: FileExtractor) -> List<PlSqlCheck>
) {
    constructor(clock: () -> Instant = Instant::now) : this(clock, { _, extractor -> listOf(extractor) })

    /**
     * A source decoded with its own charset ([SourceDecoding]). ZPA gets the decoded text, never the bytes: the charset
     * of `PlSqlConfiguration`/`AstScanner` is only used by the lexer to read files and URLs, so one value for the run is
     * enough.
     */
    private class Source(val relativePath: String, val absolute: Path, val bytes: ByteArray, encoding: String) : PlSqlFile {
        private val decoded = SourceDecoding.decode(bytes, encoding)
        val text = decoded.text
        val charset = decoded.charset
        override fun contents(): String = text
        override fun fileName(): String = absolute.fileName.toString()
        override fun type(): PlSqlFile.Type = PlSqlFile.Type.MAIN
        override fun path(): Path = absolute
        override fun toString(): String = relativePath
    }

    fun index(options: IndexOptions): IndexSummary {
        val startedAt = clock()
        val started = System.nanoTime()
        val root = options.sources.toAbsolutePath().normalize()
        val sources = discover(root, options.extensions, options.encoding)

        val prepared = ProjectAnalysisContext.prepared(
            ProjectIndexPreparation(ProjectDeclarationExtractor(PlSqlConfiguration(StandardCharsets.UTF_8, true, options.conditionalCompilation)))
                .prepare(sources.map { source -> ProjectSource(FileId(source.relativePath), ProjectSourceReader { source.text }) })
        )
        val compilationSchema = options.compilationSchema?.let { ProjectIdentifier.fromSource("\"$it\"") }
        val context = if (!options.dictionary.isAvailable && compilationSchema == null) prepared
        else prepared.withExternalContext(ProjectExternalContext.of(options.dictionary.metadata, compilationSchema))

        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context, options.conditionalCompilation)
        // The members of the object types, read before any type body is walked.
        val typeMembers = ObjectTypeMembers()
        for (source in sources) {
            if (ObjectTypeMembers.MAY_SPECIFY_OBJECT.containsMatchIn(source.text)) {
                scanner.scanFile(source, listOf(typeMembers.collector(options.compilationSchema)), source.relativePath)
            }
        }
        val catalogHolder = CatalogHolder(options.compilationSchema)
        val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
        fun extractor(source: Source) = FileExtractor(
            source.relativePath, sha256(source.bytes), source.bytes.size.toLong(), source.charset.name(), source.text.lines().size,
            options.dictionary, options.compilationSchema, catalogHolder::get,
            jsonBuiltIn = (options.conditionalCompilation.databaseVersion?.version ?: 0) >= JSON_BUILT_IN_VERSION,
            typeMembers = typeMembers
        )
        val walked: List<Result<FileFacts>> = try {
            sources.map { source ->
                executor.submit<Result<FileFacts>> {
                    try {
                        val extractor = extractor(source)
                        scanner.scanFile(source, visitors(source.relativePath, extractor), source.relativePath)
                        Result.success(extractor.facts())
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.map { it.get() }
        } finally {
            executor.shutdown()
        }
        // Built after every walk, from the run's catalog: a failed walk may have died before or after creating it.
        val facts = sources.zip(walked).map { (source, result) ->
            result.getOrElse { e ->
                val message = e.message?.lineSequence()?.firstOrNull().orEmpty()
                extractor(source).failedFacts(catalogHolder.catalog, CoverageFact("EXTRACTION_FAILED", "${e.javaClass.name}: $message"))
            }
        }
        val failedFiles = walked.count { it.isFailure }
        val parsed = System.nanoTime()

        val globalEntities = ArrayList<EntityFact>()
        val globalRelations = ArrayList<RelationFact>()
        val globalCoverage = ArrayList<CoverageFact>()
        catalogHolder.catalog?.let {
            globalEntities += it.entities
            globalRelations += it.relations
            globalCoverage += it.coverage
        }
        catalogHolder.snapshot?.failures?.forEach {
            globalCoverage += CoverageFact("PREPARATION_FAILED", "Project index preparation failed for ${it.fileId}")
        }
        globalCoverage += CoverageFact("CALL_SCOPE_LEGACY",
            "Calls named like a local value, type or nested subprogram are recognized through the legacy symbol table, a heuristic rather than proof of lexical scope")
        val dictionary = options.dictionary
        // The dictionary relations the files resolved, once each, in key order (an enumerable dictionary has them all).
        val resolved = TreeMap<String, ProjectExternalRelation>()
        for (file in facts) for (relation in file.dictionaryRelations) resolved.putIfAbsent(RelationResolver.dictionaryKey(relation), relation)
        if (dictionary.enumerable) {
            dictionary.relations.forEach {
                resolved.remove(RelationResolver.dictionaryKey(it.relation))
                RelationResolver.addDictionaryRelation(dictionary, it.relation, globalEntities, globalRelations)
            }
        }
        resolved.values.forEach { RelationResolver.addDictionaryRelation(dictionary, it, globalEntities, globalRelations) }
        // The synonyms the files followed, once each, in key order.
        facts.flatMapTo(java.util.TreeSet(compareBy<DictionarySynonym>({ it.owner }, { it.name }))) { it.dictionarySynonyms }
            .forEach { RelationResolver.addDictionarySynonym(it, globalEntities, globalRelations) }
        if (!dictionary.enumerable && dictionary.isAvailable) {
            globalCoverage += CoverageFact("DICTIONARY_NOT_ENUMERABLE",
                "The acquired metadata cannot list its relations: only relations named by code are entities, and views are recorded as TABLE")
        }

        val texts = sources.associate { it.relativePath to it.text }
        AtlasWriter.writeAtomically(options.output) { writer ->
            writer.write(
                RunInfo(
                    atlasVersion = VERSIONS.getProperty("atlas", "unknown"),
                    zpaVersion = VERSIONS.getProperty("zpa", "unknown"),
                    sourcesRoot = root.invariantSeparatorsPathString,
                    sourceEncoding = SourceDecoding.validate(options.encoding),
                    metadataOrigin = dictionary.metadata.origin.orElse(null),
                    compilationSchema = options.compilationSchema,
                    databaseVersion = options.conditionalCompilation.databaseVersion?.let { ConditionalCompilationText.version(it) },
                    databaseVersionOrigin = options.databaseVersionOrigin?.takeIf { options.conditionalCompilation.databaseVersion != null }?.name,
                    ccflags = ConditionalCompilationText.flags(options.conditionalCompilation.flags),
                    startedAt = startedAt.toString(),
                    finishedAt = clock().toString()
                ),
                facts, globalEntities, globalRelations, globalCoverage, texts::get
            )
        }
        val finished = System.nanoTime()
        return IndexSummary(sources.size, failedFiles, (parsed - started) / 1_000_000, (finished - parsed) / 1_000_000, (finished - started) / 1_000_000)
    }

    /** The catalog of the run's snapshot, built once by the first file walk (every walk sees the same snapshot). */
    private class CatalogHolder(private val compilationSchema: String?) {
        @Volatile var snapshot: ProjectSnapshot? = null
        @Volatile var catalog: DeclarationCatalog? = null

        @Synchronized
        fun get(current: ProjectSnapshot): DeclarationCatalog {
            val existing = catalog
            if (existing != null) {
                check(snapshot === current) { "Every file of a run must see the same project snapshot" }
                return existing
            }
            return DeclarationCatalog(current, compilationSchema).also {
                snapshot = current
                catalog = it
            }
        }
    }

    private fun discover(root: Path, extensions: List<String>, encoding: String): List<Source> {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in accepted }.toList()
        }.map { Source(root.relativize(it).invariantSeparatorsPathString, it, Files.readAllBytes(it), encoding) }
            .sortedBy { it.relativePath }
    }

    companion object {
        /** The first Oracle version with the built-in `JSON` type: from it on, a type named `JSON` cannot exist. */
        private const val JSON_BUILT_IN_VERSION = 21

        private val VERSIONS = Properties().apply {
            Indexer::class.java.getResourceAsStream("/zpa-atlas-version.properties")?.use { load(it) }
        }
    }
}
