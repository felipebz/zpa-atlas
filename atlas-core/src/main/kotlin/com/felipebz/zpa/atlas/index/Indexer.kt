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
import com.felipebz.zpa.atlas.writer.SourceTextIndex
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
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

data class IndexSummary(
    val files: Int,
    val failedFiles: Int,
    val parseMillis: Long,
    val writeMillis: Long,
    val totalMillis: Long,
    /** What `--incremental` did; null without it. */
    val incremental: IncrementalReport? = null,
    /** The files left out because a symbolic link leads them outside the sources folder, by relative path. */
    val outsideRoot: List<String> = emptyList()
)

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
    internal class Source(val relativePath: String, val absolute: Path, val bytes: ByteArray, encoding: String) : PlSqlFile {
        private val decoded = SourceDecoding.decode(bytes, encoding)
        val text = decoded.text
        val charset = decoded.charset
        override fun contents(): String = text
        override fun fileName(): String = absolute.fileName.toString()
        override fun type(): PlSqlFile.Type = PlSqlFile.Type.MAIN
        override fun path(): Path = absolute
        override fun toString(): String = relativePath
    }

    /**
     * The sources of a run, read, with the build of their text index started: it needs only their texts, in path
     * order. Read before the dictionary loads ([readSources]), the build overlaps that load as well.
     */
    class ReadSources internal constructor(
        internal val root: Path,
        internal val extensions: List<String>,
        internal val encoding: String,
        internal val output: Path,
        internal val sources: List<Source>,
        internal val outsideRoot: List<String>,
        internal val textIndex: SourceTextIndex,
        internal val startedAt: Instant,
        internal val started: Long
    ) : AutoCloseable {
        /** Waits for the build and deletes its file. */
        override fun close() = textIndex.close()
    }

    /** Reads the sources [index] will index with the same folder, extensions and encoding, and starts their text index for [output]. */
    fun readSources(sources: Path, extensions: List<String>, encoding: String, output: Path): ReadSources {
        val startedAt = clock()
        val started = System.nanoTime()
        val root = sources.toAbsolutePath().normalize()
        val (read, outsideRoot) = discover(root, extensions, encoding)
        val textIndex = SourceTextIndex.start(output, read.map { it.relativePath to it.text })
        return ReadSources(root, extensions, encoding, output.toAbsolutePath().normalize(), read, outsideRoot, textIndex, startedAt, started)
    }

    /**
     * Indexes [options]; with [phases], it records where the time goes, and the index is the same. With [incremental],
     * it walks only the files a change can affect (see [IncrementalRule]), takes the other files' facts from the facts
     * cache beside the index, and writes that cache; the index is the one a full run writes. [read] gives the sources
     * when the caller read them first ([readSources], the caller closes it); else they are read here.
     */
    fun index(
        options: IndexOptions, phases: PhaseTimes? = null, incremental: Boolean = false, cache: PendingFactsCache? = null, read: ReadSources? = null
    ): IndexSummary {
        val pending = if (incremental) cache ?: IncrementalRun.readCache(options.output) else null
        val inputs = if (incremental) IncrementalRun.inputs(options, phases) else null
        if (read != null) {
            require(read.root == options.sources.toAbsolutePath().normalize() && read.extensions == options.extensions && read.encoding == options.encoding &&
                read.output == options.output.toAbsolutePath().normalize()) { "The sources were read for another run" }
            return index(options, phases, incremental, pending, inputs, read)
        }
        phases?.start(PhaseTimes.READING)
        readSources(options.sources, options.extensions, options.encoding, options.output).use { return index(options, phases, incremental, pending, inputs, it) }
    }

    private fun index(
        options: IndexOptions, phases: PhaseTimes?, incremental: Boolean, pending: PendingFactsCache?, inputs: CompletableFuture<Map<String, String>>?,
        read: ReadSources
    ): IndexSummary {
        val (root, sources, textIndex) = Triple(read.root, read.sources, read.textIndex)
        val startedAt = read.startedAt
        val started = read.started
        phases?.start(PhaseTimes.PREPARATION)

        val prepared = ProjectAnalysisContext.prepared(
            ProjectIndexPreparation(ProjectDeclarationExtractor(PlSqlConfiguration(StandardCharsets.UTF_8, true, options.conditionalCompilation)))
                .prepare(sources.map { source -> ProjectSource(FileId(source.relativePath), ProjectSourceReader { source.text }) })
        )
        val compilationSchema = options.compilationSchema?.let { ProjectIdentifier.fromSource("\"$it\"") }
        val context = if (!options.dictionary.isAvailable && compilationSchema == null) prepared
        else prepared.withExternalContext(ProjectExternalContext.of(options.dictionary.metadata, compilationSchema))

        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context, options.conditionalCompilation)
        val planCatalog = if (incremental) IncrementalRun.catalog(options, context, sources.map { it.relativePath }, phases) else null
        // The members of the object types, read before any type body is walked.
        val typeMembers = ObjectTypeMembers()
        phases?.start(PhaseTimes.TYPE_MEMBERS)
        for (source in sources) {
            if (ObjectTypeMembers.MAY_SPECIFY_OBJECT.containsMatchIn(source.text)) {
                scanner.scanFile(source, listOf(typeMembers.collector(options.compilationSchema)), source.relativePath)
            }
        }
        val hashes = sources.associate { it.relativePath to sha256(it.bytes) }
        val run = if (!incremental) null else {
            val current = sources.map { IncrementalRule.Current(it.relativePath, hashes.getValue(it.relativePath), it.text) }
            IncrementalRun.plan(options, typeMembers, current, pending!!, inputs!!, planCatalog!!, phases)
        }
        val toWalk = if (run == null) sources else sources.filter { it.relativePath in run.walk }
        phases?.start(PhaseTimes.FILE_WALKS)
        phases?.walkThreads = options.threads.coerceAtLeast(1)
        val catalogHolder = CatalogHolder(options.compilationSchema)
        val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
        fun extractor(source: Source) = FileExtractor(
            source.relativePath, hashes.getValue(source.relativePath), source.bytes.size.toLong(), source.charset.name(), source.text.lines().size,
            options.dictionary, options.compilationSchema, catalogHolder::get,
            jsonBuiltIn = (options.conditionalCompilation.databaseVersion?.version ?: 0) >= JSON_BUILT_IN_VERSION,
            typeMembers = typeMembers
        )
        val walked: List<Result<FileFacts>> = try {
            toWalk.map { source ->
                executor.submit<Result<FileFacts>> {
                    try {
                        val taskStarted = System.nanoTime()
                        val extractor = extractor(source)
                        val walkStart = WalkStart()
                        val checks = visitors(source.relativePath, extractor)
                        scanner.scanFile(source, if (phases == null) checks else listOf(walkStart) + checks, source.relativePath)
                        if (phases != null) {
                            val walked = System.nanoTime()
                            phases.files += PhaseTimes.FileTime(source.relativePath, source.text.lines().size, walkStart.at - taskStarted, walked - walkStart.at)
                        }
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
        val walkedFacts = toWalk.zip(walked).associate { (source, result) ->
            source.relativePath to result.getOrElse { e ->
                val message = e.message?.lineSequence()?.firstOrNull().orEmpty()
                extractor(source).failedFacts(catalogHolder.catalog, CoverageFact(EXTRACTION_FAILED, "${e.javaClass.name}: $message"))
            }
        }
        val facts = sources.map { walkedFacts[it.relativePath] ?: run!!.cachedFacts(it.relativePath) }
        val failedFiles = walked.count { it.isFailure } +
            facts.count { it.path !in walkedFacts && it.coverage.any { coverage -> coverage.kind == EXTRACTION_FAILED } }
        val parsed = System.nanoTime()

        phases?.start(PhaseTimes.GLOBAL_FACTS)
        val globalEntities = ArrayList<EntityFact>()
        val globalRelations = ArrayList<RelationFact>()
        val globalCoverage = ArrayList<CoverageFact>()
        // A run that walks nothing has no catalog of its own walks: the plan's, built from the same declarations, is the same.
        (catalogHolder.catalog ?: run?.catalog)?.let {
            globalEntities += it.entities
            globalRelations += it.relations
            globalCoverage += it.coverage
        }
        (catalogHolder.snapshot ?: run?.snapshot)?.failures?.forEach {
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
        val phase: (String) -> Unit = { phases?.start(it) }
        // The cache is written while the index is: neither reads what the other writes.
        val saving = run?.save(facts)
        try {
            AtlasWriter.writeAtomically(options.output, textIndex, phase) { writer ->
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
                    facts, globalEntities, globalRelations, globalCoverage, texts::get, phase
                )
            }
        } catch (e: Throwable) {
            runCatching { saving?.join() }
            throw e
        }
        phases?.notes?.add("Text index: built on its own thread in ${textIndex.buildMillis} ms, from the end of the reading; the writer waited ${textIndex.waitedMillis} ms for it")
        if (saving != null) {
            phases?.start(PhaseTimes.WRITING_CACHE)
            try {
                saving.join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }
        phases?.stop()
        val finished = System.nanoTime()
        return IndexSummary(sources.size, failedFiles, (parsed - started) / 1_000_000, (finished - parsed) / 1_000_000, (finished - started) / 1_000_000, run?.report, read.outsideRoot)
    }

    /**
     * Notes when the scanner starts a file's walk: it is the first check of the walk, which [AstScanner] runs under its
     * global lock once the file is parsed.
     */
    private class WalkStart : PlSqlCheck() {
        var at = 0L

        override fun init() {
            at = System.nanoTime()
        }
    }

    /**
     * Starts reading the facts cache of the index [output] on its own thread, for [index] with `incremental`: started
     * before the dictionary is loaded, the reading overlaps that load.
     */
    fun readFactsCache(output: Path): PendingFactsCache = IncrementalRun.readCache(output)

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

    /**
     * The sources under [root] with one of [extensions], read, in path order; and the relative paths of the files left
     * out because they are symbolic links that lead outside the real [root]. A link that stays inside is read through;
     * folder links are not walked into. The server serves a file only where the same rule holds, so the index never
     * holds the text of a file outside the sources folder.
     */
    private fun discover(root: Path, extensions: List<String>, encoding: String): Pair<List<Source>, List<String>> {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val realRoot = root.toRealPath()
        val found = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in accepted }.toList()
        }
        val outside = ArrayList<String>()
        val sources = found.mapNotNull { path ->
            val relative = root.relativize(path).invariantSeparatorsPathString
            val real = path.toRealPath()
            if (!real.startsWith(realRoot)) {
                outside += relative
                return@mapNotNull null
            }
            Source(relative, path, Files.readAllBytes(real), encoding)
        }
        return sources.sortedBy { it.relativePath } to outside.sorted()
    }

    companion object {
        /** The first Oracle version with the built-in `JSON` type: from it on, a type named `JSON` cannot exist. */
        private const val JSON_BUILT_IN_VERSION = 21

        private const val EXTRACTION_FAILED = "EXTRACTION_FAILED"

        /** The version of `atlas` or `zpa` this build was made with. */
        internal fun version(name: String): String = VERSIONS.getProperty(name, "unknown")

        private val VERSIONS = Properties().apply {
            Indexer::class.java.getResourceAsStream("/zpa-atlas-version.properties")?.use { load(it) }
        }
    }
}
