//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.atlas.extract.FileExtractor
import com.felipebz.zpa.atlas.extract.FormsReferences
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.model.CoverageFact
import com.felipebz.zpa.atlas.writer.AtlasWriter
import com.felipebz.zpa.atlas.writer.RunInfo
import com.felipebz.zpa.atlas.writer.SourceTextIndex
import com.felipebz.zpa.squid.AstScanner
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.io.path.invariantSeparatorsPathString

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
     * The sources of a run, read, with the build of their text index started: it needs only their texts, in path
     * order. Read before the dictionary loads ([readSources]), the build overlaps that load as well. Closing it waits for
     * the build and deletes its file.
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
    ) : AutoCloseable by textIndex

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
        val discovered = SourceDiscovery.discover(root, extensions, encoding, readers)
        val textIndex = SourceTextIndex.start(output, discovered.sources.map { it.relativePath to it.text })
        return ReadSources(
            root, extensions, encoding, output.toAbsolutePath().normalize(), readers, discovered.sources, discovered.outsideRoot, discovered.notes, textIndex,
            startedAt, started
        )
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
            val sameFiles = read.root == options.sources.toAbsolutePath().normalize() && read.extensions == options.extensions
            val sameReading = read.encoding == options.encoding && read.readers === options.readers
            val sameOutput = read.output == options.output.toAbsolutePath().normalize()
            require(sameFiles && sameReading && sameOutput) { "The sources were read for another run" }
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
        val sources = read.sources
        val textIndex = read.textIndex
        val started = read.started
        val walked = FileWalks(options, phases, visitors).walkSources(incremental, pending, inputs, sources)
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
        val dictionaryFacts = DictionaryFacts.merge(options.dictionary, facts, globalEntities, globalRelations, globalCoverage)
        val (places, placesLeftOut) = ColumnPlaces.of(facts, globalEntities, dictionaryFacts.resolved, options.dictionary, options.nlsLengthSemantics)

        val texts = sources.associate { it.relativePath to it.text }
        val phase: (String) -> Unit = { phases?.start(it) }
        // The cache is written while the index is: neither reads what the other writes.
        val saving = run?.save(facts)
        try {
            AtlasWriter.writeAtomically(options.output, textIndex, phase) { writer ->
                writer.write(
                    runInfo(options, read),
                    // The cache keeps the calls of modules as the walks wrote them: which modules the run has is checked on every run.
                    FormsReferences.resolveModuleCalls(facts), globalEntities, globalRelations, globalCoverage, texts::get, phase,
                    dictionaryDeclaredTexts = dictionaryFacts.declaredTexts, keys = dictionaryFacts.keys, places = places, placesLeftOut = placesLeftOut
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

    /** The [RunInfo] of the run that indexes [options] from [read], finished now. */
    private fun runInfo(options: IndexOptions, read: ReadSources) = RunInfo(
        atlasVersion = version("atlas"),
        zpaVersion = version("zpa"),
        sourcesRoot = read.root.invariantSeparatorsPathString,
        sourceEncoding = SourceDecoding.validate(options.encoding),
        metadataOrigin = options.dictionary.metadata.origin.orElse(null),
        compilationSchema = options.compilationSchema,
        schemaPaths = options.fileSchemas.paths,
        databaseVersion = options.conditionalCompilation.databaseVersion?.let { ConditionalCompilationText.version(it) },
        databaseVersionOrigin = options.databaseVersionOrigin?.takeIf { options.conditionalCompilation.databaseVersion != null }?.name,
        ccflags = ConditionalCompilationText.flags(options.conditionalCompilation.flags),
        nlsLengthSemantics = options.nlsLengthSemantics,
        startedAt = read.startedAt.toString(),
        finishedAt = clock().toString()
    )

    /**
     * Starts reading the facts cache of the index [output] on its own thread, for [index] with `incremental`: started
     * before the dictionary is loaded, the reading overlaps that load.
     */
    fun readFactsCache(output: Path): PendingFactsCache = IncrementalRun.readCache(output)

    companion object {
        /** The version of `atlas` or `zpa` this build was made with. */
        internal fun version(name: String): String = VERSIONS.getProperty(name, "unknown")

        private val VERSIONS = Properties().apply {
            Indexer::class.java.getResourceAsStream("/zpa-atlas-version.properties")?.use { load(it) }
        }
    }
}
