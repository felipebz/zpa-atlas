//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import io.ktor.http.decodeURLPart

/**
 * The path the server is served under, such as `/atlas`: every route is below it, and nothing outside it is answered.
 * [path] is empty at the root, else it starts with `/` and has no trailing `/`. The reverse proxy in front passes the
 * path unchanged: the server receives `/atlas/api/run`, not `/api/run`.
 */
class BasePath private constructor(val path: String) {
    private val segments: List<String> = path.split('/').filter { it.isNotEmpty() }

    /** [sub] (`/api`, starting with `/`) under the base path. */
    fun of(sub: String): String = path + sub

    /**
     * The first segment of [requestPath] below the base path, decoded as the router decodes it; null when the path is
     * outside the base path or has nothing below it. A segment that does not decode gives [UNDECODABLE].
     */
    internal fun firstBelow(requestPath: String): String? {
        val raw = requestPath.split('/').filter { it.isNotEmpty() }
        if (raw.size <= segments.size) return null
        // Only the segments compared are decoded: the base path's, then the one below it.
        for (i in 0..segments.size) {
            val decoded = try {
                raw[i].decodeURLPart()
            } catch (_: Exception) {
                return UNDECODABLE
            }
            if (i == segments.size) return decoded
            if (decoded != segments[i]) return null
        }
        return null
    }

    override fun equals(other: Any?) = other is BasePath && other.path == path

    override fun hashCode() = path.hashCode()

    override fun toString() = path.ifEmpty { "/" }

    companion object {
        val ROOT = BasePath("")

        /** What [firstBelow] gives for a path the router cannot decode either. */
        internal const val UNDECODABLE = "\u0000"

        /**
         * [value] as a base path: `/atlas` and `/atlas/` are the same; null, empty or `/` is the root.
         *
         * @throws IllegalArgumentException when [value] is not a plain path: no leading `/`, a `.` or `..` segment,
         *     `//`, `%`, `?`, `#`, a backslash, a space or a control character
         */
        fun parse(value: String?): BasePath {
            if (value.isNullOrEmpty() || value == "/") return ROOT
            require(value.startsWith("/")) { "The base path must start with /: $value" }
            val trimmed = if (value.endsWith("/")) value.dropLast(1) else value
            require(trimmed.none { it in "%?#\\" || it.isWhitespace() || it.isISOControl() }) {
                "The base path must be a plain path, without %, ?, #, \\, spaces or control characters: $value"
            }
            val segments = trimmed.substring(1).split('/')
            require(segments.none { it.isEmpty() }) { "The base path must not contain //: $value" }
            require(segments.none { it == "." || it == ".." }) { "The base path must not contain . or .. segments: $value" }
            return BasePath(trimmed)
        }
    }
}
