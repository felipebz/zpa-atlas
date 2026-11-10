//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.InvalidQueryException
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import kotlinx.serialization.json.*

/** The arguments of one call; a value of the wrong type is refused with the argument's name. */
internal class Arguments(tool: String, accepted: Set<String>, arguments: JsonObject?) {
    private val values = arguments ?: JsonObject(emptyMap())

    init {
        values.keys.firstOrNull { it !in accepted }?.let {
            throw InvalidQueryException("Unknown argument: $it. $tool takes: ${accepted.joinToString(", ")}")
        }
    }

    private fun primitive(name: String): JsonPrimitive? = when (val value = values[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value
        else -> throw InvalidQueryException("$name must be a single value")
    }

    fun string(name: String): String? =
        primitive(name)?.let { if (it.isString) it.content.ifEmpty { null } else throw InvalidQueryException("$name must be a string") }

    fun requiredString(name: String) = string(name) ?: throw InvalidQueryException("Missing argument: $name")

    fun boolean(name: String): Boolean? =
        primitive(name)?.let { it.booleanOrNull ?: throw InvalidQueryException("$name must be true or false") }

    fun long(name: String): Long? {
        val value = primitive(name) ?: return null
        return (if (value.isString) null else value.longOrNull)
            ?: throw InvalidQueryException("$name must be an integer")
    }

    fun int(name: String, range: IntRange? = null): Int? {
        val value = primitive(name) ?: return null
        val number =
            (if (value.isString) null else value.intOrNull) ?: throw InvalidQueryException("$name must be an integer")
        if (range != null && number !in range) throw InvalidQueryException("$name must be between ${range.first} and ${range.last}")
        return number
    }

    fun requiredInt(name: String) = int(name) ?: throw InvalidQueryException("Missing argument: $name")

    fun has(name: String) = values[name].let { it != null && it !is JsonNull }

    fun list(name: String): List<String> = when (val value = values[name]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw InvalidQueryException("$name must be an array of strings")
        }

        else -> throw InvalidQueryException("$name must be an array of strings")
    }

    fun requiredList(name: String) = list(name).ifEmpty { throw InvalidQueryException("Missing argument: $name") }
}

/** A refused call: its message is the error result the agent reads. */
internal class ToolException(message: String) : RuntimeException(message)

/** The occurrence filters of find_occurrences and group_occurrences. */
internal fun Arguments.occurrenceQuery() = OccurrenceQuery(
    entity = string("entity"),
    inside = boolean("inside") ?: false,
    entityKinds = list("entity_kinds").toSet(),
    name = string("name"),
    schema = string("schema"),
    external = boolean("external"),
    container = string("container"),
    containerName = string("container_name"),
    containerInside = boolean("container_inside") ?: true,
    unitKinds = list("unit_kinds").toSet(),
    path = string("path"),
    roles = list("roles").toSet(),
    statuses = list("statuses").toSet(),
    reasons = list("reasons").toSet(),
    origins = list("origins").toSet(),
    statementKinds = list("statement_kinds").toSet(),
    valueKind = string("value_kind"),
    value = string("value"),
    candidates = boolean("candidates") ?: true
)
