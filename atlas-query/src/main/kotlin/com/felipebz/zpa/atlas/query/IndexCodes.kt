//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

// The codes the index stores, as the writer's enums in atlas-core name them (this module does not depend on it).

/** The relation kinds whose entity page lists the views that read them, and whose readers [Scope.VIEWS] follows. */
internal val VIEWED_KINDS = setOf("TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE")

/** The kinds whose query is read like a view's: the "views reading a relation" and [Scope.VIEWS] count them. */
internal val VIEW_KINDS = setOf("VIEW", "MATERIALIZED_VIEW")
internal val VIEW_KINDS_SQL = VIEW_KINDS.joinToString { "'$it'" }
internal val VIEWED_KINDS_SQL = VIEWED_KINDS.joinToString { "'$it'" }

internal val ROLES = listOf(
    "DEFINITION", "DECLARATION", "READ", "WRITE", "CALL", "SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE",
    "SQL_MERGE", "SQL_LOCK", "TYPE_USE", "RAISE", "HANDLE", "IMPLICIT",
    "DDL_CREATE", "DDL_ALTER", "DDL_DROP", "DDL_TRUNCATE", "DDL_ANALYZE"
)
internal val VALUE_KINDS = listOf("STRING", "NUMBER", "NULL", "BOOLEAN", "NAME", "BIND", "EXPRESSION", "DEFAULT")

internal val UNIT_KINDS = listOf(
    "PACKAGE", "PACKAGE_BODY", "PROCEDURE", "FUNCTION", "TRIGGER", "TYPE", "TYPE_BODY", "VIEW", "MATERIALIZED_VIEW",
    "ANONYMOUS_BLOCK", "SQL_STATEMENT", "UNPARSED", "FORMS_TRIGGER", "FORMS_PROGRAM_UNIT"
)
internal val ORIGINS = listOf("STATIC", "DYNAMIC_LITERAL", "FORMS_IMPLICIT", "HEURISTIC")
internal val STATEMENT_KINDS = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "LOCK", "DYNAMIC", "DDL")

/** Why an occurrence has its status, as the writer's `Reason` declares them. */
internal val REASONS = listOf(
    "NOT_IN_PROJECT", "NOT_IN_DICTIONARY", "UNKNOWN_MEMBER", "WRONG_KIND", "QUALIFIER_MISMATCH", "RELATION_UNRESOLVED",
    "NO_APPLICABLE_CANDIDATE", "INVALID_NOTATION", "LOCAL_SUBPROGRAM", "AMBIGUOUS", "AMBIGUOUS_TARGET", "AMBIGUOUS_COLUMN",
    "PRIVATE_OVERLOADS", "PRIVATE_SINGLE", "SCHEMA_UNKNOWN", "COLUMNS_UNKNOWN", "COLUMN_NOT_EXCLUDED", "PARTIAL_DECLARATIONS",
    "INCOMPLETE_INDEX", "DB_LINK", "PARSED", "PARSED_WITH_HOLES", "PARSED_HOLES_DROPPED", "NOT_PARSED", "EXPRESSION",
    "VARIABLE_PARSED", "VARIABLE_PARSED_WITH_HOLES", "VARIABLE_PARSED_HOLES_DROPPED", "VARIABLE_NOT_PARSED", "VARIABLE_NO_LITERAL",
    "VARIABLE_SEVERAL_WRITES", "VARIABLE_VALUE_NOT_IN_CODE", "VARIABLE_NEVER_WRITTEN", "VARIABLE_UNSEEN_WRITE",
    "VARIABLE_NOT_CHARACTER", "VARIABLE_NOT_LOCAL", "VARIABLE_UNRESOLVED"
)

/** How a file's parse went (`source_file.parse_status`). */
internal val PARSE_STATUSES = listOf("OK", "RECOVERED", "FAILED")

/** The value kinds whose arguments form one group whatever they pass. */
internal val VALUES_UNSHOWN = setOf("NULL", "BIND", "EXPRESSION", "DEFAULT")
internal val USAGE_ROLES: Set<String> = (ROLES - setOf("DEFINITION", "DECLARATION")).toSet()
internal val STATUSES = listOf("RESOLVED", "UNVERIFIED", "AMBIGUOUS", "UNRESOLVED", "EXTERNAL", "DYNAMIC", "PARTIAL_SOURCE")
internal val ENTITY_KINDS = listOf(
    "SCHEMA", "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "COLUMN", "SYNONYM", "SEQUENCE", "TRIGGER", "PACKAGE", "PROCEDURE",
    "FUNCTION", "PARAMETER", "TYPE", "RECORD_FIELD", "PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "EXCEPTION", "CURSOR",
    "LOCAL_VARIABLE", "LOCAL_CONSTANT", "LABEL", "FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM", "FORMS_TRIGGER",
    "FORMS_PROGRAM_UNIT", "FORMS_LIBRARY", "UNKNOWN_RELATION"
)

/** The codes only a later phase of the indexer produces; the other code lists have none. */
internal val RESERVED_ENTITY_KINDS = setOf(
    "SCHEMA", "LOCAL_VARIABLE", "LOCAL_CONSTANT", "LABEL", "FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM", "FORMS_TRIGGER",
    "FORMS_PROGRAM_UNIT", "FORMS_LIBRARY"
)
internal val RESERVED_UNIT_KINDS = setOf("FORMS_TRIGGER", "FORMS_PROGRAM_UNIT")
internal val RESERVED_ORIGINS = setOf("FORMS_IMPLICIT", "HEURISTIC")

/**
 * Every code list, as `GET /api/codes` serves it. The codes do not depend on an index, so the endpoint opens none: it
 * answers while the index is unavailable, and without the `Answer` envelope and its run stamp.
 */
fun indexCodes(): Codes {
    fun codes(names: List<String>, reserved: Set<String> = emptySet()) = names.map { Code(it, it in reserved) }
    return Codes(
        roles = codes(ROLES),
        statuses = codes(STATUSES),
        entityKinds = codes(ENTITY_KINDS, RESERVED_ENTITY_KINDS),
        unitKinds = codes(UNIT_KINDS, RESERVED_UNIT_KINDS),
        origins = codes(ORIGINS, RESERVED_ORIGINS),
        statementKinds = codes(STATEMENT_KINDS),
        valueKinds = codes(VALUE_KINDS),
        groupDimensions = codes(GroupDimension.entries.map { it.name }),
        reasons = codes(REASONS),
        parseStatuses = codes(PARSE_STATUSES)
    )
}

internal fun isBody(key: String) = key.endsWith("@BODY") || key.substringBeforeLast('#').endsWith("@BODY")
