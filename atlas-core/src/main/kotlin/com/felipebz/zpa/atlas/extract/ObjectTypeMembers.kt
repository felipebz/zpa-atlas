//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.checks.PlSqlCheck
import java.util.concurrent.ConcurrentHashMap

/**
 * The attribute and method names of the object types the sources specify, by type key (`db:S.T`), with the members of
 * their supertypes. Type attributes and methods are not entities: this only lets a type body tell its own members
 * from external names.
 */
class ObjectTypeMembers {
    private class Spec(val attributes: Set<String>, val methods: Set<String>, val supertype: String?)

    private val specs = ConcurrentHashMap<String, Spec>()

    /** Every attribute and method name of [typeKey] and its supertypes; empty for a type no source specifies. */
    fun of(typeKey: String): Set<String> = collect(typeKey) { it.attributes + it.methods }

    /** The method names of [typeKey] and its supertypes. */
    fun methods(typeKey: String): Set<String> = collect(typeKey) { it.methods }

    private fun collect(typeKey: String, part: (Spec) -> Set<String>): Set<String> {
        val result = HashSet<String>()
        val seen = HashSet<String>()
        var key: String? = typeKey
        while (key != null && seen.add(key)) {
            val spec = specs[key] ?: break
            result += part(spec)
            key = spec.supertype
        }
        return result
    }

    /** Reads the object type specifications of one file. [defaultSchema] is the schema of names written without one. */
    fun collector(defaultSchema: String?): PlSqlCheck = object : PlSqlCheck() {
        override fun visitFile(node: AstNode) {
            for (create in node.getDescendants(PlSqlGrammar.CREATE_TYPE)) {
                val definition = create.getFirstChildOrNull(PlSqlGrammar.OBJECT_TYPE_DEFINITION) ?: continue
                val key = typeKey(create.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME), defaultSchema) ?: continue
                val attributes = definition.getChildren(PlSqlGrammar.TYPE_ATTRIBUTE)
                    .mapNotNull { it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) }.map { normalizeIdentifier(it.tokenOriginalValue) }.toSet()
                val methods = definition.getDescendants(PlSqlGrammar.TYPE_SUBPROGRAM)
                    .mapNotNull { it.getFirstChildOrNull(PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION) }
                    .mapNotNull { it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) }.map { normalizeIdentifier(it.tokenOriginalValue) }.toSet()
                val supertype = definition.takeIf { it.firstChild.type == PlSqlKeyword.UNDER }
                    ?.let { typeKey(it.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME), defaultSchema) }
                specs[key] = Spec(attributes, methods, supertype)
            }
        }
    }

    companion object {
        /** The key of a type named by [unitName]: `db:S.T`, with [defaultSchema] when no schema is written; null without one. */
        fun typeKey(unitName: AstNode?, defaultSchema: String?): String? {
            val written = unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { normalizeIdentifier(it.tokenOriginalValue) }.orEmpty()
            if (written.isEmpty()) return null
            val schema = if (written.size >= 2) written[written.size - 2] else defaultSchema ?: return null
            return dbKey(schema, written.last())
        }

        /** Source text that may specify an object type: worth the extra parse. */
        val MAY_SPECIFY_OBJECT = Regex("(?is)\\bcreate\\b.{0,80}?\\btype\\b.{0,200}?\\b(object|under)\\b")
    }
}
