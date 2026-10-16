package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SameRowsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `two runs over the same corpus are the same, a changed cell is reported with its table and column`() {
        val first = Fixture.index(temp.resolve("first.db"))
        val second = Fixture.index(temp.resolve("second.db"))
        val same = SameRows.compare(first, second)
        assertThat(same.identical).isTrue()
        assertThat(same.lines).anyMatch { it.startsWith("occurrence: same (") }

        DriverManager.getConnection("jdbc:sqlite:$second").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE call_argument SET label = 'CHANGED' WHERE rowid = (SELECT max(rowid) FROM call_argument)") }
        }
        val changed = SameRows.compare(first, second)
        assertThat(changed.identical).isFalse()
        assertThat(changed.lines).anyMatch { it.startsWith("call_argument: row ") && it.contains("differs in label") }
    }
}
