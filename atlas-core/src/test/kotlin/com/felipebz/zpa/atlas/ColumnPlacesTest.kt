//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The places a column's value is read into in PL/SQL ([PlacesFixture]), and how each target is declared. */
class ColumnPlacesTest {
    @TempDir
    lateinit var temp: Path

    private val run = "db:SHOP.PK_ORD.RUN(P_CODE OUT VARCHAR2)@BODY"

    /**
     * Line, column, source, target (and field), class, declared type, length and unit (and scale), type source, reason,
     * and parameter mode, of the places in [path] (all when null).
     */
    private fun places(db: Path, path: String? = null) = Fixture.query(db, """
        SELECT p.start_line || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || (CASE WHEN p.bulk = 1 THEN ' BULK' ELSE '' END) || ' ' ||
               p.target_written || coalesce('.' || p.field, '') || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' ||
               coalesce(p.length || ' ' || p.length_unit || coalesce(' scale ' || p.scale, ''), '-') || ' ' ||
               coalesce(replace(replace(s.key, '$run.', ''), 'db:SHOP.', ''), '-') || ' ' || coalesce(p.reason, '-') || coalesce(' ' || p.parameter_mode, '')
        FROM column_place p JOIN entity c ON c.id = p.column_id LEFT JOIN entity s ON s.id = p.type_source_id
        JOIN source_file f ON f.id = p.file_id ${if (path == null) "" else "WHERE f.path = '$path'"} ORDER BY p.id""")

    @Test
    fun `each INTO target is classified by how it is declared, its anchors and types followed`() {
        assertThat(places(PlacesFixture.index(temp))).containsExactly(
            // Fixed sizes, a length written without unit, with CHAR through a subtype, with BYTE in RETURNING INTO.
            "19 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "20 ORDERS.CODE SELECT_INTO l_code2 ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "21 ORDERS.CODE SELECT_INTO l_any FIXED_SIZE varchar2(4000) 4000 NLS_LENGTH_SEMANTICS - -",
            "22 ORDERS.CODE SELECT_INTO l_sub FIXED_SIZE t_code 12 CHAR PK_ORD.T_CODE -",
            // An anchor to a variable anchored to the column.
            "23 ORDERS.CODE SELECT_INTO l_via ANCHORED_TO_COLUMN l_code2%type - ORDERS.CODE -",
            // An OUT parameter: the value lands in each caller's argument.
            "24 ORDERS.CODE SELECT_INTO p_code CALLER_DECIDES varchar2 - - - OUT",
            "25 ORDERS.CODE SELECT_INTO l_note ANCHORED_ELSEWHERE orders.note%type - ORDERS.NOTE -",
            "26 ORDERS.CODE SELECT_INTO g_code ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            // A record that takes two items, field by field, and one of its fields as a target.
            "27 ORDERS.AMOUNT SELECT_INTO l_pair.AMOUNT NO_SIZE number - PK_ORD.T_PAIR.AMOUNT -",
            "27 ORDERS.CODE SELECT_INTO l_pair.CODE ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "28 ORDERS.CODE SELECT_INTO l_pair.code ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            // A whole row into a %ROWTYPE: one place per column.
            "29 ORDERS.AMOUNT SELECT_INTO r_ord.AMOUNT ANCHORED_TO_COLUMN orders%rowtype - ORDERS.AMOUNT -",
            "29 ORDERS.CODE SELECT_INTO r_ord.CODE ANCHORED_TO_COLUMN orders%rowtype - ORDERS.CODE -",
            "29 ORDERS.NOTE SELECT_INTO r_ord.NOTE ANCHORED_TO_COLUMN orders%rowtype - ORDERS.NOTE -",
            "29 ORDERS.ORDER_ID SELECT_INTO r_ord.ORDER_ID ANCHORED_TO_COLUMN orders%rowtype - ORDERS.ORDER_ID -",
            // BULK COLLECT into a collection of a fixed-size VARCHAR2: its element type holds the value.
            "30 ORDERS.CODE SELECT_INTO BULK l_codes FIXED_SIZE t_codes 12 NLS_LENGTH_SEMANTICS PK_ORD.T_CODES -",
            // Line 31, upper(code), is an expression: not a place. Line 32 reads the view's column.
            "32 V_ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "33 ORDERS.CODE RETURNING l_ret FIXED_SIZE varchar2(20 byte) 20 BYTE - -",
            // A FETCH from the spec's cursor: its third item is an expression.
            "35 ORDERS.ORDER_ID FETCH l_num NO_SIZE number - - -",
            "35 ORDERS.CODE FETCH l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            // A variable of a package the sources do not declare: zpa binds it to nothing of the project.
            "37 ORDERS.CODE SELECT_INTO ext_pkg.g_code UNKNOWN - - - TARGET_UNDECIDED",
            // An anonymous block's variable has no entity: its own declaration says its type.
            "4 ORDERS.CODE SELECT_INTO l_short FIXED_SIZE varchar2(5) 5 NLS_LENGTH_SEMANTICS - -"
        )
    }

