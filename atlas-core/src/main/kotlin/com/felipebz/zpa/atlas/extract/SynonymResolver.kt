//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym

/** The object a name the project does not know reaches in the database: `owner.name`, with its `OBJECT_TYPE`. */
data class NameTarget(val owner: String, val name: String, val type: String)

/**
 * Resolves, for one file, a name the project does not know in Oracle's order for the namespace of a schema: an object
 * of the schema (a synonym of the schema is followed to its final target, any other object is the name's own object),
 * then a public synonym, but only when the schema's object names were read and it has no object of that name (a
 * public synonym also wins over a schema named like it). The synonyms followed are recorded in [dictionarySynonyms];
 * the run adds their entities once, after the scan.
 */
class SynonymResolver(private val dictionary: AtlasDictionary) {
    private val followed = LinkedHashSet<DictionarySynonym>()

    /** The distinct synonyms this file followed, every hop of each chain. */
    val dictionarySynonyms: Collection<DictionarySynonym> get() = followed

    /**
     * The object [name] reaches seen from [schema] when it is one of [targetTypes], or null. A synonym or another
     * object of [schema] wins even when its type does not fit (Oracle uses it and rejects the statement); a public
     * synonym applies only when [publicAllowed] and [schema]'s object names are known without [name]. A chain with a
     * cycle or too many hops is nothing.
     */
    fun follow(name: String, schema: String?, targetTypes: Set<String>, publicAllowed: Boolean = true): NameTarget? {
        if (schema == null || schema == AtlasDictionary.PUBLIC) return null
        val own = dictionary.synonymChain(schema, name) ?: return null
        val chain = when {
            own.isNotEmpty() -> own
            dictionary.objectType(schema, name) != null -> {
                // An object of the schema the project does not declare (a synonym not read, for example one with a
                // database link, is not an object of a known type: nothing).
                val type = dictionary.objectType(schema, name)!!
                return if (type != "SYNONYM" && type in targetTypes) NameTarget(schema, name, type) else null
            }
            publicAllowed && dictionary.knowsObjects(schema) ->
                dictionary.synonymChain(AtlasDictionary.PUBLIC, name)?.takeIf { it.isNotEmpty() } ?: return null
            else -> return null
        }
        val target = chain.last()
        if (target.targetType !in targetTypes) return null
        followed += chain
        return NameTarget(target.targetOwner, target.targetName, target.targetType)
    }

    companion object {
        val RELATIONS = setOf("TABLE", "VIEW")
        val PACKAGES_AND_TYPES = setOf("PACKAGE", "TYPE")
        val CALLABLES = setOf("FUNCTION", "PROCEDURE", "TYPE")
        val TYPES = setOf("TYPE")
        val SEQUENCES = setOf("SEQUENCE")

        /** The quoted spellings of [target] with [rest], to resolve it as if written. */
        fun written(target: NameTarget, rest: List<String>): List<String> =
            listOf("\"${target.owner}\"", "\"${target.name}\"") + rest.map { "\"$it\"" }
    }
}
