//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.semantics.DeclaredField
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.api.semantics.UsageEvidence
import com.felipebz.zpa.api.semantics.UsageUncertainty
import com.felipebz.zpa.atlas.model.*

/**
 * The variables and constants a package body declares in its own declare section, outside any subprogram: globals the
 * whole body shares for the session, private to it. Each is an entity of the kind of the spec's (`PACKAGE_VARIABLE`,
 * `PACKAGE_CONSTANT`), a child of its package, keyed `db:S.PKG.NAME@BODY`: the body suffix the index gives a body's
 * routines, so that a name the body redeclares from its spec (Oracle compiles it, and fails where the name is used)
 * keeps the spec's entity and adds the body's beside it. A second declaration of a name in the same body adds `#2`.
 *
 * Their uses are read from zpa's name binding ([BoundNames]): a name whose binding is the declaration of one of them
 * is a use of its entity, so a local of a subprogram that hides a global takes its own uses.
 *
 * The types the body declares there (record, collection, ref cursor, subtype) follow the same rule: a `TYPE` entity
 * `db:S.PKG.T@BODY`, a child of the package, whose datatype uses are read from zpa's name binding too. A record type's
 * fields are zpa's ([recordFields]): each a `RECORD_FIELD` entity `db:S.PKG.T@BODY.F`, a child of the type, found by
 * [fieldAt] where zpa's field binding places it.
 */
internal class BodyGlobals(private val bound: BoundNames, private val recordFields: (AstNode) -> List<DeclaredField>?) {
    /** One global: its entity, its declaration node and name, and the unit (the package body) it is declared in. */
    class Global(val key: String, val packageKey: String, val body: AstNode, val node: AstNode, val name: AstNode, val unitIndex: Int, val partial: Boolean)

    /** A body type or one of its fields, declared in the package body of [packageKey]. */
    class Member(val key: String, val packageKey: String, val node: AstNode, val name: AstNode, val unitIndex: Int, val partial: Boolean)

    val declared = ArrayList<Global>()

    /** The body types and their fields, each type before its fields. */
    val types = ArrayList<Member>()

    private val fieldsByStart = HashMap<Pair<Int, Int>, String>()

    /** The field entity of a body record type whose name zpa places at [line] and [column] of this file, or null. */
    fun fieldAt(line: Int, column: Int): String? = fieldsByStart[line to column]

    /**
     * Registers the globals and types of [body] (a `CREATE_PACKAGE_BODY`) of the package [packageKey] in [schema]: their
     * entities and `CONTAINS` relations in [state]. Exceptions declared like variables are left out.
     */
    fun declare(body: AstNode, packageKey: String, schema: String?, unitIndex: Int, partial: Boolean, state: FileState) {
        val used = HashMap<String, Int>()
        fun entity(key: String, kind: EntityKind, name: String, parentKey: String) {
            state.entities += EntityFact(key, kind, schema, name, parentKey, key.removePrefix("db:"), external = false, precedence = Precedence.DECLARED)
            state.relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
        }
        for (item in body.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
            val type = item.type in TYPE_DECLARATIONS
            if (!type && (item.type != PlSqlGrammar.VARIABLE_DECLARATION || item.hasDirectChildren(PlSqlKeyword.EXCEPTION))) continue
            val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
            val normalized = normalizeIdentifier(name.tokenOriginalValue)
            val count = (used[normalized] ?: 0) + 1
            used[normalized] = count
            val key = "$packageKey.$normalized@BODY" + if (count > 1) "#$count" else ""
            if (type) {
                entity(key, EntityKind.TYPE, normalized, packageKey)
                types += Member(key, packageKey, item, name, unitIndex, partial)
                bound.add(NameDeclarationKind.TYPE, name.token.line, name.token.column, key)
                if (item.type == PlSqlGrammar.RECORD_DECLARATION) fields(item, key, packageKey, unitIndex, partial, ::entity)
                continue
            }
            val kind = if (item.hasDirectChildren(PlSqlKeyword.CONSTANT)) EntityKind.PACKAGE_CONSTANT else EntityKind.PACKAGE_VARIABLE
            entity(key, kind, normalized, packageKey)
            declared += Global(key, packageKey, body, item, name, unitIndex, partial)
            bound.add(NameDeclarationKind.VARIABLE, name.token.line, name.token.column, key)
        }
    }

    /** The fields zpa gives the record type [record] (whose entity is [typeKey]), each at its written declaration. */
    private fun fields(record: AstNode, typeKey: String, packageKey: String, unitIndex: Int, partial: Boolean, entity: (String, EntityKind, String, String) -> Unit) {
        val name = record.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME)
        val written = record.getChildren(PlSqlGrammar.RECORD_FIELD_DECLARATION).associateBy { it.token.line to it.token.column }
        for (field in recordFields(name).orEmpty()) {
            val at = field.declaration.line to field.declaration.column
            val node = written[at] ?: continue
            val fieldName = normalizeIdentifier(field.name)
            val key = "$typeKey.$fieldName"
            if (fieldsByStart.putIfAbsent(at, key) != null) continue
            entity(key, EntityKind.RECORD_FIELD, fieldName, typeKey)
            types += Member(key, packageKey, node, node.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME), unitIndex, partial)
        }
    }

    /**
     * The globals whose recorded uses may be incomplete, with what may hold the others, from zpa alone: the reasons of
     * the global's [UsageEvidence.Unknown] (no use zpa is sure of, and a name or region it could not decide), and what
     * [regionUncertainty] says the analysis cannot see in the package body, the global's whole scope (a `$IF`, a
     * substitution, recovered or discarded text: names there may be uses zpa left undecided).
     *
     * Left out: a name in SQL that may be a column (the index records it, `UNVERIFIED`), and visibility outside the file
     * (a body global has none). A global zpa proves unused, or whose uses it decided in a complete body, has no gap.
     */
    fun gaps(usageEvidence: (AstNode) -> UsageEvidence, regionUncertainty: (AstNode) -> Set<UsageUncertainty>?): List<UsesGapFact> {
        val bodies = HashMap<AstNode, Set<UsageUncertainty>>()
        return declared.mapNotNull { global ->
            val reasons = sortedSetOf<UsageUncertainty>()
            (usageEvidence(global.node) as? UsageEvidence.Unknown)?.let { reasons += it.reasons }
            reasons += bodies.getOrPut(global.body) { regionUncertainty(global.body).orEmpty() }
            reasons -= NOT_GAPS
            if (reasons.isEmpty()) null else UsesGapFact(global.key, reasons.map { it.name })
        }
    }

    companion object {
        /** What the index records anyway, or what cannot apply to a name of the file: never a gap. */
        val NOT_GAPS = setOf(UsageUncertainty.SQL_NAME_MAY_BE_COLUMN, UsageUncertainty.VISIBLE_OUTSIDE_FILE)

        /** The declarations of a type in a declare section. */
        private val TYPE_DECLARATIONS = setOf(
            PlSqlGrammar.RECORD_DECLARATION, PlSqlGrammar.TABLE_OF_DECLARATION, PlSqlGrammar.VARRAY_DECLARATION,
            PlSqlGrammar.REF_CURSOR_DECLARATION, PlSqlGrammar.CUSTOM_SUBTYPE
        )
    }
}
