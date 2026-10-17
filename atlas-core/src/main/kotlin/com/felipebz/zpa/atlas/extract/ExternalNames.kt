//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.model.RelationKind

/**
 * The external entity of a multi-segment name the project does not declare, shared by unknown calls and unknown types
 * so that their keys cannot drift. When the first segment `S` is a schema of the run: `db:S.X` without parent,
 * `db:S.A.X` under package `db:S.A`. Otherwise: `db:~.A.X` under package `db:~.A` (a package, a synonym or a schema:
 * unknown until synonyms), and `db:~.A.B.X` without parent (`A.B` may be a package variable or a package of an unknown
 * schema).
 */
object ExternalNames {
    sealed class Result {
        /** The owner or the full key is declared: a missing member of a project object, without entity. */
        data object UnknownMember : Result()

        data class External(val key: String) : Result()
    }

    /**
     * @param names the normalized segments, at least two
     * @param unitSchema the schema of the unit, for the owner key of a two-segment name
     */
    fun external(
        names: List<String>,
        kind: EntityKind,
        unitSchema: String?,
        runSchemas: Set<String>,
        catalog: DeclarationCatalog,
        entities: MutableList<EntityFact>,
        relations: MutableList<RelationFact>
    ): Result {
        require(names.size >= 2) { "An external name has at least two segments" }
        val owner = if (names.size == 2) dbKey(unitSchema ?: UNKNOWN_SCHEMA, names[0]) else dbKey(names[0], *names.subList(1, names.size - 1).toTypedArray())
        val schemaQualified = names[0] in runSchemas
        val key = if (schemaQualified) dbKey(names[0], *names.drop(1).toTypedArray()) else dbKey(UNKNOWN_SCHEMA, *names.toTypedArray())
        if (catalog.isDeclared(owner) || catalog.isDeclared(key)) return Result.UnknownMember

        val schema = if (schemaQualified) names[0] else null
        val parent = when {
            schemaQualified -> if (names.size > 2) key.substringBeforeLast('.') else null
            names.size == 2 -> key.substringBeforeLast('.')
            else -> null
        }
        if (parent != null) {
            entities += EntityFact(parent, EntityKind.PACKAGE, schema, names[names.size - 2], null, parent.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
            relations += RelationFact(parent, RelationKind.CONTAINS, key)
        }
        entities += EntityFact(key, kind, schema, names.last(), parent, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
        return Result.External(key)
    }
}
