//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.io.PrintStream
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lines on [out] that say where a run of `index` is: one when a step starts, saying what it does; while it runs, one
 * every [every] at most, with how far it got; one when it ends, with its time. Each line starts with the name of its
 * step in brackets, so that the lines of a step that runs beside another ([beside]) say which step they belong to.
 * Lines, never a line redrawn in place: the output often goes to a log.
 *
 * The steps of the run's own thread follow one another ([start]): starting one ends the one before.
 */
class RunProgress(private val out: PrintStream, private val every: Duration = Duration.ofSeconds(30)) : AutoCloseable {
    /** A step of the run, started at [started]; with a [total], how many of its [unit] are done ([advance]). */
    inner class Step internal constructor(val name: String, private val unit: String?, private val total: Int?) {
        private val started = System.nanoTime()
        private val done = AtomicInteger()

        @Volatile
        internal var lastLine = started

        @Volatile
        private var ended = false

        fun advance() {
            done.incrementAndGet()
        }

        /** A line of this step, between its start and its end. */
        fun note(text: String) = line(this, text)

        /** Ends the step with [text] and its time; nothing when it already ended. */
        fun end(text: String = "done") {
            if (ended) return
            ended = true
            running.remove(this)
            line(this, "$text in ${seconds(System.nanoTime() - started)}")
        }

        internal fun progress(now: Long): String {
            val elapsed = seconds(now - started)
            return if (total == null) "running, $elapsed so far" else "${done.get()} of $total $unit, $elapsed so far"
        }
    }

    private val running: MutableSet<Step> = Collections.newSetFromMap(ConcurrentHashMap())
    private var current: Step? = null
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "atlas-index-progress").apply { isDaemon = true } }

    init {
        val tick = minOf(every.toMillis(), 1_000L).coerceAtLeast(1L)
        ticker.scheduleAtFixedRate(::tick, tick, tick, TimeUnit.MILLISECONDS)
    }

    /** Ends the step of the run's thread, if any, and starts [name], told by [text]; with a [total], counted in [unit]. */
    fun start(name: String, text: String, unit: String? = null, total: Int? = null): Step {
        current?.end()
        return beside(name, text, unit, total).also { current = it }
    }

    /** Starts [name], which runs beside the steps of the run's thread: it ends on its own ([Step.end]). */
    fun beside(name: String, text: String, unit: String? = null, total: Int? = null): Step {
        val step = Step(name, unit, total)
        line(step, text)
        running += step
        return step
    }

    /** Ends the step of the run's thread, if any. */
    fun finish() {
        current?.end()
        current = null
    }

    override fun close() {
        ticker.shutdownNow()
    }

    private fun tick() {
        val now = System.nanoTime()
        for (step in running) if (now - step.lastLine >= every.toNanos()) line(step, step.progress(now))
    }

    private fun line(step: Step, text: String) {
        step.lastLine = System.nanoTime()
        out.println("[${step.name}] $text")
    }

    companion object {
        /** [nanos] as seconds with one decimal: `4.2 s`. */
        fun seconds(nanos: Long): String = String.format(Locale.ROOT, "%.1f s", nanos / 1e9)
    }
}
