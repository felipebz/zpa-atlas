//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.DeclarationCatalog
import com.felipebz.zpa.atlas.extract.ObjectTypeMembers
import com.felipebz.zpa.atlas.extract.sha256
import java.util.Locale

/**
 * Which files an incremental run walks.
 *
 * A file's facts are a function of the inputs of its walk, and of nothing else (two full runs on any number of
 * threads give the same index, so walks do not read each other). Those inputs are:
 * - **its own path and text;**
 * - **the options and the dictionary** (compilation schema, encoding, conditional compilation, the Oracle metadata),
 *   and the code of atlas and ZPA: the cache records them, and any difference is a full run;
 * - **the project snapshot:** the declarations ZPA's preparation extracts from every file, which name resolution, call
 *   binding and type descriptions read, plus three project-wide facts: the preparation state and its failed files,
 *   and the standalone callables whose heading gave no declaration;
 * - **the declaration catalog,** derived from the snapshot: the entity keys of every declaration (an overload key
 *   collision numbers keys across files), the body subprograms that implement a specification, and the schemas of
 *   the declared entities;
 * - **the object type members,** read from every object type spec before the walks, with their supertypes.
 *
 * So a file must be walked again when its text changed, when it is new, or when one of these inputs changed in a way
 * its walk can see:
 * - **The preparation state, its failed files or the declared schemas changed:** every file is walked.
 * - **A declaration changed** (its rendering without positions differs, or it appeared or disappeared in a changed,
 *   new or removed file): its name is *touched*. A walk reaches a declaration only through a name it writes: directly,
 *   or through another declaration that refers to it (a variable of a record type, a function returning it, a subtype
 *   of it, a cursor's row type), or through a dictionary synonym of it. So the rule closes over the snapshot and the
 *   synonyms: a declaration that refers to a touched name is itself touched, and so is a synonym of a touched name,
 *   until nothing changes.
 * - **An omitted standalone callable appeared or disappeared:** its name is touched.
 * - **An object type spec changed,** or a type under it: its name is touched.
 * - **The catalog gives a file something else** (keys, roles, implemented specifications): that file is walked, and
 *   the names in what changed are touched: other files reach those keys through these names.
 *
 * Every file whose text writes a touched name, as an identifier anywhere (comments and strings included, case
 * ignored), is walked as *affected*. Line shifts alone touch nothing: a walk reads the positions of its own file only.
 */
internal object IncrementalRule {
    /** The run's facts about one file, as the rule compares them. */
    class Current(val path: String, val sha256: String, val text: String)

    class Plan(
        val walk: Set<String>,
        val changed: Int,
        val new: Int,
        val affected: Int,
        val removed: Int,
        /** Why every file is walked, when a project-wide input changed. */
        val everything: String?,
        /** Some of the names that made files affected. */
        val touched: List<String>
    )

    private val SKIPPED = setOf(
        "com.felipebz.zpa.project.SourceRange", "com.felipebz.zpa.api.project.ProjectSourceRange", "com.felipebz.zpa.api.project.ProjectDeclarationId"
    )

    /** The declarations of each of [paths] in [snapshot], as the cache keeps them; rendered in parallel, one file per task. */
    fun declarations(snapshot: ProjectSnapshot, paths: Collection<String>): Map<String, List<CachedDeclaration>> =
        paths.parallelStream().map { path ->
            path to snapshot.declarationsFor(path).declarations.map { view ->
                val rendering = Rendering(SKIPPED)
                val text = rendering.render(view)
                val name = view.qualifiedName.last.originalSpelling.removeSurrounding("\"").uppercase(Locale.ROOT)
                CachedDeclaration(sha256(text), name, rendering.strings + name)
            }
        }.toList().filter { it.second.isNotEmpty() }.toMap()

    /** The project-wide inputs; [CacheGlobal.omitted] holds `?` when ZPA's omitted callables cannot be read. */
    fun global(snapshot: ProjectSnapshot, catalog: DeclarationCatalog, typeMembers: ObjectTypeMembers): CacheGlobal = CacheGlobal(
        preparation = snapshot.preparationState.name + " " + snapshot.failures.map { it.fileId }.sorted(),
        schemas = catalog.schemas,
        omitted = omittedCallables(snapshot),
        typeMembers = typeMembers.summaries().mapValues { (_, summary) -> TypeSummary(summary.first, summary.second) }
    )

    /**
     * The standalone callables whose heading gave no declaration. ZPA keeps them in its index and offers no public
     * accessor, so they are read through its fields; `?` when that fails, and the rule then walks every file.
     */
    private fun omittedCallables(snapshot: ProjectSnapshot): Set<String> = try {
        val result = Rendering.field(snapshot, "preparationResult")!!
        val index = result.javaClass.getMethod("getIndex").invoke(result)
        (Rendering.field(index, "omittedStandaloneCallables") as Set<*>).mapTo(HashSet()) { Rendering().render(it) }
    } catch (_: Exception) {
        setOf(UNREADABLE)
    }

    private const val UNREADABLE = "?"