    @Test
    fun `a number holds its digits before the point, an OUT parameter's callers decide, a field through an element is followed`() {
        val sources = temp.resolve("src")
        PlacesFixture.write(sources)
        Files.writeString(sources.resolve("pk_acc.sql"), """
            create or replace package pk_acc as
              type rec_acc is record (
                code    orders.code%type,
                amount  orders.amount%type);
              type tab_acc is table of rec_acc index by binary_integer;
              type t_names is table of varchar2(30) index by binary_integer;
              type rec_deep is record (acc rec_acc, names t_names);
              type tab_deep is table of rec_deep index by binary_integer;
              procedure load(p_data in out tab_acc, p_id in number);
              procedure sizes(p_io in out varchar2);
            end;
            /
            create or replace package body pk_acc as
              procedure load(p_data in out tab_acc, p_id in number) is
                i number;
                l_data tab_acc;
                l_deep tab_deep;
                cursor c is select code, amount from orders where order_id = p_id;
              begin
                open c;
                i := 1;
                loop
                  fetch c into p_data(i) .code, p_data(i).amount;
                  exit when c%notfound;
                  i := i + 1;
                end loop;
                close c;
                select code, amount into l_data(1).code, l_data(1).amount from orders where rownum = 1;
                select amount into l_data(2).amount from orders where rownum = 1;
                select code into l_deep(1).acc.code from orders where rownum = 1;
                select code into l_deep(1).names(2) from orders where rownum = 1;
              end;
              procedure sizes(p_io in out varchar2) is
                l_fixed number(10,2);
                l_left  number(5,-2);
                l_any   number(*,2);
                l_int   integer;
                l_float float(10);
              begin
                select order_id into l_fixed from orders where rownum = 1;
                select order_id into l_left from orders where rownum = 1;
                select order_id into l_any from orders where rownum = 1;
                select order_id into l_int from orders where rownum = 1;
                select order_id into l_float from orders where rownum = 1;
                select code into p_io from orders where rownum = 1;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        assertThat(places(db, "pk_acc.sql")).containsExactly(
            // Fields through one element, written with or without a space before the dot: the record type's fields.
            "23 ORDERS.CODE FETCH p_data(i).code ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "23 ORDERS.AMOUNT FETCH p_data(i).amount ANCHORED_TO_COLUMN orders.amount%type - ORDERS.AMOUNT -",
            "28 ORDERS.CODE SELECT_INTO l_data(1).code ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "28 ORDERS.AMOUNT SELECT_INTO l_data(1).amount ANCHORED_TO_COLUMN orders.amount%type - ORDERS.AMOUNT -",
            "29 ORDERS.AMOUNT SELECT_INTO l_data(2).amount ANCHORED_TO_COLUMN orders.amount%type - ORDERS.AMOUNT -",
            // Through an element and a record field, and an element of a field that is a collection.
            "30 ORDERS.CODE SELECT_INTO l_deep(1).acc.code ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "31 ORDERS.CODE SELECT_INTO l_deep(1).names(2) FIXED_SIZE t_names 30 NLS_LENGTH_SEMANTICS PK_ACC.T_NAMES -",
            // A number: the digits before the point (p - s) and the scale.
            "40 ORDERS.ORDER_ID SELECT_INTO l_fixed FIXED_SIZE number(10,2) 8 DIGITS scale 2 - -",
            "41 ORDERS.ORDER_ID SELECT_INTO l_left FIXED_SIZE number(5,-2) 7 DIGITS scale -2 - -",
            "42 ORDERS.ORDER_ID SELECT_INTO l_any FIXED_SIZE number(*,2) 36 DIGITS scale 2 - -",
            "43 ORDERS.ORDER_ID SELECT_INTO l_int FIXED_SIZE integer 38 DIGITS scale 0 - -",
            // A floating number rounds rather than fails.
            "44 ORDERS.ORDER_ID SELECT_INTO l_float NO_SIZE float(10) - - -",
            "45 ORDERS.CODE SELECT_INTO p_io CALLER_DECIDES varchar2 - - - IN OUT"
        )
    }

    @Test
    fun `an element of an element is a shape of target the index does not follow`() {
        val sources = temp.resolve("src")
        PlacesFixture.write(sources)
        Files.writeString(sources.resolve("nested.sql"), """
            declare
              type tab_c is table of varchar2(12) index by pls_integer;
              type tab_tab_c is table of tab_c index by pls_integer;
              l tab_tab_c;
            begin
              select code into l(1)(1) from orders where rownum = 1;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        assertThat(places(db, "nested.sql")).containsExactly("6 ORDERS.CODE SELECT_INTO l(1)(1) UNKNOWN - - - TARGET_SHAPE_NOT_FOLLOWED")
    }

    @Test
    fun `what is not followed is counted by shape`() {
        val db = PlacesFixture.index(temp)
        assertThat(Fixture.query(db, "SELECT substr(message, 1, instr(message, ':') - 1) FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1"))
            .containsExactly("ARGUMENT", "DYNAMIC_INTO", "EXPRESSION_UPPER", "RECORD_ASSIGNMENT")
    }

    @Test
    fun `without a dictionary the columns the code names still have their places`() {
        val rows = places(PlacesFixture.index(temp, AtlasDictionary.NONE))
        assertThat(rows).contains(
            "19 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "20 ORDERS.CODE SELECT_INTO l_code2 ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -"
        )
        // A `*` without the dictionary's columns names none.
        assertThat(rows).noneMatch { it.startsWith("29 ") }
    }

    @Test
    fun `an incremental run follows a declaration that changed in another file`() {
        val db = PlacesFixture.index(temp)
        Files.writeString(temp.resolve("src/pk_ord.pks"), PlacesFixture.spec(codeLength = 30))
        Indexer().index(IndexOptions(temp.resolve("src"), db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1), incremental = true)
        assertThat(places(db)).contains("22 ORDERS.CODE SELECT_INTO l_sub FIXED_SIZE t_code 30 CHAR PK_ORD.T_CODE -")
    }
}
