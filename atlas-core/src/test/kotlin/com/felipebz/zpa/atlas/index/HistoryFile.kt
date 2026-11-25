//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

/**
 * A file of the test resources that records the digest of each format of a kept file, one line each: the format (and
 * what else the digest depends on), then the digest. A line is never changed: a new output means a new format.
 */
internal object HistoryFile {
    /** The digests of [name], by all but the last word of their line. */
    fun read(name: String): Map<String, String> {
        val lines = HistoryFile::class.java.getResourceAsStream("/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val result = LinkedHashMap<String, String>()
        for (line in lines) {
            val key = line.substringBeforeLast(' ')
            check(result.put(key, line.substringAfterLast(' ')) == null) { "$name records \"$key\" twice: a new output needs a new format" }
        }
        return result
    }
}
