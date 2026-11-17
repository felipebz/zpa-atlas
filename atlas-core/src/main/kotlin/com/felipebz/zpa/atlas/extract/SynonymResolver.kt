//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectDeclarationKind
import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalSynonym
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import java.util.Collections
import java.util.IdentityHashMap

/** The object a name the project does not know reaches in the database: `owner.name`, with its `OBJECT_TYPE`. */
data class NameTarget(val owner: String, val name: String, val type: String)

/**
 * Resolves, for one file, a name the project does not know in Oracle's order for the namespace of a schema: an object
 * of the schema (a synonym of the schema is followed to its final target, any other object is the name's own object),
 * then a public synonym, but only when the schema's object names were read and it has no object of that name (a
 * public synonym also wins over a schema named like it). A synonym is found as zpa finds it
 * ([ProjectSnapshot.findSynonym]): one created in the sources comes before the dictionary's object of the same owner
 * and name, and a chain may mix both. The target of a synonym of the sources may be a table created in the sources or
 * an object the project declares. The synonyms followed are recorded in [dictionarySynonyms]; the run adds their
 * entities once, after the scan.
 */
class SynonymResolver(private val dictionary: AtlasDictionary, private val snapshot: () -> ProjectSnapshot) {
    private val followed = LinkedHashSet<DictionarySynonym>()

    private val inSources: Set<ProjectExternalSynonym> by lazy {
        Collections.newSetFromMap(IdentityHashMap<ProjectExternalSynonym, Boolean>()).apply { addAll(snapshot().sourceSynonyms) }
    }

    /** The distinct synonyms this file followed, every hop of each chain. */
    val dictionarySynonyms: Collection<DictionarySynonym> get() = followed

    /** Whether the sources create the synonym [owner].[name]: it hides the dictionary's object of that owner and name. */
    fun writtenInSources(owner: String, name: String): Boolean = find(owner, name)?.let { it in inSources } == true

    /**
     * Records the synonyms zpa followed to answer a name (a call's target), every hop, as if this resolver had: the
     * synonym of the sources, else the dictionary's own entry. A hop over a database link has no target object and is
     * left out.
     */
    fun record(synonyms: List<ProjectExternalSynonym>) {
        for (synonym in synonyms) {
            if (synonym.dbLink.isPresent || !synonym.targetOwner.isPresent) continue
            hop(synonym)?.let { followed += it }
        }
    }

    private fun find(owner: String, name: String): ProjectExternalSynonym? =
        snapshot().findSynonym(RelationResolver.identifier(owner), RelationResolver.identifier(name), dictionary.metadata).orElse(null)

    /** [synonym] as a hop: the dictionary's entry, or one of the sources with its target's `OBJECT_TYPE`; null over a link. */
    private fun hop(synonym: ProjectExternalSynonym): DictionarySynonym? {
        val targetOwner = synonym.targetOwner.orElse(null)?.takeIf { !synonym.dbLink.isPresent }?.exact() ?: return null
        val owner = synonym.owner.exact()
        val name = synonym.name.exact()
        if (synonym !in inSources) dictionary.synonym(owner, name)?.let { return it }
        val targetName = synonym.targetName.exact()
        return DictionarySynonym(owner, name, targetOwner, targetName, objectType(targetOwner, targetName))
    }

    /** The `OBJECT_TYPE` of [owner].[name]: the dictionary's, else a table created in the sources, else a project declaration's. */
    private fun objectType(owner: String, name: String): String {
        dictionary.objectType(owner, name)?.let { return it }
        if (find(owner, name)?.let { it in inSources } == true) return "SYNONYM"
        val lookup = snapshot().findRelation(RelationResolver.identifier(owner), RelationResolver.identifier(name), dictionary.metadata)
        if (lookup.status == ProjectExternalLookup.Status.FOUND) return if (lookup.relation.get().isView) "VIEW" else "TABLE"
        val kinds = snapshot().findDeclarations(ProjectQualifiedName.of("\"$owner\"", "\"$name\"")).declarations.map { it.kind }
        return kinds.firstNotNullOfOrNull { DECLARED_TYPES[it] } ?: ""
    }

