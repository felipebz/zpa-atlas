//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The tables a `CREATE TABLE` of the sources creates, read beside the dictionary. */
class SourceTablesTest {
    @TempDir
    lateinit var temp: Path

    private fun index(sources: Path, db: Path, dictionary: AtlasDictionary = AtlasDictionary.NONE, incremental: Boolean = false): Path {
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1), incremental = incremental)
        return db
    }

    /** Line, written name, status, reason and entity of the occurrences of [file] that read a column. */
    private fun columnReads(db: Path, file: String) = Fixture.query(db, """
        SELECT o.start_line || ' ' || o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' || coalesce(e.key, '-')
        FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
        WHERE f.path = '$file' AND o.statement_id IS NOT NULL AND (e.kind = 'COLUMN' OR o.written IN ('label', 'extra'))
        ORDER BY o.start_line, o.start_col""")

    private fun write(sources: Path, name: String, text: String) {
        Files.createDirectories(sources)
        Files.writeString(sources.resolve(name), text.trimIndent())
    }

    @Test
    fun `a column of a table created only in the sources is a column fact and a place`() {
        val sources = temp.resolve("src")
        write(sources, "parcels.sql", "create table parcels (parcel_id number(10), label varchar2(12 char));")
        write(sources, "show.sql", """
            create or replace procedure show_parcel(p_id number) is
              l varchar2(30);
            begin
              select label into l from parcels where parcel_id = p_id;
            end;
            /
        """)
        val db = index(sources, temp.resolve("a.db"))
        assertThat(columnReads(db, "show.sql")).containsExactly(
            "4 label RESOLVED - db:SHOP.PARCELS.LABEL",
            "4 parcel_id RESOLVED - db:SHOP.PARCELS.PARCEL_ID"
        )
        assertThat(places(db, "show.sql")).containsExactly("4 PARCELS.LABEL SELECT_INTO l FIXED_SIZE varchar2(30) 30 BYTE - -")
        // One entity for the table, whether its CREATE TABLE or a statement on it names it.
        assertThat(Fixture.query(db, "SELECT key || ' ' || kind || ' ' || external FROM entity WHERE key LIKE 'db:SHOP.PARCELS%' ORDER BY key"))
            .containsExactly("db:SHOP.PARCELS TABLE 0", "db:SHOP.PARCELS.LABEL COLUMN 0", "db:SHOP.PARCELS.PARCEL_ID COLUMN 0")
    }

    @Test
    fun `the dictionary's columns win over the sources'`() {
        val sources = temp.resolve("src")
        write(sources, "parcels.sql", "create table parcels (parcel_id number(10), extra varchar2(12 char));")
        write(sources, "show.sql", """
            create or replace procedure show_parcel(p_id number) is
              l varchar2(30);
            begin
              select label into l from parcels where parcel_id = p_id;
              select extra into l from parcels where parcel_id = p_id;
            end;
            /
        """)
        val dictionary = shopTables("PARCELS" to listOf("PARCEL_ID" to number(10), "LABEL" to varchar2(20, LengthSemantics.CHAR)))
        val db = index(sources, temp.resolve("b.db"), dictionary)
        assertThat(columnReads(db, "show.sql")).containsExactly(
            "4 label RESOLVED - db:SHOP.PARCELS.LABEL",
            "4 parcel_id RESOLVED - db:SHOP.PARCELS.PARCEL_ID",
            // extra, which only the sources list, is no column of the dictionary's parcels.
            "5 parcel_id RESOLVED - db:SHOP.PARCELS.PARCEL_ID"
        )
    }

    @Test
    fun `a name a table created AS SELECT does not list is not proved a non-column`() {
        val sources = temp.resolve("src")
        write(sources, "parcels.sql", """
            create table parcels (parcel_id number(10), label varchar2(12 char));
            create table parcel_copy as select parcel_id from parcels;
        """)
        write(sources, "show.sql", """
            create or replace procedure show_copy(p_id number) is
              l varchar2(30);
            begin
              select label into l from parcel_copy where parcel_id = p_id;
            end;
            /
        """)
        val db = index(sources, temp.resolve("c.db"))
        assertThat(columnReads(db, "show.sql")).containsExactly(
            "4 label UNVERIFIED COLUMN_CANDIDATE db:SHOP.PARCEL_COPY.LABEL",
            "4 parcel_id UNVERIFIED COLUMN_CANDIDATE db:SHOP.PARCEL_COPY.PARCEL_ID"
        )
    }

    @Test
    fun `an incremental run walks again a file that reads a column added to a table of another file`() {
        val sources = temp.resolve("src")
        write(sources, "parcels.sql", "create table parcels (parcel_id number(10));")
        write(sources, "show.sql", """
            create or replace procedure show_parcel(p_id number) is
              l varchar2(30);
            begin
              select label into l from parcels where parcel_id = p_id;
            end;
            /
        """)
        val db = index(sources, temp.resolve("d.db"), incremental = true)
        // label is proved no column of parcels: no column fact.
        assertThat(columnReads(db, "show.sql")).containsExactly("4 parcel_id RESOLVED - db:SHOP.PARCELS.PARCEL_ID")
        write(sources, "parcels.sql", "create table parcels (parcel_id number(10), label varchar2(12 char));")
        val report = Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1), incremental = true)
            .incremental!!
        assertThat(listOf(report.fullReason, report.everything, report.changed, report.affected)).containsExactly(null, null, 1, 1)
        val full = index(sources, temp.resolve("full.db"))
        assertThat(columnReads(db, "show.sql")).contains("4 label RESOLVED - db:SHOP.PARCELS.LABEL").isEqualTo(columnReads(full, "show.sql"))
        assertThat(places(db, "show.sql")).containsExactly("4 PARCELS.LABEL SELECT_INTO l FIXED_SIZE varchar2(30) 30 BYTE - -")
            .isEqualTo(places(full, "show.sql"))
    }

    private fun incremental(sources: Path, db: Path) =
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1), incremental = true).incremental!!

    @Test
    fun `an incremental run walks again a file that reads a column dropped from a quoted lower-case table`() {
        val sources = temp.resolve("src")
        write(sources, "tables.sql", "create table \"crate_log\" (qty number(3), \"tag\" varchar2(5));")
        write(sources, "reader.sql", """
            create or replace procedure read_log is
              n number; m varchar2(2);
            begin
              select qty, "tag" into n, m from "crate_log";
            end;
            /
        """)
        val db = index(sources, temp.resolve("e.db"), incremental = true)
        write(sources, "tables.sql", "create table \"crate_log\" (qty number(3));")
        val report = incremental(sources, db)
        assertThat(listOf(report.fullReason, report.everything, report.changed, report.affected)).containsExactly(null, null, 1, 1)
        val full = index(sources, temp.resolve("full.db"))
        assertThat(columnReads(db, "reader.sql")).isEqualTo(columnReads(full, "reader.sql"))
            .containsExactly("4 qty RESOLVED - db:SHOP.crate_log.QTY")
        assertThat(places(db, "reader.sql")).isEqualTo(places(full, "reader.sql"))
    }

    @Test
    fun `an incremental run stays incremental when the file that creates a table is deleted`() {
        val sources = temp.resolve("src")
        write(sources, "parcels.sql", "create table parcels (parcel_id number(10), label varchar2(12 char));")
        write(sources, "show.sql", """
            create or replace procedure show_parcel(p_id number) is
              l varchar2(30);
            begin
              select label into l from parcels where parcel_id = p_id;
            end;
            /
        """)
        val db = index(sources, temp.resolve("f.db"), incremental = true)
        assertThat(places(db, "show.sql")).hasSize(1)
        Files.delete(sources.resolve("parcels.sql"))
        val report = incremental(sources, db)
        assertThat(listOf(report.fullReason, report.everything, report.removed, report.affected)).containsExactly(null, null, 1, 1)
        val full = index(sources, temp.resolve("full.db"))
        assertThat(columnReads(db, "show.sql")).isEqualTo(columnReads(full, "show.sql"))
            .containsExactly("4 label UNVERIFIED COLUMN_CANDIDATE db:SHOP.PARCELS.LABEL", "4 parcel_id UNVERIFIED COLUMN_CANDIDATE db:SHOP.PARCELS.PARCEL_ID")
        assertThat(places(db, "show.sql")).isEqualTo(places(full, "show.sql"))
    }
}
