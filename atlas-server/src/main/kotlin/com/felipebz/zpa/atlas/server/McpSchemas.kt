//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/** The input schemas of the tools of [AtlasMcpTools], one function per tool; the code lists come from [codes]. */
internal class McpSchemas(private val codes: Codes) {
    fun searchEntities() = schema(listOf("name")) {
        string(
            "name",
            "A name prefix (run_job), or a dotted name (pack_process.run_job; the first part may be the schema)."
        )
        enumString("kind", producedCodes(codes.entityKinds), "Only entities of this kind.")
        boolean("include_external", "Include entities outside the indexed sources (dictionary only). Default true.")
        integer("limit", 1..McpLimits.SEARCH_MAX, McpLimits.SEARCH_DEFAULT)
    }

    fun getEntity() = schema(listOf("key")) {
        string("key", KEY_DESCRIPTION)
        integer(
            "list_limit",
            1..McpLimits.LIST_MAX,
            McpLimits.LIST_DEFAULT,
            "Items kept in each list of the answer; `truncated` gives the size of a cut list."
        )
    }

    fun findReferencingKeys() = schema(listOf("key")) {
        string("key", "A TABLE, VIEW or COLUMN key, as search_entities or another answer gives it.")
        integer("offset", 0..Int.MAX_VALUE, 0, OFFSET_DESCRIPTION)
        integer("limit", 1..McpLimits.REFERENCING_MAX, McpLimits.REFERENCING_DEFAULT)
    }

    fun findColumnPlaces() = schema(listOf("key")) {
        string("key", "A COLUMN key (a table's or a view's), as search_entities or another answer gives it.")
        integer("offset", 0..Int.MAX_VALUE, 0, OFFSET_DESCRIPTION)
        integer("limit", 1..McpLimits.PLACES_MAX, McpLimits.PLACES_DEFAULT)
        integer(
            "size",
            1..Int.MAX_VALUE,
            null,
            "The size to compare each place with, in the column's unit; the column's by default."
        )
        enumArray(
            "verdicts",
            Verdict.entries.map { it.name },
            "The verdicts to list; all but FOLLOWS by default.",
            minItems = 1
        )
        boolean(
            "concatenated",
            "false (the default): the places the value lands in alone; true: those where it is one part " +
                "of a concatenation (a message built from several columns) too, which a column may have thousands of."
        )
    }

