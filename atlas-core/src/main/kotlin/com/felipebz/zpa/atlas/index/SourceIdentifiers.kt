//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The identifiers written in the source files, by a cheap lexical pass: unquoted words upper-cased, quoted words
 * exact. Comments and string literals are not skipped, so the set may hold more than the code names; it is only used to
 * narrow what is read from the database.
 *
 * The bytes are read as Latin-1, whatever the file's encoding: identifiers are ASCII in practice, and Latin-1 decodes
 * any byte. An unquoted word starts with an ASCII letter and goes on with ASCII letters, digits, `_`, `$` and `#`; a
 * quoted word is one or more characters other than `"`, CR and LF between two `"`. Where a `"` opens no quoted word,
 * the scan goes on from the character after it.
 */
object SourceIdentifiers {
    /** The names written in the sources [read] holds, the bytes as they were read, scanned on [threads] threads. */
    fun scan(read: Indexer.ReadSources, threads: Int): Set<String> = scan(read.sources.map { it.bytes }, threads)

    internal fun scan(files: List<ByteArray>, threads: Int = 1): Set<String> {
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

    private fun scanned(files: List<ByteArray>): Names = Names().also { names -> files.forEach { names.scan(it) } }

    /** The names found so far, kept by their bytes: a name written again costs a lookup, not a string. */
    private class Names {
        private val found = HashMap<Name, Name>()
        private var buffer = ByteArray(256)
        private val probe = Name(buffer, 0, 0)

        fun scan(bytes: ByteArray) {
            var at = 0
            val end = bytes.size
            while (at < end) {
                val byte = bytes[at].toInt()
                if (isLetter(byte)) {
                    var after = at + 1
                    while (after < end && isWordPart(bytes[after].toInt())) after++
                    add(bytes, at, after, upperCase = true)
                    at = after
                } else if (byte == QUOTE) {
                    var close = at + 1
                    while (close < end && bytes[close].toInt().let { it != QUOTE && it != CR && it != LF }) close++
                    if (close < end && bytes[close].toInt() == QUOTE && close > at + 1) {
                        add(bytes, at + 1, close, upperCase = false)
                        at = close + 1
                    } else {
                        at++
                    }
                } else {
                    at++
                }
            }
        }

        private fun add(bytes: ByteArray, from: Int, to: Int, upperCase: Boolean) {
            val length = to - from
            if (buffer.size < length) {
                buffer = ByteArray(maxOf(length, buffer.size * 2))
                probe.bytes = buffer
            }
            var hash = 0
            for (i in 0 until length) {
                var byte = bytes[from + i].toInt()
                if (upperCase && byte >= 'a'.code) byte -= 'a'.code - 'A'.code
                buffer[i] = byte.toByte()
                hash = 31 * hash + byte
            }
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

        fun toSet(): Set<String> = found.keys.mapTo(HashSet(found.size * 2)) { String(it.bytes, 0, it.length, StandardCharsets.ISO_8859_1) }
    }

    /** A name's bytes ([length] of [bytes]); one instance is reused as the probe of every lookup. */
    private class Name(var bytes: ByteArray, var length: Int, var hash: Int) {
        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean =
            other is Name && other.hash == hash && other.length == length && Arrays.equals(bytes, 0, length, other.bytes, 0, length)
    }

    private const val QUOTE = '"'.code
    private const val CR = '\r'.code
    private const val LF = '\n'.code

    /** An ASCII letter; [byte] is signed, so the bytes above 127 are negative and none. */
    private fun isLetter(byte: Int) = byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code

    private fun isWordPart(byte: Int) = isLetter(byte) || byte in '0'.code..'9'.code || byte == '_'.code || byte == '$'.code || byte == '#'.code
}
