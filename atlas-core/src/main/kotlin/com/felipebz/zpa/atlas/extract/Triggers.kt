//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.model.*

/** The events and the relation of a trigger, and the columns its body reaches through the correlation names. */
internal class Triggers(private val state: FileState, private val resolver: RelationResolver, private val plsql: PlSqlNames) {
    /**
     * The events of a trigger, its `TRIGGER_ON` relation, and for a DML trigger the scope of its correlation names
     * (`NEW` and `OLD`, or their `REFERENCING` aliases); null for a system trigger.
     */
    fun trigger(node: AstNode, key: String, schema: String?): TriggerScope? {
        val system = node.getFirstChildOrNull(PlSqlGrammar.SYSTEM_TRIGGER)
        if (system != null) {
            systemEvents(system, key)
            return null
        }
        val dml = node.getFirstChildOrNull(PlSqlGrammar.SIMPLE_DML_TRIGGER, PlSqlGrammar.INSTEAD_OF_DML_TRIGGER, PlSqlGrammar.COMPOUND_DML_TRIGGER)
            ?: return null
        val target = dml.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
        val relation = if (target != null && !dml.hasDirectChildren(PlSqlKeyword.NESTED)) {
            val written = names(target)
            resolver.resolve(WrittenRelation(written.getOrNull(written.size - 2), written.last(), null), schema).also {
                state.relations += RelationFact(key, RelationKind.TRIGGER_ON, it.entityKey)
            }
        } else {
            null
        }
        dmlEvents(dml, key, relation)
        val referencing = dml.getFirstChildOrNull(PlSqlGrammar.REFERENCING_CLAUSE)
        fun aliases(keyword: PlSqlKeyword): Set<String> {
            val children = referencing?.children.orEmpty()
            return children.withIndex().filter { it.value.type == keyword }
                .mapNotNull { (i, _) -> children.drop(i + 1).firstOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME } }
                .map { normalizeIdentifier(it.tokenOriginalValue) }.toSet()
        }
        val whenExpression = dml.children.indexOfFirst { it.type == PlSqlKeyword.WHEN }.takeIf { it >= 0 }?.let { dml.children.getOrNull(it + 2) }
        return TriggerScope(key, aliases(PlSqlKeyword.NEW).ifEmpty { setOf("NEW") }, aliases(PlSqlKeyword.OLD).ifEmpty { setOf("OLD") }, relation, whenExpression)
    }

    /** The events of a DML trigger; an `UPDATE OF` column resolves on the trigger's relation. */
    private fun dmlEvents(dml: AstNode, key: String, relation: ResolvedRelation?) {
        val row = dml.hasDirectChildren(PlSqlKeyword.ROW)
        val (timing, level) = when (dml.type) {
            PlSqlGrammar.COMPOUND_DML_TRIGGER -> TriggerTiming.COMPOUND to null
            PlSqlGrammar.INSTEAD_OF_DML_TRIGGER -> TriggerTiming.INSTEAD_OF to TriggerLevel.ROW
            else -> (if (dml.firstChild.type == PlSqlKeyword.BEFORE) TriggerTiming.BEFORE else TriggerTiming.AFTER) to
                (if (row) TriggerLevel.ROW else TriggerLevel.STATEMENT)
        }
        val events = if (dml.type == PlSqlGrammar.INSTEAD_OF_DML_TRIGGER) {
            dml.children.takeWhile { it.type != PlSqlKeyword.ON }.filter { it.type in DML_EVENTS }.map { it.tokenValue.uppercase() to emptyList<AstNode>() }
        } else {
            dml.getChildren(PlSqlGrammar.DML_EVENT_CLAUSE).map { it.firstChild.tokenValue.uppercase() to it.getChildren(PlSqlGrammar.IDENTIFIER_NAME) }
        }
        for ((event, columns) in events) {
            if (columns.isEmpty()) {
                state.triggerEvents += TriggerEventFact(key, timing, level, event, null)
                continue
            }
            for (column in columns) {
                val name = normalizeIdentifier(column.tokenOriginalValue)
                val resolved = relation?.let { resolver.resolveColumn(it, name) }
                val columnKey = resolved?.takeIf { it.status != Status.UNRESOLVED }?.entityKey
                if (columnKey == null) state.coverage += CoverageFact("TRIGGER_COLUMN_UNRESOLVED", "$key: UPDATE OF column $name is not a column of the trigger's relation")
                state.triggerEvents += TriggerEventFact(key, timing, level, event, columnKey)
            }
        }
    }

    /** The DDL or database events of a system trigger: each database event carries its own timing. */
    private fun systemEvents(system: AstNode, key: String) {
        val sharedTiming = when (system.firstChild.type) {
            PlSqlKeyword.BEFORE -> TriggerTiming.BEFORE
            PlSqlKeyword.AFTER -> TriggerTiming.AFTER
            PlSqlKeyword.INSTEAD -> TriggerTiming.INSTEAD_OF
            else -> null
        }
        for (ddl in system.getChildren(PlSqlGrammar.DDL_EVENT)) {
            state.triggerEvents += TriggerEventFact(key, sharedTiming ?: TriggerTiming.AFTER, null, tokensOf(ddl).joinToString(" ") { it.value.uppercase() }, null)
        }
        for (database in system.getChildren(PlSqlGrammar.DATABASE_EVENT)) {
            val tokens = tokensOf(database).map { it.value.uppercase() }
            val timing = if (tokens.first() == "BEFORE") TriggerTiming.BEFORE else TriggerTiming.AFTER
            state.triggerEvents += TriggerEventFact(key, timing, null, tokens.drop(1).joinToString(" "), null)
        }
    }

    /**
     * A column of the trigger's relation through a correlation name: `:new.col` and `:old.col` in the body (in PL/SQL
     * or in static SQL), `new.col` without the colon in the `WHEN` clause. `:new` columns take the roles of any value
     * (assignment and `INTO` targets, `OUT` arguments of resolved calls); `:old` columns are read only. The occurrence
     * spans the whole reference, as other column occurrences span their qualified name. Pseudocolumns give nothing.
     */
    fun correlationColumn(name: AstNode, context: Context, statementId: Int?): Boolean {
        val scope = context.trigger ?: return false
        if (name.type != PlSqlGrammar.MEMBER_EXPRESSION) return false
        val parts = name.children.filter { it.type != PlSqlPunctuator.DOT }
        if (parts.size != 2 || parts[0].type != PlSqlGrammar.VARIABLE_NAME || parts[1].type != PlSqlGrammar.IDENTIFIER_NAME) return false
        val host = parts[0].getFirstChildOrNull(PlSqlGrammar.HOST_AND_INDICATOR_VARIABLE)
        val correlation = if (host != null) {
            host.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.tokenOriginalValue ?: return false
        } else {
            // Without a colon, only in the WHEN clause.
            val inWhen = scope.whenExpression != null && generateSequence(name) { it.parentOrNull }.any { it === scope.whenExpression }
            if (!inWhen) return false
            parts[0].tokenOriginalValue
        }
        val normalized = normalizeIdentifier(correlation)
        val isNew = normalized in scope.newNames
        if (!isNew && normalized !in scope.oldNames) return false
        val target = scope.target ?: return false
        val column = normalizeIdentifier(parts[1].tokenOriginalValue)
        // `:new.rowid`: captured like `t.rowid` in SQL, with no occurrence.
        if (column in SQL_PSEUDOCOLUMNS) return true
        val roles = if (isNew) plsql.valueRoles(name, listOf(correlation, column)) else setOf(Role.READ)
        val resolved = resolver.resolveColumn(target, column)
        state.occurrences += OccurrenceFact(context.unitIndex, scope.key, statementId, spanOf(name), writtenText(name), resolved.entityKey,
            roles, status(resolved.status, context.partial), context.origin, resolved.reason)
        return true
    }
}

private val DML_EVENTS = setOf(PlSqlKeyword.INSERT, PlSqlKeyword.UPDATE, PlSqlKeyword.DELETE)