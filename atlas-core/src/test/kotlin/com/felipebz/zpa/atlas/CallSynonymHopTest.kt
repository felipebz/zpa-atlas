//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A call zpa resolves through a synonym keeps the synonym's hop: its entity and its `SYNONYM_FOR` edge. */
class CallSynonymHopTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the synonyms zpa follows to a call's target are recorded`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pk_tools.sql"), """
            create or replace package pk_tools as
              function twice(p_n number) return number;
            end;
            /
            create or replace package body pk_tools as
              function twice(p_n number) return number is begin return p_n * 2; end;
            end;
            /
            create or replace procedure p_use is
              v number;
            begin
              v := syn_tools.twice(1);
            end;
            /
        """.trimIndent() + "\n")
        val db = temp.resolve("hop.db")
        // An acquired dictionary reads the synonym into its list and into its metadata, where zpa follows it.
        val dictionary = AtlasDictionary.of("tools", listOf("SHOP"), emptyList(),
            synonyms = listOf(DictionarySynonym("SHOP", "SYN_TOOLS", "SHOP", "PK_TOOLS", "PACKAGE")),
            objects = mapOf("SHOP" to mapOf("SYN_TOOLS" to "SYNONYM", "PK_TOOLS" to "PACKAGE", "P_USE" to "PROCEDURE")))
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
        assertThat(Fixture.query(db, "SELECT o.written || ' ' || e.key || ' ' || o.status FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE o.roles = 'CALL'"))
            .containsExactly("syn_tools.twice db:SHOP.PK_TOOLS.TWICE(P_N IN NUMBER) RESOLVED")
        assertThat(Fixture.query(db, "SELECT key || ' ' || kind FROM entity WHERE key = 'db:SHOP.SYN_TOOLS'")).containsExactly("db:SHOP.SYN_TOOLS SYNONYM")
        assertThat(Fixture.query(db, "SELECT f.key || ' ' || r.kind || ' ' || t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id WHERE f.key = 'db:SHOP.SYN_TOOLS'"))
            .containsExactly("db:SHOP.SYN_TOOLS SYNONYM_FOR db:SHOP.PK_TOOLS")
    }
}
