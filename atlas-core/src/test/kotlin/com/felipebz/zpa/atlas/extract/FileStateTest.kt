//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.atlas.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FileStateTest {
    private fun occurrence(written: String, statement: Int, span: SourceSpan) =
        OccurrenceFact(0, null, statement, span, written, "db:SHOP.$written", setOf(Role.READ), Status.RESOLVED, Origin.STATIC, null)

    private val first = SourceSpan(1, 0, 1, 4)
    private val second = SourceSpan(2, 0, 2, 4)
    private val third = SourceSpan(3, 0, 3, 4)

    @Test
    fun `occurrences removed from the end leave the lookups by statement and by span to the ones added after`() {
        val state = FileState()
        state.occurrences += occurrence("a", 1, first)
        state.occurrences += occurrence("b", 1, second)
        state.occurrences += occurrence("c", 2, second)
        assertThat(state.occurrencesOf(1).map { it.written }).containsExactly("a", "b")
        assertThat(state.occurrencesAt(second).map { it.written }).containsExactly("b", "c")

        state.removeOccurrencesFrom(1)
        // Others at the positions the removed ones had.
        state.occurrences += occurrence("d", 3, second)
        state.occurrences += occurrence("e", 1, third)

        assertThat(state.occurrences.map { it.written }).containsExactly("a", "d", "e")
        assertThat(state.occurrencesOf(1).map { it.written }).containsExactly("a", "e")
        assertThat(state.occurrencesOf(2)).isEmpty()
        assertThat(state.occurrencesOf(3).map { it.written }).containsExactly("d")
        assertThat(state.occurrencesAt(first).map { it.written }).containsExactly("a")
        assertThat(state.occurrencesAt(second).map { it.written }).containsExactly("d")
        assertThat(state.occurrencesAt(third).map { it.written }).containsExactly("e")
    }
}