    fun findOccurrences() = schema(emptyList()) {
        queryFilters()
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..McpLimits.FIND_MAX, McpLimits.FIND_DEFAULT)
    }

    fun findUsages() = schema(listOf("key")) {
        usageFilters()
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..McpLimits.FIND_MAX, McpLimits.FIND_DEFAULT)
    }

    fun groupUsages() = schema(listOf("key", "group_by")) {
        usageFilters()
        enumArray(
            "group_by",
            producedCodes(codes.groupDimensions),
            "One to three dimensions, as in group_occurrences.",
            minItems = 1,
            maxItems = 3
        )
        integer("limit", 1..McpLimits.GROUP_MAX, McpLimits.GROUP_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    fun findImpact() = schema(listOf("key")) {
        string("key", KEY_DESCRIPTION)
        enumArray(
            "include",
            USAGE_SCOPES.map { it.name },
            "What the first hop adds to the entity, as in find_usages; the same default by kind."
        )
        enumArray(
            "roles",
            producedCodes(codes.roles),
            "The first hop's uses have any of these roles. Without it, every role but DEFINITION and DECLARATION."
        )
        enumArray("statuses", producedCodes(codes.statuses), "The first hop's uses have one of these statuses.")
        integer(
            "max_depth",
            1..McpLimits.IMPACT_DEPTH_MAX,
            McpLimits.IMPACT_DEPTH_DEFAULT,
            "Hops followed; nodes at this depth stop with DEPTH_LIMIT."
        )
        enumString(
            "stop",
            ImpactStop.entries.map { it.name },
            "Only the nodes that stop for this reason, such as ENTRY_POINT; levels and stops still count the whole graph."
        )
        integer("offset", 0..Int.MAX_VALUE, 0, OFFSET_DESCRIPTION)
        integer("limit", 1..McpLimits.IMPACT_MAX, McpLimits.IMPACT_DEFAULT)
        boolean(
            "keys",
            "Every node gives its full key, and via.uses is a key, as the index answers; use it with stop or offset to get the keys of the nodes another tool will be called on. Default false: n and name."
        )
        boolean(
            "follow_triggers",
            "Default true: a DML trigger node is followed through the writes that fire it. False: every trigger stops with TRIGGER, as calls alone reach code."
        )
    }

    fun getValues() = schema(listOf("key")) {
        string("key", "A PARAMETER or COLUMN key, as search_entities or another answer gives it.")
        integer("limit", 1..McpLimits.VALUES_MAX, McpLimits.VALUES_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    fun findValueSites() = schema(listOf("key", "value_kind")) {
        string("key", "A PARAMETER or COLUMN key, as search_entities or another answer gives it.")
        enumString("value_kind", producedCodes(codes.valueKinds), "The valueKind of a get_values group.")
        string(
            "value", "The value of that group, exactly as get_values gives it: S, not 'S'. Required for STRING, " +
                "NUMBER, BOOLEAN and NAME; ignored for the other kinds."
        )
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..McpLimits.FIND_MAX, McpLimits.FIND_DEFAULT)
    }

    private fun SchemaBuilder.usageFilters() {
        string("key", KEY_DESCRIPTION)
        // The default by kind restates defaultUsageScopes: change both together.
        enumArray(
            "include",
            USAGE_SCOPES.map { it.name },
            "Entities added to the one asked for: IMPLEMENTATIONS its spec or " +
                "body (for a parameter, the same parameter of its routine's spec or body), CHILDREN its contents, VIEWS the view " +
                "columns computed from it and the views reading it, transitively. Default by kind, as the entity page: " +
                "IMPLEMENTATIONS for a procedure, function or parameter, IMPLEMENTATIONS and CHILDREN " +
                "for a package, CHILDREN for a type, none otherwise; [] for the entity alone. The answer's included lists what was followed."
        )
        enumArray(
            "roles",
            producedCodes(codes.roles),
            "The occurrence has any of these roles. Without it, every role but DEFINITION and DECLARATION."
        )
        enumArray("statuses", producedCodes(codes.statuses), "The occurrence has one of these statuses.")
        boolean("candidates", "Include AMBIGUOUS occurrences where one of the entities is a candidate. Default true.")
        enumString(
            "via", UsagePath.entries.map { it.name }, "DIRECT: only rows on the entity, its spec or body, or its " +
                "contents; THROUGH_VIEWS: only rows reached through a view (viaView). Default both."
        )
    }

    fun groupOccurrences() = schema(listOf("group_by")) {
        queryFilters()
        enumArray(
            "group_by",
            producedCodes(codes.groupDimensions),
            "One to three dimensions.",
            minItems = 1,
            maxItems = 3
        )
        integer("limit", 1..McpLimits.GROUP_MAX, McpLimits.GROUP_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    fun searchText() = schema(listOf("text")) {
        string(
            "text",
            "A literal piece of text, 3 characters or more, compared without case; * is a character like any other."
        )
        string("path", "Only files whose path starts with this, or with * a pattern of the whole path.")
        string("container", "Only lines inside this entity key (a package, a subprogram), its contents included.")
        string(
            "container_name",
            "Only lines inside every entity this dotted name matches (pack, pack.proc, * as wildcard), contents included."
        )
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..McpLimits.TEXT_MAX, McpLimits.TEXT_DEFAULT)
    }

    fun getSource() = schema(listOf("path", "line")) {
        string("path", "A file path as an answer gives it (relative to the sources folder).")
        integer("line", 1..Int.MAX_VALUE, null, "The line, from 1.")
        integer(
            "context",
            0..McpLimits.SOURCE_CONTEXT_MAX,
            McpLimits.SOURCE_CONTEXT_DEFAULT,
            "Lines shown before and after it."
        )
    }

    private fun SchemaBuilder.queryFilters() {
        string("entity", "Occurrences of this entity key.")
        boolean("inside", "With entity: also its contents (a package's members, a table's columns). Default false.")
        enumArray("entity_kinds", producedCodes(codes.entityKinds), "The occurrence's entity has one of these kinds.")
        string(
            "name",
            "The entity's name, or a dotted name (company.st_active, hr.company.st_active); * is a wildcard."
        )
        string("schema", "The entity's schema, exact.")
        boolean("external", "true: only entities outside the indexed sources; false: only inside.")
        string("container", "Occurrences inside this entity key (a package, a subprogram).")
        string("container_name", "Occurrences inside every entity this dotted name matches, as for name.")
        boolean("container_inside", "container and container_name include their contents. Default true.")
        enumArray("unit_kinds", producedCodes(codes.unitKinds), "The occurrence is in a unit of one of these kinds.")
        string("path", "The file path: a prefix, or with * a pattern of the whole path.")
        enumArray(
            "roles",
            producedCodes(codes.roles),
            "The occurrence has any of these roles. Without it, every role, definitions included."
        )
        enumArray("statuses", producedCodes(codes.statuses), "The occurrence has one of these statuses.")
        stringArray("reasons", "The occurrence has one of these reasons (such as NOT_IN_DICTIONARY).")
        enumArray(
            "origins",
            producedCodes(codes.origins),
            "STATIC for code, DYNAMIC_LITERAL for a name inside dynamic SQL text, " +
                "FORMS_IMPLICIT for what Forms does for a data block without code (no line)."
        )
        enumArray(
            "statement_kinds",
            producedCodes(codes.statementKinds),
            "The occurrence is in a SQL statement of one of these kinds."
        )
        enumString("value_kind", producedCodes(codes.valueKinds), "A value written or passed of this kind.")
        string("value", "The value written or passed, exact or with *; with value_kind, or any kind with text.")
        boolean(
            "candidates",
            "An AMBIGUOUS occurrence matches the entity filters through its candidates. Default true."
        )
    }

    /** The codes of [list] the indexer produces: the reserved ones are never stored. */
    private fun producedCodes(list: List<Code>) = list.filterNot { it.reserved }.map { it.code }

    companion object {
        private const val KEY_DESCRIPTION = "An entity key, as search_entities or another answer gives it."
        private const val AFTER_DESCRIPTION = "The nextAfter of the previous page."
        private const val OFFSET_DESCRIPTION = "The nextOffset of the previous page."
        private const val GROUP_LIMIT_DESCRIPTION = "The first groups by count; totalGroups counts them all."

        /** The scopes find_usages, group_usages and find_impact can add to the entity asked for (`include`). */
        val USAGE_SCOPES = listOf(Scope.IMPLEMENTATIONS, Scope.CHILDREN, Scope.VIEWS)
    }
}

/** A tool's input schema, built property by property. */
private class SchemaBuilder {
    val properties = LinkedHashMap<String, JsonObject>()

    fun string(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
    }

    fun boolean(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
    }

    fun integer(name: String, range: IntRange?, default: Int?, description: String? = null) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            if (range != null) {
                put("minimum", range.first)
                if (range.last != Int.MAX_VALUE) put("maximum", range.last)
            }
            if (default != null) put("default", default)
            if (description != null) put("description", description)
        }
    }

    fun enumString(name: String, values: List<String>, description: String) {
        properties[name] = buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            put("description", description)
        }
    }

    fun enumArray(
        name: String,
        values: List<String>,
        description: String,
        minItems: Int? = null,
        maxItems: Int? = null
    ) {
        properties[name] = buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            }
            if (minItems != null) put("minItems", minItems)
            if (maxItems != null) put("maxItems", maxItems)
            put("description", description)
        }
    }

    fun stringArray(name: String, description: String) {
        properties[name] = buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", description)
        }
    }
}

private fun schema(required: List<String>, block: SchemaBuilder.() -> Unit): ToolSchema {
    val builder = SchemaBuilder().apply(block)
    return ToolSchema(properties = JsonObject(builder.properties), required = required.ifEmpty { null })
}
