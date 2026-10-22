//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.util.Locale

/**
 * The SQL of an [OccurrenceQuery]: a `WITH` clause, the `FROM` of the matching occurrences (`o`, and `h` for the
 * entity hits) and their `WHERE`, with every value bound. [matched] is the entity a row matched (the candidate for an
 * `AMBIGUOUS` occurrence matched through it), [via] whether it matched through a candidate.
 *
 * @param entityId the id of [OccurrenceQuery.entity], resolved by the caller
 * @param containerId the id of [OccurrenceQuery.container], resolved by the caller
 */
internal class OccurrenceQuerySql(query: OccurrenceQuery, entityId: Long?, containerId: Long?) {
    private val ctes = ArrayList<String>()
    private val cteParameters = ArrayList<Any?>()
    private val conditions = ArrayList<String>()
    private val conditionParameters = ArrayList<Any?>()

    var from: String
        private set
    var matched: String
        private set
    val via: String

    init {
        if (query.hasEntityFilter) {
            val entityConditions = ArrayList<String>()
            val entityParameters = ArrayList<Any?>()
            if (entityId != null) {
                if (query.inside) {
                    ctes += "subject(id) AS (SELECT ? UNION ${contained("subject")})"
                    cteParameters += entityId
                    entityConditions += "e.id IN (SELECT id FROM subject)"
                } else {
                    entityConditions += "e.id = ?"
                    entityParameters += entityId
                }
            }
            if (query.entityKinds.isNotEmpty()) {
                entityConditions += "e.kind IN (${marks(query.entityKinds.size)})"
                entityParameters.addAll(query.entityKinds.sorted())
            }
            var entityJoins = ""
            if (query.name != null) {
                val (joins, match, parameters) = parentChain("e", query.name.split('.'), ::segmentMatch)
                entityJoins = joins
                entityConditions += match
                entityParameters.addAll(parameters)
            }
            if (query.schema != null) {
                entityConditions += "e.schema_name = ?"
                entityParameters += query.schema
            }
            if (query.external != null) {
                entityConditions += "e.external = ?"
                entityParameters += query.external
            }
            ctes += "ent(id) AS (SELECT e.id FROM entity e $entityJoins WHERE ${entityConditions.joinToString(" AND ")})"
            cteParameters.addAll(entityParameters)
            // An occurrence matched directly is never also reported through a candidate.
            ctes += if (query.candidates) {
                """hits(oid, matched, via) AS (
                     SELECT o.id, o.entity_id, 0 FROM occurrence o WHERE o.entity_id IN (SELECT id FROM ent)
                     UNION ALL
                     SELECT c.occurrence_id, min(c.entity_id), 1 FROM occurrence_candidate c
                     WHERE c.entity_id IN (SELECT id FROM ent)
                       AND NOT EXISTS (SELECT 1 FROM occurrence d WHERE d.id = c.occurrence_id AND d.entity_id IN (SELECT id FROM ent))
                     GROUP BY c.occurrence_id)"""
            } else {
                "hits(oid, matched, via) AS (SELECT o.id, o.entity_id, 0 FROM occurrence o WHERE o.entity_id IN (SELECT id FROM ent))"
            }
            from = "hits h JOIN occurrence o ON o.id = h.oid"
            matched = "h.matched"
            via = "h.via"
        } else {
            from = "occurrence o"
            matched = "o.entity_id"
            via = "0"
        }
        if (containerId != null) {
            if (query.containerInside) {
                ctes += "within(id) AS (SELECT ? UNION ${contained("within")})"
                cteParameters += containerId
                conditions += "o.container_entity_id IN (SELECT id FROM within)"
            } else {
                conditions += "o.container_entity_id = ?"
                conditionParameters += containerId
            }
        }
        if (query.containerName != null) {
            val (joins, match, parameters) = parentChain("c", query.containerName.split('.'), ::segmentMatch)
            val named = "SELECT c.id FROM entity c $joins WHERE $match"
            if (query.containerInside) {
                ctes += "within_named(id) AS ($named UNION ${contained("within_named")})"
                cteParameters.addAll(parameters)
                conditions += "o.container_entity_id IN (SELECT id FROM within_named)"
            } else {
                conditions += "o.container_entity_id IN ($named)"
                conditionParameters.addAll(parameters)
            }
        }
        if (query.unitKinds.isNotEmpty()) {
            conditions += "o.unit_id IN (SELECT id FROM unit WHERE kind IN (${marks(query.unitKinds.size)}))"
            conditionParameters.addAll(query.unitKinds.sorted())
        }
        if (query.path != null) {
            conditions += "o.file_id IN (SELECT id FROM source_file WHERE path LIKE ? ESCAPE '\\')"
            conditionParameters += if ('*' in query.path) pattern(query.path) else escapeLike(query.path) + "%"
        }
        if (query.roles.isNotEmpty()) {
            conditions += query.roles.sorted().joinToString(" OR ", "(", ")") { "(',' || o.roles || ',') LIKE ?" }
            conditionParameters.addAll(query.roles.sorted().map { "%,$it,%" })
        }
        listOf("o.status" to query.statuses, "o.reason" to query.reasons, "o.origin" to query.origins).forEach { (column, values) ->
            if (values.isNotEmpty()) {
                conditions += "$column IN (${marks(values.size)})"
                conditionParameters.addAll(values.sorted())
            }
        }
        if (query.statementKinds.isNotEmpty()) {
            conditions += "o.statement_id IN (SELECT id FROM sql_statement WHERE kind IN (${marks(query.statementKinds.size)}))"
            conditionParameters.addAll(query.statementKinds.sorted())
        }
        if (query.valueKind != null || query.value != null) {
            val (valueCondition, valueParameters) = valueCondition(query.valueKind, query.value)
            conditions += "o.id IN (SELECT occurrence_id FROM written_value WHERE $valueCondition UNION ALL SELECT occurrence_id FROM call_argument WHERE $valueCondition)"
            conditionParameters.addAll(valueParameters + valueParameters)
        }
    }

