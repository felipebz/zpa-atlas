//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.symbols.Symbol

/** What a name the project could only guess resolves to, when it names a declaration of an enclosing unit or of the object itself. */
internal enum class OwnName {
    /** A local or private value, a type attribute: nothing to index. */
    VALUE,

    /** A local subprogram or a method of the type: a `LOCAL_SUBPROGRAM` call. */
    SUBPROGRAM
}

/** The names a unit declares for itself: of its enclosing units, or of the object type whose body it is. */
internal class OwnNames(private val typeMembers: ObjectTypeMembers) {
    /**
     * A name whose leading segments name enclosing units of the place it is written (optionally the schema, the package
     * body, enclosing subprograms: `valida.x`, `pk.valida.x`, `pk.x`) is the rest of the name declared in the innermost
     * unit named, as the unqualified name there. In a type body, `SELF.x` and a name whose first segment is an
     * attribute or method of the type are the object's own members. Null when the name is neither.
     */
    fun ownName(name: AstNode, segments: List<String>, context: Context): OwnName? {
        val names = segments.map { normalizeIdentifier(it) }
        context.objectType?.let { type ->
            val members = typeMembers.of(type)
            val methods = typeMembers.methods(type)
            if (names[0] == "SELF") {
                val member = names.getOrNull(1) ?: return OwnName.VALUE
                return if (names.size == 2 && (member in methods || isUnitSubprogram(name, member, context))) OwnName.SUBPROGRAM else OwnName.VALUE
            }
            if (names[0] in members) return if (names.size == 1 && names[0] in methods) OwnName.SUBPROGRAM else OwnName.VALUE
        }
        if (names.size < 2) return null
        val units = enclosingUnits(name)
        if (units.isEmpty()) return null
        var index: Int
        var consumed: Int
        if (names.size > 2 && names[0] == (units[0].schema ?: context.schema) && names[1] == units[0].name) {
            index = 0
            consumed = 2
        } else {
            index = units.indexOfLast { it.name == names[0] }
            if (index < 0) return null
            consumed = 1
        }
        while (consumed < names.size - 1 && index + 1 < units.size && names[consumed] == units[index + 1].name) {
            index++
            consumed++
        }
        if (context.dynamic != null) return null
        val declared = unitDeclarations(units[index].node).firstOrNull { it.name == names[consumed] } ?: return null
        return if (declared.subprogram && consumed == names.size - 1) OwnName.SUBPROGRAM else OwnName.VALUE
    }

    /** A method declared in the type body around [node] (a method body). */
    private fun isUnitSubprogram(node: AstNode, name: String, context: Context): Boolean {
        if (context.dynamic != null) return false
        val body = node.getFirstAncestorOrNull(PlSqlGrammar.CREATE_TYPE_BODY) ?: return false
        return typeBodyMethods(body).any { it == name }
    }

    /** A named unit enclosing a name: a package body, a standalone subprogram or a nested subprogram. */
    private class EnclosingUnit(val node: AstNode, val schema: String?, val name: String)

    /** The named units around [node], outermost first. */
    private fun enclosingUnits(node: AstNode): List<EnclosingUnit> {
        val result = ArrayList<EnclosingUnit>()
        var current = node.parentOrNull
        while (current != null) {
            when (current.type) {
                PlSqlGrammar.CREATE_PACKAGE_BODY, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION -> {
                    val written = names(current.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME))
                    if (written.isNotEmpty()) result += EnclosingUnit(current, written.getOrNull(written.size - 2), written.last())
                }
                PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION ->
                    current.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { result += EnclosingUnit(current, null, normalizeIdentifier(it.tokenOriginalValue)) }
            }
            current = current.parentOrNull
        }
        return result.asReversed()
    }
}

/** A name a unit declares for itself: a parameter or an item of its declare section; [subprogram] for a nested subprogram. */
internal class UnitDeclaration(val name: String, val subprogram: Boolean)

/**
 * What [unit] (a package body, a standalone or nested subprogram) declares at its own level: its parameters and the
 * items of its declare section, nested subprograms by name only.
 */
internal fun unitDeclarations(unit: AstNode): List<UnitDeclaration> {
    val result = ArrayList<UnitDeclaration>()
    unit.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION)?.forEach { parameter ->
        parameter.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { result += UnitDeclaration(normalizeIdentifier(it.tokenOriginalValue), false) }
    }
    for (item in unit.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
        if (item.type == PlSqlGrammar.PRAGMA_DECLARATION) continue
        val subprogram = item.type == PlSqlGrammar.PROCEDURE_DECLARATION || item.type == PlSqlGrammar.FUNCTION_DECLARATION
        item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { result += UnitDeclaration(normalizeIdentifier(it.tokenOriginalValue), subprogram) }
    }
    return result
}

/** The names of the methods of a type [body]: its member and static subprograms and its map or order function. */
internal fun typeBodyMethods(body: AstNode): List<String> =
    body.children.filter { it.type == PlSqlGrammar.TYPE_SUBPROGRAM || it.type == PlSqlGrammar.MAP_ORDER_FUNCTION }.mapNotNull { element ->
        element.getFirstChildOrNull(PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION)
            ?.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }
    }

/**
 * A subprogram the legacy table declares outside a package scope and below the file scope: nested in a standalone
 * unit, a subprogram or a block. `Symbol.isGlobal` is not used: it is true for subprograms nested in standalone
 * units, because their scope's outer scope is the file scope. The unit itself (recursion) is declared in the file
 * scope and package members in the package spec or body scope; those are not local.
 */
internal fun isLocalSubprogram(symbol: Symbol): Boolean {
    val scope = symbol.scope
    if (scope.outer == null) return false
    return scope.type != PlSqlGrammar.CREATE_PACKAGE && scope.type != PlSqlGrammar.CREATE_PACKAGE_BODY
}

/**
 * A value or type the legacy table declares outside a package specification and below the file scope: in a
 * block, a subprogram or a package body. zpa-core indexes the package values and types of specifications only, so
 * these are never project declarations.
 */
internal fun isLocalDeclaration(symbol: Symbol): Boolean {
    val scope = symbol.scope
    return scope.outer != null && scope.type != PlSqlGrammar.CREATE_PACKAGE
}
