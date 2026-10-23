//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.DeclarationCatalog
import com.felipebz.zpa.atlas.extract.ObjectTypeMembers
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.squid.AstScanner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** What an incremental run did: the files it walked and why, or why it walked them all. */
data class IncrementalReport(
    val total: Int,
    val walked: Int,
    val changed: Int,
    val new: Int,
    val affected: Int,
    val removed: Int,
    /** Why the run is a full one; null for an incremental run. */
    val fullReason: String?,
    /** Why an incremental run walked every file, when a project-wide input changed. */
    val everything: String? = null,
    val touched: List<String> = emptyList()
) {
    fun line(): String = if (fullReason != null) "Full run: $fullReason; the facts cache is written for the next run" else buildString {
        append("Incremental run: walked $walked of $total files ($changed changed, $new new, $affected affected), $removed removed")
        if (everything != null) append("; every file is affected: $everything")
        else if (touched.isNotEmpty()) append("; affected through ${touched.joinToString(", ")}")
    }
}

/**
 * The facts cache of an index, being read on its own thread: started by [Indexer.readFactsCache] before the dictionary
 * is loaded, so that reading it overlaps that load.
 */
class PendingFactsCache internal constructor(internal val result: CompletableFuture<IncrementalRun.CacheRead>)

/**
 * An `index --incremental` run: it reads the facts cache beside the index, plans which files to walk with
 * [IncrementalRule], gives the cached facts of the others, and writes the cache for the next run.
 */
