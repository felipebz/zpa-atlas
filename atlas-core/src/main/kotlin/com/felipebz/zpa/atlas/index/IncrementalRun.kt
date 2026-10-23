//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.extract.DeclarationCatalog
import com.felipebz.zpa.atlas.extract.ObjectTypeMembers
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.squid.AstScanner
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture

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
    private val cached: Map<String, CachedFile>,
    val walk: Set<String>,
    val report: IncrementalReport
) {
    fun cachedFacts(path: String): FileFacts = cached.getValue(path).facts

    /** Starts writing the cache of this run on another thread, [facts] being every file's, walked or not. */
    fun save(facts: List<FileFacts>): CompletableFuture<Void> = CompletableFuture.runAsync {
        val byPath = facts.associateBy { it.path }
        val files = current.sortedBy { it.path }.map { source ->
            CachedFile(source.path, source.sha256, declarations[source.path].orEmpty(), digests.getValue(source.path), byPath.getValue(source.path))
        }
        FactsCache.write(cachePath, CacheContents(inputs, global, files))
    }

    companion object {
        /**
         * Starts reading the facts cache of [options] on another thread, so that it overlaps the preparation: the
         * cache's contents, or the reason for a full run.
         */
        fun readCache(options: IndexOptions): CompletableFuture<Any> = CompletableFuture.supplyAsync {
            val cachePath = FactsCache.pathFor(options.output.toAbsolutePath())
            if (!Files.exists(cachePath)) return@supplyAsync "no facts cache yet (${cachePath.fileName})"
            try {
                FactsCache.read(cachePath, options.dictionary, inputsOf(options))
            } catch (e: CacheMismatchException) {
                "the facts cache was written with another ${e.input}"
            } catch (e: DamagedCacheException) {
                "the facts cache cannot be used: ${e.message}"
            }
        }

        /** The plan of a run whose cache [readCache] gives: [CacheContents], or the reason for a full run. */
        fun plan(
            options: IndexOptions, context: ProjectAnalysisContext, typeMembers: ObjectTypeMembers, current: List<IncrementalRule.Current>,
            read: CompletableFuture<Any>
        ): IncrementalRun {
            val cachePath = FactsCache.pathFor(options.output.toAbsolutePath())
            val snapshot = context.createSnapshot()
            val catalog = DeclarationCatalog(snapshot, options.compilationSchema)
            val inputs = inputsOf(options)
            val global = IncrementalRule.global(snapshot, catalog, typeMembers)
            val digests = current.associate { it.path to catalog.digestFor(it.path) }
            val paths = current.mapTo(HashSet()) { it.path }

            fun full(reason: String) = IncrementalRun(
                cachePath, snapshot, catalog, inputs, global, current, IncrementalRule.declarations(snapshot) { it in paths }, digests, emptyMap(), paths,
                IncrementalReport(current.size, current.size, 0, 0, 0, 0, reason)
            )

            val contents = when (val result = read.join()) {
                is CacheContents -> result
                else -> return full(result.toString())
            }
            val cached = contents.files.associateBy { it.path }
            val unchanged = current.filter { cached[it.path]?.sha256 == it.sha256 }.mapTo(HashSet()) { it.path }
            val rendered = IncrementalRule.declarations(snapshot) { it in paths && it !in unchanged }
            val declarations = current.associate { it.path to if (it.path in unchanged) cached.getValue(it.path).declarations else rendered[it.path].orEmpty() }
            val plan = IncrementalRule.plan(contents, current, declarations, digests, global, options.dictionary.synonyms)
            return IncrementalRun(
                cachePath, snapshot, catalog, inputs, global, current, declarations, digests, cached, plan.walk,
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
            "dictionary (the Oracle options or what they read)" to
                sha256(Rendering().render(options.dictionary).replace(ACQUIRED_AT, ""))
        )

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
