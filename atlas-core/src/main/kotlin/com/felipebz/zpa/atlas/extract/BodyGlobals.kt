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
     * entities and `CONTAINS` relations in [state]. Exceptions declared like variables are left out. A package of a
     * Forms module, which zpa's project does not declare, registers its specification's the same way, with an empty
     * [suffix].
     */
    fun declare(body: AstNode, packageKey: String, schema: String?, unitIndex: Int, partial: Boolean, state: FileState, suffix: String = "@BODY") {
        val used = HashMap<String, Int>()
        fun entity(key: String, kind: EntityKind, name: String, parentKey: String) {
            state.entities += EntityFact(key, kind, schema, name, parentKey, displayOf(key), external = false, precedence = Precedence.DECLARED)
            state.relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
        }
        for (item in body.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
            val type = item.type in TYPE_DECLARATIONS
            if (!type && (item.type != PlSqlGrammar.VARIABLE_DECLARATION || item.hasDirectChildren(PlSqlKeyword.EXCEPTION))) continue
            val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
            val normalized = normalizeIdentifier(name.tokenOriginalValue)
            val count = (used[normalized] ?: 0) + 1
            used[normalized] = count
            val key = "$packageKey.$normalized$suffix" + if (count > 1) "#$count" else ""
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

    /**
     * Registers the variables, constants and types a routine declares: in its own declare section, in its inner blocks
     * and in its nested subprograms. [routine] is the routine's node, [parentKey] the entity its declarations are
     * children of (the routine, or for a method of a type body, the type), and [keyBase] the key their keys start with
     * (the routine's key, or the type's key and the method's name and [signature]). Each is keyed `keyBase.NAME`, as a
     * parameter is, with each nested subprogram it is in between, by name and [signature] (`keyBase.HELPER(P_A IN NUMBER).NAME`),
     * so overloads keep apart whatever their order; the same key again (an inner block), or a key
     * [taken] already (a parameter's), adds `#2`. A variable or constant is a `LOCAL_VARIABLE` or `LOCAL_CONSTANT`, a
     * type is a `TYPE` entity with its fields, as for the body's own. Cursors and exceptions are left out. Their uses are
     * read as the body globals' are, and their gaps come from [routine] as a body global's come from its body.
     */
    fun declareRoutine(
        routine: AstNode, parentKey: String, keyBase: String, schema: String?, unitIndex: Int, partial: Boolean, state: FileState,
        taken: (String) -> Boolean
    ) {
        RoutineDeclarations(routine, parentKey, schema, unitIndex, partial, state, taken).visit(routine, keyBase)
    }

    /** The declarations of one routine as [declareRoutine] registers them, with the keys given so far. */
    private inner class RoutineDeclarations(
        private val routine: AstNode, private val parentKey: String, private val schema: String?, private val unitIndex: Int,
        private val partial: Boolean, private val state: FileState, private val taken: (String) -> Boolean
    ) {
        private val used = HashSet<String>()

        /** Registers every declare section under [node], an inner block's included; a nested subprogram adds its name to [prefix]. */
        fun visit(node: AstNode, prefix: String) {
            for (child in node.children) {
                when (child.type) {
                    PlSqlGrammar.DECLARE_SECTION -> {
                        section(child, prefix)
                        visit(child, prefix)
                    }
                    PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION -> visitSubprogram(child, prefix)
                    else -> visit(child, prefix)
                }
            }
        }

        /** Visits the nested subprogram [subprogram] under its name and signature after [prefix]; nothing when it has no name. */
        private fun visitSubprogram(subprogram: AstNode, prefix: String) {
            val name = subprogram.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: return
            visit(subprogram, "$prefix.${normalizeIdentifier(name.tokenOriginalValue)}${signature(subprogram)}")
        }

        /** Registers the variables, constants and types of the declare section [section], keyed after [prefix]. */
        private fun section(section: AstNode, prefix: String) {
            for (item in section.children) {
                val type = item.type in TYPE_DECLARATIONS
                if (!type && (item.type != PlSqlGrammar.VARIABLE_DECLARATION || item.hasDirectChildren(PlSqlKeyword.EXCEPTION))) continue
                val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
                val normalized = normalizeIdentifier(name.tokenOriginalValue)
                val key = uniqueKey("$prefix.$normalized")
                used += key
                if (type) registerType(item, name, key, normalized) else registerVariable(item, name, key, normalized)
            }
        }

        /** [base], or with the first `#2` on that is neither used in this routine nor [taken]. */
        private fun uniqueKey(base: String): String {
            var count = 1
            while ((if (count == 1) base else "$base#$count").let { it in used || taken(it) }) count++
            return if (count == 1) base else "$base#$count"
        }

        /** Registers the type [item] named [name], with a record type's fields. */
        private fun registerType(item: AstNode, name: AstNode, key: String, normalized: String) {
            entity(key, EntityKind.TYPE, normalized, parentKey)
            types += Member(key, parentKey, item, name, unitIndex, partial)
            bound.add(NameDeclarationKind.TYPE, name.token.line, name.token.column, key)
            if (item.type == PlSqlGrammar.RECORD_DECLARATION) fields(item, key, parentKey, unitIndex, partial, ::entity)
        }

        /** Registers the variable or constant [item] named [name]. */
        private fun registerVariable(item: AstNode, name: AstNode, key: String, normalized: String) {
            val kind = if (item.hasDirectChildren(PlSqlKeyword.CONSTANT)) EntityKind.LOCAL_CONSTANT else EntityKind.LOCAL_VARIABLE
            entity(key, kind, normalized, parentKey)
            declared += Global(key, parentKey, routine, item, name, unitIndex, partial)
            bound.add(NameDeclarationKind.VARIABLE, name.token.line, name.token.column, key)
        }

        /** Adds the entity [key] and its `CONTAINS` relation from [parentKey]. */
        private fun entity(key: String, kind: EntityKind, name: String, parentKey: String) {
            state.entities += EntityFact(key, kind, schema, name, parentKey, displayOf(key), external = false, precedence = Precedence.DECLARED)
            state.relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
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
        /**
         * The written signature of a subprogram or method [node] (a `PROCEDURE_DECLARATION`, `FUNCTION_DECLARATION` or
         * `TYPE_CONSTRUCTOR`), as a key segment after its name: its parameters as a subprogram key writes them,
         * `(P_A IN NUMBER,P_B OUT VARCHAR2)`, and a function's ` RETURN` type. Overloads differ in it, so their
         * declarations get keys of their own whatever their order in the file.
         */
        fun signature(node: AstNode): String {
            val parameters = (node.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS) ?: node).getChildren(PlSqlGrammar.PARAMETER_DECLARATION)
            val list = parameters.joinToString(",", "(", ")") { parameter ->
                val name = normalizeIdentifier(parameter.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME).tokenOriginalValue)
                val mode = when {
                    !parameter.hasDirectChildren(PlSqlKeyword.OUT) -> "IN"
                    parameter.hasDirectChildren(PlSqlKeyword.IN) -> "IN OUT"
                    else -> "OUT"
                }
                "$name $mode ${parameter.getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.let(::typeText).orEmpty()}"
            }
            val returns = if (node.type == PlSqlGrammar.FUNCTION_DECLARATION) node.getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.let { " RETURN ${typeText(it)}" }.orEmpty() else ""
            return list + returns
        }

        /** A datatype as written, upper-cased, a space only between two words (`VARCHAR2(10)`, `PKG.T%TYPE`, `REF T`). */
        private fun typeText(datatype: AstNode): String {
            val text = StringBuilder()
            for (token in datatype.tokens) {
                val value = token.originalValue.uppercase()
                if (text.isNotEmpty() && text.last().isLetterOrDigit() && value.first().isLetterOrDigit()) text.append(' ')
                text.append(value)
            }
            return text.toString()
        }

        /** What the index records anyway, or what cannot apply to a name of the file: never a gap. */
        val NOT_GAPS = setOf(UsageUncertainty.SQL_NAME_MAY_BE_COLUMN, UsageUncertainty.VISIBLE_OUTSIDE_FILE)

        /** The declarations of a type in a declare section. */
        private val TYPE_DECLARATIONS = setOf(
            PlSqlGrammar.RECORD_DECLARATION, PlSqlGrammar.TABLE_OF_DECLARATION, PlSqlGrammar.VARRAY_DECLARATION,
            PlSqlGrammar.REF_CURSOR_DECLARATION, PlSqlGrammar.CUSTOM_SUBTYPE
        )
    }
}