    /** `WITH RECURSIVE …` (empty without common table expressions) and its parameters. */
    val with: String get() = if (ctes.isEmpty()) "" else "WITH RECURSIVE " + ctes.joinToString(",\n")

    /** `WHERE …` of the matching occurrences, plus [extra] conditions (each `AND`ed). */
    fun where(vararg extra: String): String {
        val all = conditions + extra
        return if (all.isEmpty()) "" else "WHERE " + all.joinToString(" AND ")
    }

    /**
     * Only the occurrences of [hits], a JSON array of `[occurrence id, matched entity id]` pairs, each matched as given:
     * for a query without filters of its own, so that rows chosen elsewhere group as the query's rows do.
     */
    fun restrictToHits(hits: String) {
        addCte("chosen(oid, matched) AS (SELECT json_extract(value, '$[0]'), json_extract(value, '$[1]') FROM json_each(?))", listOf(hits))
        from = "chosen u JOIN occurrence o ON o.id = u.oid"
        matched = "u.matched"
    }

    /** Adds a common table expression to [with], after those of the query. */
    fun addCte(cte: String, parameters: List<Any?>) {
        ctes += cte
        cteParameters.addAll(parameters)
    }

    /** The parameters of [with]. */
    val withParameters: List<Any?> get() = cteParameters

    /** The parameters of [where]; those of its extra conditions come after. */
    val whereParameters: List<Any?> get() = conditionParameters

    /** The parameters of [with] then [where], for a statement with no other parameter between them. */
    val parameters: List<Any?> get() = cteParameters + conditionParameters

