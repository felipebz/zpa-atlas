//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.SourceSpan
import java.sql.PreparedStatement
import java.sql.Types

/** Pending rows (all statements together) after which the batches are executed. */
private const val FLUSH_ROWS = 20_000

/**
 * Pending batches of some statements, executed once [FLUSH_ROWS] rows are pending: the driver keeps every bound
 * parameter of a pending row as an object, so batching a whole corpus duplicates the index in the heap. Each
 * statement keeps its own insertion order, so the rows and their order do not depend on when a batch runs.
 */
internal class Batches(private vararg val statements: PreparedStatement) : AutoCloseable {
    private var pending = 0

    fun add(statement: PreparedStatement) {
        statement.addBatch()
        pending++
    }

    fun flushIfFull() {
        if (pending >= FLUSH_ROWS) flush()
    }

    fun flush() {
        for (statement in statements) statement.executeBatch()
        pending = 0
    }

    override fun close() {
        for (statement in statements) statement.close()
    }
}

internal fun PreparedStatement.setNullableString(index: Int, value: String?) =
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)

internal fun PreparedStatement.setNullableLong(index: Int, value: Long?) =
    if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)

internal fun PreparedStatement.setSpan(index: Int, span: SourceSpan) {
    setInt(index, span.startLine)
    setInt(index + 1, span.startCol)
    setInt(index + 2, span.endLine)
    setInt(index + 3, span.endCol)
}
