//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.extract.*
import com.felipebz.zpa.atlas.model.CoverageFact
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.project.*
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The file walks of an [Indexer] run with [options]: zpa's project preparation once over the database sources, the
 * object types' members, then one [AstScanner] walk per file, each with a new [FileExtractor] and the checks of
 * [visitors]. With [phases], it records where the time goes.
 */
internal class FileWalks(
    private val options: IndexOptions,
    private val phases: PhaseTimes?,
    private val visitors: (path: String, extractor: FileExtractor) -> List<PlSqlCheck>
) {
    /**
     * What the walks leave for the rest of the run: every file's facts, in path order, how many files failed, the
     * incremental plan, and the entities, relations and notes of the run's declarations.
     */
    class Walked(
        val facts: List<FileFacts>,
        val failedFiles: Int,
        val run: IncrementalRun?,
        val globalEntities: ArrayList<EntityFact>,
        val globalRelations: ArrayList<RelationFact>,
        val globalCoverage: ArrayList<CoverageFact>
    )

    /**
     * Prepares the project, reads the object types' members and walks the files. Its own function, so that what only
     * the walks use (zpa's project analysis and scanner, the run's declaration catalog) is let go once they end: the
     * frame of [Indexer.index] keeps its locals until it returns.
     */
    fun walkSources(
        incremental: Boolean,
        pending: PendingFactsCache?,
        inputs: CompletableFuture<Map<String, String>>?,
        sources: List<Source>
    ): Walked {
        phases?.start(PhaseTimes.PREPARATION)

        // The project is the database sources: a module's program units are its own, never names of the database.
        val database = sources.filter { it.forms == null }
        // One context for the run: the index is prepared with the files' schemas, and the scan keeps them.
        val extractor = ProjectDeclarationExtractor(
            PlSqlConfiguration(
                StandardCharsets.UTF_8,
                true,
                options.conditionalCompilation
            )
        )
        val prepared = ProjectAnalysisContext.prepared(
            ProjectIndexPreparation.withSchemas(extractor, options.fileSchemas.context)
                .prepare(database.map { source ->
                    ProjectSource(
                        FileId(source.relativePath),
                        ProjectSourceReader { source.text })
                })
        )
        val context =
            if (options.dictionary.isAvailable) prepared.withMetadata(options.dictionary.metadata) else prepared

        val scanner =
            AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context, options.conditionalCompilation)
        val planCatalog =
            if (incremental) IncrementalRun.catalog(options, context, sources.map { it.relativePath }, phases) else null
        // The members of the object types, read before any type body is walked.
        val typeMembers = ObjectTypeMembers()
        phases?.start(PhaseTimes.TYPE_MEMBERS)
        for (source in database) {
            if (ObjectTypeMembers.MAY_SPECIFY_OBJECT.containsMatchIn(source.text)) {
                scanner.scanFile(
                    source,
                    listOf(typeMembers.collector(source.relativePath, options.fileSchemas)),
                    source.relativePath
                )
            }
        }
        val hashes = sources.associate { it.relativePath to it.sha256 }
        val run = if (!incremental) null else {
            val current = sources.map {
                val reader =
                    if (it.forms == null) null else options.readers.fingerprint(it.absolute.fileName.toString())
                IncrementalRule.Current(it.relativePath, hashes.getValue(it.relativePath), it.text, reader)
            }
            IncrementalRun.plan(options, typeMembers, current, pending!!, inputs!!, planCatalog!!, phases)
        }
        val toWalk = if (run == null) sources else sources.filter { it.relativePath in run.walk }
        phases?.start(PhaseTimes.FILE_WALKS)
        phases?.walkThreads = options.threads.coerceAtLeast(1)
        val catalogHolder = CatalogHolder(options.fileSchemas)
        val (walkedFacts, failedWalks) = walk(scanner, typeMembers, catalogHolder, toWalk)
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
        scanner: AstScanner, typeMembers: ObjectTypeMembers, catalogHolder: CatalogHolder, toWalk: List<Source>
    ): Pair<Map<String, FileFacts>, Int> {
        val strings = FactStrings()
        val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
        fun extractor(source: Source) = FileExtractor(
            source.relativePath, source.sha256, source.size, source.charset.name(), source.lineCount,
            options.dictionary, options.fileSchemas, catalogHolder::get,
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
                        scanner.scanFile(
                            source,
                            listOf(walkStart) + visitors(source.relativePath, extractor),
                            source.relativePath
                        )
                        val walked = System.nanoTime()
                        val facts = extractor.facts()
                        if (phases != null) {
                            phases.files += PhaseTimes.FileTime(
                                source.relativePath,
                                source.lineCount,
                                walkStart.at - taskStarted,
                                walked - walkStart.at,
                                System.nanoTime() - walked
                            )
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
                extractor(source).failedFacts(
                    catalogHolder.catalog,
                    CoverageFact(EXTRACTION_FAILED, "${e.javaClass.name}: $message")
                )
            }
        }
        return walkedFacts to walked.count { it.isFailure }
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

    /** The catalog of the run's snapshot, built once by the first file walk (every walk sees the same snapshot). */
    private class CatalogHolder(private val schemas: FileSchemas) {
        @Volatile
        var snapshot: ProjectSnapshot? = null
        @Volatile
        var catalog: DeclarationCatalog? = null

        @Synchronized
        fun get(current: ProjectSnapshot): DeclarationCatalog {
            val existing = catalog
            if (existing != null) {
                check(snapshot === current) { "Every file of a run must see the same project snapshot" }
                return existing
            }
            return DeclarationCatalog(current, schemas).also {
                snapshot = current
                catalog = it
            }
        }
    }

    companion object {
        /** The first Oracle version with the built-in `JSON` type: from it on, a type named `JSON` cannot exist. */
        private const val JSON_BUILT_IN_VERSION = 21

        private const val EXTRACTION_FAILED = "EXTRACTION_FAILED"
    }
}
