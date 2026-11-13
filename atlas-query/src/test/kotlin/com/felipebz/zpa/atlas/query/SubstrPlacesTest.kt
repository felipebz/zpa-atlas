//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ZpaExperimentalApi::class)

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.lookupTables
import com.felipebz.zpa.atlas.number
import com.felipebz.zpa.atlas.varchar2
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** A column's value read through `SUBSTR` and `SUBSTRB`: a place, compared with the size and with the most they keep. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubstrPlacesTest {
    private val temp: Path = Files.createTempDirectory("atlas-substr")
    private lateinit var db: Path
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("orders.sql"),
            "create table orders (order_id number(10), code varchar2(20 char), note varchar2(40 byte), amount number(8,2));\n")
        Files.writeString(sources.resolve("pk_ss.sql"), """
            create or replace package pk_ss as
              function code_of(p_id number) return varchar2;
              function short_code(p_id number) return varchar2;
              procedure take(p_v varchar2);
              procedure out_code(p_id number, p_out out varchar2);
              procedure show_ss(p_id number, p_len number);
            end;
            /
            create or replace package body pk_ss as
              function code_of(p_id number) return varchar2 is
                l varchar2(20 char);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;

              function short_code(p_id number) return varchar2 is
                l varchar2(20 char);
              begin
                select code into l from orders where order_id = p_id;
                return substr(l, 1, 3);
              end;

              procedure take(p_v varchar2) is
                l1 varchar2(2 char);
              begin
                l1 := p_v;
              end;

              procedure out_code(p_id number, p_out out varchar2) is
                l varchar2(20 char);
              begin
                select code into l from orders where order_id = p_id;
                p_out := substr(l, 1, 3);
              end;

              procedure show_ss(p_id number, p_len number) is
                s1 varchar2(5 char); s2 varchar2(5 char); s3 varchar2(5 char); s4 varchar2(5 char); s5 varchar2(5 char);
                s6 varchar2(5 byte); s7 varchar2(5 byte); s8 varchar2(5 char); s9 varchar2(5 char); s10 varchar2(5 char);
                s11 varchar2(5 char); s12 varchar2(5 char); s13 varchar2(5 char); s14 varchar2(5 char); s15 varchar2(5 char);
                n1 varchar2(5 char);
                a1 varchar2(5 char);
                f1 varchar2(5 char); f2 varchar2(5 char);
                o1 varchar2(5 char);
                k1 varchar2(2 char);
                type t_codes is table of varchar2(5 char);
                codes t_codes;
                cursor c_ss is select substr(code, 1, 3) v from orders;
              begin
                select substr(code, 1, 5) into s1 from orders where order_id = p_id;
                select substr(code, 1, 8) into s2 from orders where order_id = p_id;
                select substr(code, -3) into s3 from orders where order_id = p_id;
                select substr(code, 16) into s4 from orders where order_id = p_id;
                select substr(code, 1, p_len) into s5 from orders where order_id = p_id;
                select substr(code, 1, 5) into s6 from orders where order_id = p_id;
                select substrb(note, 1, 5) into s7 from orders where order_id = p_id;
                select substrb(note, 1, 5) into s8 from orders where order_id = p_id;
                select upper(substr(code, 1, 3)) into s9 from orders where order_id = p_id;
                select replace(substr(code, 1, 3), 'a', 'xx') into s10 from orders where order_id = p_id;
                select substr(replace(code, 'a', 'xx'), 1, 5) into s11 from orders where order_id = p_id;
                select '#' || substr(code, 1, 3) into s12 from orders where order_id = p_id;
                select nvl(substr(code, 1, 3), 'none') into s13 from orders where order_id = p_id;
                select substr(substr(code, 1, 8), 1, 4) into s14 from orders where order_id = p_id;
                select substr(code, -3, 10) into s15 from orders where order_id = p_id;
                select substr(amount, 1, 4) into n1 from orders where order_id = p_id;
                select substr(code, 1, 3) bulk collect into codes from orders;
                open c_ss;
                fetch c_ss into k1;
                close c_ss;
                for r in (select code from orders) loop
                  a1 := substr(r.code, 1, 3);
                  take(substr(r.code, 1, 3));
                end loop;
                f1 := short_code(p_id);
                f2 := substr(code_of(p_id), 1, 4);
                out_code(p_id, o1);
              end;
            end;
            /
        """.trimIndent() + "\n")
        // Still counted: the column passed to a routine in SQL (its result is followed), and SUBSTRC.
        Files.writeString(sources.resolve("pk_left.sql"), """
            create or replace procedure show_left(p_id number) is
              x1 varchar2(5 char); x2 varchar2(5 char);
            begin
              select substr(pk_ss.code_of(order_id), 1, 3) into x1 from orders where order_id = p_id;
              select substrc(code, 1, 3) into x2 from orders where order_id = p_id;
            end;
            /
        """.trimIndent() + "\n")
        db = temp.resolve("substr.db")
        // The table's columns, so that zpa reads the names of the queries as its columns.
        val columns = lookupTables("SHOP", "ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR),
            "NOTE" to varchar2(40), "AMOUNT" to number(8, 2)))
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = columns, threads = 1))
        index = AtlasIndex(db, sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    /**
     * Each place of the column [name] in file and line order: its target, the column, how it is read, the functions,
     * whether it is concatenated, its verdict against [size] (why when not known, the room left when told) and the most
     * SUBSTR keeps.
     */
    private fun places(name: String, size: Int? = null): List<String> =
        index.columnPlaces("db:SHOP.ORDERS.$name", PlaceFilter(size, Verdict.entries.toSet(), concatenated = true)).result!!.rows
            .sortedWith(compareBy({ it.path }, { it.line }, { it.targetWritten })).map {
                listOfNotNull(
                    it.targetWritten, it.read.name, it.source + if (it.bulk) " BULK" else "", it.functions.joinToString(",").ifEmpty { "-" },
                    "concatenated".takeIf { _ -> it.concatenated }, it.verdict, it.verdictReason, it.room?.let { room -> "room $room" },
                    it.kept?.let { kept -> "kept $kept ${it.keptUnit}" }
                ).joinToString(" ")
            }

    @Test
    fun `each target of a value read through SUBSTR fits by what it keeps, by the column's size or by neither`() {
        assertThat(places("CODE")).containsExactly(
            "x1 CODE RETURNED SUBSTR FITS kept 3 CHAR",                   // code_of's result, in SQL
            "l CODE SELECT_INTO - FITS", "l CODE SELECT_INTO - FITS",
            "l1 CODE IN_ARGUMENT SUBSTR SMALLER kept 3 CHAR",             // take's, from take(substr(r.code, 1, 3))
            "l CODE SELECT_INTO - FITS",
            "p_out CODE ASSIGNMENT SUBSTR CALLER_DECIDES",
            "s1 CODE SELECT_INTO SUBSTR FITS kept 5 CHAR",
            "s2 CODE SELECT_INTO SUBSTR SMALLER kept 8 CHAR",
            "s3 CODE SELECT_INTO SUBSTR FITS kept 3 CHAR",                // a negative start
            "s4 CODE SELECT_INTO SUBSTR FITS kept 5 CHAR",                // start 16 of 20
            "s5 CODE SELECT_INTO SUBSTR SMALLER",                         // a length not literal: no limit
            "s6 CODE SELECT_INTO SUBSTR UNKNOWN UNIT_MISMATCH kept 5 CHAR",
            "s9 CODE SELECT_INTO UPPER,SUBSTR FITS kept 3 CHAR",
            "s10 CODE SELECT_INTO REPLACE,SUBSTR SMALLER",                // the REPLACE outside drops the limit
            "s11 CODE SELECT_INTO SUBSTR,REPLACE FITS kept 5 CHAR",
            "s12 CODE SELECT_INTO SUBSTR concatenated FITS room 2 kept 3 CHAR",
            "s13 CODE SELECT_INTO NVL,SUBSTR FITS kept 3 CHAR",
            "s14 CODE SELECT_INTO SUBSTR,SUBSTR FITS kept 4 CHAR",
            "s15 CODE SELECT_INTO SUBSTR FITS kept 3 CHAR",
            "codes CODE SELECT_INTO BULK SUBSTR FITS kept 3 CHAR",
            "k1 CODE FETCH SUBSTR SMALLER kept 3 CHAR",
            "a1 CODE ASSIGNMENT SUBSTR FITS kept 3 CHAR",
            "f1 CODE RETURNED SUBSTR FITS kept 3 CHAR",                   // return substr(l, 1, 3)
            "f2 CODE RETURNED SUBSTR FITS kept 4 CHAR",                   // the function's result is the first argument
            "o1 CODE OUT_ARGUMENT SUBSTR FITS kept 3 CHAR"                // p_out := substr(l, 1, 3)
        )
        // SUBSTRB keeps bytes: five of them fit five bytes, and five characters.
        assertThat(places("NOTE")).containsExactly("s7 NOTE SELECT_INTO SUBSTRB FITS kept 5 BYTE", "s8 NOTE SELECT_INTO SUBSTRB FITS kept 5 BYTE")
        // The text of a number: what SUBSTR keeps of it alone compares.
        assertThat(places("AMOUNT")).containsExactly("n1 AMOUNT SELECT_INTO SUBSTR FITS kept 4 CHAR")
    }

    @Test
    fun `a size given compares with what SUBSTR keeps the same way, and a start leaves that size less what it takes off`() {
        val at = { size: Int -> places("CODE", size).filter { it.startsWith("s") || it.startsWith("k1") || it.startsWith("l1") }.map { it.substringBefore(" kept") } }
        assertThat(at(50)).containsExactly(
            "l1 CODE IN_ARGUMENT SUBSTR SMALLER",
            "s1 CODE SELECT_INTO SUBSTR FITS",
            "s2 CODE SELECT_INTO SUBSTR SMALLER",
            "s3 CODE SELECT_INTO SUBSTR FITS",
            "s4 CODE SELECT_INTO SUBSTR SMALLER",                         // at most 35
            "s5 CODE SELECT_INTO SUBSTR SMALLER",
            "s6 CODE SELECT_INTO SUBSTR UNKNOWN UNIT_MISMATCH",
            "s9 CODE SELECT_INTO UPPER,SUBSTR FITS",
            "s10 CODE SELECT_INTO REPLACE,SUBSTR SMALLER",
            "s11 CODE SELECT_INTO SUBSTR,REPLACE FITS",
            "s12 CODE SELECT_INTO SUBSTR concatenated FITS room 2",
            "s13 CODE SELECT_INTO NVL,SUBSTR FITS",
            "s14 CODE SELECT_INTO SUBSTR,SUBSTR FITS",
            "s15 CODE SELECT_INTO SUBSTR FITS",
            "k1 CODE FETCH SUBSTR SMALLER"
        )
        assertThat(places("CODE", 50).single { it.startsWith("s4 ") }).endsWith("kept 35 CHAR")
        assertThat(at(4)).containsExactly(
            "l1 CODE IN_ARGUMENT SUBSTR SMALLER",
            "s1 CODE SELECT_INTO SUBSTR FITS",
            "s2 CODE SELECT_INTO SUBSTR FITS",
            "s3 CODE SELECT_INTO SUBSTR FITS",
            "s4 CODE SELECT_INTO SUBSTR FITS",                            // a start past the size keeps nothing
            "s5 CODE SELECT_INTO SUBSTR FITS",
            "s6 CODE SELECT_INTO SUBSTR UNKNOWN UNIT_MISMATCH",
            "s9 CODE SELECT_INTO UPPER,SUBSTR FITS",
            "s10 CODE SELECT_INTO REPLACE,SUBSTR FITS",
            "s11 CODE SELECT_INTO SUBSTR,REPLACE FITS",
            "s12 CODE SELECT_INTO SUBSTR concatenated FITS room 2",
            "s13 CODE SELECT_INTO NVL,SUBSTR FITS",
            "s14 CODE SELECT_INTO SUBSTR,SUBSTR FITS",
            "s15 CODE SELECT_INTO SUBSTR FITS",
            "k1 CODE FETCH SUBSTR SMALLER"
        )
        assertThat(places("CODE", 4).single { it.startsWith("s4 ") }).endsWith("kept 0 CHAR")
    }

    @Test
    fun `a SUBSTR not followed for another reason is still counted`() {
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") })
            .containsExactly("EXPRESSION_OTHER_SUBSTRC: 1", "EXPRESSION_SUBSTR: 1")
    }
}
