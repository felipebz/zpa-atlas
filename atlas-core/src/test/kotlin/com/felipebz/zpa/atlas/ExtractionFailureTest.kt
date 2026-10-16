package com.felipebz.zpa.atlas

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.checks.PlSqlCheck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExtractionFailureTest {
    @TempDir
    lateinit var temp: Path

    private class Failing : PlSqlCheck() {
        override fun visitFile(node: AstNode) = throw IllegalStateException("boom\nsecond line")
    }

    private val failing = "hr/pack_process.pkb"

    private fun rowsPerFile(db: Path, sql: String) = Fixture.query(db, sql)

    @Test
    fun `a file whose walk throws is indexed as FAILED and the other files are unchanged`() {
        val normal = Fixture.index(temp.resolve("normal.db"))
        // The failing visitor runs after the extractor, so the extractor's facts exist and must be discarded.
        val broken = Fixture.index(temp.resolve("broken.db")) { path, extractor ->
            if (path == failing) listOf(extractor, Failing()) else listOf(extractor)
        }

        assertThat(Fixture.query(broken, "SELECT path, parse_status FROM source_file WHERE path = '$failing'"))
            .containsExactly("$failing\tFAILED")
        assertThat(Fixture.query(broken, "SELECT c.kind, c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE f.path = '$failing'"))
            .containsExactly("EXTRACTION_FAILED\tcom.felipebz.zpa.squid.AnalysisException: Error executing checks on file pack_process.pkb: boom")
        assertThat(Fixture.query(broken, "SELECT u.kind, u.name FROM unit u JOIN source_file f ON f.id = u.file_id WHERE f.path = '$failing'"))
            .containsExactly("UNPARSED\t")
        assertThat(Fixture.query(broken, "SELECT count(*) FROM sql_statement s JOIN source_file f ON f.id = s.file_id WHERE f.path = '$failing'"))
            .containsExactly("0")

        // Only the project-index declarations remain, in the unparsed unit; the AST-derived package body definition is gone.
        val keys = "SELECT e.key || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id " +
            "WHERE f.path = '$failing' AND o.roles = 'DEFINITION' AND NOT (e.key = 'db:HR.PACK_PROCESS' AND o.start_col = 0 AND o.start_line = 1) ORDER BY 1"
        assertThat(Fixture.query(broken, keys)).isNotEmpty.isEqualTo(Fixture.query(normal, keys))
        assertThat(Fixture.query(broken, "SELECT DISTINCT o.roles || ' ' || o.status FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE f.path = '$failing'"))
            .containsExactly("DEFINITION PARTIAL_SOURCE")

        val others = "SELECT f.path || ' ' || o.start_line || ':' || o.start_col || ' ' || coalesce(e.key, '-') || ' ' || o.roles || ' ' || o.status " +
            "FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id WHERE f.path <> '$failing' ORDER BY 1"
        assertThat(rowsPerFile(broken, others)).isEqualTo(rowsPerFile(normal, others))
    }
}
