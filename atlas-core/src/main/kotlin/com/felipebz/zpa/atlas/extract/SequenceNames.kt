//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.project.ProjectAnalysis
import com.felipebz.zpa.api.project.SequenceReferenceResolution
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.atlas.model.*

/** Sequence pseudocolumns (`seq.nextval`) and the sequence entities they read. */
internal class SequenceNames(
    private val state: FileState,
    private val scopes: SqlScopes,
    private val values: PackageValues,
    private val synonyms: SynonymResolver,
    private val runSchemas: Set<String>,
    private val projectAnalysis: ProjectAnalysis
) {
    /**
     * `seq.nextval`, `s.seq.currval`: a project sequence, else an external `SEQUENCE` entity, `db:S.SEQ` when
     * `S` is a schema of the run and `db:~.…` otherwise (the dictionary does not know sequences). Not a sequence when the
     * first segment is captured lexically, is an SQL qualifier, or the legacy table binds the whole name.
     */
    fun sequenceReference(name: AstNode, segments: List<String>, context: Context, statementId: Int?) {
        val symbol = (name.firstChild as? SemanticAstNode)?.symbol
        if (symbol != null && symbol.kind != Symbol.Kind.PACKAGE && isLocalDeclaration(symbol)) return
        if (statementId != null && scopes.isSqlQualified(name, segments)) return
        if (projectAnalysis.resolveSequenceReference(name) == SequenceReferenceResolution.RESOLVED_NON_SEQUENCE) return
        val written = segments.dropLast(1)
        val children = name.children
        val lastDot = children.indexOfLast { it.type == PlSqlPunctuator.DOT }
        val nameParts = children.subList(0, lastDot)
        val text = nameParts.joinToString("") { writtenText(it) }
        val span = spanOf(nameParts.first(), nameParts.last())
        val (key, status) = sequenceEntity(written, context.schema, followSynonym = true)
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, text, key,
            setOf(Role.READ), status(status, context.partial), context.origin, null)
    }

    /**
     * The sequence [written] names, as `seq.nextval` finds it: the project sequence (`RESOLVED`), else an external
     * `SEQUENCE` entity, `db:S.SEQ` when `S` is a schema of the run and `db:~.…` otherwise (`UNVERIFIED`). With
     * [followSynonym], a one-segment name the project does not declare may be a synonym of the sequence; a DDL
     * statement names the sequence itself.
     */
    fun sequenceEntity(written: List<String>, schema: String?, followSynonym: Boolean): Pair<String, Status> {
        // A sequence the project does not declare may be named by a synonym: `syn_seq.nextval` reads the target.
        val target = if (followSynonym && values.sequence(written, schema) == null && written.size == 1) {
            synonyms.follow(normalizeIdentifier(written[0]), schema, SynonymResolver.SEQUENCES)
        } else {
            null
        }
        val resolvedName = target?.let { SynonymResolver.written(it, emptyList()) } ?: written
        val declared = values.sequence(resolvedName, schema)
        if (declared != null) return declared to Status.RESOLVED
        val names = resolvedName.map { normalizeIdentifier(it) }
        val schemaQualified = names.size == 2 && (names[0] in runSchemas || target != null)
        val key = if (schemaQualified) dbKey(names[0], names[1]) else dbKey(UNKNOWN_SCHEMA, *names.toTypedArray())
        state.entities += EntityFact(key, EntityKind.SEQUENCE, if (schemaQualified) names[0] else null, names.last(), null, key.removePrefix("db:"),
            external = true, precedence = Precedence.MENTIONED)
        return key to Status.UNVERIFIED
    }
}
