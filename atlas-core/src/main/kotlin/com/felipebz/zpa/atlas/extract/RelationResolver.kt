package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.model.*

/** A relation name as written: `[schema.]name[@link]`, each part normalized. */
data class WrittenRelation(val schema: String?, val name: String, val link: String?)

data class ResolvedRelation(
    val entityKey: String,
    /** The schema of the relation entity, null when unknown. */
    val schema: String?,
    val status: Status,
    val reason: String?,
    /** The dictionary relation, when the lookup found it. */
    val relation: ProjectExternalRelation?
)

data class ResolvedColumn(val entityKey: String?, val status: Status, val reason: String?)

/** Entity precedence when several sources produce the same key: lower wins. */
object Precedence {
    const val DECLARED = 0
    const val SOURCE = 1
    const val DICTIONARY = 2
    const val MENTIONED = 3
}

/**
 * Resolves relation and column names against the dictionary; nothing here is shared between files. An entity a result
 * refers to is added to [entities], except for dictionary relations: those are only recorded in
 * [dictionaryRelations], and the run adds their entities (the relation and its columns) once, after the scan.
 */
class RelationResolver(private val dictionary: AtlasDictionary, private val entities: MutableList<EntityFact>, private val relations: MutableList<RelationFact>) {
    private val found = LinkedHashMap<String, ProjectExternalRelation>()

    /** The distinct dictionary relations this file resolved, by entity key, in first-resolution order. */
    val dictionaryRelations: Collection<ProjectExternalRelation> get() = found.values

    fun resolve(written: WrittenRelation, unitSchema: String?): ResolvedRelation {
        if (written.link != null) {
            val key = "db:" + listOfNotNull(written.schema, written.name).joinToString(".") + "@" + written.link
            mentioned(key, EntityKind.UNKNOWN_RELATION, written.schema, written.name + "@" + written.link, null)
            return ResolvedRelation(key, written.schema, Status.EXTERNAL, "DB_LINK", null)
        }
        val schema = written.schema ?: unitSchema
        if (schema == null) {
            val key = dbKey(UNKNOWN_SCHEMA, written.name)
            mentioned(key, EntityKind.UNKNOWN_RELATION, null, written.name, null)
            return ResolvedRelation(key, null, Status.UNVERIFIED, "SCHEMA_UNKNOWN", null)
        }
        val key = dbKey(schema, written.name)
        val lookup = dictionary.metadata.findRelation(identifier(schema), identifier(written.name))
        return when (lookup.status) {
            ProjectExternalLookup.Status.FOUND -> {
                val relation = lookup.relation.get()
                addDictionaryRelation(relation)
                ResolvedRelation(dictionaryKey(relation), schema, Status.RESOLVED, null, relation)
            }
            ProjectExternalLookup.Status.RELATION_NOT_FOUND -> {
                mentioned(key, EntityKind.UNKNOWN_RELATION, schema, written.name, null)
                ResolvedRelation(key, schema, Status.UNRESOLVED, "NOT_IN_DICTIONARY", null)
            }
            else -> {
                mentioned(key, EntityKind.UNKNOWN_RELATION, schema, written.name, null)
                ResolvedRelation(key, schema, Status.UNVERIFIED, null, null)
            }
        }
    }

    fun resolveColumn(relation: ResolvedRelation, column: String): ResolvedColumn {
        val key = relation.entityKey + "." + column
        val dictionaryRelation = relation.relation
        if (dictionaryRelation == null) {
            mentioned(key, EntityKind.COLUMN, relation.schema, column, relation.entityKey)
            return when (relation.status) {
                Status.EXTERNAL -> ResolvedColumn(key, Status.EXTERNAL, relation.reason)
                // A column of a relation the dictionary lacks is as unresolved as the relation (not an edge).
                Status.UNRESOLVED -> ResolvedColumn(key, Status.UNRESOLVED, "RELATION_UNRESOLVED")
                else -> ResolvedColumn(key, Status.UNVERIFIED, relation.reason)
            }
        }
        val lookup = dictionary.metadata.findColumn(dictionaryRelation.schema, dictionaryRelation.name, identifier(column))
        return when (lookup.status) {
            ProjectExternalLookup.Status.FOUND -> ResolvedColumn(relation.entityKey + "." + lookup.column.get().name.exact(), Status.RESOLVED, null)
            ProjectExternalLookup.Status.COLUMN_NOT_FOUND -> {
                mentioned(key, EntityKind.COLUMN, relation.schema, column, relation.entityKey)
                ResolvedColumn(key, Status.UNRESOLVED, "NOT_IN_DICTIONARY")
            }
            else -> {
                mentioned(key, EntityKind.COLUMN, relation.schema, column, relation.entityKey)
                ResolvedColumn(key, Status.UNVERIFIED, null)
            }
        }
    }

    /**
     * Whether the dictionary relation of [relation] has [column]: true or false when the dictionary can tell (a
     * relation with complete columns), null otherwise (no dictionary relation, partial columns, a hidden `SYS_`
     * name). Adds no entity.
     */
    fun hasColumn(relation: ResolvedRelation, column: String): Boolean? {
        val dictionaryRelation = relation.relation ?: return null
        return when (dictionary.metadata.findColumn(dictionaryRelation.schema, dictionaryRelation.name, identifier(column)).status) {
            ProjectExternalLookup.Status.FOUND -> true
            ProjectExternalLookup.Status.COLUMN_NOT_FOUND -> false
            else -> null
        }
    }

    /** Records a dictionary relation the file refers to; its entities are emitted once per run. */
    fun addDictionaryRelation(relation: ProjectExternalRelation) {
        found.putIfAbsent(dictionaryKey(relation), relation)
    }

    private fun mentioned(key: String, kind: EntityKind, schema: String?, name: String, parentKey: String?) {
        entities += EntityFact(key, kind, schema, name, parentKey, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
        if (parentKey != null) relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
    }

    companion object {
        /** A source spelling already normalized: quoted so that it is taken exactly. */
        fun identifier(normalized: String): ProjectIdentifier = ProjectIdentifier.fromSource("\"" + normalized + "\"")

        fun dictionaryKey(relation: ProjectExternalRelation): String =
            dbKey(relation.schema.exact(), relation.name.exact())

        /** The relation entity of the dictionary with all its columns (`CONTAINS`). */
        fun addDictionaryRelation(dictionary: AtlasDictionary, relation: ProjectExternalRelation, entities: MutableList<EntityFact>, relations: MutableList<RelationFact>) {
            val schema = relation.schema.exact()
            val name = relation.name.exact()
            val key = dictionaryKey(relation)
            entities += EntityFact(key, dictionary.kindOf(relation).entityKind, schema, name, null, "$schema.$name", external = false, precedence = Precedence.DICTIONARY)
            for (column in relation.columns) {
                val columnName = column.name.exact()
                val columnKey = "$key.$columnName"
                entities += EntityFact(columnKey, EntityKind.COLUMN, schema, columnName, key, "$schema.$name.$columnName", external = false, precedence = Precedence.DICTIONARY)
                relations += RelationFact(key, RelationKind.CONTAINS, columnKey)
            }
        }
    }
}
