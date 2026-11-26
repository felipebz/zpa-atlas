//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.SourceSpan
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/** Pending rows (all inserts together) after which [RowWriter.flushIfFull] hands them to the writing thread. */
private const val FLUSH_ROWS = 20_000

/** The batches of rows that exist at once: one being filled, the others waiting or being written. */
private const val BATCHES = 3

/**
 * Writes rows through [connection] on a thread of its own, while the caller builds the next ones. The caller fills
 * [Insert]s, one row at a time, and the rows are handed over in batches of about [FLUSH_ROWS]; at most [BATCHES] exist
 * at once, so a caller that builds faster than SQLite writes waits for a batch to come back. Each insert's rows are
 * written in the order they were added, so the rows and their ids do not depend on when a batch is written.
 *
 * From its creation to [finish] or [close], the writing thread is the only one that uses [connection] and the
 * statements it prepares: the caller reaches the connection only through [execute], whose action runs on that thread,
 * after the rows added before it. The connection is handed over by starting the thread and handed back by joining it.
 *
 * An error on the writing thread is thrown to the caller at its next hand-over ([flushIfFull], [execute], [drain],
 * [finish]), and the rows after it are not written. [close] without [finish] (the caller failed) drops what is left,
 * and returns once the thread has ended.
 */
internal class RowWriter(private val connection: Connection, name: String) : AutoCloseable {
    /** The statement of one SQL, as the caller fills it: its parameters keep their values from one row to the next. */
    inner class Insert internal constructor(internal val sql: String, internal val id: Int) : AutoCloseable {
        internal val width = sql.count { it == '?' }
        private val row = arrayOfNulls<Any>(width)

        fun setLong(index: Int, value: Long) {
            row[index - 1] = value
        }

        fun setInt(index: Int, value: Int) {
            row[index - 1] = value
        }

        fun setString(index: Int, value: String) {
            row[index - 1] = value
        }

        fun setNull(index: Int) {
            row[index - 1] = null
        }

        fun setNullableString(index: Int, value: String?) {
            row[index - 1] = value
        }

        fun setNullableLong(index: Int, value: Long?) {
            row[index - 1] = value
        }

        fun setSpan(index: Int, span: SourceSpan) {
            setInt(index, span.startLine)
            setInt(index + 1, span.startCol)
            setInt(index + 2, span.endLine)
            setInt(index + 3, span.endCol)
        }

        /** Adds the row the parameters hold to the pending rows. */
        fun add() {
            current.add(this, row)
        }

        /** Hands over the pending rows, then has the statement closed after them. */
        override fun close() {
            execute { statements.remove(id)?.close() }
        }
    }

    /** The rows of one batch, by the id of their insert. */
    private class Batch {
        val rows = ArrayList<Rows?>()
        var count = 0

        fun add(insert: Insert, row: Array<Any?>) {
            while (rows.size <= insert.id) rows += null
            val pending = rows[insert.id] ?: Rows(insert).also { rows[insert.id] = it }
            pending.add(row)
            count++
        }

        fun clear() {
            for (pending in rows) pending?.clear()
            count = 0
        }
    }

    /** The values of [insert]'s rows in a batch, row after row. */
    private class Rows(val insert: Insert) {
        var values = arrayOfNulls<Any>(insert.width * 64)
        var size = 0

        fun add(row: Array<Any?>) {
            val at = size * row.size
            if (at + row.size > values.size) values = values.copyOf(values.size * 2)
            System.arraycopy(row, 0, values, at, row.size)
            size++
        }

        fun clear() {
            values.fill(null, 0, size * insert.width)
            size = 0
        }
    }

    private sealed interface Work
    private class Written(val batch: Batch) : Work
    private class Action(val run: (Connection) -> Unit) : Work
    private class Barrier : Work {
        val reached = CountDownLatch(1)
    }
    private data object Stop : Work

    private val work = LinkedBlockingQueue<Work>()
    private val free = ArrayBlockingQueue<Batch>(BATCHES)
    private var current: Batch
    private var inserts = 0
    private var finished = false

    /** The statements prepared so far, by insert id: the writing thread's only. */
    private val statements = HashMap<Int, PreparedStatement>()

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var aborted = false

    @Volatile
    private var idleNanos = 0L
    private var waitedNanos = 0L

    /** How long the writing thread waited for rows so far: the building the writing did not hide. */
    val idleMillis: Long get() = idleNanos / 1_000_000

    /** How long the caller waited for the writing thread so far, for a batch to come back or for [drain]. */
    val waitedMillis: Long get() = waitedNanos / 1_000_000

    private val thread: Thread

    init {
        repeat(BATCHES - 1) { free.add(Batch()) }
        current = Batch()
        thread = Thread(::run, name).apply {
            isDaemon = true
            start()
        }
    }

    fun insert(sql: String): Insert = Insert(sql, inserts++)

    /** Hands the pending rows over once there are enough of them. */
    fun flushIfFull() {
        if (current.count >= FLUSH_ROWS) handOver()
    }

    /** Runs [action] with the connection on the writing thread, after the rows added before it. */
    fun execute(action: (Connection) -> Unit) {
        handOver()
        work.add(Action(action))
    }

    /** Returns once every row added and every action so far are written. */
    fun drain() {
        handOver()
        val barrier = Barrier()
        work.add(barrier)
        val started = System.nanoTime()
        barrier.reached.await()
        waitedNanos += System.nanoTime() - started
        failure?.let { throw it }
    }

    /** Writes what is left, closes the statements and ends the writing thread; the connection is the caller's again. */
    fun finish() {
        drain()
        work.add(Stop)
        join()
        finished = true
        failure?.let { throw it }
    }

    override fun close() {
        if (finished) return
        aborted = true
        work.add(Stop)
        join()
        finished = true
    }

    /** Waits for the writing thread to end, even when interrupted: the connection must not be used by two threads. */
    private fun join() {
        var interrupted = false
        while (true) {
            try {
                thread.join()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun handOver() {
        check(!finished) { "The rows were already written" }
        failure?.let { throw it }
        if (current.count == 0) return
        work.add(Written(current))
        val started = System.nanoTime()
        current = free.take()
        waitedNanos += System.nanoTime() - started
        failure?.let { throw it }
    }

    private fun run() {
        while (true) {
            val started = System.nanoTime()
            val next = work.take()
            idleNanos += System.nanoTime() - started
            when (next) {
                is Written -> {
                    attempt { write(next.batch) }
                    next.batch.clear()
                    free.add(next.batch)
                }
                is Action -> attempt { next.run(connection) }
                is Barrier -> next.reached.countDown()
                Stop -> {
                    for (statement in statements.values) {
                        try {
                            statement.close()
                        } catch (e: Throwable) {
                            if (failure == null && !aborted) failure = e
                        }
                    }
                    statements.clear()
                    return
                }
            }
        }
    }

    private inline fun attempt(step: () -> Unit) {
        if (failure != null || aborted) return
        try {
            step()
        } catch (e: Throwable) {
            failure = e
        }
    }

    private fun write(batch: Batch) {
        for (pending in batch.rows) {
            if (pending == null || pending.size == 0) continue
            val insert = pending.insert
            val statement = statements.getOrPut(insert.id) { connection.prepareStatement(insert.sql) }
            val values = pending.values
            var at = 0
            repeat(pending.size) {
                for (parameter in 1..insert.width) statement.setObject(parameter, values[at++])
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
