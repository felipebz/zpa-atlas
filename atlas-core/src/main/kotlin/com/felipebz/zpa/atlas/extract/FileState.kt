//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.AstNodeType
import com.felipebz.zpa.api.AggregateSqlFunctionsGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.SingleRowSqlFunctionsGrammar
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.symbols.SymbolTable
import com.felipebz.zpa.atlas.model.*
import java.util.IdentityHashMap

/**
 * The facts of one file in the order they are found (the writer numbers them by position), and the counters of what is
 * counted rather than indexed. Every collaborator of [FileExtractor] adds to the same lists.
 */
internal class FileState {
    val units = ArrayList<UnitFact>()
    val partialUnits = ArrayList<Boolean>()
    val statements = ArrayList<StatementFact>()
    val occurrences = ArrayList<OccurrenceFact>()
    val entities = ArrayList<EntityFact>()
    val relations = ArrayList<RelationFact>()
    val coverage = ArrayList<CoverageFact>()
    val triggerEvents = ArrayList<TriggerEventFact>()
    var recoveredRegions = 0

    /** Datatype names captured by a local declaration: counted, not indexed (locals are not entities). */
    var localTypeReferences = 0

    /** Unqualified names in static SQL that may be a column of a source with unknown columns: counted, not indexed. */
    var undecidedSqlNames = 0

    /** Column writes with no value, by reason ([WrittenValues]). */
    val unvaluedWrites = sortedMapOf<String, Int>()

    /** Modes of the formals that resolved calls bind their actual expressions to, for the roles of the values passed. */
    val argumentModes = IdentityHashMap<AstNode, ProjectParameterMode>()

    fun addUnit(unit: UnitFact, partial: Boolean): Int {
        units += unit
        partialUnits += partial
        return units.size - 1
    }

    /** Removes the entities and relations added since the marks (up to [entityEnd] and [relationEnd]). */
    fun rollBack(entityMark: Int, relationMark: Int, entityEnd: Int = entities.size, relationEnd: Int = relations.size) {
        entities.subList(entityMark, entityEnd).clear()
        relations.subList(relationMark, relationEnd).clear()
    }
}

internal fun status(normal: Status, partial: Boolean) = if (partial) Status.PARTIAL_SOURCE else normal

internal fun names(unitName: AstNode?): List<String> =
    unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { normalizeIdentifier(it.tokenOriginalValue) }.orEmpty()

/**
 * A DML trigger seen from its body: the correlation names, the relation they read and write ([target], null for a
 * nested-table trigger), and the `WHEN` expression, where correlation names are written without a colon.
 */
internal class TriggerScope(val key: String, val newNames: Set<String>, val oldNames: Set<String>, val target: ResolvedRelation?, val whenExpression: AstNode?)

internal class Context(
    val unitIndex: Int,
    val partial: Boolean,
    val schema: String?,
    val container: String?,
    val trigger: TriggerScope?,
    /** The legacy symbol table of the file, for the file's own nodes; null in a dynamic text, which binds to nothing of the file. */
    val symbols: SymbolTable?,
    /** The package spec or body around the code, as written in its CREATE; null elsewhere. */
    val enclosingPackage: List<String>? = null,
    /** Variables, cursors and types declared at the level of the package body around the code. */
    val bodyNames: Set<String> = emptySet(),
    /** In a type body, the key of its type (`db:S.T`); null elsewhere. */
    val objectType: String? = null,
    /** The SQL text of a dynamic statement being walked; null in the code of the file. */
    val dynamic: DynamicSqlText? = null
) {
    /** The origin of the occurrences found here: in a dynamic text, `DYNAMIC_LITERAL`. */
    val origin: Origin get() = if (dynamic == null) Origin.STATIC else Origin.DYNAMIC_LITERAL

    fun inside(container: String) = Context(unitIndex, partial, schema, container, trigger, symbols, enclosingPackage, bodyNames, objectType, dynamic)

    /**
     * The walk of a dynamic [text] run by this code: same unit, container and schema, and nothing of the code around it
     * (its symbols, its package, the body's private names, its object type, its trigger): Oracle compiles the text on its
     * own, and `:new` / `:old` in it are bind variables.
     */
    fun dynamicText(text: DynamicSqlText) = Context(unitIndex, partial, schema, container, null, null, dynamic = text)

    /** Whether [node] is or contains a placeholder of the dynamic text: never a name. */
    fun hasHoles(node: AstNode): Boolean = dynamic != null && dynamic.holesIn(node).isNotEmpty()
}

internal val PSEUDOCOLUMNS = setOf("NEXTVAL", "CURRVAL")

/** Names static SQL captures itself: never a column of a source, never a PL/SQL name. */
internal val SQL_PSEUDOCOLUMNS = setOf(
    "ROWNUM", "ROWID", "LEVEL", "CONNECT_BY_ISLEAF", "CONNECT_BY_ISCYCLE", "ORA_ROWSCN", "COLUMN_VALUE", "OBJECT_VALUE", "OBJECT_ID"
)

internal val COLUMN_READ = setOf(Role.READ)

/** Built-in SQL functions the grammar parses as their own expression, named by a synonym in the dictionary. */
internal val KEYWORD_FUNCTIONS = arrayOf<AstNodeType>(
    AggregateSqlFunctionsGrammar.XMLAGG_EXPRESSION, SingleRowSqlFunctionsGrammar.XMLSEQUENCE_EXPRESSION,
    // `json(x)` with one argument: a constructor of a user type `JSON` (PL/JSON) below version 21.
    SingleRowSqlFunctionsGrammar.JSON_CONSTRUCTOR
)

internal fun FileState.valueOccurrence(span: SourceSpan, written: String, context: Context, statementId: Int?, status: Status, entityKey: String?,
                                       reason: String?, candidates: List<String>, roles: Set<Role>) {
    occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, written, entityKey,
        roles, status(status, context.partial), context.origin, reason, candidates)
}

/** A function called without parentheses: a `CALL` occurrence without actuals. */
internal fun FileState.callOccurrence(name: AstNode, context: Context, statementId: Int?, outcome: CallOutcome.Call) {
    occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
        setOf(Role.CALL), status(outcome.status, context.partial), context.origin, outcome.reason, outcome.candidateKeys)
}
