//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectExternalSynonym
import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A dictionary whose metadata changes only in a synonym invalidates the facts cache: zpa reads names through it. */
class DictionarySynonymRebuildTest {
    @TempDir
    lateinit var temp: Path

    /** An acquired dictionary of `SHOP`, its metadata holding `SHOP.ITEMS` for `STOCK.ITEM_LIST` when [synonym]. */
    private fun dictionary(synonym: Boolean): AtlasDictionary {
        val metadata = ProjectExternalMetadata.builder("oracle FREE as SHOP").completeSchema("SHOP").completeSchema("STOCK")
            .relation(ProjectExternalRelation.of("STOCK", "ITEM_LIST", true, listOf(ProjectExternalColumn.of("QTY", 1, null))))
            .apply { if (synonym) synonym(ProjectExternalSynonym.of("SHOP", "ITEMS", "STOCK", "ITEM_LIST", null)) }
            .build()
        return AtlasDictionary.lookupOnly(metadata, setOf("SHOP", "STOCK"))
    }

    @Test
    fun `only a synonym of the metadata changes, and the index is rebuilt`() {
        assertThat(IncrementalRun.fingerprint(dictionary(true))).isNotEqualTo(IncrementalRun.fingerprint(dictionary(false)))
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("p_items.prc"), "create or replace procedure p_items is\n  v number;\nbegin\n  select qty into v from items;\nend;\n/\n")
        val db = temp.resolve("items.db")
        fun index(synonym: Boolean) =
            Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary(synonym), threads = 1), incremental = true).incremental!!
        val reads = "SELECT o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-') FROM occurrence o WHERE o.written = 'qty'"
        index(synonym = false)
        val without = Fixture.query(db, reads)
        val second = index(synonym = true)
        // Through the synonym zpa knows the columns of `items`: the read is decided, and no longer only a candidate.
        assertThat(second.fullReason ?: second.everything).isNotNull()
        assertThat(Fixture.query(db, reads)).isNotEqualTo(without).noneMatch { it.endsWith("COLUMN_CANDIDATE") }
    }
}
