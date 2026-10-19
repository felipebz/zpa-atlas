//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.model.*

/**
 * Static SQL statements: the statement fact, the relations its references name, the columns its DML writes, and the
 * PL/SQL names, datatypes and column reads inside it.
 */
internal class SqlStatements(
    private val state: FileState,
    private val resolver: RelationResolver,
    private val scopes: SqlScopes,
    private val plsql: PlSqlNames,
    private val typeReferences: TypeReferences,
    private val triggers: Triggers,
    private val columnReads: ColumnReads
) {
    fun statement(root: AstNode, context: Context): Statement {
        val kind = when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> StatementKind.SELECT
            DmlGrammar.INSERT_EXPRESSION -> StatementKind.INSERT
            DmlGrammar.UPDATE_EXPRESSION -> StatementKind.UPDATE
            DmlGrammar.DELETE_EXPRESSION -> StatementKind.DELETE
            DmlGrammar.MERGE_EXPRESSION -> StatementKind.MERGE
            else -> StatementKind.LOCK
        }
        val id = state.statements.size
        state.statements += StatementFact(id, context.unitIndex, context.container, kind, spanOf(root), sha256(normalizedSql(root)))
        val statement = Statement(root, context, id, state)
        val targets = HashSet<AstNode>()
        when (kind) {
            StatementKind.UPDATE, StatementKind.DELETE -> {
                val clause = root.getFirstChildOrNull(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)
                if (clause != null) targets += clause
                val reference = clause?.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(root)
                } else {
                    val role = if (kind == StatementKind.UPDATE) Role.SQL_UPDATE else Role.SQL_DELETE
                    val alias = clause.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                    val target = target(statement, reference, setOf(role), alias)
                    if (target != null && kind == StatementKind.UPDATE) {
                        root.getChildren(DmlGrammar.UPDATE_COLUMN).forEach { setColumn(statement, it, target, Role.SQL_UPDATE, allowRow = true) }
                    }
                }
            }
            StatementKind.INSERT -> for (clause in root.getDescendants(DmlGrammar.INSERT_INTO_CLAUSE)) {
                val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(clause)
                    continue
                }
                val alias = clause.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                val target = target(statement, reference, setOf(Role.SQL_INSERT), alias) ?: continue
                val columns = clause.getFirstChildOrNull(DmlGrammar.INSERT_COLUMNS)
                val setClause = clause.nextSiblingOrNull?.takeIf { it.type == DmlGrammar.INSERT_SET_CLAUSE }
                // Each INTO with its own VALUES, else the query of the insert.
                val source = clause.nextSiblingOrNull?.takeIf { it.type == DmlGrammar.VALUES_CLAUSE }?.let { WrittenValues.valuesClause(it) }
                    ?: WriteSource.Query(WrittenValues.insertQuery(clause))
                when {
                    columns != null -> {
                        val listed = columns.children.filter { !isPunctuation(it) }
                        val values = source.forColumns(listed.size)
                        listed.forEachIndexed { position, it -> column(statement, it, target, Role.SQL_INSERT, statement.writtenValues(values, position)) }
                    }
                    setClause != null -> setClause.getChildren(DmlGrammar.UPDATE_COLUMN).forEach { setColumn(statement, it, target, Role.SQL_INSERT, allowRow = false) }
                    else -> columnReads.implicitColumns(statement, target, setOf(Role.SQL_INSERT, Role.WRITE, Role.IMPLICIT), written = source)
                }
            }
            StatementKind.MERGE -> {
                val reference = root.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(root)
                } else {
                    val update = root.getFirstChildOrNull(DmlGrammar.MERGE_UPDATE_CLAUSE)
                    val insert = root.getFirstChildOrNull(DmlGrammar.MERGE_INSERT_CLAUSE)
                    val roles = buildSet {
                        add(Role.SQL_MERGE)
                        if (update != null) add(Role.SQL_UPDATE)
                        if (update != null && update.hasDirectChildren(PlSqlKeyword.DELETE)) add(Role.SQL_DELETE)
                        if (insert != null) add(Role.SQL_INSERT)
                    }
                    val alias = root.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                    val target = target(statement, reference, roles, alias)
                    if (target != null) {
                        update?.getChildren(DmlGrammar.UPDATE_COLUMN)?.forEach { setColumn(statement, it, target, Role.SQL_UPDATE, allowRow = false) }
                        if (insert != null) {
                            val children = insert.children
                            val start = children.indexOfFirst { it.type == PlSqlKeyword.INSERT }
                            val end = children.indexOfFirst { it.type == PlSqlKeyword.VALUES }
                            val columns = children.subList(start + 1, end).filter { !isPunctuation(it) }
                            val source = WrittenValues.mergeValues(insert)
                            if (columns.isEmpty()) {
                                columnReads.implicitColumns(statement, target, setOf(Role.SQL_INSERT, Role.WRITE, Role.IMPLICIT), written = source)
                            } else {
                                val values = source.forColumns(columns.size)
                                columns.forEachIndexed { position, it -> column(statement, it, target, Role.SQL_INSERT, statement.writtenValues(values, position)) }
                            }
                        }
                    }
                }
            }
            StatementKind.LOCK -> root.getChildren(DmlGrammar.TABLE_REFERENCE).forEach { target(statement, it, setOf(Role.SQL_LOCK), null) }
            else -> Unit
        }
        for (clause in root.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)) {
            if (clause in targets) continue
            val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE) ?: continue
            target(statement, reference, setOf(Role.SQL_SELECT), null)
        }
        // The PL/SQL compiler resolves the function names of static SQL, so they follow the PL/SQL rules.
        root.getDescendants(PlSqlGrammar.METHOD_CALL).forEach { call ->
            plsql.callSite(call, context, id) { callee, segments -> columnReads.qualifiedColumn(statement, callee, segments, method = true) }
            columnReads.correlationValue(statement, call)
            makeRefRelation(call)?.let { makeRef(statement, it) }
        }
        root.getDescendants(*KEYWORD_FUNCTIONS).forEach { typeReferences.sqlFunctionKeyword(it.firstChild, context, id) }
        // `CAST(… AS t)` and the other datatypes of the statement.
        root.getDescendants(*NAMED_DATATYPES).forEach { typeReferences.typeUse(it, context, id) }
        root.getDescendants(PlSqlGrammar.JSON_DATATYPE).forEach { typeReferences.jsonTypeUse(it, context, id) }
        // Names: correlation columns of a trigger; INTO targets are PL/SQL values; every other name is a column read when
        // an SQL scope captures it.
        root.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION)
            .filter { plsql.isValueName(it) && it.getFirstAncestorOrNull(*NAMED_DATATYPES) == null }
            .filterNot { columnReads.isCorrelationArgument(statement, it) || isMakeRefRelation(it) }
            .filterNot { triggers.correlationColumn(it, context, id) }
            .forEach { if (it.getFirstAncestorOrNull(DmlGrammar.INTO_CLAUSE) != null) plsql.valueReference(it, context, id, sql = false) else columnReads.sqlName(statement, it) }
        root.getDescendants(DmlGrammar.SELECT_COLUMN).forEach { columnReads.selectStar(statement, it) }
        return statement
    }

    private fun isPunctuation(node: AstNode) = node.type is PlSqlPunctuator

    /**
     * The relation `MAKE_REF(t, key…)` names, resolved as a `FROM` reference. It builds a reference without reading the
     * relation: `TYPE_USE`, never an SQL role.
     */
    private fun makeRef(statement: Statement, name: AstNode) {
        if (statement.context.hasHoles(name)) return
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) } ?: return
        val resolved = resolver.resolve(WrittenRelation(segments.getOrNull(segments.size - 2), segments.last(), null), statement.context.schema)
        statement.occurrence(name, resolved.entityKey, setOf(Role.TYPE_USE), resolved.status, resolved.reason)
        // A view depends on the row type instead (verified on Oracle 26ai); a procedure on the relation only.
        if (state.units[statement.context.unitIndex].kind == UnitKind.VIEW) {
            resolved.relation?.let { typeReferences.rowTypeUse(name, it, statement.context, statement.id) }
        }
    }

    private fun isMakeRefRelation(name: AstNode): Boolean {
        val call = name.parentOrNull?.parentOrNull?.parentOrNull ?: return false
        return name.parentOrNull?.type == PlSqlGrammar.ARGUMENT && makeRefRelation(call) === name
    }

    private fun subqueryTarget(node: AstNode) {
        state.coverage += CoverageFact("DML_SUBQUERY_TARGET", "DML on an inline view or collection at line ${node.tokenLine}: its base relation is not a write target")
    }

    /**
     * A relation occurrence, or null for names that are not relations (WITH names, DUAL) or not known (a placeholder of
     * a dynamic text: a source whose columns are unknown, with no entity).
     */
    private fun target(statement: Statement, reference: AstNode, roles: Set<Role>, alias: String?): Target? {
        if (statement.context.hasHoles(reference)) return null
        val written = scopes.written(reference)
        if (written.link == null && written.schema == null && written.name in statement.localNames) return null
        if (written.link == null && written.name == "DUAL" && (written.schema == null || written.schema == "SYS")) return null
        val resolved = resolver.resolve(written, statement.context.schema)
        statement.occurrence(reference, resolved.entityKey, roles, resolved.status, resolved.reason)
        return Target(reference, written, resolved, alias, written.schema ?: statement.context.schema).also { statement.targets[reference] = it }
    }

    private fun setColumn(statement: Statement, updateColumn: AstNode, target: Target, role: Role, allowRow: Boolean) {
        var left = updateColumn.firstChild
        // `SET (col) = (…)`: one parenthesized column is a BRACKED_EXPRESSION, not a MULTIPLE_VALUE_EXPRESSION.
        while (left.type == PlSqlGrammar.BRACKED_EXPRESSION) {
            left = left.children.singleOrNull { !isPunctuation(it) } ?: break
        }
        val right = WrittenValues.setValue(updateColumn)
        when {
            left.type == PlSqlGrammar.MULTIPLE_VALUE_EXPRESSION -> {
                // `SET (a, b) = (SELECT x, y …)`: the select list by position.
                val columns = left.children.filter { !isPunctuation(it) }
                val values = WriteSource.Query(right).forColumns(columns.size)
                columns.forEachIndexed { position, it -> column(statement, it, target, role, statement.writtenValues(values, position)) }
            }
            allowRow && left.type == PlSqlGrammar.VARIABLE_NAME && left.tokenOriginalValue.uppercase() == "ROW" ->
                columnReads.implicitColumns(statement, target, setOf(role, Role.WRITE, Role.IMPLICIT), written = WriteSource.None(WrittenValues.SET_ROW))
            allowRow && columnReads.correlationCall(statement, left)?.let { (function, source) -> function == "VALUE" && source.target === target } == true ->
                columnReads.implicitColumns(statement, target, setOf(role, Role.WRITE, Role.IMPLICIT), written = WriteSource.None(WrittenValues.SET_VALUE))
            else -> {
                // `SET (col) = (SELECT x …)` is one column and a query; `SET col = expr` the expression.
                val query = right?.let { WrittenValues.subquery(it) }
                val values = when {
                    right == null -> ColumnValues.None(WrittenValues.COUNT_MISMATCH)
                    query != null && left !== updateColumn.firstChild -> WriteSource.Query(query).forColumns(1)
                    else -> ColumnValues.Rows(listOf(listOf(right)))
                }
                column(statement, left, target, role, statement.writtenValues(values, 0))
            }
        }
    }

    private fun column(statement: Statement, node: AstNode, target: Target, role: Role, values: List<WrittenValueFact>) {
        if (statement.context.hasHoles(node)) return
        val parts = node.children.filter { it.type != PlSqlPunctuator.DOT }.ifEmpty { listOf(node) }
        if (parts.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) {
            state.coverage += CoverageFact("COLUMN_EXPRESSION", "Written column at line ${node.tokenLine} is not a plain name")
            return
        }
        val segments = parts.map { normalizeIdentifier(it.tokenOriginalValue) }
        val qualifier = segments.dropLast(1)
        val roles = setOf(role, Role.WRITE)
        if (qualifier.isNotEmpty() && !scopes.qualifierMatches(qualifier, target)) {
            statement.occurrence(node, null, roles, Status.UNRESOLVED, "QUALIFIER_MISMATCH", values = values)
            return
        }
        val resolved = resolver.resolveColumn(target.resolved, segments.last())
        statement.occurrence(node, resolved.entityKey, roles, resolved.status, resolved.reason, values = values)
    }
}
