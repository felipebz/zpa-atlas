//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DdlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.project.ProjectDeclarationKind
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The object a DDL statement acts on: `TRUNCATE`, `DROP`, `ALTER`, `ANALYZE TABLE` and `CREATE` of the object types in
 * [DdlObject]. Each statement in scope gets a `DDL` statement row and its target one occurrence with the role of the
 * action. Oracle resolves a DDL name without synonyms, so the target is the object of the statement's type in the
 * schema written (else the unit's): a name of another kind is `WRONG_KIND`, without entity. Every other DDL, and the
 * names inside a statement (columns, constraints, the object of `CREATE SYNONYM … FOR`), give nothing here.
 */
internal class DdlTargets(
    private val path: String,
    private val state: FileState,
    private val dictionary: AtlasDictionary,
    private val resolver: RelationResolver,
    private val values: PackageValues,
    private val catalog: DeclarationCatalog,
    private val plsql: PlSqlNames
) {
    /** The DDL statements in scope, by node type. */
    fun isDdl(node: AstNode): Boolean = node.type in STATEMENTS

    /** Where the project index declares an object of this file (a `CREATE SEQUENCE` of a source file has a definition). */
    private val declarationStarts by lazy { catalog.occurrencesFor(path).mapTo(HashSet()) { it.span.startLine to it.span.startCol } }

    fun statement(node: AstNode, context: Context) {
        val (action, objectType, name) = target(node) ?: return
        // A statement of the file the project already declares: its definition records it. A dynamic text is never
        // part of the project index.
        if (context.dynamic == null && (node.tokenLine to node.token.column) in declarationStarts) return
        val id = state.statements.size
        state.statements += StatementFact(id, context.unitIndex, context.container, StatementKind.DDL, spanOf(node), sha256(normalizedSql(node)))
        if (name.any { context.hasHoles(it) }) return
        val identifiers = name.flatMap { if (it.type == PlSqlGrammar.UNIT_NAME) it.children else listOf(it) }.filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
        if (identifiers.isEmpty() || identifiers.size > 2) return
        val written = identifiers.map { it.tokenOriginalValue }
        val schema = if (written.size == 2) normalizeIdentifier(written[0]) else if (objectType == DdlObject.PUBLIC_SYNONYM) AtlasDictionary.PUBLIC else context.schema
        val outcome = resolve(objectType, written, schema, context)
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, id, spanOf(name.first(), name.last()),
            name.joinToString("") { writtenText(it) }, outcome.entityKey, setOf(action), status(outcome.status, context.partial), context.origin, outcome.reason)
    }

    /** The action, the object type and the name nodes of a statement in scope; null for anything else. */
    private fun target(node: AstNode): Triple<Role, DdlObject, List<AstNode>>? {
        val unitName = node.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
        return when (node.type) {
            DdlGrammar.TRUNCATE_TABLE -> unitName?.let { Triple(Role.DDL_TRUNCATE, DdlObject.TABLE, listOf(it)) }
            DdlGrammar.ANALYZE_STATEMENT -> {
                val children = node.children
                if (children.getOrNull(1)?.type != PlSqlKeyword.TABLE) return null
                val name = children.drop(2).takeWhile { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlPunctuator.DOT }
                if (name.isEmpty()) null else Triple(Role.DDL_ANALYZE, DdlObject.TABLE, name)
            }
            DdlGrammar.DROP_COMMAND -> {
                unitName ?: return null
                val keywords = node.children.drop(1).takeWhile { it !== unitName }
                    .filter { it.type != PlSqlKeyword.IF && it.type != PlSqlKeyword.EXISTS }
                    .joinToString(" ") { it.tokenValue.uppercase() }
                DROPPED[keywords]?.let { Triple(Role.DDL_DROP, it, listOf(unitName)) }
            }
            DdlGrammar.CREATE_SYNONYM -> unitName?.let {
                Triple(Role.DDL_CREATE, if (node.hasDirectChildren(PlSqlKeyword.PUBLIC)) DdlObject.PUBLIC_SYNONYM else DdlObject.SYNONYM, listOf(it))
            }
            DdlGrammar.CREATE_TABLE -> unitName?.let { Triple(Role.DDL_CREATE, DdlObject.TABLE, listOf(it)) }
            DdlGrammar.CREATE_SEQUENCE -> unitName?.let { Triple(Role.DDL_CREATE, DdlObject.SEQUENCE, listOf(it)) }
            else -> ALTERED[node.type]?.let { type -> unitName?.let { Triple(Role.DDL_ALTER, type, listOf(it)) } }
        }
    }

    private class Outcome(val entityKey: String?, val status: Status, val reason: String?)

    private val wrongKind = Outcome(null, Status.UNRESOLVED, "WRONG_KIND")

    private fun resolve(objectType: DdlObject, written: List<String>, schema: String?, context: Context): Outcome {
        val names = written.map { normalizeIdentifier(it) }
        return when (objectType) {
            DdlObject.TABLE, DdlObject.VIEW, DdlObject.MATERIALIZED_VIEW -> {
                val relation = WrittenRelation(names.getOrNull(names.size - 2), names.last(), null)
                resolver.resolveOwn(relation, context.schema, objectType.relationKinds, objectType.objectTypes)
                    ?.let { Outcome(it.entityKey, it.status, it.reason) } ?: wrongKind
            }
            DdlObject.SYNONYM, DdlObject.PUBLIC_SYNONYM -> {
                if (schema == null) return Outcome(null, Status.UNVERIFIED, "SCHEMA_UNKNOWN")
                val synonym = dictionary.synonym(schema, names.last())
                when {
                    // The synonym itself, as the dictionary gives its entity; its target is not followed.
                    synonym != null -> RelationResolver.synonymEntity(synonym).let {
                        state.entities += it
                        Outcome(it.key, Status.RESOLVED, null)
                    }
                    dictionary.objectType(schema, names.last()).let { it != null && it != "SYNONYM" } -> wrongKind
                    // Absent only when the dictionary read the schema's names (public synonyms are read by name).
                    dictionary.knowsObjects(schema) -> Outcome(null, Status.UNRESOLVED, "NOT_IN_DICTIONARY")
                    else -> Outcome(null, Status.UNVERIFIED, null)
                }
            }
            DdlObject.SEQUENCE -> {
                if (schema != null && dictionary.objectType(schema, names.last()).let { it != null && it != "SEQUENCE" } ||
                    values.schemaObjects(written, context.schema).any { it.kind != ProjectDeclarationKind.SEQUENCE }) return wrongKind
                val (key, status) = plsql.sequenceEntity(written, context.schema, followSynonym = false)
                Outcome(key, status, null)
            }
            else -> {
                val declarations = values.schemaObjects(written, context.schema)
                val own = declarations.filter { it.kind in objectType.declarationKinds }
                when {
                    own.isNotEmpty() -> Outcome(catalog.keyOf((own.firstOrNull { it.kind != ProjectDeclarationKind.PACKAGE_BODY } ?: own.first()).id), Status.RESOLVED, null)
                    declarations.isNotEmpty() -> wrongKind
                    schema == null -> Outcome(null, Status.UNRESOLVED, "NOT_IN_PROJECT")
                    else -> when (dictionary.objectType(schema, names.last())) {
                        null -> Outcome(null, Status.UNRESOLVED, "NOT_IN_PROJECT")
                        objectType.objectTypes.single() -> {
                            // As a call or a type use of an object of the dictionary that the project does not declare.
                            val key = dbKey(schema, names.last())
                            state.entities += EntityFact(key, objectType.entityKind!!, schema, names.last(), null, key.removePrefix("db:"),
                                external = true, precedence = Precedence.MENTIONED)
                            Outcome(key, Status.UNRESOLVED, "NOT_IN_PROJECT")
                        }
                        else -> wrongKind
                    }
                }
            }
        }
    }

    private companion object {
        val ALTERED = mapOf(
            DdlGrammar.ALTER_TABLE to DdlObject.TABLE, DdlGrammar.ALTER_VIEW to DdlObject.VIEW,
            PlSqlGrammar.ALTER_MATERIALIZED_VIEW to DdlObject.MATERIALIZED_VIEW, DdlGrammar.ALTER_SEQUENCE to DdlObject.SEQUENCE,
            DdlGrammar.ALTER_PACKAGE to DdlObject.PACKAGE, PlSqlGrammar.ALTER_TYPE to DdlObject.TYPE,
            DdlGrammar.ALTER_PROCEDURE to DdlObject.PROCEDURE, DdlGrammar.ALTER_FUNCTION to DdlObject.FUNCTION
        )

        /** The object types of `DROP`, as their keywords are written. */
        val DROPPED = mapOf(
            "TABLE" to DdlObject.TABLE, "VIEW" to DdlObject.VIEW, "MATERIALIZED VIEW" to DdlObject.MATERIALIZED_VIEW,
            "SEQUENCE" to DdlObject.SEQUENCE, "SYNONYM" to DdlObject.SYNONYM, "PUBLIC SYNONYM" to DdlObject.PUBLIC_SYNONYM,
            "PACKAGE" to DdlObject.PACKAGE, "PACKAGE BODY" to DdlObject.PACKAGE, "TYPE" to DdlObject.TYPE, "TYPE BODY" to DdlObject.TYPE,
            "PROCEDURE" to DdlObject.PROCEDURE, "FUNCTION" to DdlObject.FUNCTION
        )

        val STATEMENTS = setOf(
            DdlGrammar.TRUNCATE_TABLE, DdlGrammar.ANALYZE_STATEMENT, DdlGrammar.DROP_COMMAND, DdlGrammar.CREATE_SYNONYM,
            DdlGrammar.CREATE_TABLE, DdlGrammar.CREATE_SEQUENCE
        ) + ALTERED.keys
    }
}

