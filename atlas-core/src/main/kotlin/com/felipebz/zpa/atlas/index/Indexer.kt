package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalContext
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.DeclarationCatalog
import com.felipebz.zpa.atlas.extract.FileExtractor
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
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.Properties
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
    val threads: Int = Runtime.getRuntime().availableProcessors()
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

    private class Source(val relativePath: String, val absolute: Path, val bytes: ByteArray) : PlSqlFile {
        val text = String(bytes, StandardCharsets.UTF_8)
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
        val sources = discover(root, options.extensions)

        val prepared = ProjectAnalysisContext.prepared(
            ProjectIndexPreparation(ProjectDeclarationExtractor(PlSqlConfiguration(StandardCharsets.UTF_8, true)))
                .prepare(sources.map { source -> ProjectSource(FileId(source.relativePath), ProjectSourceReader { source.text }) })
        )
        val compilationSchema = options.compilationSchema?.let { ProjectIdentifier.fromSource("\"$it\"") }
        val context = if (!options.dictionary.isAvailable && compilationSchema == null) prepared
        else prepared.withExternalContext(ProjectExternalContext.of(options.dictionary.metadata, compilationSchema))

        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context)
        val catalogHolder = CatalogHolder(options.compilationSchema)
        val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
        fun extractor(source: Source) = FileExtractor(
            source.relativePath, sha256(source.bytes), source.bytes.size.toLong(), source.text.lines().size,
            options.dictionary, options.compilationSchema, catalogHolder::get
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
        val dictionary = options.dictionary
        if (dictionary.enumerable) {
            dictionary.relations.forEach { RelationResolver.addDictionaryRelation(dictionary, it.relation, globalEntities, globalRelations) }
        } else if (dictionary.isAvailable) {
            globalCoverage += CoverageFact("DICTIONARY_NOT_ENUMERABLE",
                "The acquired metadata cannot list its relations: only relations named by code are entities, and views are recorded as TABLE")
        }

        AtlasWriter.writeAtomically(options.output) { writer ->
            writer.write(
                RunInfo(
                    atlasVersion = VERSIONS.getProperty("atlas", "unknown"),
                    zpaVersion = VERSIONS.getProperty("zpa", "unknown"),
                    sourcesRoot = root.invariantSeparatorsPathString,
                    metadataOrigin = dictionary.metadata.origin.orElse(null),
                    compilationSchema = options.compilationSchema,
                    startedAt = startedAt.toString(),
                    finishedAt = clock().toString()
                ),
                facts, globalEntities, globalRelations, globalCoverage
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

    private fun discover(root: Path, extensions: List<String>): List<Source> {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in accepted }.toList()
        }.map { Source(root.relativize(it).invariantSeparatorsPathString, it, Files.readAllBytes(it)) }
            .sortedBy { it.relativePath }
    }

    companion object {
        private val VERSIONS = Properties().apply {
            Indexer::class.java.getResourceAsStream("/zpa-atlas-version.properties")?.use { load(it) }
        }
    }
}
