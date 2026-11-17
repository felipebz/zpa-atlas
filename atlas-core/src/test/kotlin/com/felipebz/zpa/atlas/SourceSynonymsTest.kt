//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The synonyms a `CREATE [PUBLIC] SYNONYM` of the sources creates, followed as zpa follows them. */
class SourceSynonymsTest {
    @TempDir
    lateinit var temp: Path

    private val reader = """
        create or replace procedure read_crate is
          n number; m varchar2(2);
        begin
          select qty, tag into n, m from crate_s;
        end;
        /
    """

    private fun index(sources: Path, db: Path, dictionary: AtlasDictionary = AtlasDictionary.NONE, incremental: Boolean = false) =
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1), incremental = incremental)

    /** Line, written name, status and entity of the column reads of [file]. */
    private fun columnReads(db: Path, file: String) = Fixture.query(db, """
        SELECT o.start_line || ' ' || o.written || ' ' || o.status || ' ' || coalesce(e.key, '-')
        FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
        WHERE f.path = '$file' AND o.statement_id IS NOT NULL AND e.kind = 'COLUMN'
        ORDER BY o.start_line, o.start_col""")

    private fun synonymRows(db: Path, key: String) = Fixture.query(db, "SELECT key || ' ' || kind FROM entity WHERE key = '$key'") +
        Fixture.query(db, "SELECT r.kind || ' ' || t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id WHERE f.key = '$key'")

    private fun write(sources: Path, name: String, text: String) {
        Files.createDirectories(sources)
        Files.writeString(sources.resolve(name), text.trimIndent() + "\n")
    }

    private fun crate(sources: Path, synonym: String = "create synonym crate_s for crate_log;") {
        write(sources, "tables.sql", "create table crate_log (qty number(3), tag varchar2(5));")
        write(sources, "synonyms.sql", synonym)
        write(sources, "reader.sql", reader)
    }

    @Test
    fun `a synonym of the sources is followed to a table of the sources`() {
        val sources = temp.resolve("src")
        crate(sources)
        val db = temp.resolve("a.db")
        index(sources, db)
        assertThat(columnReads(db, "reader.sql")).containsExactly("4 qty RESOLVED db:SHOP.CRATE_LOG.QTY", "4 tag RESOLVED db:SHOP.CRATE_LOG.TAG")
        assertThat(places(db, "reader.sql")).hasSize(2).allMatch { it.startsWith("4 CRATE_LOG.") }
        // One entity for the synonym, whether its CREATE SYNONYM or a reader names it.
        assertThat(synonymRows(db, "db:SHOP.CRATE_S")).containsExactly("db:SHOP.CRATE_S SYNONYM", "SYNONYM_FOR db:SHOP.CRATE_LOG")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:SHOP.CRATE_S.%'")).isEmpty()
    }

    /** An enumerable dictionary with a table of the synonym's name. */
    private val crateTable = AtlasDictionary.of("crates", listOf("SHOP"), listOf(
        DictionaryRelation(ProjectExternalRelation.of("SHOP", "CRATE_S", true, listOf(ProjectExternalColumn.of("QTY", 1, number()))), DictionaryRelationKind.TABLE)
    ), objects = mapOf("SHOP" to mapOf("CRATE_S" to "TABLE")))

    @Test
    fun `a synonym of the sources hides the dictionary's object of the same name`() {
        val sources = temp.resolve("src")
        crate(sources)
        val db = temp.resolve("b.db")
        index(sources, db, crateTable)
        assertThat(columnReads(db, "reader.sql")).containsExactly("4 qty RESOLVED db:SHOP.CRATE_LOG.QTY", "4 tag RESOLVED db:SHOP.CRATE_LOG.TAG")
        // The hidden table is no entity: the name is the synonym's alone.
        assertThat(synonymRows(db, "db:SHOP.CRATE_S")).containsExactly("db:SHOP.CRATE_S SYNONYM", "SYNONYM_FOR db:SHOP.CRATE_LOG")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:SHOP.CRATE_S.%'")).isEmpty()
    }

    @Test
    fun `a synonym of the sources whose target is a package is no relation, not the table it hides`() {
        val sources = temp.resolve("src")
        write(sources, "api.sql", """
            create or replace package crate_api is procedure ping; end;
            /
        """)
        write(sources, "synonyms.sql", "create synonym crate_s for crate_api;")
        write(sources, "reader.sql", """
            create or replace procedure count_crates is
              n number;
            begin
              select count(*) into n from crate_s;
              select qty into n from crate_s;
              select s.qty into n from crate_s s;
            end;
            /
        """)
        val db = temp.resolve("f.db")
        index(sources, db, crateTable)
        assertThat(Fixture.query(db, """
            SELECT o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' || e.key || ' ' || e.kind
            FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
            WHERE f.path = 'reader.sql' AND o.written = 'crate_s'"""))
            .containsExactly(
                "crate_s UNRESOLVED NOT_IN_DICTIONARY db:SHOP.CRATE_S SYNONYM",
                "crate_s UNRESOLVED NOT_IN_DICTIONARY db:SHOP.CRATE_S SYNONYM",
                "crate_s UNRESOLVED NOT_IN_DICTIONARY db:SHOP.CRATE_S SYNONYM"
            )
        // The reads of a column through it stay unresolved, with no column entity: a synonym holds no column.
        assertThat(Fixture.query(db, """
            SELECT o.start_line || ' ' || o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' || coalesce(o.entity_id, '-')
            FROM occurrence o JOIN source_file f ON f.id = o.file_id
            WHERE f.path = 'reader.sql' AND o.written LIKE '%qty' ORDER BY o.start_line"""))
            .containsExactly("5 qty UNRESOLVED COLUMN_CANDIDATE -", "6 s.qty UNRESOLVED RELATION_UNRESOLVED -")
        // As for a dictionary synonym of a package read as a relation, no hop is followed: the synonym's entity alone.
        assertThat(synonymRows(db, "db:SHOP.CRATE_S")).containsExactly("db:SHOP.CRATE_S SYNONYM")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:SHOP.CRATE_S.%'")).isEmpty()
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind = 'COLUMN' AND parent_id IN (SELECT id FROM entity WHERE kind = 'SYNONYM')")).isEmpty()
    }

    @Test
    fun `a DDL statement on a synonym of the sources is of the wrong kind, not the table it hides`() {
        val sources = temp.resolve("src")
        write(sources, "tables.sql", "create table crate_log (qty number(3));")
        write(sources, "synonyms.sql", "create synonym crate_s for crate_log;")
        write(sources, "ddl.sql", """
            alter table crate_s add (extra number);
            drop table crate_s;
        """)
        val db = temp.resolve("g.db")
        index(sources, db, crateTable)
        assertThat(Fixture.query(db, """
            SELECT o.start_line || ' ' || o.roles || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' || coalesce(o.entity_id, '-')
            FROM occurrence o JOIN source_file f ON f.id = o.file_id
            WHERE f.path = 'ddl.sql' AND o.written = 'crate_s' ORDER BY o.start_line"""))
            .containsExactly("1 DDL_ALTER UNRESOLVED WRONG_KIND -", "2 DDL_DROP UNRESOLVED WRONG_KIND -")
    }

    @Test
    fun `a public synonym of the sources is followed from a schema without that name`() {
        val sources = temp.resolve("src")
        crate(sources, "create public synonym crate_s for shop.crate_log;")
        val dictionary = AtlasDictionary.of("crates", listOf("SHOP"), emptyList(), objects = mapOf("SHOP" to mapOf("READ_CRATE" to "PROCEDURE")))
        val db = temp.resolve("c.db")
        index(sources, db, dictionary)
        assertThat(columnReads(db, "reader.sql")).containsExactly("4 qty RESOLVED db:SHOP.CRATE_LOG.QTY", "4 tag RESOLVED db:SHOP.CRATE_LOG.TAG")
        assertThat(synonymRows(db, "db:PUBLIC.CRATE_S")).containsExactly("db:PUBLIC.CRATE_S SYNONYM", "SYNONYM_FOR db:SHOP.CRATE_LOG")
    }

    @Test
    fun `an incremental run walks again a reader of a synonym whose table loses a column`() {
        val sources = temp.resolve("src")
        crate(sources)
        val db = temp.resolve("d.db")
        index(sources, db, incremental = true)
        write(sources, "tables.sql", "create table crate_log (qty number(3));")
        val report = index(sources, db, incremental = true).incremental!!
        // The reader, and the file of the synonym, which writes the table's name.
        assertThat(listOf(report.fullReason, report.everything, report.changed, report.affected)).containsExactly(null, null, 1, 2)
        val full = temp.resolve("d-full.db")
        index(sources, full)
        assertThat(columnReads(db, "reader.sql")).containsExactly("4 qty RESOLVED db:SHOP.CRATE_LOG.QTY").isEqualTo(columnReads(full, "reader.sql"))
        assertThat(places(db, "reader.sql")).isEqualTo(places(full, "reader.sql"))
    }

    @Test
    fun `an incremental run walks again a reader of a synonym whose target changes`() {
        val sources = temp.resolve("src")
        crate(sources)
        write(sources, "bins.sql", "create table crate_bin (qty number(3), tag varchar2(5));")
        val db = temp.resolve("e.db")
        index(sources, db, incremental = true)
        write(sources, "synonyms.sql", "create synonym crate_s for crate_bin;")
        val report = index(sources, db, incremental = true).incremental!!
        assertThat(listOf(report.fullReason, report.everything, report.changed, report.affected)).containsExactly(null, null, 1, 1)
        val full = temp.resolve("e-full.db")
        index(sources, full)
        assertThat(columnReads(db, "reader.sql")).containsExactly("4 qty RESOLVED db:SHOP.CRATE_BIN.QTY", "4 tag RESOLVED db:SHOP.CRATE_BIN.TAG")
            .isEqualTo(columnReads(full, "reader.sql"))
        assertThat(places(db, "reader.sql")).isEqualTo(places(full, "reader.sql"))
        assertThat(synonymRows(db, "db:SHOP.CRATE_S")).isEqualTo(synonymRows(full, "db:SHOP.CRATE_S"))
    }
}