/**
 * The object types DDL targets are indexed for: the dictionary kinds and `OBJECT_TYPE`s a relation may have, the
 * project declarations of a PL/SQL object. A body is its package or type.
 */
internal enum class DdlObject(
    val relationKinds: Set<DictionaryRelationKind> = emptySet(),
    val objectTypes: Set<String> = emptySet(),
    val declarationKinds: Set<ProjectDeclarationKind> = emptySet(),
    val entityKind: EntityKind? = null
) {
    TABLE(setOf(DictionaryRelationKind.TABLE, DictionaryRelationKind.OBJECT_TABLE), setOf("TABLE")),
    VIEW(setOf(DictionaryRelationKind.VIEW), setOf("VIEW")),
    MATERIALIZED_VIEW(setOf(DictionaryRelationKind.MATERIALIZED_VIEW), setOf("MATERIALIZED VIEW")),
    SEQUENCE,
    SYNONYM,
    PUBLIC_SYNONYM,
    PACKAGE(objectTypes = setOf("PACKAGE"), declarationKinds = setOf(ProjectDeclarationKind.PACKAGE, ProjectDeclarationKind.PACKAGE_BODY), entityKind = EntityKind.PACKAGE),
    TYPE(objectTypes = setOf("TYPE"), declarationKinds = setOf(ProjectDeclarationKind.STANDALONE_TYPE), entityKind = EntityKind.TYPE),
    PROCEDURE(objectTypes = setOf("PROCEDURE"), declarationKinds = setOf(ProjectDeclarationKind.STANDALONE_PROCEDURE), entityKind = EntityKind.PROCEDURE),
    FUNCTION(objectTypes = setOf("FUNCTION"), declarationKinds = setOf(ProjectDeclarationKind.STANDALONE_FUNCTION), entityKind = EntityKind.FUNCTION)
}
