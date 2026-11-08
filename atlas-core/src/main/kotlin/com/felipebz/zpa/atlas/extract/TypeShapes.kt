//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.atlas.model.ShapeField
import com.felipebz.zpa.atlas.model.TypeShape
import java.util.*

/**
 * The type a declaration writes ([TypeShape]), read from zpa's syntax tree, with what each name in it names:
 * - a size, read as written: a character length and its unit (`CHAR`, `BYTE`, or none written, which the compile-time
 *   `NLS_LENGTH_SEMANTICS` decides), a number's precision, a predefined bounded type (`PLS_INTEGER`);
 * - an anchor (`x%TYPE`, `x%ROWTYPE`) or a type name: the entity the index already resolves it to (its `TYPE_USE`,
 *   [FileState.typeTargets]), or the member of a Forms module's package it names ([FileState.formsMembers]), whose use
 *   is the package's; for a name of a local declaration, which the index does not resolve there, the entity of that
 *   declaration when it has one, else its own shape. zpa's name binding does not answer the name of an anchor: its
 *   first segment is followed with the declaration zpa's parser attaches to it (the legacy symbol), as the index
 *   already does to tell a local anchor; a type name with zpa's type binding.
 */
internal class TypeShapes(
    private val state: FileState,
    private val bound: BoundNames,
    private val typeBinding: (AstNode) -> NameBinding,
    private val root: AstNode?
) {
    /** The declarations of the file, by the position of their name. */
    private val declarations: Map<Long, AstNode> by lazy {
        val result = HashMap<Long, AstNode>()
        root?.getDescendants(*DECLARATIONS)?.forEach { node ->
            node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { result.putIfAbsent(position(it.token.line, it.token.column), node) }
        }
        result
    }

    /** The loop records (`for r in …`) of the file, by the position of their name. */
    private val iterands: Map<Long, AstNode> by lazy {
        root?.getDescendants(PlSqlGrammar.ITERAND_DECLARATION)?.associateBy { node ->
            node.firstChild.token.let { position(it.line, it.column) }
        }.orEmpty()
    }

    /** The loop record declared with its name at [line] and [column], or null. */
    fun iterand(line: Int, column: Int): AstNode? = iterands[position(line, column)]

    /** The declarations being followed, to stop at a cycle (`a b%TYPE; b a%TYPE`, which Oracle rejects). */
    private val following: MutableSet<AstNode> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * The shape of the declaration [node] (or of the declaration under its wrappers, with the same span), whose entity
     * is [key] (null for a declaration without an entity): a variable's, constant's, parameter's or field's type, a
     * type's definition, a cursor's row. Null for a node that declares none of these.
     */
    fun of(node: AstNode, key: String?): TypeShape? {
        val declaration = declarationIn(node) ?: return null
        if (!following.add(declaration)) return TypeShape.Unknown("ANCHOR_CYCLE")
        try {
            return declaration(declaration, key)
        } finally {
            following.remove(declaration)
        }
    }

    /** [node] when it is a declaration, else the declaration under its wrappers that spans the same text. */
    private fun declarationIn(node: AstNode): AstNode? {
        var current = node
        while (current.type !in DECLARATION_TYPES) {
            current = current.children.singleOrNull { it.hasToken() && spanOf(it) == spanOf(node) } ?: return null
        }
        return current
    }

    private fun declaration(node: AstNode, key: String?): TypeShape? = when (node.type) {
        PlSqlGrammar.RECORD_DECLARATION -> TypeShape.Record(node.getChildren(PlSqlGrammar.RECORD_FIELD_DECLARATION).map { field ->
            val name = normalizeIdentifier(field.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME).tokenOriginalValue)
            ShapeField(name, key?.let { "$it.$name" }, datatypeOf(field))
        })
        PlSqlGrammar.TABLE_OF_DECLARATION -> TypeShape.Collection(datatypeOf(node.getFirstChild(PlSqlGrammar.NESTED_TABLE_DEFINITION)) ?: unknown())
        PlSqlGrammar.VARRAY_DECLARATION -> TypeShape.Collection(datatypeOf(node.getFirstChild(PlSqlGrammar.VARRAY_TYPE_DEFINITION)) ?: unknown())
        PlSqlGrammar.REF_CURSOR_DECLARATION -> TypeShape.Unknown("CURSOR_VARIABLE", strongCursor = node.hasDirectChildren(PlSqlKeyword.RETURN))
        // A standalone type: a collection (`AS TABLE OF t`, `AS VARRAY(n) OF t`), or an object type, which has no size.
        PlSqlGrammar.CREATE_TYPE -> when {
            node.hasDirectChildren(PlSqlGrammar.OBJECT_TYPE_DEFINITION) -> TypeShape.Unsized("OBJECT")
            else -> (node.getFirstChildOrNull(PlSqlGrammar.NESTED_TABLE_DEFINITION, PlSqlGrammar.VARRAY_TYPE_DEFINITION) ?: node)
                .getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.let { TypeShape.Collection(datatype(it)) }
        }
        PlSqlGrammar.CURSOR_DECLARATION -> node.getFirstChildOrNull(com.felipebz.zpa.api.DmlGrammar.SELECT_EXPRESSION)
            ?.let { query -> state.cursorItems[query]?.let { TypeShape.CursorRow(it) } ?: TypeShape.Unknown("CURSOR_QUERY_UNKNOWN") }
            ?: datatypeOf(node)
            ?: TypeShape.Unknown("CURSOR_QUERY_UNKNOWN")
        // An OUT or IN OUT parameter: the value lands in the caller's argument.
        PlSqlGrammar.PARAMETER_DECLARATION -> datatypeOf(node)?.let { type ->
            if (node.hasDirectChildren(PlSqlKeyword.OUT)) TypeShape.OutParameter(if (node.hasDirectChildren(PlSqlKeyword.IN)) "IN OUT" else "OUT", type) else type
        }
        else -> datatypeOf(node)
    }

    private fun unknown() = TypeShape.Unknown("TYPE_UNKNOWN")

    /** The shape of the `DATATYPE` child of [node], or null when it has none. */
    private fun datatypeOf(node: AstNode): TypeShape? = node.getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.let(::datatype)

    /** The shape of a `DATATYPE` node. */
    fun datatype(datatype: AstNode): TypeShape {
        val node = datatype.firstChild
        val written = DeclaredTexts.text(tokensOf(node)).uppercase()
        return when (node.type) {
            PlSqlGrammar.CHARACTER_DATAYPE -> character(node, written)
            PlSqlGrammar.NUMERIC_DATATYPE -> numeric(node, written)
            PlSqlGrammar.ANCHORED_DATATYPE -> anchored(node)
            PlSqlGrammar.CUSTOM_DATATYPE -> named(node)
            else -> TypeShape.Unsized(written)
        }
    }

    private fun character(node: AstNode, written: String): TypeShape {
        val first = node.firstChild.type
        if (first == PlSqlKeyword.LONG || first == PlSqlKeyword.ROWID || first == PlSqlKeyword.UROWID) return TypeShape.Unsized(written)
        val constraint = node.getFirstChildOrNull(PlSqlGrammar.CHARACTER_DATATYPE_CONSTRAINT)
        // The length is the literal after the parenthesis (zpa skips DATATYPE_LENGTH); a constant or a directive gives none.
        val length = constraint?.children?.getOrNull(1)?.takeIf { it.type != PlSqlKeyword.CHARACTER }?.let(::integer)
        val unit = when {
            first == PlSqlKeyword.RAW -> "BYTE"
            first == PlSqlKeyword.NCHAR || first == PlSqlKeyword.NVARCHAR2 -> "CHAR"
            constraint?.hasDirectChildren(PlSqlKeyword.CHAR) == true -> "CHAR"
            constraint?.hasDirectChildren(PlSqlKeyword.BYTE) == true -> "BYTE"
            else -> NLS_LENGTH_SEMANTICS
        }
        return when {
            length != null -> TypeShape.Sized(written, length, unit)
            // A CHAR or NCHAR without a length is one character long.
            first == PlSqlKeyword.CHAR || first == PlSqlKeyword.CHARACTER || first == PlSqlKeyword.NCHAR -> TypeShape.Sized(written, 1, unit)
            else -> TypeShape.Unsized(written)
        }
    }

    /**
     * A number's size as a column's `NUMBER(p,s)` is compared with it: the digits it holds before the point (`p - s`,
     * none below zero) and its scale `s`. `NUMBER(p)` and the predefined `INTEGER`, `INT`, `SMALLINT`, `DECIMAL`, `DEC`
     * and `NUMERIC` (`NUMBER(38,0)`) have scale 0; `NUMBER(*,s)` has precision 38; a negative scale rounds to the left of
     * the point and holds that many more digits (`NUMBER(5,-2)` holds 7). A floating type (`FLOAT(n)`, `REAL`,
     * `DOUBLE PRECISION`, `BINARY_FLOAT`, `BINARY_DOUBLE`) and `NUMBER`/`NUMBER(*)` round instead of failing: no size.
     */
    private fun numeric(node: AstNode, written: String): TypeShape {
        val first = node.firstChild.type
        val constraint = node.getFirstChildOrNull(PlSqlGrammar.NUMERIC_DATATYPE_CONSTRAINT)
        val precision = constraint?.getFirstChildOrNull(PlSqlGrammar.NUMERIC_PRECISION)
        val scale = constraint?.getFirstChildOrNull(PlSqlGrammar.NUMERIC_SCALE)
        return when {
            first in FLOATING -> TypeShape.Unsized(written)
            precision != null -> {
                val any = precision.hasDirectChildren(com.felipebz.zpa.api.PlSqlPunctuator.MULTIPLICATION)
                if (any && scale == null) return TypeShape.Unsized(written)
                val digits = if (any) 38 else integer(precision) ?: return TypeShape.Unknown("SIZE_NOT_A_LITERAL")
                val s = if (scale == null) 0 else integer(scale) ?: return TypeShape.Unknown("SIZE_NOT_A_LITERAL")
                TypeShape.Sized(written, maxOf(0, digits - s), "DIGITS", s)
            }
            first in WHOLE_NUMBERS -> TypeShape.Sized(written, 38, "DIGITS", 0)
            first in BOUNDED -> TypeShape.Sized(written, null, null)
            else -> TypeShape.Unsized(written)
        }
    }

    /** The integer [node] writes, a literal token, signed or not; null for anything else. */
    private fun integer(node: AstNode): Int? = tokensOf(node).takeIf { it.size in 1..2 }?.joinToString("") { it.originalValue }?.toIntOrNull()

    /** `x%TYPE` or `x%ROWTYPE`: the entity the index resolved, else the local declaration the first segment names. */
    private fun anchored(node: AstNode): TypeShape {
        val rowType = node.lastChild.type == PlSqlKeyword.ROWTYPE
        val name = node.firstChild
        if (node in state.typeTargets) {
            val key = state.typeTargets[node]
            return TypeShape.Anchored(rowType, key, null, emptyList(), if (key == null) "ANCHOR_UNRESOLVED" else null)
        }
        state.formsMembers[spanOf(name)]?.let { return TypeShape.Anchored(rowType, it.key, null, it.fields, null) }
        val segments = segmentsOf(name) ?: return TypeShape.Anchored(rowType, null, null, emptyList(), "ANCHOR_NOT_A_NAME")
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        val declaration = (first as? SemanticAstNode)?.symbol?.node?.let(::declaringNode)
            ?: return TypeShape.Anchored(rowType, null, null, emptyList(), "ANCHOR_UNRESOLVED")
        val path = segments.drop(1)
        val (key, shape) = entityOrShape(declaration)
        return TypeShape.Anchored(rowType, key, shape, path, null)
    }

    /** A type name: the entity the index resolved, else the local type zpa's type binding names. */
    private fun named(node: AstNode): TypeShape {
        if (node in state.typeTargets) {
            val key = state.typeTargets[node]
            return TypeShape.Named(key, null, if (key == null) "TYPE_UNRESOLVED" else null)
        }
        val name = node.firstChild
        state.formsMembers[spanOf(name)]?.takeIf { it.fields.isEmpty() }?.let { return TypeShape.Named(it.key, null, null) }
        val declaration = when (val binding = typeBinding(name)) {
            is NameBinding.Local -> binding.declaration.takeIf { it.fileId == null }?.let { declarations[position(it.line, it.column)] }
            else -> null
        } ?: (((if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild) as? SemanticAstNode)?.symbol?.node?.let(::declaringNode))
        if (declaration == null) return TypeShape.Named(null, null, "TYPE_UNRESOLVED")
        val (key, shape) = entityOrShape(declaration)
        return TypeShape.Named(key, shape, null)
    }

    /** The entity of the local declaration [node] when it has one, else its shape. */
    private fun entityOrShape(node: AstNode): Pair<String?, TypeShape?> {
        val name = node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)
        val key = name?.let { bound.keyAt(it.token.line, it.token.column) }
        return if (key != null) key to null else null to (of(node, null) ?: TypeShape.Unknown("DECLARATION_UNKNOWN"))
    }

    /** The declaration whose name is at [line] and [column] of the file, or null. */
    fun localDeclaration(line: Int, column: Int): AstNode? = declarations[position(line, column)]

    /** The declaration node a legacy symbol's name belongs to. */
    private fun declaringNode(identifier: AstNode): AstNode? {
        var node: AstNode? = identifier
        while (node != null && node.type !in DECLARATION_TYPES) node = node.parentOrNull
        return node
    }

    private fun segmentsOf(name: AstNode): List<String>? = when (name.type) {
        PlSqlGrammar.VARIABLE_NAME -> listOf(normalizeIdentifier(name.tokenOriginalValue))
        PlSqlGrammar.MEMBER_EXPRESSION -> name.children.filter { it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .takeIf { parts -> parts.size * 2 - 1 == name.numberOfChildren }?.map { normalizeIdentifier(it.tokenOriginalValue) }
        else -> null
    }

    private fun position(line: Int, column: Int) = (line.toLong() shl 32) or column.toLong()

    companion object {
        /** The unit of a character length written without `CHAR` or `BYTE`: the compile-time setting decides it. */
        const val NLS_LENGTH_SEMANTICS = "NLS_LENGTH_SEMANTICS"

        private val DECLARATIONS = arrayOf(
            PlSqlGrammar.VARIABLE_DECLARATION, PlSqlGrammar.PARAMETER_DECLARATION, PlSqlGrammar.CURSOR_PARAMETER_DECLARATION,
            PlSqlGrammar.CURSOR_DECLARATION, PlSqlGrammar.RECORD_FIELD_DECLARATION, PlSqlGrammar.TYPE_ATTRIBUTE, PlSqlGrammar.RECORD_DECLARATION,
            PlSqlGrammar.TABLE_OF_DECLARATION, PlSqlGrammar.VARRAY_DECLARATION, PlSqlGrammar.CUSTOM_SUBTYPE, PlSqlGrammar.REF_CURSOR_DECLARATION
        )
        private val DECLARATION_TYPES = DECLARATIONS.toSet() + PlSqlGrammar.CREATE_TYPE

        /** The predefined integer types bounded by a range rather than a precision. */
        private val BOUNDED = setOf(
            PlSqlKeyword.PLS_INTEGER, PlSqlKeyword.BINARY_INTEGER, PlSqlKeyword.NATURAL, PlSqlKeyword.NATURALN, PlSqlKeyword.POSITIVE,
            PlSqlKeyword.POSITIVEN, PlSqlKeyword.SIGNTYPE
        )

        /** The predefined subtypes of `NUMBER(38,0)` when written without a precision. */
        private val WHOLE_NUMBERS = setOf(
            PlSqlKeyword.INTEGER, PlSqlKeyword.INT, PlSqlKeyword.SMALLINT, PlSqlKeyword.DECIMAL, PlSqlKeyword.DEC, PlSqlKeyword.NUMERIC
        )

        /** The floating types: they round a value to their precision rather than fail on it. */
        private val FLOATING = setOf(
            PlSqlKeyword.FLOAT, PlSqlKeyword.REAL, PlSqlKeyword.DOUBLE, PlSqlKeyword.BINARY_FLOAT, PlSqlKeyword.BINARY_DOUBLE
        )
    }
}