    fun plan(
        cached: CacheContents,
        current: List<Current>,
        declarations: Map<String, List<CachedDeclaration>>,
        digests: Map<String, String>,
        global: CacheGlobal,
        synonyms: List<DictionarySynonym>
    ): Plan {
        val before = cached.files.associateBy { it.path }
        val paths = current.mapTo(HashSet()) { it.path }
        val changed = current.filter { before[it.path]?.let { old -> old.sha256 != it.sha256 } == true }.mapTo(HashSet()) { it.path }
        val new = current.filter { it.path !in before }.mapTo(HashSet()) { it.path }
        val removed = before.keys.filter { it !in paths }
        val all = { why: String -> Plan(paths, changed.size, new.size, paths.size - changed.size - new.size, removed.size, why, emptyList()) }

        when {
            UNREADABLE in global.omitted || UNREADABLE in cached.global.omitted -> return all("the omitted callables of the preparation cannot be read")
            global.preparation != cached.global.preparation -> return all("the preparation state or its failed files changed")
            global.schemas != cached.global.schemas -> return all("the schemas of the declared entities changed")
        }

        val touched = LinkedHashSet<String>()
        // Declarations that appeared, disappeared or changed in the files whose text changed.
        for (path in changed + new + removed) {
            val old = before[path]?.declarations.orEmpty().groupingBy { it.rendering }.eachCount()
            val now = declarations[path].orEmpty().groupingBy { it.rendering }.eachCount()
            for (declaration in before[path]?.declarations.orEmpty() + declarations[path].orEmpty()) {
                if (old[declaration.rendering] != now[declaration.rendering]) touched += declaration.name
            }
        }
        for (name in global.omitted.symmetricDifference(cached.global.omitted)) touched += Rendering().also { it.render(name) }.strings
        touched += changedTypes(cached.global.typeMembers, global.typeMembers).map { it.substringAfterLast('.') }
        val catalogChanged = current.filter { it.path !in changed && it.path !in new && before.getValue(it.path).catalogDigest != digests[it.path] }
            .mapTo(HashSet()) { it.path }
        for (path in catalogChanged) touched += catalogNames(before.getValue(path).catalogDigest, digests.getValue(path))

        if (touched.isEmpty()) return Plan(changed + new + catalogChanged, changed.size, new.size, catalogChanged.size, removed.size, null, emptyList())
        // Close over the snapshot and the synonyms: what refers to a touched name, or names it, is touched.
        val byReference = HashMap<String, MutableList<String>>()
        for (list in declarations.values) for (declaration in list) for (name in declaration.references) byReference.getOrPut(name) { ArrayList() } += declaration.name
        for (synonym in synonyms) byReference.getOrPut(synonym.targetName.uppercase(Locale.ROOT)) { ArrayList() } += synonym.name.uppercase(Locale.ROOT)
        val queue = ArrayDeque(touched)
        while (queue.isNotEmpty()) {
            for (name in byReference[queue.removeFirst()].orEmpty()) {
                if (touched.add(name)) queue += name
            }
        }

        val affected = run {
            val lengths = touched.mapTo(HashSet()) { it.length }
            current.parallelStream().filter { source ->
                source.path !in changed && source.path !in new && (source.path in catalogChanged || writesAny(source.text, touched, lengths))
            }.map { it.path }.toList().toHashSet()
        }
        return Plan(changed + new + affected, changed.size, new.size, affected.size, removed.size, null, touched.take(TOUCHED_SHOWN))
    }

    private const val TOUCHED_SHOWN = 5

    /**
     * The names in the lines that differ between two catalog digests of a file (see [DeclarationCatalog.digests]): the
     * written name and the entity's name of a declaration line, the entities' names of an `implements` line. A note
     * line names nothing other files read.
     */
    private fun catalogNames(before: String, now: String): Set<String> {
        val old = before.lines().toSet()
        val new = now.lines().toSet()
        val names = HashSet<String>()
        for (line in (old - new) + (new - old)) {
            when {
                line.startsWith(IMPLEMENTS) -> line.removePrefix(IMPLEMENTS).split(" -> ").forEach { names += keyName(it) }
                '|' in line -> {
                    val (written, key) = line.split('|')
                    names += written.removeSurrounding("\"").uppercase(Locale.ROOT)
                    names += keyName(key)
                }
            }
        }
        names -= ""
        return names
    }

    private const val IMPLEMENTS = "implements "

    /** The name of the entity [key] (`db:S.P.F(P_A IN NUMBER)@BODY#2` is `F`), upper-cased. */
    private fun keyName(key: String): String =
        key.substringBefore('(').substringBefore('@').substringBefore('#').substringAfterLast('.').removeSurrounding("\"").uppercase(Locale.ROOT)

    /** The type keys whose spec changed, appeared or disappeared, and every type under one of them. */
    private fun changedTypes(before: Map<String, TypeSummary>, now: Map<String, TypeSummary>): Set<String> {
        val changed = (before.keys + now.keys).filterTo(HashSet()) { key ->
            val old = before[key]
            val new = now[key]
            old?.members != new?.members || old?.supertype != new?.supertype
        }
        var grew = true
        while (grew) {
            grew = false
            for ((key, type) in now) if (key !in changed && type.supertype in changed) grew = changed.add(key) || grew
        }
        return changed
    }

    private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)

    /**
     * Whether [text] writes one of [names] (upper-cased) as a word anywhere ([SourceWords]): it may write more than the
     * names it uses, never fewer. A word is compared upper-cased with `Locale.ROOT`, a quoted one too, as the names
     * are. [lengths] are the names' lengths: an ASCII word of another length is skipped without being copied. A word
     * with other characters is always compared, as its upper case may be longer (`ß` is `SS`).
     */
    fun writesAny(text: String, names: Set<String>, lengths: Set<Int>): Boolean {
        fun matches(from: Int, to: Int): Boolean {
            if (to - from !in lengths && (from until to).all { text[it].code < 0x80 }) return false
            return text.substring(from, to).uppercase(Locale.ROOT) in names
        }
        return SourceWords.find(text, ::matches, ::matches)
    }
}