    /**
     * The chain of synonyms from [owner].[name], every hop, empty when it is no synonym: at each hop the synonym of the
     * sources, else the dictionary's, else the metadata's. Null with a cycle, too many hops or a database link.
     */
    private fun chain(owner: String, name: String): List<DictionarySynonym>? {
        val chain = ArrayList<DictionarySynonym>()
        var at = owner to name
        while (true) {
            val found = find(at.first, at.second)
            val hop = if (found != null && found in inSources) {
                hop(found) ?: return null
            } else {
                dictionary.synonym(at.first, at.second) ?: found?.let { hop(it) ?: return null } ?: return chain
            }
            if (chain.size == AtlasDictionary.MAX_HOPS || chain.any { it.owner == hop.owner && it.name == hop.name }) return null
            chain += hop
            at = hop.targetOwner to hop.targetName
        }
    }

    /**
     * The object [name] reaches seen from [schema] when it is one of [targetTypes], or null. A synonym or another
     * object of [schema] wins even when its type does not fit (Oracle uses it and rejects the statement); a public
     * synonym applies only when [publicAllowed] and [schema]'s object names are known without [name]. A chain with a
     * cycle or too many hops is nothing.
     */
    fun follow(name: String, schema: String?, targetTypes: Set<String>, publicAllowed: Boolean = true): NameTarget? {
        if (schema == null || schema == AtlasDictionary.PUBLIC) return null
        val own = chain(schema, name) ?: return null
        val chain = when {
            own.isNotEmpty() -> own
            dictionary.objectType(schema, name) != null -> {
                // An object of the schema the project does not declare (a synonym not read, for example one with a
                // database link, is not an object of a known type: nothing).
                val type = dictionary.objectType(schema, name)!!
                return if (type != "SYNONYM" && type in targetTypes) NameTarget(schema, name, type) else null
            }
            publicAllowed && dictionary.knowsObjects(schema) ->
                chain(AtlasDictionary.PUBLIC, name)?.takeIf { it.isNotEmpty() } ?: return null
            else -> return null
        }
        val target = chain.last()
        if (target.targetType !in targetTypes) return null
        followed += chain
        return NameTarget(target.targetOwner, target.targetName, target.targetType)
    }

    companion object {
        val RELATIONS = setOf("TABLE", "VIEW", "MATERIALIZED VIEW")
        val PACKAGES_AND_TYPES = setOf("PACKAGE", "TYPE")

        /** One-segment calls: an operator (`XMLSEQUENCE`) is called like a function. */
        val CALLABLES = setOf("FUNCTION", "PROCEDURE", "TYPE", "OPERATOR")
        val TYPES = setOf("TYPE")
        val SEQUENCES = setOf("SEQUENCE")

        /** The `OBJECT_TYPE` of a schema object the project declares. */
        private val DECLARED_TYPES = mapOf(
            ProjectDeclarationKind.PACKAGE to "PACKAGE",
            ProjectDeclarationKind.PACKAGE_BODY to "PACKAGE",
            ProjectDeclarationKind.STANDALONE_TYPE to "TYPE",
            ProjectDeclarationKind.STANDALONE_FUNCTION to "FUNCTION",
            ProjectDeclarationKind.STANDALONE_PROCEDURE to "PROCEDURE",
            ProjectDeclarationKind.SEQUENCE to "SEQUENCE"
        )

        /** The quoted spellings of [target] with [rest], to resolve it as if written. */
        fun written(target: NameTarget, rest: List<String>): List<String> =
            listOf("\"${target.owner}\"", "\"${target.name}\"") + rest.map { "\"$it\"" }
    }
}
