//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.semantics.NameBinding
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
 * Their uses are read from zpa's name binding ([NameBinding]): a name whose binding is the declaration of one of them
 * is a use of its entity, so a local of a subprogram that hides a global takes its own uses.
 */
internal class BodyGlobals(private val nameBinding: (AstNode) -> NameBinding) {
    /** One global: its entity, its declaration node and name, and the unit (the package body) it is declared in. */
    class Global(val key: String, val packageKey: String, val body: AstNode, val node: AstNode, val name: AstNode, val unitIndex: Int, val partial: Boolean)

    val declared = ArrayList<Global>()
    private val byName = HashMap<Long, String>()

    /**
     * Registers the globals of [body] (a `CREATE_PACKAGE_BODY`) of the package [packageKey] in [schema]: their entities and
     * `CONTAINS` relations in [state]. Exceptions declared like variables are left out.
     */
    fun declare(body: AstNode, packageKey: String, schema: String?, unitIndex: Int, partial: Boolean, state: FileState) {
        val used = HashMap<String, Int>()
        for (item in body.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
            if (item.type != PlSqlGrammar.VARIABLE_DECLARATION || item.hasDirectChildren(PlSqlKeyword.EXCEPTION)) continue
            val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
            val normalized = normalizeIdentifier(name.tokenOriginalValue)
            val count = (used[normalized] ?: 0) + 1
            used[normalized] = count
            val key = "$packageKey.$normalized@BODY" + if (count > 1) "#$count" else ""
            val kind = if (item.hasDirectChildren(PlSqlKeyword.CONSTANT)) EntityKind.PACKAGE_CONSTANT else EntityKind.PACKAGE_VARIABLE
            state.entities += EntityFact(key, kind, schema, normalized, packageKey, key.removePrefix("db:"), external = false, precedence = Precedence.DECLARED)
            state.relations += RelationFact(packageKey, RelationKind.CONTAINS, key)
            declared += Global(key, packageKey, body, item, name, unitIndex, partial)
            byName.putIfAbsent(position(name.token.line, name.token.column), key)
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

    /**
     * The global [name] (a `VARIABLE_NAME`, or a `MEMBER_EXPRESSION` such as `pkg.g_x` or `g_rec.f`) is a use of, from
     * zpa's binding of the whole name, else of the segment of a chain that names the global (`g_rec` of `g_rec.f`);
     * null for anything else. In SQL a column may still take the name ([NameBinding.ColumnOrLocal]): the caller decides
     * by the rules of SQL names. A dynamic text is parsed apart from the file: zpa binds none of its names.
     */
    fun of(name: AstNode, context: Context): String? {
        if (byName.isEmpty() || context.dynamic != null) return null
        globalOf(nameBinding(name))?.let { return it }
        if (name.type != PlSqlGrammar.MEMBER_EXPRESSION) return null
        // `g_rec.f`, `g_tab.count`, `pkg.g_rec.f`: the segment zpa binds to a global. zpa answers a later segment only
        // when every segment before it names a unit, and a field or method after a value never.
        for (segment in name.children) {
            if (segment.type != PlSqlGrammar.VARIABLE_NAME && segment.type != PlSqlGrammar.IDENTIFIER_NAME) continue
            globalOf(nameBinding(segment))?.let { return it }
        }
        return null
    }

    private fun globalOf(binding: NameBinding): String? {
        val declaration = when (binding) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> return null
        }
        if (declaration.fileId != null || declaration.kind != NameDeclarationKind.VARIABLE) return null
        return byName[position(declaration.line, declaration.column)]
    }

    private fun position(line: Int, column: Int) = (line.toLong() shl 32) or column.toLong()

    private companion object {
        /** What the index records anyway, or what cannot apply to a body global. */
        val NOT_GAPS = setOf(UsageUncertainty.SQL_NAME_MAY_BE_COLUMN, UsageUncertainty.VISIBLE_OUTSIDE_FILE)
    }
}
