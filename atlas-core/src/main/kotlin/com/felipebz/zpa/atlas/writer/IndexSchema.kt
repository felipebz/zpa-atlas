//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import java.sql.Connection

/** Creates the empty schema: the tables, then the secondary indexes. */
internal fun createSchema(connection: Connection) {
    execute(connection, tableDdl())
    execute(connection, indexDdl())
}

internal fun execute(connection: Connection, ddl: List<String>) {
    connection.createStatement().use { statement ->
        for (sql in ddl) statement.execute(sql)
    }
}

/**
 * An IN list in a CHECK made each batched insert several times slower when measured; a chain of equalities
 * accepts the same values without that cost.
 */
private inline fun <reified E> check(column: String): String where E : Enum<E>, E : StoredEnum =
    "CHECK (${enumValues<E>().joinToString(" OR ") { "$column = '${it.name}'" }})"

/** A comma-separated set of roles (empty for dynamic SQL): removing every role name and comma must leave nothing. */
private fun rolesCheck(): String {
    val names = Role.entries.map { it.name }.sortedByDescending { it.length }
    val stripped = names.fold("roles") { expression, name -> "replace($expression,'$name','')" }
    return "CHECK (replace($stripped,',','') = '')"
}

/** The tables, with their inline constraints (`PRIMARY KEY`, `UNIQUE`), in creation order. */
internal fun tableDdl(): List<String> = listOf(
    """
    CREATE TABLE run (
        id INTEGER PRIMARY KEY,
        schema_version INTEGER NOT NULL,
        atlas_version TEXT NOT NULL,
        zpa_version TEXT NOT NULL,
        sources_root TEXT NOT NULL,
        source_encoding TEXT NOT NULL,
        metadata_origin TEXT NULL,
        compilation_schema TEXT NULL,
        database_version TEXT NULL,
        database_version_origin TEXT NULL CHECK (database_version_origin IN ('OPTION', 'DATABASE', 'JDBC')),
        ccflags TEXT NULL,
        started_at TEXT NOT NULL,
        finished_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE source_file (
        id INTEGER PRIMARY KEY,
        path TEXT NOT NULL UNIQUE,
        kind TEXT NOT NULL ${check<FileKind>("kind")},
        sha256 TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        compilation_schema TEXT NULL,
        parse_status TEXT NOT NULL ${check<ParseStatus>("parse_status")},
        recovered_regions INTEGER NOT NULL,
        encoding TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE entity (
        id INTEGER PRIMARY KEY,
        key TEXT NOT NULL,
        kind TEXT NOT NULL ${check<EntityKind>("kind")},
        schema_name TEXT NULL,
        name TEXT NOT NULL,
        parent_id INTEGER NULL REFERENCES entity(id),
        display TEXT NOT NULL,
        external INTEGER NOT NULL CHECK (external IN (0, 1))
    )
    """,
    """
    CREATE TABLE unit (
        id INTEGER PRIMARY KEY,
        file_id INTEGER NOT NULL REFERENCES source_file(id),
        entity_id INTEGER NULL REFERENCES entity(id),
        kind TEXT NOT NULL ${check<UnitKind>("kind")},
        name TEXT NOT NULL,
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        origin_path TEXT NULL,
        origin_line_offset INTEGER NULL
    )
    """,
    """
    CREATE TABLE relation (
        from_id INTEGER NOT NULL REFERENCES entity(id),
        kind TEXT NOT NULL ${check<RelationKind>("kind")},
        to_id INTEGER NOT NULL REFERENCES entity(id),
        PRIMARY KEY (from_id, kind, to_id)
    )
    """,
    """
    CREATE TABLE sql_statement (
        id INTEGER PRIMARY KEY,
        file_id INTEGER NOT NULL REFERENCES source_file(id),
        unit_id INTEGER NOT NULL REFERENCES unit(id),
        container_entity_id INTEGER NULL REFERENCES entity(id),
        kind TEXT NOT NULL ${check<StatementKind>("kind")},
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        normalized_hash TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE occurrence (
        id INTEGER PRIMARY KEY,
        file_id INTEGER NOT NULL REFERENCES source_file(id),
        unit_id INTEGER NOT NULL REFERENCES unit(id),
        container_entity_id INTEGER NULL REFERENCES entity(id),
        statement_id INTEGER NULL REFERENCES sql_statement(id),
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        written TEXT NOT NULL,
        entity_id INTEGER NULL REFERENCES entity(id),
        roles TEXT NOT NULL ${rolesCheck()},
        status TEXT NOT NULL ${check<Status>("status")},
        origin TEXT NOT NULL ${check<Origin>("origin")},
        reason TEXT NULL,
        -- The span of the name, when it is not the span (a definition or declaration spans the whole of it).
        name_start_line INTEGER NULL,
        name_start_col INTEGER NULL,
        name_end_line INTEGER NULL,
        name_end_col INTEGER NULL
    )
    """,
    """
    CREATE TABLE occurrence_candidate (
        occurrence_id INTEGER NOT NULL REFERENCES occurrence(id),
        entity_id INTEGER NOT NULL REFERENCES entity(id),
        PRIMARY KEY (occurrence_id, entity_id)
    )
    """,
    """
    CREATE TABLE call_argument (
        occurrence_id INTEGER NOT NULL REFERENCES occurrence(id),
        ordinal INTEGER NOT NULL,
        label TEXT NULL,
        parameter_entity_id INTEGER NULL REFERENCES entity(id),
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        value_kind TEXT NOT NULL ${check<ValueKind>("value_kind")},
        -- The literal's value (a decoded string, a canonical number), the name or bind as written; null otherwise.
        value TEXT NULL,
        PRIMARY KEY (occurrence_id, ordinal)
    )
    """,
    // The value a column write writes, one per row of its statement; classified as call_argument values are.
    """
    CREATE TABLE written_value (
        occurrence_id INTEGER NOT NULL REFERENCES occurrence(id),
        row_number INTEGER NOT NULL,
        value_kind TEXT NOT NULL ${check<ValueKind>("value_kind")},
        value TEXT NULL,
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        PRIMARY KEY (occurrence_id, row_number)
    )
    """,
    """
    CREATE TABLE coverage_note (
        id INTEGER PRIMARY KEY,
        file_id INTEGER NULL REFERENCES source_file(id),
        kind TEXT NOT NULL,
        message TEXT NOT NULL
    )
    """,
    // The event is free text: DML events, but also the DDL and database events of system triggers.
    """
    CREATE TABLE trigger_event (
        trigger_id INTEGER NOT NULL REFERENCES entity(id),
        timing TEXT NOT NULL ${check<TriggerTiming>("timing")},
        level TEXT NULL ${check<TriggerLevel>("level")},
        event TEXT NOT NULL,
        column_id INTEGER NULL REFERENCES entity(id)
    )
    """,
    // What a declaration says, as written: one row per entity whose declaration has a type or a value. The entity is
    // the key: a routine's spec and body are two entities, so each keeps its own text.
    """
    CREATE TABLE declared_text (
        entity_id INTEGER PRIMARY KEY REFERENCES entity(id),
        datatype TEXT NULL,
        value TEXT NULL,
        value_cut INTEGER NOT NULL CHECK (value_cut IN (0, 1))
    )
    """,
    // The documentation comment of one declaration: a package or a cursor declared in a spec and defined in a body is
    // one entity with a row for each half (in_body), a routine's spec and body are two entities.
    """
    CREATE TABLE doc_comment (
        entity_id INTEGER NOT NULL REFERENCES entity(id),
        file_id INTEGER NOT NULL REFERENCES source_file(id),
        in_body INTEGER NOT NULL CHECK (in_body IN (0, 1)),
        shape TEXT NOT NULL ${check<DocShape>("shape")},
        start_line INTEGER NOT NULL,
        text TEXT NOT NULL,
        text_cut INTEGER NOT NULL CHECK (text_cut IN (0, 1))
    )
    """,
    // The body globals whose recorded uses may be incomplete, one row per entity: reasons lists, comma-separated, what
    // zpa says may hold the uses it could not decide (CONDITIONAL_COMPILATION, ...). An entity without a row has none.
    """
    CREATE TABLE uses_gap (
        entity_id INTEGER PRIMARY KEY REFERENCES entity(id),
        reasons TEXT NOT NULL
    )
    """,
    // The object types whose listed attributes may not be all, one row per type: reasons, comma-separated, are
    // NOT_EXTRACTED and ALTER_TYPE. A type without a row lists every attribute its CREATE TYPE writes.
    """
    CREATE TABLE members_gap (
        entity_id INTEGER PRIMARY KEY REFERENCES entity(id),
        reasons TEXT NOT NULL
    )
    """,
    // The primary, unique and foreign keys the dictionary gives (ALL_CONSTRAINTS), one row per key: the keys of the
    // relations that are entities, the keys their foreign keys reference and the foreign keys referencing theirs. A key
    // whose relation is not an entity (the dictionary did not read it) has no relation_id; a foreign key names the key it
    // references (referenced_owner, referenced_name), and links it (referenced_id) when that key has a row.
    """
    CREATE TABLE dictionary_key (
        id INTEGER PRIMARY KEY,
        owner TEXT NOT NULL,
        name TEXT NOT NULL,
        kind TEXT NOT NULL ${check<KeyKind>("kind")},
        table_owner TEXT NOT NULL,
        table_name TEXT NOT NULL,
        relation_id INTEGER NULL REFERENCES entity(id),
        enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
        delete_rule TEXT NULL,
        referenced_owner TEXT NULL,
        referenced_name TEXT NULL,
        referenced_id INTEGER NULL REFERENCES dictionary_key(id),
        UNIQUE (owner, name)
    )
    """,
    // The columns of a key in key order (position from 1); column_id is null when the column is not an entity.
    """
    CREATE TABLE dictionary_key_column (
        key_id INTEGER NOT NULL REFERENCES dictionary_key(id),
        position INTEGER NOT NULL,
        column_name TEXT NOT NULL,
        column_id INTEGER NULL REFERENCES entity(id),
        PRIMARY KEY (key_id, position)
    )
    """,
    // A place a column's value is read into in PL/SQL (SELECT INTO, FETCH INTO, RETURNING INTO): the column read (a
    // view's column for a read through a view), the target as written (its span) and its entity, the field of a record
    // target, how the target is declared (class, the declared type as written, a length and its unit; for a number the
    // digits before the point and the scale), the entity whose declaration decides the type (type_source_id), why it is
    // UNKNOWN, and the mode of an OUT or IN OUT parameter target, whose callers' arguments hold the value.
    """
    CREATE TABLE column_place (
        id INTEGER PRIMARY KEY,
        file_id INTEGER NOT NULL REFERENCES source_file(id),
        unit_id INTEGER NOT NULL REFERENCES unit(id),
        container_entity_id INTEGER NULL REFERENCES entity(id),
        statement_id INTEGER NULL REFERENCES sql_statement(id),
        start_line INTEGER NOT NULL,
        start_col INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        end_col INTEGER NOT NULL,
        column_id INTEGER NOT NULL REFERENCES entity(id),
        source TEXT NOT NULL ${check<PlaceSource>("source")},
        bulk INTEGER NOT NULL CHECK (bulk IN (0, 1)),
        target_entity_id INTEGER NULL REFERENCES entity(id),
        target_written TEXT NOT NULL,
        field TEXT NULL,
        class TEXT NOT NULL ${check<PlaceClass>("class")},
        declared_type TEXT NULL,
        length INTEGER NULL,
        length_unit TEXT NULL,
        type_source_id INTEGER NULL REFERENCES entity(id),
        reason TEXT NULL,
        scale INTEGER NULL,
        parameter_mode TEXT NULL CHECK (parameter_mode IN ('OUT', 'IN OUT'))
    )
    """,
    SOURCE_LINE_DDL
).map { it.trimIndent() }

