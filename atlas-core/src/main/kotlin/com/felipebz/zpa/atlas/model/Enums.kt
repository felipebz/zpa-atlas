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
    FORMS_MODULE
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
    MATERIALIZED_VIEW,
    ANONYMOUS_BLOCK,
    SQL_STATEMENT,

    /** Tokens the parser could not recognize (a `RECOVERY` region); kept so that declarations found there have a unit. */
    UNPARSED,
    FORMS_TRIGGER,
    FORMS_PROGRAM_UNIT
}

enum class EntityKind(val reserved: Boolean = false) : StoredEnum {
    SCHEMA(reserved = true),
    TABLE,
    VIEW,
    MATERIALIZED_VIEW,
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
    /** An attribute of a standalone object type, written in its `CREATE TYPE`. */
    ATTRIBUTE,
    PACKAGE_VARIABLE,
    PACKAGE_CONSTANT,
    EXCEPTION,
    CURSOR,
    LOCAL_VARIABLE,
    LOCAL_CONSTANT,
    LABEL(reserved = true),
    FORMS_MODULE,
    FORMS_BLOCK,
    FORMS_ITEM,
    FORMS_TRIGGER,
    FORMS_PROGRAM_UNIT,
    FORMS_LIBRARY,
    FORMS_RECORD_GROUP,
    UNKNOWN_RELATION
}

enum class RelationKind(val reserved: Boolean = false) : StoredEnum {
    CONTAINS,
    IMPLEMENTS,
    OVERLOAD_OF(reserved = true),
    SYNONYM_FOR,

    /** A view column to a column its value is computed from (another view's column included: one hop per view). */
    VIEW_BASE,
    TRIGGER_ON,
    FORMS_BLOCK_TABLE,
    FORMS_ITEM_COLUMN,
    FORMS_ATTACHED_LIBRARY
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
    IMPLICIT,
    DDL_CREATE,
    DDL_ALTER,
    DDL_DROP,
    DDL_TRUNCATE,
    DDL_ANALYZE
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
    DYNAMIC_LITERAL,
    FORMS_IMPLICIT,
    HEURISTIC(reserved = true)
}

/**
 * Why an occurrence has its status (`occurrence.reason`): every reason the indexer writes. The extractors write them
 * as text; a test indexes the fixture and fails on a reason missing here.
 */
enum class Reason : StoredEnum {
    // The name is not found, or not what it is used as.
    NOT_IN_PROJECT,
    NOT_IN_DICTIONARY,
    UNKNOWN_MEMBER,
    WRONG_KIND,
    QUALIFIER_MISMATCH,
    RELATION_UNRESOLVED,
    NO_APPLICABLE_CANDIDATE,
    INVALID_NOTATION,
    LOCAL_SUBPROGRAM,
    // Several candidates.
    AMBIGUOUS,
    AMBIGUOUS_TARGET,
    AMBIGUOUS_COLUMN,
    PRIVATE_OVERLOADS,
    // Found, or not checkable, with something the index could not see.
    PRIVATE_SINGLE,
    SCHEMA_UNKNOWN,
    COLUMNS_UNKNOWN,
    COLUMN_NOT_EXCLUDED,
    PARTIAL_DECLARATIONS,
    INCOMPLETE_INDEX,
    DB_LINK,
    // What became of a dynamic statement's text.
    PARSED,
    PARSED_WITH_HOLES,
    PARSED_HOLES_DROPPED,
    NOT_PARSED,
    EXPRESSION,
    VARIABLE_PARSED,
    VARIABLE_PARSED_WITH_HOLES,
    VARIABLE_PARSED_HOLES_DROPPED,
    VARIABLE_NOT_PARSED,
    VARIABLE_NO_LITERAL,
    VARIABLE_SEVERAL_WRITES,
    VARIABLE_VALUE_NOT_IN_CODE,
    VARIABLE_NEVER_WRITTEN,
    VARIABLE_UNSEEN_WRITE,
    VARIABLE_NOT_CHARACTER,
    VARIABLE_NOT_LOCAL,
    VARIABLE_UNRESOLVED
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

/**
 * Where a declaration's documentation comment is: the block right [ABOVE] it, the first block after the `IS`/`AS` of
 * a package or a routine ([AFTER_IS]) or after a routine's `BEGIN` ([AFTER_BEGIN]), or the comment ending its own line
 * ([END_OF_LINE]).
 */
enum class DocShape : StoredEnum {
    ABOVE,
    AFTER_IS,
    AFTER_BEGIN,
    END_OF_LINE
}

/** What a call argument passes or a column write writes; see `ExpressionValues`. `DEFAULT` is only written. */
enum class ValueKind : StoredEnum {
    STRING,
    NUMBER,
    NULL,
    BOOLEAN,
    NAME,
    BIND,
    EXPRESSION,
    DEFAULT
}

enum class StatementKind : StoredEnum {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    LOCK,
    DYNAMIC,
    DDL
}

/** The kind of a key the dictionary gives (`ALL_CONSTRAINTS.CONSTRAINT_TYPE` `P`, `U`, `R`). */
enum class KeyKind(val constraintType: String) : StoredEnum {
    PRIMARY_KEY("P"),
    UNIQUE("U"),
    FOREIGN_KEY("R");

