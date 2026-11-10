//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


/** A request the index cannot answer as asked (an unknown role, a limit out of range, a path outside the index). */
open class InvalidQueryException(message: String) : RuntimeException(message)

/** An impact graph of [nodes] nodes, more than [limit]: a smaller depth gives a smaller one. */
class GraphTooLargeException(val nodes: Int, val limit: Int) :
    RuntimeException("The graph has $nodes nodes, more than $limit: lower maxDepth")

/** A query ran longer than the index allows; narrower filters make it faster. */
class QueryTimeoutException(val seconds: Double) :
    RuntimeException(
        "The query took longer than ${
            "%.0f".format(
                java.util.Locale.ROOT,
                seconds
            )
        } s; narrow the filters and try again."
    )

/** The file was written by an indexer with another schema version. */
class SchemaMismatchException(val found: Int, val expected: Int) :
    RuntimeException("The index has schema version $found; this server reads version $expected. Re-run `zpa-atlas index` to rebuild it, or use the matching zpa-atlas version.")