    /**
     * A condition on a value row (`written_value` or `call_argument`, unaliased) and its parameters. Without [kind],
     * [value] matches a row of any kind with text, each kind by its own case rule.
     */
    fun valueCondition(kind: String?, value: String?): Pair<String, List<Any?>> {
        if (kind == null) {
            val each = TEXT_VALUE_KINDS.map { valueCondition(it, value) }
            return each.joinToString(" OR ", "(", ")") { "(${it.first})" } to each.flatMap { it.second }
        }
        if (value == null) return "value_kind = ?" to listOf(kind)
        val text = if (kind == "NAME" || kind == "BOOLEAN") value.uppercase(Locale.ROOT) else value
        return when {
            '*' !in text -> "value_kind = ? AND value = ?" to listOf(kind, text)
            kind == "STRING" -> "value_kind = ? AND value GLOB ?" to listOf(kind, globPattern(text))
            else -> "value_kind = ? AND value LIKE ? ESCAPE '\\'" to listOf(kind, pattern(text))
        }
    }

    companion object {
        /** The value kinds whose rows have a text a value filter can match. */
        val TEXT_VALUE_KINDS = listOf("STRING", "NUMBER", "BOOLEAN", "NAME")

        /** The most segments a dotted name may have: the name and up to three parents (or schema). */
        const val MAX_SEGMENTS = 4

        /**
         * Joins and a condition that match the entity [alias] and its parents to dotted [segments]: the last segment is
         * the entity, each earlier one the next parent up the `parent_id` chain (`<alias>_p1`, `<alias>_p2`…), and the
         * first may instead be the schema of the entity below it when that one has no parent. [match] gives the
         * condition of one segment on a column, told whether it is the last; the parameters follow the condition.
         */
        fun parentChain(
            alias: String, segments: List<String>, match: (column: String, segment: String, last: Boolean) -> Pair<String, List<Any?>>
        ): Triple<String, String, List<Any?>> {
            val last = segments.size - 1
            fun up(k: Int) = if (k == 0) alias else "${alias}_p$k"
            val joins = (1..last).joinToString(" ") { k -> "LEFT JOIN entity ${up(k)} ON ${up(k)}.id = ${up(k - 1)}.parent_id" }
            val conditions = ArrayList<String>()
            val parameters = ArrayList<Any?>()
            match("$alias.name", segments[last], true).let { (condition, values) -> conditions += condition; parameters.addAll(values) }
            for (k in 1..last) {
                val (byName, nameValues) = match("${up(k)}.name", segments[last - k], false)
                if (k < last) {
                    conditions += byName
                    parameters.addAll(nameValues)
                } else {
                    val (bySchema, schemaValues) = match("${up(k - 1)}.schema_name", segments[last - k], false)
                    conditions += "($byName OR (${up(k)}.id IS NULL AND $bySchema))"
                    parameters.addAll(nameValues + schemaValues)
                }
            }
            return Triple(joins, conditions.joinToString(" AND "), parameters)
        }

        /** A segment of a dotted name filter on [column]: exact and case-insensitive, or a `*` pattern. */
        @Suppress("UNUSED_PARAMETER")
        fun segmentMatch(column: String, segment: String, last: Boolean): Pair<String, List<Any?>> =
            if ('*' in segment) "$column LIKE ? ESCAPE '\\'" to listOf(pattern(segment)) else "$column = ? COLLATE NOCASE" to listOf(segment)

        /**
         * The recursive step of a common table expression [cte] of entity ids: the entities they contain. The
         * `CROSS JOIN` keeps the order and so the lookup by the primary key (`from_id`, `kind`). Left to choose, SQLite
         * planned the step with an automatic index on `relation` by `kind` when a value filter was beside it: on a
         * real index, one package's 1,655 entities took 38 s that way, against 0.2 s with this order.
         */
        fun contained(cte: String) =
            "SELECT r.to_id FROM $cte s CROSS JOIN relation r ON r.from_id = s.id AND r.kind = 'CONTAINS'"

        fun marks(count: Int) = List(count) { "?" }.joinToString(",")

        fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** A case-sensitive `GLOB` pattern for [text]: `*` is any text, every other character is itself. */
        fun globPattern(text: String) = text.replace("[", "[[]").replace("?", "[?]")

        /** A `LIKE` pattern for [text]: `*` is any text, every other character is itself. */
        fun pattern(text: String) = text.split('*').joinToString("%") { escapeLike(it) }
    }
}
