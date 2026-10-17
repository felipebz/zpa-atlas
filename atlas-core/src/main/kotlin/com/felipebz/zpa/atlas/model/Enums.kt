//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

/**
 * Every enumeration stored in the index. Each value is listed in the `CHECK` constraint of its column, including the
 * values that only later phases produce ([reserved]), so that adding a producer never needs a schema migration.
 */
interface StoredEnum {
    val name: String
}

enum class FileKind(val reserved: Boolean = false) : StoredEnum {
    DB_SOURCE,
    FORMS_MODULE(reserved = true)
}

enum class ParseStatus : StoredEnum {
    OK,
    RECOVERED,
    FAILED
}

enum class UnitKind(val reserved: Boolean = false) : StoredEnum {
    PACKAGE,
    PACKAGE_BODY,
    PROCEDURE,
    FUNCTION,
    TRIGGER,
    TYPE,
    TYPE_BODY,
    VIEW,
    ANONYMOUS_BLOCK,
    SQL_STATEMENT,

    /** Tokens the parser could not recognize (a `RECOVERY` region); kept so that declarations found there have a unit. */
    UNPARSED,
    FORMS_TRIGGER(reserved = true),
    FORMS_PROGRAM_UNIT(reserved = true)
}

enum class EntityKind(val reserved: Boolean = false) : StoredEnum {
    SCHEMA(reserved = true),
    TABLE,
    VIEW,
    OBJECT_TABLE,
    COLUMN,
    SYNONYM,
    SEQUENCE,
    TRIGGER,
    PACKAGE,
    PROCEDURE,
    FUNCTION,
    PARAMETER,
    TYPE,
    RECORD_FIELD,
    PACKAGE_VARIABLE,
    PACKAGE_CONSTANT,
    EXCEPTION,
    CURSOR,
    LOCAL_VARIABLE(reserved = true),
    LOCAL_CONSTANT(reserved = true),
    LABEL(reserved = true),
    FORMS_MODULE(reserved = true),
    FORMS_BLOCK(reserved = true),
    FORMS_ITEM(reserved = true),
    FORMS_TRIGGER(reserved = true),
    FORMS_PROGRAM_UNIT(reserved = true),
    FORMS_LIBRARY(reserved = true),
    UNKNOWN_RELATION
}

enum class RelationKind(val reserved: Boolean = false) : StoredEnum {
    CONTAINS,
    IMPLEMENTS,
    OVERLOAD_OF(reserved = true),
    SYNONYM_FOR,
    VIEW_BASE(reserved = true),
    TRIGGER_ON,
    FORMS_BLOCK_TABLE(reserved = true),
    FORMS_ITEM_COLUMN(reserved = true),
    FORMS_ATTACHED_LIBRARY(reserved = true)
}

enum class Role(val reserved: Boolean = false) : StoredEnum {
    DEFINITION,
    DECLARATION,
    READ,
    WRITE,
    CALL,
    SQL_SELECT,
    SQL_INSERT,
    SQL_UPDATE,
    SQL_DELETE,
    SQL_MERGE,
    SQL_LOCK,
    TYPE_USE,
    RAISE,
    HANDLE,
    IMPLICIT
}

enum class Status(val reserved: Boolean = false) : StoredEnum {
    RESOLVED,
    UNVERIFIED,
    AMBIGUOUS,
    UNRESOLVED,
    EXTERNAL,
    DYNAMIC,
    PARTIAL_SOURCE
}

enum class Origin(val reserved: Boolean = false) : StoredEnum {
    STATIC,
    DYNAMIC_LITERAL(reserved = true),
    FORMS_IMPLICIT(reserved = true),
    HEURISTIC(reserved = true)
}

enum class TriggerTiming : StoredEnum {
    BEFORE,
    AFTER,
    INSTEAD_OF,
    COMPOUND
}

/** `FOR EACH ROW` or not; none for compound and system triggers. */
enum class TriggerLevel : StoredEnum {
    ROW,
    STATEMENT
}

enum class StatementKind : StoredEnum {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    LOCK,
    DYNAMIC
}

/** The `occurrence.roles` text: the sorted, comma-separated role names. */
fun Collection<Role>.encode(): String = map { it.name }.toSortedSet().joinToString(",")
