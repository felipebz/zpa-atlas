//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.atlas.model.*

/**
 * What the `NAME` values written to columns or passed to calls name ([ValueName]), once the file is walked: the entity
 * the code reads at the name (a variable, constant, parameter, column, Forms item or global), and for a record's field
 * the field and, when the code names the record (a cursor `FOR` loop record, a variable, a parameter), the record as
 * [IntoTargets.record] finds it for `target := r.f`. A name with no read entity and no record keeps no [ValueName].
 */
internal class ValueNames(private val state: FileState, private val targets: IntoTargets) {
    fun resolve(shapes: TypeShapes) {
        if (state.occurrences.none { occurrence -> occurrence.arguments.any { it.valueKind == ValueKind.NAME } ||
                occurrence.writtenValues.any { it.valueKind == ValueKind.NAME } }) return
        val withEntity = state.occurrences.filter { it.entityKey != null }
        val bySpan = withEntity.groupBy { it.span }
        val reads = withEntity.filter { Role.READ in it.roles }.groupBy { it.span }
        fun name(span: SourceSpan, kind: ValueKind, value: String?): ValueName? =
            if (kind == ValueKind.NAME) name(span, value.orEmpty(), bySpan, reads, shapes) else null
        for ((index, occurrence) in state.occurrences.withIndex()) {
            if (occurrence.arguments.none { it.valueKind == ValueKind.NAME } && occurrence.writtenValues.none { it.valueKind == ValueKind.NAME }) continue
            state.occurrences[index] = occurrence.copy(
                arguments = occurrence.arguments.map { it.copy(name = name(it.span, it.valueKind, it.value)) },
                writtenValues = occurrence.writtenValues.map { it.copy(name = name(it.span, it.valueKind, it.value)) }
            )
        }
    }

    private fun name(
        span: SourceSpan, value: String, bySpan: Map<SourceSpan, List<OccurrenceFact>>, reads: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes
    ): ValueName? {
        val node = state.valueNames[span]
        val entity = reads[node?.let { spanOf(it) } ?: span]?.firstOrNull()?.entityKey
        // A record the code names: one with an entity is the entity read there (not the sequence of `seq.nextval`).
        val row = node?.takeIf { it.type == PlSqlGrammar.MEMBER_EXPRESSION }?.let { targets.record(it, bySpan, shapes) }
            ?.takeIf { it.entityKey == null || it.entityKey == entity }
        val segments = value.split('.')
        val field = when {
            entity != null -> fieldAfter(entity, segments)
            row != null -> segments.last()
            else -> null
        }
        if (entity == null && row == null) return null
        return ValueName(entity, field, row)
    }

    /** The segments of [segments] after the one the entity [key] is named by, joined; null when none follow. */
    private fun fieldAfter(key: String, segments: List<String>): String? {
        val own = key.substringAfterLast('.').substringBefore('@').substringBefore('#')
        val at = segments.indexOfLast { it == own }
        return if (at < 0 || at == segments.lastIndex) null else segments.drop(at + 1).joinToString(".")
    }
}
