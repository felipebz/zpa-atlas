//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The identifiers written in the source files, by a lexical pass over their decoded texts ([SourceWords]): unquoted
 * words upper-cased with `Locale.ROOT`, quoted words as written, as zpa's `OracleIdentifier` normalizes them. Comments
 * and string literals are not skipped, so the set may hold more than the code names; it is only used to narrow what is
 * read from the database.
 */
object SourceIdentifiers {
    /** The names written in the sources [read] holds, scanned on [threads] threads. */
    fun scan(read: Indexer.ReadSources, threads: Int): Set<String> = scan(read.sources.map { it.text }, threads)

    internal fun scan(files: List<String>, threads: Int = 1): Set<String> {
        val count = threads.coerceIn(1, maxOf(files.size, 1))
        if (count == 1) return scanned(files).toSet()
        // Every count-th file to a thread, so that the large files spread; each keeps its own names, joined at the end.
        val executor = Executors.newFixedThreadPool(count)
        try {
            val parts = (0 until count).map { part -> executor.submit(Callable { scanned(files.slice(part until files.size step count)) }) }
            val names = parts.first().get()
            parts.drop(1).forEach { names.addAll(it.get()) }
            return names.toSet()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            executor.shutdownNow()
        }
    }

    private fun scanned(files: List<String>): Names = Names().also { names -> files.forEach { names.scan(it) } }

    /** The names found so far, kept by their chars: a name written again costs a lookup, not a string. */
    private class Names {
        private val found = HashMap<Name, Name>()
        private var buffer = CharArray(256)
        private val probe = Name(buffer, 0, 0)

        fun scan(text: String) {
            SourceWords.find(text, { from, to -> unquoted(text, from, to); false }, { from, to -> add(text, from, to); false })
        }

        /** An ASCII word is upper-cased as it is copied; another goes through `uppercase`, which may change its length. */
        private fun unquoted(text: String, from: Int, to: Int) {
            val length = reserve(to - from)
            var hash = 0
            for (i in 0 until length) {
                var c = text[from + i]
                if (c.code >= 0x80) {
                    val upper = text.substring(from, to).uppercase(Locale.ROOT)
                    add(upper, 0, upper.length)
                    return
                }
                if (c in 'a'..'z') c -= 'a' - 'A'
                buffer[i] = c
                hash = 31 * hash + c.code
            }
            keep(length, hash)
        }

        private fun add(text: String, from: Int, to: Int) {
            val length = reserve(to - from)
            var hash = 0
            for (i in 0 until length) {
                val c = text[from + i]
                buffer[i] = c
                hash = 31 * hash + c.code
            }
            keep(length, hash)
        }

        /** Grows the buffer to hold [length] chars; returns [length]. */
        private fun reserve(length: Int): Int {
            if (buffer.size < length) {
                buffer = CharArray(maxOf(length, buffer.size * 2))
                probe.chars = buffer
            }
            return length
        }

        /** Keeps the name in the first [length] chars of the buffer, of [hash]. */
        private fun keep(length: Int, hash: Int) {
            probe.length = length
            probe.hash = hash
            if (!found.containsKey(probe)) {
                val name = Name(buffer.copyOf(length), length, hash)
                found[name] = name
            }
        }

        fun addAll(other: Names) {
            other.found.keys.forEach { found.putIfAbsent(it, it) }
        }

        fun toSet(): Set<String> = found.keys.mapTo(HashSet(found.size * 2)) { String(it.chars, 0, it.length) }
    }

    /** A name's chars ([length] of [chars]); one instance is reused as the probe of every lookup. */
    private class Name(var chars: CharArray, var length: Int, var hash: Int) {
        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean =
            other is Name && other.hash == hash && other.length == length && Arrays.equals(chars, 0, length, other.chars, 0, length)
    }
}
