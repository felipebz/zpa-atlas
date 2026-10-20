//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.PlSqlTokenType
import com.felipebz.zpa.atlas.model.*

/** The `READ`s of the names written in conditional compilation conditions; [ConditionNames] parses their texts. */
internal class ConditionNameReads(
    private val state: FileState,
    private val catalog: DeclarationCatalog,
    private val segmentValues: SegmentValues,
    private val runSchemas: Set<String>
) {
    /**
     * The names written in the conditional compilation conditions Oracle evaluated, from the skipped-text trivia of
     * [token] ([ConditionNames]): one `READ` per name, resolved by [SegmentValues.segmentValue], at the name's own position, without
     * statement. A condition name is always a package constant, never a local value: one the project and the
     * synonyms do not know is a read of its external package ([externalOwner]). Closing texts and `$ERROR` give
     * nothing; a text that cannot be read gives a `CONDITION_UNREADABLE` note.
     */
    fun conditionNames(token: Token, context: Context) {
        for (trivia in token.trivia) {
            if (!trivia.isSkippedText) continue
            val skipped = trivia.token
            val whole = when (skipped.type) {
                PlSqlTokenType.CONDITIONAL_BRANCH_BOUNDARY -> false
                PlSqlTokenType.CONDITIONAL_DIRECTIVE -> true
                else -> continue
            }
            val text = skipped.originalValue
            val names = ConditionNames.read(text, whole) ?: run {
                state.coverage += CoverageFact("CONDITION_UNREADABLE", "Conditional compilation text at line ${skipped.line} could not be read: its condition names are not indexed")
                continue
            }
            val packageName = context.enclosingPackage?.let { qualifiedName(it) }
            for (name in names) {
                val (startLine, startColumn) = lineAt(skipped, text, name.start)
                val (endLine, endColumn) = lineAt(skipped, text, name.end)
                val span = SourceSpan(startLine, startColumn, endLine, endColumn)
                val written = text.substring(name.start, name.end)
                when (val value = segmentValues.segmentValue(name.segments, context, packageName, valueReadable = true, callable = true, callAllowed = true)) {
                    is SegmentValue.Member -> state.valueOccurrence(span, written, context, null, value.status, value.entityKey, value.reason, value.candidates, setOf(Role.READ))
                    is SegmentValue.Call -> state.valueOccurrence(span, written, context, null, value.outcome.status, value.outcome.entityKey, value.outcome.reason,
                        value.outcome.candidateKeys, setOf(Role.READ))
                    null -> externalOwner(name.segments, context)?.let {
                        state.valueOccurrence(span, written, context, null, Status.UNRESOLVED, it, "NOT_IN_PROJECT", emptyList(), setOf(Role.READ))
                    }
                }
            }
        }
    }

    /**
     * The external package that owns a condition name the project does not know: `db:S.PKG` for `S.PKG.C` when `S` is
     * a schema of the run, else `db:~.PKG` (as [ExternalNames] keys the owner of an unknown name). Null when that
     * package is a project declaration (the member is then simply missing).
     */
    private fun externalOwner(segments: List<String>, context: Context): String? {
        val names = segments.map { normalizeIdentifier(it) }
        val schemaQualified = names.size == 3 && names[0] in runSchemas
        val schema = if (schemaQualified) names[0] else null
        val name = if (schemaQualified) names[1] else names[0]
        if (catalog.isDeclared(dbKey(schema ?: context.schema ?: UNKNOWN_SCHEMA, name))) return null
        val key = dbKey(schema ?: UNKNOWN_SCHEMA, name)
        state.entities += EntityFact(key, EntityKind.PACKAGE, schema, name, null, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
        return key
    }
}

/** The line and column of [offset] inside the text of [token], with the lexer's line breaks (LF, or CR not followed by LF). */
private fun lineAt(token: Token, text: String, offset: Int): Pair<Int, Int> {
    var line = token.line
    var column = token.column
    for (i in 0 until offset) {
        val c = text[i]
        if (c == '\n' || c == '\r' && text.getOrNull(i + 1) != '\n') {
            line++
            column = 0
        } else {
            column++
        }
    }
    return line to column
}