/**
 * The text of every file, one row per line, for a search of any piece of text (trigram tokens, case folded). The
 * rowids follow the files in path order and their lines in order; a line shorter than a trigram has no row.
 * entity_id is the innermost container of the line (see [SourceLines]), null outside any. [SourceTextIndex] builds
 * the same table apart, so its shadow tables can be copied: the statement must be the same in both.
 */
internal const val SOURCE_LINE_DDL =
    "CREATE VIRTUAL TABLE source_line USING fts5(text, file_id UNINDEXED, line UNINDEXED, entity_id UNINDEXED, tokenize = 'trigram')"

/**
 * The FTS5 settings `source_line` is built with, set before its first row; they are kept in `source_line_config`.
 * With the defaults (a 1 MB hash, automerge 4) half of the build was merging segments. A 64 MB hash flushes fewer,
 * larger segments, and automerge 8 merges them less often; the index still ends in a handful of segments, so a
 * query reads about as much as before. Measured: 29 s of build down to 17 s, the index file no larger.
 */
internal val SOURCE_LINE_SETTINGS = listOf(
    "INSERT INTO source_line(source_line, rank) VALUES ('hashsize', ${64 * 1024 * 1024})",
    "INSERT INTO source_line(source_line, rank) VALUES ('automerge', 8)"
)

