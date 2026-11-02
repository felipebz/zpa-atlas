//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind

/**
 * The entities whose uses are read from zpa's name binding ([NameBinding]): a body's own globals and types
 * ([BodyGlobals]) and the parameters of subprograms and cursors. Each is registered by the position of its declaration's
 * name; a name whose binding is that declaration is a use of the entity, so a local that hides it takes its own uses.
 */
internal class BoundNames(private val nameBinding: (AstNode) -> NameBinding, private val typeBinding: (AstNode) -> NameBinding) {
    private val keys = HashMap<Long, String>()
    private val kinds = HashMap<Long, NameDeclarationKind>()

    /** Registers [key] as the entity of the declaration of [kind] whose name is at [line] (1-based) and [column] (0-based). */
    fun add(kind: NameDeclarationKind, line: Int, column: Int, key: String) {
        val at = position(line, column)
        if (keys.putIfAbsent(at, key) == null) kinds[at] = kind
    }

    /** The entity registered for the declaration whose name is at [line] (1-based) and [column] (0-based), or null. */
    fun keyAt(line: Int, column: Int): String? = keys[position(line, column)]

    /**
     * The entity [name] (a `VARIABLE_NAME`, or a `MEMBER_EXPRESSION` such as `pkg.g_x` or `g_rec.f`) is a use of, from
     * zpa's binding of the whole name, else of the segment of a chain that names the entity (`g_rec` of `g_rec.f`);
     * null for anything else. In SQL a column may still take the name ([NameBinding.ColumnOrLocal]): the caller decides
     * by the rules of SQL names. A dynamic text is parsed apart from the file: zpa binds none of its names.
     */
    fun of(name: AstNode, context: Context): String? {
        if (keys.isEmpty() || context.dynamic != null) return null
        entityOf(nameBinding(name))?.let { return it }
        if (name.type != PlSqlGrammar.MEMBER_EXPRESSION) return null
        // `g_rec.f`, `p_tab.count`, `pkg.g_rec.f`: the segment zpa binds to an entity. zpa answers a later segment only
        // when every segment before it names a unit, and a field or method after a value never.
        return segmentsOf(name.children)
    }

    /** The entity one of [parts] (the written segments of a name, dots between them) is a use of, as [of] reads a chain. */
    fun ofSegments(parts: List<AstNode>, context: Context): String? {
        if (keys.isEmpty() || context.dynamic != null) return null
        return segmentsOf(parts)
    }

    /**
     * The type entity the datatype name [name] (a `VARIABLE_NAME` or `MEMBER_EXPRESSION` of a `CUSTOM_DATATYPE`) names,
     * from zpa's binding of the whole datatype name; null for anything else. Types are read only here: never a value.
     */
    fun typeOf(name: AstNode, context: Context): String? {
        if (keys.isEmpty() || context.dynamic != null) return null
        return entityOf(typeBinding(name), types = true)
    }

    private fun segmentsOf(parts: List<AstNode>): String? {
        for (segment in parts) {
            if (segment.type != PlSqlGrammar.VARIABLE_NAME && segment.type != PlSqlGrammar.IDENTIFIER_NAME) continue
            entityOf(nameBinding(segment))?.let { return it }
        }
        return null
    }

    private fun entityOf(binding: NameBinding, types: Boolean = false): String? {
        val declaration = when (binding) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> return null
        }
        if (declaration.fileId != null || (declaration.kind == NameDeclarationKind.TYPE) != types) return null
        val at = position(declaration.line, declaration.column)
        return keys[at]?.takeIf { kinds[at] == declaration.kind }
    }

    private fun position(line: Int, column: Int) = (line.toLong() shl 32) or column.toLong()
}