    companion object {
        fun of(constraintType: String): KeyKind = entries.first { it.constraintType == constraintType }
    }
}

/**
 * How the PL/SQL target a column's value is read into is declared ([com.felipebz.zpa.atlas.index.ColumnPlaces]), in
 * the order a page lists them: [FIXED_SIZE] a length or precision of its own (or a
 * predefined bounded type, `PLS_INTEGER`), [UNKNOWN] not decided, [CALLER_DECIDES] an `OUT` or `IN OUT` parameter, whose
 * callers' arguments hold the value, [ANCHORED_ELSEWHERE] anchored to another column, [NO_SIZE] a type without a size
 * (`NUMBER`, `CLOB`, `DATE`), [ANCHORED_TO_COLUMN] anchored to the column itself (`%TYPE`, a `%ROWTYPE` of its table, a
 * cursor record over it, or an anchor to something so anchored).
 */
enum class PlaceClass : StoredEnum {
    FIXED_SIZE,
    UNKNOWN,
    CALLER_DECIDES,
    ANCHORED_ELSEWHERE,
    NO_SIZE,
    ANCHORED_TO_COLUMN
}

/**
 * Where the unit of a character length comes from: [WRITTEN] in the declaration (`10 CHAR`, or a type that has one,
 * `NVARCHAR2`, `RAW`), [COMPILED] the `NLS_LENGTH_SEMANTICS` the dictionary says the unit declaring it was compiled with
 * (`ALL_PLSQL_OBJECT_SETTINGS`), [OPTION] the index's `--nls-length-semantics`.
 */
enum class UnitSource : StoredEnum {
    WRITTEN,
    COMPILED,
    OPTION
}

/**
 * What reads a column's value into a PL/SQL target: `SELECT … INTO`, `FETCH … INTO`, `RETURNING … INTO`, or an
 * assignment `target := r.f` of a field of a row record (a cursor `FOR` loop record or a `%ROWTYPE` variable).
 */
enum class PlaceSource : StoredEnum {
    SELECT_INTO,
    FETCH,
    RETURNING,
    ASSIGNMENT,
    /** The argument a call passes for an `OUT` or `IN OUT` parameter whose place leaves its size to the callers. */
    OUT_ARGUMENT,
    /** A target inside a routine that a column's value, passed to the routine's `IN` parameter, is written into. */
    IN_ARGUMENT,
    /** A target a function's result is written into, the function returning a column's value. */
    RETURNED
}

/** The `occurrence.roles` text: the sorted, comma-separated role names. */
fun Collection<Role>.encode(): String = map { it.name }.toSortedSet().joinToString(",")