/** The secondary indexes, created after the rows are loaded. Readers do not depend on their creation order. */
internal fun indexDdl(): List<String> = listOf(
    "CREATE UNIQUE INDEX entity_key ON entity(key)",
    "CREATE INDEX entity_parent ON entity(parent_id)",
    "CREATE INDEX occurrence_entity_roles ON occurrence(entity_id, roles)",
    "CREATE INDEX occurrence_file_line ON occurrence(file_id, start_line)",
    "CREATE INDEX occurrence_container ON occurrence(container_entity_id)",
    "CREATE INDEX occurrence_candidate_entity ON occurrence_candidate(entity_id)",
    // Case-insensitive name prefix search (`name LIKE 'abc%'`, LIKE folds ASCII case like NOCASE).
    "CREATE INDEX entity_name_nocase ON entity(name COLLATE NOCASE)",
    "CREATE INDEX relation_from ON relation(from_id)",
    "CREATE INDEX call_argument_parameter ON call_argument(parameter_entity_id)",
    "CREATE INDEX relation_to ON relation(to_id)",
    "CREATE INDEX trigger_event_trigger ON trigger_event(trigger_id)",
    "CREATE INDEX trigger_event_column ON trigger_event(column_id)",
    "CREATE INDEX doc_comment_entity ON doc_comment(entity_id)",
    "CREATE INDEX dictionary_key_relation ON dictionary_key(relation_id)",
    "CREATE INDEX dictionary_key_referenced ON dictionary_key(referenced_id)",
    "CREATE INDEX dictionary_key_column_column ON dictionary_key_column(column_id)",
    "CREATE INDEX column_place_column ON column_place(column_id)",
    "CREATE INDEX column_place_target ON column_place(target_entity_id)"
)
