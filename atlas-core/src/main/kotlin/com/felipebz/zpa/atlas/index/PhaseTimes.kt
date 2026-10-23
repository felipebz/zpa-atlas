//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Collections
import java.util.Locale

/**
 * Where a run of `index` spends its time: one phase after another, each with its wall time and the peak heap used during
 * it, and the time of each file walk. Measuring adds nothing to the index.
 *
 * The peak heap of a phase is the sum of the peaks of the heap memory pools, reset when the phase starts. The pools peak
 * at different moments, so the sum can exceed what was used at any one moment; no garbage collection is asked for.
 *
 * @param started when the run started, as [System.nanoTime] gives it
 */
class PhaseTimes(private val started: Long = System.nanoTime()) {
    class Phase(val name: String, val nanos: Long, val peakHeapBytes: Long)

    /**
     * One file walk: [beforeWalkNanos] from the walk's task starting to the walk taking the scanner's lock (the
     * extractor's setup, the parse, the wait for the lock), [walkNanos] under the lock (semantic analysis and
     * extraction) until the scanner returns.
     */
    class FileTime(val path: String, val lines: Int, val beforeWalkNanos: Long, val walkNanos: Long) {
        val nanos: Long get() = beforeWalkNanos + walkNanos
    }

    private val phases = ArrayList<Phase>()
    private var current: String? = null
    private var currentStart = 0L
    private val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

    val files: MutableList<FileTime> = Collections.synchronizedList(ArrayList())

    /** The threads the file walks ran on. */
    var walkThreads = 0

    /** Ends the phase running, if any, and starts [name]. */
    fun start(name: String) {
        stop()
        heapPools.forEach { it.resetPeakUsage() }
        current = name
        currentStart = System.nanoTime()
    }

    /** Ends the phase running, if any. */
    fun stop() {
        val name = current ?: return
        phases += Phase(name, System.nanoTime() - currentStart, heapPools.sumOf { it.peakUsage?.used ?: 0L })
        current = null
    }

    /** The table of the phases and the file walks, the total counted from the start of the run to now. */
    fun report(): String {
        stop()
        val total = System.nanoTime() - started
        val width = maxOf(phases.maxOfOrNull { it.name.length } ?: 0, GAP.length, TOTAL.length)
        fun row(name: String, nanos: Long, peak: String) =
            String.format(Locale.ROOT, "  %-${width}s %9d %6.1f%% %s", name, nanos / 1_000_000, 100.0 * nanos / total, peak).trimEnd() + "\n"
        return buildString {
            append(String.format(Locale.ROOT, "Phases:%n  %-${width}s %9s %7s %s%n", "phase", "ms", "share", "peak heap MB"))
            for (phase in phases) append(row(phase.name, phase.nanos, String.format(Locale.ROOT, "%12d", phase.peakHeapBytes / MB)))
            append(row(GAP, total - phases.sumOf { it.nanos }, ""))
            append(row(TOTAL, total, ""))
            walks()?.let { append(it) }
        }
    }

    private fun walks(): String? {
        val walked = files.toList()
        if (walked.isEmpty()) return null
        val wall = phases.firstOrNull { it.name == FILE_WALKS }?.nanos ?: return null
        val before = walked.sumOf { it.beforeWalkNanos }
        val under = walked.sumOf { it.walkNanos }
        return buildString {
            append(String.format(Locale.ROOT, "File walks: %d files on %d threads, wall %d ms; the sum of the per-file times is %d ms:%n",
                walked.size, walkThreads, wall / 1_000_000, (before + under) / 1_000_000))
            append(String.format(Locale.ROOT, "  before the walk (the extractor's setup, the parse, the wait for the scanner's lock) %d ms%n", before / 1_000_000))
            append(String.format(Locale.ROOT, "  the walk, under the scanner's lock (semantic analysis and extraction) %d ms: the lock held %.1f%% of the wall time%n",
                under / 1_000_000, 100.0 * under / wall))
            append(String.format(Locale.ROOT, "Slowest files:%n  %9s %9s %9s %7s  %s%n", "ms", "before", "walk", "lines", "path"))
            for (file in walked.sortedWith(compareByDescending<FileTime> { it.nanos }.thenBy { it.path }).take(SLOWEST)) {
                append(String.format(Locale.ROOT, "  %9d %9d %9d %7d  %s%n", file.nanos / 1_000_000, file.beforeWalkNanos / 1_000_000,
                    file.walkNanos / 1_000_000, file.lines, file.path))
            }
        }
    }

    companion object {
        const val DICTIONARY_NAMES = "oracle dictionary: written names"
        const val DICTIONARY_METADATA = "oracle dictionary: metadata load"
        const val READING = "reading the sources"
        const val PREPARATION = "project preparation"
        const val TYPE_MEMBERS = "object type members"
        const val INCREMENTAL_PLAN = "incremental: reading the facts cache, plan"
        const val FILE_WALKS = "file walks"
        const val GLOBAL_FACTS = "global facts"
        const val WRITING_TABLES = "writing: tables"
        const val WRITING_ENTITIES = "writing: entities and relations"
        const val WRITING_FILES = "writing: file rows"
        const val WRITING_LINES = "writing: source lines and text index"
        const val WRITING_INDEXES = "writing: secondary indexes"
        const val WRITING_COMMIT = "writing: commit and file move"
        const val WRITING_CACHE = "writing: facts cache, after the index"
        const val GAP = "outside the phases"
        const val TOTAL = "total"
        private const val SLOWEST = 10
        private const val MB = 1024L * 1024
    }
}
