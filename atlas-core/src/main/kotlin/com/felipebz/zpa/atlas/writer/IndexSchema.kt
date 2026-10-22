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
    // The text of every file, one row per line, for a search of any piece of text (trigram tokens, case folded). The
    // rowids follow the files in path order and their lines in order; a line shorter than a trigram has no row.
    // entity_id is the innermost container of the line (see SourceLines), null outside any.
    """
    CREATE VIRTUAL TABLE source_line USING fts5(text, file_id UNINDEXED, line UNINDEXED, entity_id UNINDEXED, tokenize = 'trigram')
    """
).map { it.trimIndent() }

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
    "CREATE INDEX trigger_event_column ON trigger_event(column_id)"
)