internal class IncrementalRun private constructor(
    private val cachePath: Path,
    val snapshot: ProjectSnapshot,
    val catalog: DeclarationCatalog,
    private val inputs: Map<String, String>,
    private val global: CacheGlobal,
    private val current: List<IncrementalRule.Current>,
    private val declarations: Map<String, List<CachedDeclaration>>,
    private val digests: Map<String, String>,
    private val cached: Map<String, FileFacts>,
    val walk: Set<String>,
    val report: IncrementalReport
) {
    fun cachedFacts(path: String): FileFacts = cached.getValue(path)

    /** Starts writing the cache of this run on another thread, [facts] being every file's, walked or not. */
    fun save(facts: List<FileFacts>): CompletableFuture<Void> = CompletableFuture.runAsync {
        val byPath = facts.associateBy { it.path }
        val files = current.sortedBy { it.path }.map { source ->
            CachedFile(source.path, source.sha256, declarations[source.path].orEmpty(), digests.getValue(source.path), byPath.getValue(source.path), emptyList())
        }
        FactsCache.write(cachePath, CacheContents(inputs, global, files))
    }

    /** The cache as read, or why it cannot be used; and how long the reading took. */
    class CacheRead(val contents: CacheContents?, val reason: String?, val millis: Long)

    companion object {
        /** Starts reading the facts cache beside [index] on its own thread. */
        fun readCache(index: Path): PendingFactsCache = PendingFactsCache(onItsOwnThread("facts cache") {
            val started = System.nanoTime()
            val cachePath = FactsCache.pathFor(index.toAbsolutePath())
            val (contents, reason) = when {
                !Files.exists(cachePath) -> null to "no facts cache yet (${cachePath.fileName})"
                else -> try {
                    FactsCache.read(cachePath) to null
                } catch (e: DamagedCacheException) {
                    null to "the facts cache cannot be used: ${e.message}"
                }
            }
            CacheRead(contents, reason, (System.nanoTime() - started) / 1_000_000)
        })

        /** Starts computing what the facts depend on besides the sources, on its own thread: the dictionary's share is large. */
        fun inputs(options: IndexOptions, phases: PhaseTimes?): CompletableFuture<Map<String, String>> = onItsOwnThread("facts cache inputs") {
            val started = System.nanoTime()
            inputsOf(options).also { phases?.notes?.add("Facts cache inputs (versions, options, dictionary) computed on their own thread in ${(System.nanoTime() - started) / 1_000_000} ms") }
        }

        private fun <T> onItsOwnThread(name: String, task: () -> T): CompletableFuture<T> {
            val executor = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }
            return CompletableFuture.supplyAsync(task, executor).whenComplete { _, _ -> executor.shutdown() }
        }

        /** The run's own snapshot and catalog, and what the catalog gives each file. */
        class Catalog(val snapshot: ProjectSnapshot, val catalog: DeclarationCatalog, val digests: Map<String, String>)

        /**
         * Starts building the plan's snapshot and catalog of [context], and the catalog digests of [paths], on its own
         * thread: they need only the prepared declarations, so they overlap the object type members pass.
         */
        fun catalog(options: IndexOptions, context: ProjectAnalysisContext, paths: List<String>, phases: PhaseTimes?): CompletableFuture<Catalog> =
            onItsOwnThread("incremental catalog") {
                val started = System.nanoTime()
                val snapshot = context.createSnapshot()
                val catalog = DeclarationCatalog(snapshot, options.compilationSchema)
                val built = System.nanoTime()
                val digests = catalog.digests(paths)
                phases?.notes?.add("Plan snapshot and catalog built on their own thread in ${(built - started) / 1_000_000} ms, " +
                    "catalog digests in ${(System.nanoTime() - built) / 1_000_000} ms")
                Catalog(snapshot, catalog, digests)
            }

        /** The plan of a run whose cache [read] gives, the inputs being [inputs]'s and the catalog [catalog]'s. */
        fun plan(
            options: IndexOptions, typeMembers: ObjectTypeMembers, current: List<IncrementalRule.Current>, read: PendingFactsCache,
            inputs: CompletableFuture<Map<String, String>>, catalog: CompletableFuture<Catalog>, phases: PhaseTimes? = null
        ): IncrementalRun {
            val cachePath = FactsCache.pathFor(options.output.toAbsolutePath())
            phases?.start(PhaseTimes.PLAN_CATALOG)
            val (snapshot, runCatalog, digests) = catalog.join().let { Triple(it.snapshot, it.catalog, it.digests) }
            phases?.start(PhaseTimes.PLAN_GLOBAL)
            val global = IncrementalRule.global(snapshot, runCatalog, typeMembers)
            phases?.start(PhaseTimes.PLAN_INPUTS)
            val runInputs = inputs.join()
            phases?.start(PhaseTimes.PLAN_WAIT)
            val read = read.result.join()
            phases?.notes?.add("Facts cache read on its own thread in ${read.millis} ms")

            fun full(reason: String): IncrementalRun {
                phases?.start(PhaseTimes.PLAN_DECLARATIONS)
                return IncrementalRun(
                    cachePath, snapshot, runCatalog, runInputs, global, current, IncrementalRule.declarations(snapshot, current.map { it.path }), digests,
                    emptyMap(), current.mapTo(HashSet()) { it.path }, IncrementalReport(current.size, current.size, 0, 0, 0, 0, reason)
                )
            }

            val contents = read.contents ?: return full(read.reason!!)
            val stored = contents.inputs
            (runInputs.keys.firstOrNull { stored[it] != runInputs[it] } ?: stored.keys.firstOrNull { it !in runInputs })?.let {
                return full("the facts cache was written with another $it")
            }
            val byKey = options.dictionary.relations.associate { RelationResolver.dictionaryKey(it.relation) to it.relation }
            val cached = try {
                contents.files.associate { it.path to FactsCache.withRelations(it, options.dictionary, byKey) }
            } catch (e: DamagedCacheException) {
                return full("the facts cache cannot be used: ${e.message}")
            }
            phases?.start(PhaseTimes.PLAN_DECLARATIONS)
            val cachedFiles = contents.files.associateBy { it.path }
            val changed = current.filter { cachedFiles[it.path]?.sha256 != it.sha256 }.map { it.path }
            val rendered = IncrementalRule.declarations(snapshot, changed)
            val declarations = current.associate { it.path to (cachedFiles[it.path]?.takeIf { old -> old.sha256 == it.sha256 }?.declarations ?: rendered[it.path].orEmpty()) }
            phases?.start(PhaseTimes.PLAN_RULE)
            val plan = IncrementalRule.plan(contents, current, declarations, digests, global, options.dictionary.synonyms)
            return IncrementalRun(
                cachePath, snapshot, runCatalog, runInputs, global, current, declarations, digests, cached, plan.walk,
                IncrementalReport(current.size, plan.walk.size, plan.changed, plan.new, plan.affected, plan.removed, null, plan.everything, plan.touched)
            )
        }

        /** What the facts depend on besides the sources, by the name a full run's reason gives it. */
        private fun inputsOf(options: IndexOptions): Map<String, String> = linkedMapOf(
            "atlas version" to Indexer.version("atlas"),
            "ZPA version" to Indexer.version("zpa"),
            "atlas or ZPA build" to CODE,
            "--compilation-schema" to options.compilationSchema.orEmpty(),
            "--encoding" to options.encoding,
            "--extensions" to options.extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.sorted().joinToString(","),
            "conditional compilation (--database-version, --ccflags)" to Rendering().render(options.conditionalCompilation),
            "dictionary (the Oracle options or what they read)" to try {
                fingerprint(options.dictionary)
            } catch (_: Exception) {
                // The metadata's fields could not be read: the whole dictionary, rendered, is slower but complete.
                sha256(Rendering().render(options.dictionary).replace(ACQUIRED_AT, ""))
            }
        )

        /**
         * A checksum of everything [dictionary] holds, its acquisition time aside: what it was built from (the metadata,
         * its relations, synonyms, schemas and object names), not the maps it derives from them. Each part is rendered on
         * its own and fed to the checksum, so no text of the whole dictionary is ever built.
         */
        internal fun fingerprint(dictionary: AtlasDictionary): String {
            if (dictionary === AtlasDictionary.NONE) return "none"
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            val metadata = dictionary.metadata
            add(metadata.origin.orElse("").replace(ACQUIRED_AT, ""))
            // ZPA keeps the metadata's complete schemas and relations private; it has no other way to list them.
            add(Rendering().render(Rendering.field(metadata, "completeSchemas")))
            val relations = Rendering.field(metadata, "relations") as Map<*, *>
            add("relations ${relations.size}")
            relations.values.map { it as com.felipebz.zpa.api.project.ProjectExternalRelation }.sortedBy { RelationResolver.dictionaryKey(it) + "\u0000" + it.schema.originalSpelling + "." + it.name.originalSpelling }
                .forEach { relation ->
                    add("${relation.schema.originalSpelling}.${relation.name.originalSpelling} complete=${relation.isColumnsComplete} view=${relation.isView}")
                    add(Rendering().render(relation.rowObjectType))
                    relation.columns.forEach { add(Rendering().render(it)) }
                }
            add("enumerable ${dictionary.enumerable}")
            dictionary.relations.map { RelationResolver.dictionaryKey(it.relation) + " " + it.kind }.sorted().forEach(::add)
            add("schemas ${dictionary.schemas.sorted()}")
            add("synonyms ${dictionary.synonyms.size}")
            dictionary.synonyms.map { it.toString() }.sorted().forEach(::add)
            for ((schema, objects) in dictionary.objects.toSortedMap()) {
                add("objects of $schema ${objects.size}")
                objects.entries.map { "${it.key} ${it.value}" }.sorted().forEach(::add)
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        /** The acquisition time in the dictionary's origin: it differs on every load of the same metadata. */
        private val ACQUIRED_AT = Regex(" at \\d{4}-\\d{2}-\\d{2}T[^ \"]*")

        /** The checksums of the atlas and ZPA jars; a build run from class folders (the tests) relies on the versions. */
        private val CODE: String by lazy {
            listOf(Indexer::class.java, AstScanner::class.java).joinToString(" ") { type ->
                val location = Path.of(type.protectionDomain.codeSource.location.toURI())
                if (Files.isRegularFile(location)) sha256(Files.readAllBytes(location)) else "classes"
            }
        }
    }
}
