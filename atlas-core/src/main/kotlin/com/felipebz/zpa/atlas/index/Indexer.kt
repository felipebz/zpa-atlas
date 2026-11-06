//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalContext
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryKeys
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.*
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.forms.FormsModuleText
import com.felipebz.zpa.atlas.model.*
import com.felipebz.zpa.atlas.writer.AtlasWriter
import com.felipebz.zpa.atlas.writer.RunInfo
import com.felipebz.zpa.atlas.writer.SourceTextIndex
import com.felipebz.zpa.project.*
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
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
    val databaseVersionOrigin: DatabaseVersionOrigin? = null,
    /**
     * `BYTE` or `CHAR`: the unit of a PL/SQL length written without one when the dictionary does not say how the unit
     * declaring it was compiled.
     */
    val nlsLengthSemantics: String = "BYTE",
    /** The readers of Forms modules: a file one of them claims is read by it, whatever [extensions] says. */
    val readers: FormsModuleReaders = FormsModuleReaders.BUILT_IN
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
 * a new [FileExtractor]. Files are scanned on [IndexOptions.threads] threads: the walk itself runs under the scanner's
 * global lock, the extraction after it, on the file's thread. Each file's facts stay in memory and are written by one
 * [AtlasWriter] at the end.
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
     * A source: a database source decoded with its own charset ([SourceDecoding]), or a Forms module, whose [text] is
     * its module text ([FormsModuleText]) and whose [forms] says where each unit of it is. ZPA gets the text, never the
     * bytes: the charset of `PlSqlConfiguration`/`AstScanner` is only used by the lexer to read files and URLs, so one
     * value for the run is enough. The file's bytes are not kept: only their hash and size are written.
     */
    internal class Source(
        val relativePath: String, val absolute: Path, val text: String, val charset: Charset, val sha256: String, val size: Long,
        val forms: FormsLayout? = null
    ) : PlSqlFile {
        /** `text.lines().size`, counted without making the lines: a line ends at `\r\n`, `\n` or `\r`. */
        val lineCount = lineCount(text)
        val kind: FileKind get() = if (forms == null) FileKind.DB_SOURCE else FileKind.FORMS_MODULE
        override fun contents(): String = text
        override fun fileName(): String = absolute.fileName.toString()
        override fun type(): PlSqlFile.Type = PlSqlFile.Type.MAIN
        override fun path(): Path = absolute
        override fun toString(): String = relativePath

        companion object {
            fun database(relativePath: String, absolute: Path, bytes: ByteArray, encoding: String): Source {
                val decoded = SourceDecoding.decode(bytes, encoding)
                return Source(relativePath, absolute, decoded.text, decoded.charset, sha256(bytes), bytes.size.toLong())
            }
        }
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
        internal val readers: FormsModuleReaders,
        internal val sources: List<Source>,
        internal val outsideRoot: List<String>,
        /** What the reading left out: files of a module format that are not indexed, files that held no module, duplicated module names. */
        internal val notes: List<CoverageFact>,
        internal val textIndex: SourceTextIndex,
        internal val startedAt: Instant,
        internal val started: Long
    ) : AutoCloseable {
        /** Waits for the build and deletes its file. */
        override fun close() = textIndex.close()
    }

    /**
     * Reads the sources [index] will index with the same folder, extensions, encoding and readers, and starts their text
     * index for [output].
     */
    fun readSources(
        sources: Path, extensions: List<String>, encoding: String, output: Path, readers: FormsModuleReaders = FormsModuleReaders.BUILT_IN
    ): ReadSources {
        val startedAt = clock()
        val started = System.nanoTime()
        val root = sources.toAbsolutePath().normalize()
        val discovered = discover(root, extensions, encoding, readers)
        val read = discovered.sources
        val textIndex = SourceTextIndex.start(output, read.map { it.relativePath to it.text })
        return ReadSources(root, extensions, encoding, output.toAbsolutePath().normalize(), readers, read, discovered.outsideRoot, discovered.notes, textIndex,
            startedAt, started)
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
                read.output == options.output.toAbsolutePath().normalize() && read.readers === options.readers) { "The sources were read for another run" }
            return index(options, phases, incremental, pending, inputs, read)
        }
        phases?.start(PhaseTimes.READING)
        readSources(options.sources, options.extensions, options.encoding, options.output, options.readers).use {
            return index(options, phases, incremental, pending, inputs, it)
        }
    }

    private fun index(
        options: IndexOptions, phases: PhaseTimes?, incremental: Boolean, pending: PendingFactsCache?, inputs: CompletableFuture<Map<String, String>>?,
        read: ReadSources
    ): IndexSummary {
        val (root, sources, textIndex) = Triple(read.root, read.sources, read.textIndex)
        val startedAt = read.startedAt
        val started = read.started
        val walked = walkSources(options, phases, incremental, pending, inputs, sources)
        val (facts, run) = walked.facts to walked.run
        val failedFiles = walked.failedFiles
        val parsed = System.nanoTime()

        phases?.start(PhaseTimes.GLOBAL_FACTS)
        val globalEntities = walked.globalEntities
        val globalRelations = walked.globalRelations
        val globalCoverage = walked.globalCoverage
        globalCoverage += read.notes
        globalCoverage += CoverageFact("CALL_SCOPE_LEGACY",
            "Calls named like a local value, type or nested subprogram are recognized through the legacy symbol table, a heuristic rather than proof of lexical scope")
        val dictionary = options.dictionary
        // The dictionary relations the files resolved, once each, in key order (an enumerable dictionary has them all).
        val resolved = TreeMap<String, ProjectExternalRelation>()
        for (file in facts) for (relation in file.dictionaryRelations) resolved.putIfAbsent(RelationResolver.dictionaryKey(relation), relation)
        if (dictionary.enumerable) dictionary.relations.forEach { resolved[RelationResolver.dictionaryKey(it.relation)] = it.relation }
        // The relations at the other end of their keys, so that both ends of a key are entities.
        resolved += DictionaryKeys.linked(dictionary, resolved)
        val globalDeclaredTexts = ArrayList<DeclaredTextFact>()
        resolved.values.forEach { RelationResolver.addDictionaryRelation(dictionary, it, globalEntities, globalRelations, globalDeclaredTexts) }
        val keys = DictionaryKeys.facts(dictionary, resolved)
        // The synonyms the files followed, once each, in key order.
        facts.flatMapTo(java.util.TreeSet(compareBy<DictionarySynonym>({ it.owner }, { it.name }))) { it.dictionarySynonyms }
            .forEach { RelationResolver.addDictionarySynonym(it, globalEntities, globalRelations) }
        if (!dictionary.enumerable && dictionary.isAvailable) {
            globalCoverage += CoverageFact("DICTIONARY_NOT_ENUMERABLE",
                "The acquired metadata cannot list its relations: only relations named by code are entities, and views are recorded as TABLE")
        }
        if (dictionary.isAvailable && !dictionary.seesEveryTable) {
            globalCoverage += CoverageFact(DictionaryKeys.REFERENCED_BY_INCOMPLETE,
                "${DictionaryKeys.TABLES_NOT_VISIBLE}: the loading user has none of ${AtlasDictionary.EVERY_TABLE_PRIVILEGES.joinToString()}, " +
                    "and ALL_CONSTRAINTS lists only the constraints of the tables it owns or was granted a privilege on: " +
                    "the foreign keys referencing a key may be more than those recorded")
        }
        val (places, placesLeftOut) = columnPlaces(facts, globalEntities, resolved, dictionary, options.nlsLengthSemantics)

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
                        nlsLengthSemantics = options.nlsLengthSemantics,
                        startedAt = startedAt.toString(),
                        finishedAt = clock().toString()
                    ),
                    // The cache keeps the calls of modules as the walks wrote them: which modules the run has is checked on every run.
                    FormsReferences.resolveModuleCalls(facts), globalEntities, globalRelations, globalCoverage, texts::get, phase,
                    dictionaryDeclaredTexts = globalDeclaredTexts, keys = keys, places = places, placesLeftOut = placesLeftOut
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
     * What the walks leave for the rest of the run: every file's facts, in path order, how many files failed, the
     * incremental plan, and the entities, relations and notes of the run's declarations.
     */
    private class Walked(
        val facts: List<FileFacts>, val failedFiles: Int, val run: IncrementalRun?,
        val globalEntities: ArrayList<EntityFact>, val globalRelations: ArrayList<RelationFact>, val globalCoverage: ArrayList<CoverageFact>
    )

    /**
     * Prepares the project, reads the object types' members and walks the files. Its own function, so that what only
     * the walks use (zpa's project analysis and scanner, the run's declaration catalog) is let go once they end: the
     * frame of [index] keeps its locals until it returns.
     */
    private fun walkSources(
        options: IndexOptions, phases: PhaseTimes?, incremental: Boolean, pending: PendingFactsCache?, inputs: CompletableFuture<Map<String, String>>?,
        sources: List<Source>
    ): Walked {
        phases?.start(PhaseTimes.PREPARATION)

        // The project is the database sources: a module's program units are its own, never names of the database.
        val database = sources.filter { it.forms == null }
        val prepared = ProjectAnalysisContext.prepared(
            ProjectIndexPreparation(ProjectDeclarationExtractor(PlSqlConfiguration(StandardCharsets.UTF_8, true, options.conditionalCompilation)))
                .prepare(database.map { source -> ProjectSource(FileId(source.relativePath), ProjectSourceReader { source.text }) })
        )
        val compilationSchema = options.compilationSchema?.let { ProjectIdentifier.fromSource("\"$it\"") }
        val context = if (!options.dictionary.isAvailable && compilationSchema == null) prepared
        else prepared.withExternalContext(ProjectExternalContext.of(options.dictionary.metadata, compilationSchema))

        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context, options.conditionalCompilation)
        val planCatalog = if (incremental) IncrementalRun.catalog(options, context, sources.map { it.relativePath }, phases) else null
        // The members of the object types, read before any type body is walked.
        val typeMembers = ObjectTypeMembers()
        phases?.start(PhaseTimes.TYPE_MEMBERS)
        for (source in database) {
            if (ObjectTypeMembers.MAY_SPECIFY_OBJECT.containsMatchIn(source.text)) {
                scanner.scanFile(source, listOf(typeMembers.collector(options.compilationSchema)), source.relativePath)
            }
        }
        val hashes = sources.associate { it.relativePath to it.sha256 }
        val run = if (!incremental) null else {
            val current = sources.map {
                val reader = if (it.forms == null) null else options.readers.fingerprint(it.absolute.fileName.toString())
                IncrementalRule.Current(it.relativePath, hashes.getValue(it.relativePath), it.text, reader)
            }
            IncrementalRun.plan(options, typeMembers, current, pending!!, inputs!!, planCatalog!!, phases)
        }
        val toWalk = if (run == null) sources else sources.filter { it.relativePath in run.walk }
        phases?.start(PhaseTimes.FILE_WALKS)
        phases?.walkThreads = options.threads.coerceAtLeast(1)
        val catalogHolder = CatalogHolder(options.compilationSchema)
        val (walkedFacts, failedWalks) = walk(options, phases, scanner, typeMembers, catalogHolder, toWalk)
        val facts = sources.map { walkedFacts[it.relativePath] ?: run!!.cachedFacts(it.relativePath) }
        val failedFiles = failedWalks +
            facts.count { it.path !in walkedFacts && it.coverage.any { coverage -> coverage.kind == EXTRACTION_FAILED } }
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
        return Walked(facts, failedFiles, run, globalEntities, globalRelations, globalCoverage)
    }

    /**
     * Walks [toWalk] on the run's threads: each file's facts by path, and how many walks failed (their facts say so). Its
     * own function, so that what only the walks use (the shared strings of the facts, the extractors) is let go once
     * they end, not kept until the index is written.
     */
    private fun walk(
        options: IndexOptions, phases: PhaseTimes?, scanner: AstScanner, typeMembers: ObjectTypeMembers, catalogHolder: CatalogHolder,
        toWalk: List<Source>
    ): Pair<Map<String, FileFacts>, Int> {
        val strings = FactStrings()
        val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
        fun extractor(source: Source) = FileExtractor(
            source.relativePath, source.sha256, source.size, source.charset.name(), source.lineCount,
            options.dictionary, options.compilationSchema, catalogHolder::get,
            jsonBuiltIn = (options.conditionalCompilation.databaseVersion?.version ?: 0) >= JSON_BUILT_IN_VERSION,
            typeMembers = typeMembers, strings = strings, forms = source.forms
        )
        val walked: List<Result<FileFacts>> = try {
            toWalk.map { source ->
                executor.submit<Result<FileFacts>> {
                    try {
                        val taskStarted = System.nanoTime()
                        val extractor = extractor(source)
                        val walkStart = WalkStart()
                        scanner.scanFile(source, listOf(walkStart) + visitors(source.relativePath, extractor), source.relativePath)
                        val walked = System.nanoTime()
                        val facts = extractor.facts()
                        if (phases != null) {
                            phases.files += PhaseTimes.FileTime(source.relativePath, source.lineCount, walkStart.at - taskStarted, walked - walkStart.at,
                                System.nanoTime() - walked)
                        }
                        Result.success(facts)
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
        return walkedFacts to walked.count { it.isFailure }
    }

    /**
     * Where column values are read into PL/SQL, now that every target, type and cursor of every file is known: the places
     * by file path, and what is not followed, by file path and shape. Its own function, so that what only the following
     * uses (the entity kinds, the declared shapes by key) is let go before the index is written.
     */
    private fun columnPlaces(
        facts: List<FileFacts>, globalEntities: List<EntityFact>, resolved: Map<String, ProjectExternalRelation>, dictionary: AtlasDictionary,
        lengthSemantics: String
    ): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> {
        val kinds = HashMap<String, EntityKind>()
        for (entity in globalEntities) kinds.putIfAbsent(entity.key, entity.kind)
        for (file in facts) for (entity in file.entities) kinds.putIfAbsent(entity.key, entity.kind)
        val relationColumns = resolved.mapValues { (key, relation) -> relation.rowFields.sortedBy { it.position.asInt }.map { "$key.${it.name.exact()}" } }
        val columnPlaces = ColumnPlaces(facts, kinds, relationColumns, dictionary.lengthSemantics, lengthSemantics)
        val places = HashMap<String, List<ColumnPlaceFact>>()
        val placesLeftOut = HashMap<String, Map<String, Int>>()
        for (file in facts) {
            val (found, expressions) = columnPlaces.of(file)
            if (found.isNotEmpty()) places[file.path] = found
            val counts = TreeMap(file.placesLeftOut)
            expressions.forEach { (shape, count) -> counts.merge(shape, count, Int::plus) }
            if (counts.isNotEmpty()) placesLeftOut[file.path] = counts
        }
        // Through routines, across files: the targets inside them of the columns passed to their IN parameters, and what
        // comes back out of them through RETURN and OUT parameters to their callers.
        val (followed, followedLeftOut) = columnPlaces.followed(facts, places)
        for ((path, found) in followed) places[path] = places[path].orEmpty() + found
        for ((path, counts) in followedLeftOut) placesLeftOut[path] = TreeMap(placesLeftOut[path].orEmpty()).apply { counts.forEach { (k, v) -> merge(k, v, Int::plus) } }
        return places to placesLeftOut
    }

    /**
     * The first check of a file's walk, which [AstScanner] runs once the file is parsed: notes when the walk starts. The
     * scanner has no rule checks, so the walks of the run's files run on every thread at once.
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

    /** What [discover] found: the sources, the files left out as links outside the folder, and the notes of what else it left out. */
    private class Discovered(val sources: List<Source>, val outsideRoot: List<String>, val notes: List<CoverageFact>)

    /**
     * The sources under [root], read, in path order: the files one of [readers] reads, whatever their extension, and the
     * files with one of [extensions]; and the relative paths of the files left out because they are symbolic links that
     * lead outside the real [root]. A link that stays inside is read through; folder links are not walked into. The
     * server serves a file only where the same rule holds, so the index never holds the text of a file outside the
     * sources folder.
     *
     * A module is known by the identity its reader gives its file ([FormsModuleReaders.moduleName], by default the file's
     * name): of the files with the same identity, the first by path is indexed; a file whose reader gives a blank one
     * is not read.
     * The files a reader knows but does not index, the files that held no module and the modules left out for their
     * name are each counted in a note.
     */
    private fun discover(root: Path, extensions: List<String>, encoding: String, readers: FormsModuleReaders): Discovered {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val realRoot = root.toRealPath()
        val found = Files.walk(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() && path.fileName.toString().let { name ->
                    readers.readerOf(name) != null || readers.skipped(name) != null || path.extension.lowercase(Locale.ROOT) in accepted
                }
            }.toList()
        }.sortedBy { root.relativize(it).invariantSeparatorsPathString }
        val outside = ArrayList<String>()
        val sources = ArrayList<Source>()
        val skipped = TreeMap<String, MutableList<String>>()
        val notModules = ArrayList<String>()
        val modules = HashMap<String, String>()
        val duplicates = ArrayList<String>()
        for (path in found) {
            val relative = root.relativize(path).invariantSeparatorsPathString
            val real = path.toRealPath()
            if (!real.startsWith(realRoot)) {
                outside += relative
                continue
            }
            val name = path.fileName.toString()
            readers.skipped(name)?.let { what ->
                skipped.getOrPut(what) { ArrayList() } += relative
                continue
            }
            val reader = readers.readerOf(name)
            if (reader == null) {
                sources += Source.database(relative, path, Files.readAllBytes(real), encoding)
                continue
            }
            // Known by its identity: two files with the same one are the same module.
            val moduleName = readers.moduleName(name)
            if (moduleName == null) {
                notModules += "$relative (${reader.name}: no module name)"
                continue
            }
            modules[moduleName]?.let { first ->
                duplicates += "$relative (indexed from $first)"
                continue
            }
            val bytes = Files.readAllBytes(real)
            val module = try {
                reader.read(name, bytes)
            } catch (e: Exception) {
                notModules += "$relative (${reader.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()})"
                continue
            }
            if (module == null) {
                notModules += "$relative (${reader.name})"
                continue
            }
            modules[moduleName] = relative
            val text = FormsModuleText.of(module, moduleName)
            sources += Source(relative, path, text.text, StandardCharsets.UTF_8, sha256(bytes), bytes.size.toLong(), FormsLayout.of(module, moduleName, text))
        }
        val notes = ArrayList<CoverageFact>()
        for ((what, paths) in skipped) {
            notes += CoverageFact("FORMS_FILE_SKIPPED", "${paths.size} $what file(s) not indexed: ${shown(paths)}")
        }
        if (notModules.isNotEmpty()) notes += CoverageFact("FORMS_FILE_NOT_READ", "${notModules.size} file(s) held no module their reader could read: ${shown(notModules)}")
        if (duplicates.isNotEmpty()) {
            notes += CoverageFact("FORMS_MODULE_DUPLICATED", "${duplicates.size} module file(s) not indexed: a file of the same module, first by path, is: ${shown(duplicates)}")
        }
        return Discovered(sources, outside.sorted(), notes)
    }

    /** The first items of [items], for a note. */
    private fun shown(items: List<String>): String = items.take(NOTE_SHOWN).joinToString(", ") + if (items.size > NOTE_SHOWN) ", …" else ""

    companion object {
        private fun lineCount(text: String): Int {
            var count = 1
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> count++
                    '\r' -> {
                        count++
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                }
                i++
            }
            return count
        }

        /** How many paths a note of [discover] names. */
        private const val NOTE_SHOWN = 20

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
