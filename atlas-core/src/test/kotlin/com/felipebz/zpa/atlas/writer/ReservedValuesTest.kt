package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Values reserved for later phases must be accepted by the F0.1 DDL, so that no phase needs a migration. */
class ReservedValuesTest {
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { AtlasWriter.createSchema(it) }

    @Test
    fun `the reserved values are the ones the later phases need`() {
        assertThat(EntityKind.entries.filter { it.reserved }.map { it.name }).containsExactly(
            "SCHEMA", "SYNONYM", "EXCEPTION", "CURSOR", "LOCAL_VARIABLE", "LOCAL_CONSTANT", "LABEL", "FORMS_MODULE",
            "FORMS_BLOCK", "FORMS_ITEM", "FORMS_TRIGGER", "FORMS_PROGRAM_UNIT", "FORMS_LIBRARY"
        )
        assertThat(RelationKind.entries.filter { it.reserved }.map { it.name }).containsExactly(
            "OVERLOAD_OF", "SYNONYM_FOR", "VIEW_BASE", "FORMS_BLOCK_TABLE", "FORMS_ITEM_COLUMN", "FORMS_ATTACHED_LIBRARY"
        )
        assertThat(Role.entries.filter { it.reserved }.map { it.name }).containsExactly("READ", "TYPE_USE", "RAISE", "HANDLE")
        assertThat(Status.entries.filter { it.reserved }).isEmpty()
        assertThat(Origin.entries.filter { it.reserved }.map { it.name }).containsExactly("DYNAMIC_LITERAL", "FORMS_IMPLICIT", "HEURISTIC")
        assertThat(UnitKind.entries.filter { it.reserved }.map { it.name }).containsExactly("FORMS_TRIGGER", "FORMS_PROGRAM_UNIT")
        assertThat(FileKind.entries.filter { it.reserved }.map { it.name }).containsExactly("FORMS_MODULE")
    }

    @Test
    fun `the DDL accepts every value, reserved ones included, in every enumerated column`() {
        database().use { connection ->
            connection.createStatement().use { statement ->
                FileKind.entries.forEachIndexed { i, kind ->
                    statement.execute("INSERT INTO source_file VALUES ($i + 1, 'f$i', '${kind.name}', 'h', 0, NULL, '${ParseStatus.entries[i % ParseStatus.entries.size].name}', 0)")
                }
                ParseStatus.entries.forEach { statement.execute("INSERT INTO source_file VALUES (NULL, 'p${it.name}', 'DB_SOURCE', 'h', 0, NULL, '${it.name}', 0)") }
                EntityKind.entries.forEachIndexed { i, kind ->
                    statement.execute("INSERT INTO entity VALUES ($i + 1, 'forms:k$i', '${kind.name}', NULL, 'n', NULL, 'd', 0)")
                }
                UnitKind.entries.forEachIndexed { i, kind ->
                    statement.execute("INSERT INTO unit VALUES ($i + 1, 1, NULL, '${kind.name}', 'u', 1, 0, 1, 1, 'FORM/BLOCK/ITEM/WHEN-VALIDATE-ITEM', 10)")
                }
                RelationKind.entries.forEachIndexed { i, kind -> statement.execute("INSERT INTO relation VALUES (1, '${kind.name}', ${i + 2})") }
                StatementKind.entries.forEachIndexed { i, kind ->
                    statement.execute("INSERT INTO sql_statement VALUES ($i + 1, 1, 1, NULL, '${kind.name}', 1, 0, 1, 1, 'h')")
                }
                val roles = Role.entries.map { it.name } + Role.entries.encode()
                val rows = Status.entries.size.coerceAtLeast(Origin.entries.size).coerceAtLeast(roles.size)
                for (i in 0 until rows) {
                    statement.execute(
                        "INSERT INTO occurrence VALUES (${i + 1}, 1, 1, NULL, 1, 1, 0, 1, 1, 'w', 1, '${roles[i % roles.size]}', " +
                            "'${Status.entries[i % Status.entries.size].name}', '${Origin.entries[i % Origin.entries.size].name}', NULL)"
                    )
                }
                statement.execute("INSERT INTO occurrence_candidate VALUES (1, 2)")
            }
            assertThat(count(connection, "occurrence")).isEqualTo(Role.entries.size + 1L)
        }
    }

    @Test
    fun `the DDL rejects values outside the enumerations`() {
        database().use { connection ->
            connection.createStatement().use { statement ->
                assertThatThrownBy { statement.execute("INSERT INTO entity VALUES (1, 'k', 'NOT_A_KIND', NULL, 'n', NULL, 'd', 0)") }
                    .isInstanceOf(SQLException::class.java)
                statement.execute("INSERT INTO source_file VALUES (1, 'f', 'DB_SOURCE', 'h', 0, NULL, 'OK', 0)")
                statement.execute("INSERT INTO unit VALUES (1, 1, NULL, 'PACKAGE', 'u', 1, 0, 1, 1, NULL, NULL)")
                assertThatThrownBy {
                    statement.execute("INSERT INTO occurrence VALUES (1, 1, 1, NULL, NULL, 1, 0, 1, 1, 'w', NULL, 'WRITE,BOGUS', 'RESOLVED', 'STATIC', NULL)")
                }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun count(connection: Connection, table: String): Long =
        connection.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getLong(1) } }
}
