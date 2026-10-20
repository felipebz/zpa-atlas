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

internal fun isBody(key: String) = key.endsWith("@BODY") || key.substringBeforeLast('#').endsWith("@BODY")
