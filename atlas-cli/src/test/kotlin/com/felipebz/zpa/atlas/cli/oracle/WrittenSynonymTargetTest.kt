//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.index.SourceIdentifiers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A synonym with an accented name, of a table outside the requested schemas: its target is read only when the sources'
 * written names hold its name, and the column used through it resolves only then.
 */
class WrittenSynonymTargetTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a column through a synonym with an accented name resolves`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(
            sources.resolve("read_stock.prc"),
            "create or replace procedure read_stock is\n  v number;\nbegin\n  select qty into v from syn_depósito where id = 1;\nend;\n/\n"
        )
        val output = temp.resolve("atlas.db")
        val synonyms = listOf(DictionarySynonym("HR", "SYN_DEPÓSITO", "STOCK", "WAREHOUSE", "TABLE"))
        val indexer = Indexer()
        val options = IndexOptions(sources, output, compilationSchema = "HR", threads = 1)
        indexer.readSources(sources, options.extensions, options.encoding, output).use { read ->
            // What OracleMetadataLoader requests: the targets of the synonyms the sources name, outside the requested schemas.
            val written = SourceIdentifiers.scan(read, 1)
            val targets = OracleMetadataLoader.synonymTargets(synonyms.filter { it.name in written }, synonyms, setOf("HR"))
            val metadata = ProjectExternalMetadata.builder("test").completeSchema("HR").apply {
                if ("STOCK" to "WAREHOUSE" in targets) {
                    relation(ProjectExternalRelation.of("STOCK", "WAREHOUSE", true, listOf(ProjectExternalColumn.of("ID", 1, null), ProjectExternalColumn.of("QTY", 2, null))))
                }
            }.build()
            val dictionary = AtlasDictionary.lookupOnly(metadata, setOf("HR"), synonyms, mapOf("HR" to mapOf("SYN_DEPÓSITO" to "SYNONYM", "READ_STOCK" to "PROCEDURE")))
            indexer.index(options.copy(dictionary = dictionary), read = read)
        }
        // Without the target, the synonym and the column stay UNVERIFIED.
        assertThat(occurrence(output, "syn_depósito")).isEqualTo("RESOLVED db:STOCK.WAREHOUSE TABLE")
        assertThat(occurrence(output, "qty")).isEqualTo("RESOLVED db:STOCK.WAREHOUSE.QTY COLUMN")
    }

    private fun occurrence(database: Path, written: String): String =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "SELECT o.status || ' ' || e.key || ' ' || e.kind FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE o.written = ?"
            ).use { statement ->
                statement.setString(1, written)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else error("No occurrence of $written") }
            }
        }
}
