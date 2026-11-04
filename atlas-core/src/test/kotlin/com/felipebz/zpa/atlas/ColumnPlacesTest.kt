//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
               coalesce(replace(replace(s.key, '$run.', ''), 'db:SHOP.', ''), '-') || ' ' || coalesce(p.reason, '-') || coalesce(' ' || p.parameter_mode, '') || coalesce(' via ' || p.functions, '')
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
            // Line 31 reads the column through UPPER. Line 32 reads the view's column.
            "31 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via UPPER",
            "32 V_ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "33 ORDERS.CODE RETURNING l_ret FIXED_SIZE varchar2(20 byte) 20 BYTE - -",
            // A FETCH from the spec's cursor: its third item reads the column through UPPER.
            "35 ORDERS.ORDER_ID FETCH l_num NO_SIZE number - - -",
            "35 ORDERS.CODE FETCH l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "35 ORDERS.CODE FETCH l_any FIXED_SIZE varchar2(4000) 4000 NLS_LENGTH_SEMANTICS - - via UPPER",
            // A variable of a package the sources do not declare: zpa binds it to nothing of the project.
            "37 ORDERS.CODE SELECT_INTO ext_pkg.g_code UNKNOWN - - - TARGET_UNDECIDED",
            // A field of a cursor loop record assigned to a variable.
            "40 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
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
              select code into l(1)(1).x from orders where rownum = 1;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        assertThat(places(db, "nested.sql")).containsExactly(
            "6 ORDERS.CODE SELECT_INTO l(1)(1) UNKNOWN - - - TARGET_SHAPE_NOT_FOLLOWED",
            "7 ORDERS.CODE SELECT_INTO l(1)(1).x UNKNOWN - - - TARGET_SHAPE_NOT_FOLLOWED"
        )
    }

    @Test
    fun `a field of a row record assigned to a target is a place of its column`() {
        val sources = temp.resolve("src")
        PlacesFixture.write(sources)
        Files.writeString(sources.resolve("assign.sql"), """
            create or replace procedure assign_codes(p_out out varchar2) is
              l_code  varchar2(12);
              l_any   orders.code%type;
              l_up    varchar2(12);
              r_ord   orders%rowtype;
              cursor c is select o.code from orders o;
              cursor c_up is select upper(code) c from orders;
            begin
              for r in (select code from orders) loop
                l_code := r.code;
              end loop;
              for r in c loop
                l_any := r.code;
              end loop;
              select * into r_ord from orders where order_id = 1;
              l_code := r_ord.code;
              l_up := upper(r_ord.code);
              for r in c_up loop
                l_up := r.c;
              end loop;
              p_out := r_ord.code;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        val proc = "db:SHOP.ASSIGN_CODES(P_OUT OUT VARCHAR2)."
        assertThat(places(db, "assign.sql").map { it.replace(proc, "") }).containsExactly(
            // The record of a loop over a query, of a loop over a cursor, and a %ROWTYPE variable.
            "10 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "13 ORDERS.CODE ASSIGNMENT l_any ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "15 ORDERS.AMOUNT SELECT_INTO r_ord.AMOUNT ANCHORED_TO_COLUMN orders%rowtype - ORDERS.AMOUNT -",
            "15 ORDERS.CODE SELECT_INTO r_ord.CODE ANCHORED_TO_COLUMN orders%rowtype - ORDERS.CODE -",
            "15 ORDERS.NOTE SELECT_INTO r_ord.NOTE ANCHORED_TO_COLUMN orders%rowtype - ORDERS.NOTE -",
            "15 ORDERS.ORDER_ID SELECT_INTO r_ord.ORDER_ID ANCHORED_TO_COLUMN orders%rowtype - ORDERS.ORDER_ID -",
            "16 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            // Line 17 assigns the field through UPPER, line 19 a cursor field that is upper(code).
            "17 ORDERS.CODE ASSIGNMENT l_up FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via UPPER",
            "19 ORDERS.CODE ASSIGNMENT l_up FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via UPPER",
            "21 ORDERS.CODE ASSIGNMENT p_out CALLER_DECIDES varchar2 - - - OUT"
        )
        assertThat(Fixture.query(db, """SELECT c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id
            WHERE c.kind = 'COLUMN_VALUE_NOT_FOLLOWED' AND f.path = 'assign.sql' ORDER BY 1""").map { it.substringBefore(" ") })
            .isEmpty()
    }

    @Test
    fun `a field of an element of a collection of a row type takes its column's type`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("rows.sql"), """
            create table t_lot (code varchar2(20 byte), qty number(9));
            /
            create or replace package pk_rows as
              procedure w1;
            end;
            /
            create or replace package body pk_rows as
              cursor c is select code, qty from t_lot;
              type tab_row is table of t_lot%rowtype index by binary_integer;
              type tab_cur is table of c%rowtype index by binary_integer;
              type rec_pair is record (cd varchar2(5), qty number);
              type tab_pair is table of rec_pair index by binary_integer;
              procedure w1 is l tab_row; begin for r in (select code from t_lot) loop l(1).code := r.code; end loop; end;
              procedure w2 is l tab_cur; begin for r in (select code from t_lot) loop l(1).code := r.code; end loop; end;
              procedure w4 is l tab_row; begin select code into l(1).code from t_lot where rownum = 1; end;
              procedure w5 is l t_lot%rowtype; begin for r in (select code from t_lot) loop l.code := r.code; end loop; end;
              procedure w6 is l tab_row; r2 t_lot%rowtype; begin select * into r2 from t_lot where rownum = 1; l(1).code := r2.code; end;
              procedure w7 is l tab_pair; begin select code into l(1).cd from t_lot where rownum = 1; end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(places(db, "rows.sql").map { it.replace("T_LOT.", "") }).containsExactly(
            // An element of a collection of a table's row, and of a cursor's row: the row's field is the column.
            "13 CODE ASSIGNMENT l(1).code ANCHORED_TO_COLUMN tab_row - CODE -",
            "14 CODE ASSIGNMENT l(1).code ANCHORED_TO_COLUMN tab_cur - CODE -",
            "15 CODE SELECT_INTO l(1).code ANCHORED_TO_COLUMN tab_row - CODE -",
            "16 CODE ASSIGNMENT l.code ANCHORED_TO_COLUMN t_lot%rowtype - CODE -",
            // Without a dictionary `select *` names no column; the field of the %ROWTYPE variable still does.
            "17 CODE ASSIGNMENT l(1).code ANCHORED_TO_COLUMN tab_row - CODE -",
            // An element of a record type: its field's own declaration, as before.
            "18 CODE SELECT_INTO l(1).cd FIXED_SIZE varchar2(5) 5 NLS_LENGTH_SEMANTICS - -"
        )
    }

    @Test
    fun `a column read through value functions is a place, other expressions stay counted`() {
        val sources = temp.resolve("src")
        PlacesFixture.write(sources)
        Files.writeString(sources.resolve("funcs.sql"), """
            declare
              l_code  varchar2(12);
              l_amt   number(5);
              l_flag  varchar2(1);
              cursor c_max is select max(code) from orders;
            begin
              select nvl(code, '-') into l_code from orders where order_id = 1;
              select max(code) into l_code from orders;
              select nvl(amount, 0) into l_amt from orders where order_id = 1;
              for r in (select code from orders) loop
                l_code := upper(trim(r.code));
              end loop;
              select nvl2(code, 'Y', 'N') into l_flag from orders where order_id = 1;
              select nvl(code, note) into l_code from orders where order_id = 1;
              select nvl(substr(code, 1, 3), '-') into l_code from orders where order_id = 1;
              open c_max;
              fetch c_max into l_code;
              close c_max;
              for r in (select upper(code) code from orders) loop
                l_code := r.code;
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        assertThat(places(db, "funcs.sql")).containsExactly(
            "7 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via NVL",
            "8 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via MAX",
            "9 ORDERS.AMOUNT SELECT_INTO l_amt FIXED_SIZE number(5) 5 DIGITS scale 0 - - via NVL",
            "11 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via UPPER,TRIM",
            // Lines 13 (NVL2's first argument), 14 (two columns) and 15 (SUBSTR inside NVL) are not places.
            "17 ORDERS.CODE FETCH l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via MAX",
            "20 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - - via UPPER"
        )
        assertThat(Fixture.query(db, """SELECT c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id
            WHERE c.kind = 'COLUMN_VALUE_NOT_FOLLOWED' AND f.path = 'funcs.sql' ORDER BY 1""").map { it.substringBefore(" ") })
            .containsExactly("EXPRESSION_NVL2:", "EXPRESSION_NVL:")
    }

    @Test
    fun `the arguments callers pass for an OUT parameter are places, through the callers' own OUT parameters`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("calls.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));
            /
            create or replace package pk_ord as
              procedure get_code(p_id number, o_code out varchar2);
              procedure relay(p_id number, o_code out varchar2);
            end;
            /
            create or replace package body pk_ord as
              procedure get_code(p_id number, o_code out varchar2) is
              begin
                select code into o_code from orders where order_id = p_id;
              end;
              procedure relay(p_id number, o_code out varchar2) is
              begin
                get_code(p_id, o_code);
              end;
            end;
            /
            create or replace procedure show_code is
              l_code varchar2(12);
              l_wide varchar2(30);
              l_any  orders.code%type;
              type r_t is record (c varchar2(12));
              r      r_t;
              type a_t is table of varchar2(12) index by pls_integer;
              a      a_t;
            begin
              pk_ord.get_code(1, l_code);
              pk_ord.get_code(1, l_wide);
              pk_ord.get_code(o_code => l_code, p_id => 1);
              pk_ord.get_code(1, l_any);
              pk_ord.get_code(1, r.c);
              pk_ord.get_code(1, a(1));
              pk_ord.relay(1, l_code);
              pk_ord.get_code(1, l_code || 'x');
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(places(db, "calls.sql")).containsExactly(
            // The parameter's own place stays: a caller the index does not see may pass anything.
            "11 ORDERS.CODE SELECT_INTO o_code CALLER_DECIDES varchar2 - - - OUT",
            // relay passes its own OUT parameter: its callers are followed in turn.
            "15 ORDERS.CODE OUT_ARGUMENT o_code CALLER_DECIDES varchar2 - - - OUT",
            "28 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "29 ORDERS.CODE OUT_ARGUMENT l_wide FIXED_SIZE varchar2(30) 30 NLS_LENGTH_SEMANTICS - -",
            // A named argument.
            "30 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "31 ORDERS.CODE OUT_ARGUMENT l_any ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "32 ORDERS.CODE OUT_ARGUMENT r.c FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "33 ORDERS.CODE OUT_ARGUMENT a(1) FIXED_SIZE a_t 12 NLS_LENGTH_SEMANTICS SHOW_CODE().A_T -",
            // Through relay's o_code.
            "34 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -"
        )
        // Line 35 passes an expression: counted.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'").map { it.substringBefore(" ") })
            .containsExactly("OUT_ARGUMENT_NOT_FOLLOWED:")
        assertThat(Fixture.query(db, "SELECT a.key || ' ' || g.key FROM column_place p JOIN entity a ON a.id = p.argument_parameter_id JOIN entity g ON g.id = p.argument_routine_id WHERE p.start_line = 34"))
            .containsExactly("db:SHOP.PK_ORD.RELAY(P_ID IN NUMBER,O_CODE OUT VARCHAR2).O_CODE db:SHOP.PK_ORD.RELAY(P_ID IN NUMBER,O_CODE OUT VARCHAR2)")
    }

    @Test
    fun `the arguments a routine passes to its own nested subprogram's OUT parameter are places`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("nested.sql"), """
            create table ord_x (order_id number(10), code varchar2(20 byte));

            create or replace procedure show_x is
              l_code varchar2(12);
              l_wide varchar2(30);
              procedure inner(o_i out varchar2) is
              begin
                select code into o_i from ord_x where order_id = 1;
              end;
              procedure outer(p_n number, o_o out varchar2) is
              begin
                inner(o_o);
              end;
            begin
              inner(l_code);
              outer(o_o => l_wide, p_n => 1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(places(db, "nested.sql")).containsExactly(
            "8 ORD_X.CODE SELECT_INTO o_i CALLER_DECIDES varchar2 - - - OUT",
            // outer passes its own OUT parameter: outer's callers are followed in turn.
            "12 ORD_X.CODE OUT_ARGUMENT o_o CALLER_DECIDES varchar2 - - - OUT",
            "15 ORD_X.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 NLS_LENGTH_SEMANTICS - -",
            "16 ORD_X.CODE OUT_ARGUMENT l_wide FIXED_SIZE varchar2(30) 30 NLS_LENGTH_SEMANTICS - -"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")).isEmpty()
    }

    @Test
    fun `an object type method's OUT parameter, whose calls the index does not record, is counted`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("box.sql"), """
            create table ord_x (order_id number(10), code varchar2(20 byte));
            create or replace type t_box as object (n number, member procedure get_code(o_c out varchar2));
            /
            create or replace type body t_box as
              member procedure get_code(o_c out varchar2) is
              begin
                select code into o_c from ord_x where order_id = 1;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(places(db, "box.sql")).containsExactly("7 ORD_X.CODE SELECT_INTO o_c CALLER_DECIDES varchar2 - - - OUT")
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'").map { it.substringBefore(":") })
            .containsExactly("OUT_PARAMETER_NOT_FOLLOWED")
    }

    @Test
    fun `a column passed to a routine's IN parameter is a place of each target the routine writes the parameter into`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("fmt.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), ref_code varchar2(20 byte));
            create table short_codes (c varchar2(5));

            create or replace package pk_fmt as
              g_short varchar2(12);
              function f_assign(p_code varchar2) return number;
              function f_anchored(p_code orders.code%type) return number;
              function f_upper(p_code varchar2) return number;
              function f_nvl(p_code varchar2) return number;
              function f_relay(p_code varchar2) return number;
              function f_global(p_code varchar2) return number;
              function f_wide(p_code varchar2) return number;
              function f_filter(p_code varchar2) return number;
              function f_return(p_code varchar2) return varchar2;
              procedure p_assign(p_code varchar2);
            end;
            /
            create or replace package body pk_fmt as
              function f_assign(p_code varchar2) return number is l varchar2(12); begin l := p_code; return 1; end;
              function f_anchored(p_code orders.code%type) return number is l varchar2(12); begin l := p_code; return 1; end;
              function f_upper(p_code varchar2) return number is l varchar2(12); begin l := upper(p_code); return 1; end;
              function f_nvl(p_code varchar2) return number is l varchar2(12); begin l := nvl(p_code, '-'); return 1; end;
              function f_relay(p_code varchar2) return number is begin return f_assign(p_code); end;
              function f_global(p_code varchar2) return number is begin g_short := p_code; return 1; end;
              function f_wide(p_code varchar2) return number is l varchar2(30); begin l := p_code; return 1; end;
              function f_filter(p_code varchar2) return number is n number;
                begin select count(*) into n from orders where code = p_code; return n; end;
              function f_return(p_code varchar2) return varchar2 is begin return p_code; end;
              procedure p_assign(p_code varchar2) is l varchar2(12); begin l := p_code; end;
            end;
            /
            create or replace procedure show_code is
              n number;
              l varchar2(12);
              r orders%rowtype;
            begin
              select count(*) into n from orders where pk_fmt.f_assign(code) = 1;
              update orders set order_id = order_id where pk_fmt.f_assign(code) = 1;
              select pk_fmt.f_anchored(code) into n from orders;
              select pk_fmt.f_upper(code) into n from orders;
              select pk_fmt.f_nvl(code) into n from orders;
              select pk_fmt.f_relay(code) into n from orders;
              select pk_fmt.f_global(code) into n from orders;
              select pk_fmt.f_wide(code) into n from orders;
              select pk_fmt.f_filter(code) into n from orders;
              select * into r from orders;
              pk_fmt.p_assign(r.code);
              for c in (select code from orders) loop pk_fmt.p_assign(c.code); end loop;
              select pk_fmt.f_return(code) into l from orders;
              select pk_fmt.f_relay(ref_code) into n from orders;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.target_written || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' || coalesce(p.functions, '-') || ' ' ||
                   replace(a.key, 'db:SHOP.PK_FMT.', '') || ' ' || p.call_count || ' ' || p.call_path || ':' || p.call_line
            FROM column_place p JOIN entity a ON a.id = p.argument_parameter_id JOIN entity c ON c.id = p.column_id
            WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line, c.key""")).containsExactly(
            // f_assign's local: from the WHERE of a SELECT and of an UPDATE, one place for both calls; f_relay passes
            // its parameter on to f_assign, which gives the column passed only to f_relay a place there too.
            "19 CODE l FIXED_SIZE varchar2(12) - F_ASSIGN(P_CODE IN VARCHAR2).P_CODE 2 fmt.sql:37",
            "19 REF_CODE l FIXED_SIZE varchar2(12) - F_RELAY(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:50",
            // A %TYPE parameter does not limit the value: the local does.
            "20 CODE l FIXED_SIZE varchar2(12) - F_ANCHORED(P_CODE IN ORDERS.CODE%TYPE).P_CODE 1 fmt.sql:39",
            "21 CODE l FIXED_SIZE varchar2(12) UPPER F_UPPER(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:40",
            "22 CODE l FIXED_SIZE varchar2(12) NVL F_NVL(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:41",
            "24 CODE g_short FIXED_SIZE varchar2(12) - F_GLOBAL(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:43",
            "25 CODE l FIXED_SIZE varchar2(30) - F_WIDE(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:44",
            // A record's field passed in PL/SQL, from a %ROWTYPE variable and a cursor loop record: one place.
            "29 CODE l FIXED_SIZE varchar2(12) - P_ASSIGN(P_CODE IN VARCHAR2).P_CODE 2 fmt.sql:47"
            // f_filter only compares its parameter: no place.
        )
        // What each function returns into n or l is counted once, as an expression of the column; f_return returns its
        // parameter, which reaches l as the function's result.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1").map { it.substringBefore(" ") })
            .containsExactly("EXPRESSION_OTHER_ROUTINE:")
        assertThat(Fixture.query(db, "SELECT p.start_line || ' ' || p.target_written || ' ' || p.class FROM column_place p WHERE p.source = 'RETURNED'"))
            .containsExactly("49 l FIXED_SIZE")
    }

    @Test
    fun `an IN parameter selected into a target, passed to a nested subprogram, or to a routine with no PL SQL body`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("more.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));

            create or replace package pk_more as
              procedure p_into(p_code varchar2);
              procedure p_nested(p_code varchar2);
            end;
            /
            create or replace package body pk_more as
              procedure p_into(p_code varchar2) is l varchar2(9);
              begin select p_code into l from dual; end;
              procedure p_nested(p_code varchar2) is
                procedure inner(p_i varchar2) is l varchar2(13); begin l := p_i; end;
              begin inner(p_code); end;
            end;
            /
            create or replace function f_java(p_code varchar2) return number
              as language java name 'Fmt.f(java.lang.String) return int';
            /
            create or replace procedure show_more is
              n number;
              procedure direct(p_d varchar2) is l varchar2(7); begin l := p_d; end;
            begin
              for r in (select code from orders) loop
                pk_more.p_into(r.code);
                pk_more.p_nested(r.code);
                direct(r.code);
              end loop;
              select f_java(code) into n from orders;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' ||
                   coalesce(replace(a.key, 'db:SHOP.PK_MORE.', ''), '-') || ' ' || p.call_count || ' ' || p.call_path || ':' || p.call_line
            FROM column_place p LEFT JOIN entity a ON a.id = p.argument_parameter_id WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line""")).containsExactly(
            // Selected into a local, as an assignment would write it.
            "10 l FIXED_SIZE varchar2(9) P_INTO(P_CODE IN VARCHAR2).P_CODE 1 more.sql:24",
            // Passed on to a nested subprogram, which has no entity: followed where it is declared.
            "12 l FIXED_SIZE varchar2(13) P_NESTED(P_CODE IN VARCHAR2).P_CODE 1 more.sql:25",
            // Passed straight to a nested subprogram of the calling routine.
            "21 l FIXED_SIZE varchar2(7) - 1 more.sql:26"
        )
        // f_java's body is Java: what it is given is counted, as for a routine outside the index.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' AND message LIKE 'ARGUMENT%'")
            .map { it.substringBefore(" not") }).containsExactly("ARGUMENT_ROUTINE_OUTSIDE: 1 column value(s) passed to a routine whose PL/SQL body is")
    }

    @Test
    fun `an IN parameter selected into a record, and calls of routines outside the index counted at each call`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("rec.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));
            create table short_codes (c varchar2(5));

            create or replace package pk_rec as
              procedure p_one(p_code varchar2);
              procedure p_two(p_code varchar2);
              procedure p_rowtype(p_code varchar2);
              procedure d_one;
              procedure d_two;
              procedure d_rowtype;
            end;
            /
            create or replace package body pk_rec as
              type r1_t is record (f varchar2(3));
              type r2_t is record (n number, f varchar2(4));
              procedure p_one(p_code varchar2) is r r1_t; begin select p_code into r from dual; end;
              procedure p_two(p_code varchar2) is r r2_t; begin select 1, p_code into r from dual; end;
              procedure p_rowtype(p_code varchar2) is r short_codes%rowtype; begin select p_code into r from dual; end;
              procedure d_one is r r1_t; begin select code into r from orders where rownum = 1; end;
              procedure d_two is r r2_t; begin select 1, code into r from orders where rownum = 1; end;
              procedure d_rowtype is r short_codes%rowtype; begin select code into r from orders where rownum = 1; end;
            end;
            /
        """.trimIndent())
        Files.writeString(sources.resolve("ext.sql"), """
            create or replace function f_ext(p_code varchar2) return number
              as language java name 'Ext.f(java.lang.String) return int';
            /
        """.trimIndent())
        Files.writeString(sources.resolve("show.sql"), """
            create or replace procedure show_rec is
              n number;
            begin
              for r in (select code from orders) loop
                pk_rec.p_one(r.code);
                pk_rec.p_two(r.code);
                pk_rec.p_rowtype(r.code);
              end loop;
              select f_ext(code) into n from orders;
              select f_ext(code) into n from orders where order_id = 1;
              select count(*) into n from orders where pk_missing.f(code) = 1;
              select count(*) into n from orders where pk_missing.f(code) = 2;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        // The parameter gives the place the column gives in the same position of the same target (the %ROWTYPE's columns
        // are not known without the dictionary, for both).
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.source || ' ' || coalesce(p.field, '-') || ' ' || p.class || ' ' ||
                   coalesce(p.length, -1) || ' ' || coalesce(p.reason, '-')
            FROM column_place p WHERE p.start_line BETWEEN 16 AND 21 ORDER BY p.start_line""")).containsExactly(
            "16 IN_ARGUMENT F FIXED_SIZE 3 -",
            "17 IN_ARGUMENT F FIXED_SIZE 4 -",
            "18 IN_ARGUMENT - UNKNOWN -1 ROWTYPE_COLUMNS_UNKNOWN",
            "19 SELECT_INTO F FIXED_SIZE 3 -",
            "20 SELECT_INTO F FIXED_SIZE 4 -",
            "21 SELECT_INTO - UNKNOWN -1 ROWTYPE_COLUMNS_UNKNOWN"
        )
        // One per call, in the caller's file: the call spec's file counts nothing.
        assertThat(Fixture.query(db, """
            SELECT f.path || ' ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id
            WHERE n.message LIKE 'ARGUMENT%'""").map { it.substringBefore(" column") }).containsExactly("show.sql ARGUMENT_ROUTINE_OUTSIDE: 4")
    }

    @Test
    fun `an IN parameter read in a statement on a table`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("tab.sql"), """
            create or replace package pk_tab as
              procedure p_dual(p_code varchar2);
              procedure p_sel(p_code varchar2);
              procedure p_nvl(p_code varchar2);
              procedure p_ret(p_code varchar2);
            end;
            /
            create or replace package body pk_tab as
              procedure p_dual(p_code varchar2) is l varchar2(7); begin select p_code into l from dual; end;
              procedure p_sel(p_code varchar2) is l varchar2(8); begin select p_code into l from orders where rownum = 1; end;
              procedure p_nvl(p_code varchar2) is l varchar2(9); begin select nvl(alt_code, p_code) into l from orders where rownum = 1; end;
              procedure p_ret(p_code varchar2) is l varchar2(10); begin update orders set order_id = 1 where rownum = 1 returning p_code into l; end;
            end;
            /
            create or replace procedure show_tab is
            begin
              for r in (select code from orders) loop
                pk_tab.p_dual(r.code);
                pk_tab.p_sel(r.code);
                pk_tab.p_nvl(r.code);
                pk_tab.p_ret(r.code);
              end loop;
            end;
            /
        """.trimIndent())
        fun type(length: Int?) = ProjectExternalDatatype.of(if (length == null) "NUMBER" else "VARCHAR2", null, length,
            length?.let { LengthSemantics.BYTE }, if (length == null) 10 else null, if (length == null) 0 else null)
        val orders = ProjectExternalRelation.of("SHOP", "ORDERS", true, listOf(
            ProjectExternalColumn.of("ORDER_ID", 1, type(null)), ProjectExternalColumn.of("CODE", 2, type(20)),
            ProjectExternalColumn.of("ALT_CODE", 3, type(20))))
        val known = AtlasDictionary.of("tab", listOf("SHOP"), listOf(DictionaryRelation(orders, DictionaryRelationKind.TABLE)))
        val places = "SELECT p.start_line || ' ' || p.class || ' ' || coalesce(p.length, -1) || ' ' || coalesce(p.functions, '-') " +
            "FROM column_place p WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line"
        val notes = "SELECT message FROM coverage_note WHERE message LIKE 'ARGUMENT%'"
        val withColumns = temp.resolve("known.db")
        Indexer().index(IndexOptions(sources, withColumns, compilationSchema = "SHOP", dictionary = known, threads = 1))
        // The dictionary lists the columns of orders, none named p_code: the name is the parameter.
        assertThat(Fixture.query(withColumns, places)).containsExactly("9 FIXED_SIZE 7 -", "10 FIXED_SIZE 8 -", "11 FIXED_SIZE 9 NVL", "12 FIXED_SIZE 10 -")
        assertThat(Fixture.query(withColumns, notes)).isEmpty()
        // The columns not known (no dictionary; the table only in the sources, or nowhere): p_code may be a column of orders.
        for (table in listOf(true, false)) {
            if (table) Files.writeString(sources.resolve("orders.sql"), "create table orders (order_id number(10), code varchar2(20 byte), alt_code varchar2(20 byte));\n")
            else Files.delete(sources.resolve("orders.sql"))
            val withoutColumns = temp.resolve("unknown-$table.db")
            Indexer().index(IndexOptions(sources, withoutColumns, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
            assertThat(Fixture.query(withoutColumns, places)).containsExactly("9 FIXED_SIZE 7 -")
            assertThat(Fixture.query(withoutColumns, notes).map { it.substringBefore(" parameter") }).containsExactly("ARGUMENT_COLUMN_POSSIBLE: 3")
        }
    }

    @Test
    fun `the expressions a parameter is written within, and the fields whose column is not known, are counted by what they are`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("split.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_split as
              procedure p_concat(p_code varchar2);
              procedure p_substr(p_code varchar2);
              procedure p_to_char(p_code varchar2);
              procedure p_length(p_code varchar2);
              procedure p_case(p_code varchar2);
              procedure p_other(p_code varchar2);
              procedure p_rec(p_code varchar2);
            end;
            /
            create or replace package body pk_split as
              procedure p_concat(p_code varchar2) is l varchar2(30); begin l := p_code || '-'; end;
              procedure p_substr(p_code varchar2) is l varchar2(30); begin l := substr(p_code, 1, 3); end;
              procedure p_to_char(p_code varchar2) is l varchar2(30); begin l := to_char(p_code); end;
              procedure p_length(p_code varchar2) is n number; begin n := length(p_code); end;
              procedure p_case(p_code varchar2) is l varchar2(30); begin l := case when p_code is null then 'x' else p_code end; end;
              procedure p_other(p_code varchar2) is l varchar2(30); begin l := replace(p_code, 'a', 'b'); end;
              procedure p_rec(p_code varchar2) is begin null; end;
            end;
            /
            create or replace procedure show_split is
              cursor c_expr is select code || note as joined, upper(code) as up from orders;
              r_unknown unknown_tab%rowtype;
            begin
              for r in (select code from orders) loop
                pk_split.p_concat(r.code);
                pk_split.p_substr(r.code);
                pk_split.p_to_char(r.code);
                pk_split.p_length(r.code);
                pk_split.p_case(r.code);
                pk_split.p_other(r.code);
              end loop;
              for x in c_expr loop
                pk_split.p_rec(x.joined);
                pk_split.p_rec(x.up);
              end loop;
              for y in (select nvl(note, code) as n from orders) loop
                pk_split.p_rec(y.n);
              end loop;
              select * into r_unknown from unknown_tab where rownum = 1;
              pk_split.p_rec(r_unknown.code);
            end;
            /
            create or replace package pk_cur as
              cursor c_spec return orders%rowtype;
            end;
            /
            create or replace procedure show_more is
              r_anchor nowhere.missing_tab%rowtype;
              r_spec pk_cur.c_spec%rowtype;
            begin
              pk_split.p_rec(r_anchor.code);
              pk_split.p_rec(r_spec.code);
              for s in (select * from missing_tab) loop
                pk_split.p_rec(s.code);
              end loop;
              for z in (select 'a' as lit from dual) loop
                pk_split.p_rec(z.lit);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("split.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        // x.up is read through UPPER and x.joined (code || note) and p_concat's p_code || '-' are concatenations, which
        // the index follows; r_unknown's relation is not known but its field is named:
        // the column is told by name, and p_rec writes it nowhere. Neither is counted; nor is r_anchor, whose type the
        // index does not resolve at all.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_COLUMN_NOT_KNOWN_CURSOR: 1",                      // r_spec, a cursor declared with no query
            "ARGUMENT_COLUMN_NOT_KNOWN_EXPRESSION_NVL: 1",              // y.n, nvl(note, code)
            "ARGUMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1",                   // z.lit, a literal
            "ARGUMENT_COLUMN_NOT_KNOWN_STAR: 1",                        // s.code, select * from a table not known
            "ARGUMENT_EXPRESSION_CASE: 1",
            "ARGUMENT_EXPRESSION_LENGTH: 1",
            "ARGUMENT_EXPRESSION_OTHER_REPLACE: 1",                     // replace(p_code, …)
            "ARGUMENT_EXPRESSION_SUBSTR: 1",
            "ARGUMENT_EXPRESSION_TO_CHAR: 1"
        )
    }

    @Test
    fun `a column's value concatenated into a target is a place of the column, which says so`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("cat.sql"), CONCATENATIONS)
        val db = temp.resolve("cat.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' ||
                   coalesce(p.length, -1) || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, c.key""")).containsExactly(
            "12 l CODE IN_ARGUMENT FIXED_SIZE 11 - 1",          // k7  'C:' || p_code
            "13 l CODE IN_ARGUMENT FIXED_SIZE 12 UPPER 1",      // k8  upper(p_code) || '-' || nvl(p_code, '?'): one place, by its first part
            "15 l CODE IN_ARGUMENT FIXED_SIZE 14 - 1",          // k10 concat(p_code, 'x')
            "16 l CODE IN_ARGUMENT FIXED_SIZE 15 - 1",          // k11 select p_code || 'x' into l
            "29 l1 CODE SELECT_INTO FIXED_SIZE 16 - 1",         // k1
            "30 l2 CODE SELECT_INTO FIXED_SIZE 17 - 1",         // k2, a place for each column
            "30 l2 NOTE SELECT_INTO FIXED_SIZE 17 - 1",
            "31 l3 CODE SELECT_INTO FIXED_SIZE 18 UPPER 1",     // k3 upper(code || 'x')
            "33 l4 CODE ASSIGNMENT FIXED_SIZE 19 - 1",          // k4 r.code || '#'
            "34 l5 CODE FETCH FIXED_SIZE 20 - 1",               // k5 the cursor's item code || '/'
            "35 l6 CODE RETURNING FIXED_SIZE 10 - 1",           // k6
            "47 o_text CODE SELECT_INTO CALLER_DECIDES -1 - 1", // an OUT parameter ...
            "55 l7 CODE OUT_ARGUMENT FIXED_SIZE 21 - 1",        // ... and its caller's argument
            "57 l8 CODE ASSIGNMENT FIXED_SIZE 22 - 1",          // r.code || r.note, a place for each field
            "57 l8 NOTE ASSIGNMENT FIXED_SIZE 22 - 1"
        )
        // k9: substr(p_code || 'x', 1, 5), the concatenation inside a function the index does not follow.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'").map { it.substringBefore(" parameter") })
            .containsExactly("ARGUMENT_EXPRESSION_SUBSTR: 1")
    }

    @Test
    fun `the largest not followed counts say what they are made of`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("m.sql"), MADE_OF)
        val db = temp.resolve("m.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1",                       // pk_m.take(x.lit), a literal
            "ARGUMENT_EXPRESSION_OTHER_ARITHMETIC: 1",                      // to_number(p_code) + 1
            "ARGUMENT_EXPRESSION_OTHER_DECODE: 1",                          // decode(p_code, …)
            "EXPRESSION_OTHER_ARITHMETIC: 1",                               // order_id * 2
            "EXPRESSION_OTHER_DECODE: 1",                                   // decode(code, 'a', 'b')
            "EXPRESSION_OTHER_ROUTINE: 1",                                  // pk_m.fmt(code)
            "FETCH_CURSOR_NOT_KNOWN_OPENED_DYNAMIC: 1",                     // d, opened for a string
            "FETCH_CURSOR_NOT_KNOWN_OPENED_STATIC: 1",                      // c, opened for a query
            "FETCH_CURSOR_NOT_KNOWN_PARAMETER: 1",                          // from_param's p
            "FETCH_CURSOR_NOT_KNOWN_STRONG: 1",                             // from_strong's p, of order_cur
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_EXPRESSION_OTHER_DECODE: 1", // w.dc
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 2",              // x.v (extractvalue over table(…)), x.rn (rownum)
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_REF_CURSOR: 1"              // r.code, r p%rowtype
        )
        // Places already: lower(…) is a value function the index follows (take's l := lower(p_code), the select's
        // lower(code)); unknown_tab's code is taken by name as a column of it, so y.code is followed; z.code is the
        // code of orders the inline view reads; pk_m.fmt(code) returns its parameter into l.
        assertThat(Fixture.query(db, "SELECT p.start_line || ' ' || p.source FROM column_place p ORDER BY p.start_line"))
            .containsExactly("18 IN_ARGUMENT", "48 ASSIGNMENT", "52 ASSIGNMENT", "57 RETURNED", "58 SELECT_INTO")
    }

    @Test
    fun `a column read through an inline view or a WITH query gives the place a direct read gives`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("iv.sql"), INLINE_VIEWS)
        val db = temp.resolve("iv.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        fun places(from: Int, to: Int) = Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || coalesce('.' || p.field, '') || ' ' || replace(replace(c.key, 'db:SHOP.', ''), 'ORDERS.', '') || ' ' ||
                   p.source || ' ' || p.class || ' ' || coalesce(p.length, '-') || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.start_line BETWEEN $from AND $to ORDER BY p.start_line, p.start_col, c.key""")
        assertThat(places(1, 46)).containsExactly(
            "9 l CODE IN_ARGUMENT FIXED_SIZE 5 - 0",                // take's l := p_code, from pk_iv.take(z.code)
            "20 l1 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v1 v.code
            "21 l2 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v2 bare name
            "22 l3 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v3 renamed
            "23 l4 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v4 WITH
            "24 l5 CODE SELECT_INTO FIXED_SIZE 5 - 0",              //    WITH read by another
            "25 l6 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v5 nested
            "26 l7 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v6 union: both columns
            "26 l7 NOTE SELECT_INTO FIXED_SIZE 5 - 0",
            "27 l8 CODE SELECT_INTO FIXED_SIZE 5 UPPER 0",          // v7
            "28 l9 CODE SELECT_INTO FIXED_SIZE 5 NVL 0",
            "29 l10 CODE SELECT_INTO FIXED_SIZE 5 - 1",
            "31 r_all.CODE CODE SELECT_INTO ANCHORED_TO_COLUMN - - 0", // v9 * of the view into orders%rowtype
            "31 r_all.NOTE NOTE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "31 r_all.ORDER_ID ORDER_ID SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "32 l12 CODE SELECT_INTO FIXED_SIZE 5 - 0",             //    a view joined with a table
            "32 l13 ITEMS.SKU SELECT_INTO FIXED_SIZE 5 - 0",
            "34 l1 CODE ASSIGNMENT FIXED_SIZE 5 - 0",               // v10 cursor loop
            "37 r_iv.CODE CODE FETCH ANCHORED_TO_COLUMN - - 0",     //     FETCH into the cursor's %ROWTYPE
            "38 l2 CODE ASSIGNMENT FIXED_SIZE 5 - 0",
            "40 l3 CODE ASSIGNMENT FIXED_SIZE 5 - 0",               //     a loop over a WITH query
            "45 l5 CODE SELECT_INTO FIXED_SIZE 5 MAX 0"             // v12 MAX is a value function; COUNT gives nothing
        )
        // The same reads made directly give the same places.
        assertThat(places(52, 70).map { it.substringAfter(' ') }).containsExactly(
            "l8 CODE SELECT_INTO FIXED_SIZE 5 UPPER 0",
            "l9 CODE SELECT_INTO FIXED_SIZE 5 NVL 0",
            "l10 CODE SELECT_INTO FIXED_SIZE 5 - 1",
            "r_all.CODE CODE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_all.NOTE NOTE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_all.ORDER_ID ORDER_ID SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_iv.CODE CODE FETCH ANCHORED_TO_COLUMN - - 0"
        )
        // v8 is counted under the shape a direct replace(code, …) has, in both procedures; v11 under _NO_COLUMN.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") })
            .containsExactly("EXPRESSION_OTHER_REPLACE: 2", "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1")
    }

    @Test
    fun `an expression is named by an Oracle built-in only, a collection element and any other call apart`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("names.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_names as
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_names as
              procedure take(p_code varchar2) is
                type t_map is table of varchar2(30) index by varchar2(20);
                m t_map;
                l varchar2(30);
              begin
                l := fmt_code(p_code);
                l := m(p_code);
                l := lpad(p_code, 30);
              end;
            end;
            /
            create or replace procedure show_names is
              l varchar2(30);
            begin
              select fmt_code(code), lpad(code, 30) into l, l from orders where rownum = 1;
              for r in (select code from orders) loop
                pk_names.take(r.code);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("names.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        // fmt_code is not in the index: a routine, not named; m(p_code) is an element of the local collection m.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_EXPRESSION_OTHER_ELEMENT: 1",
            "ARGUMENT_EXPRESSION_OTHER_LPAD: 1",
            "ARGUMENT_EXPRESSION_OTHER_ROUTINE: 1",
            "EXPRESSION_OTHER_LPAD: 1",
            "EXPRESSION_OTHER_ROUTINE: 1"
        )
    }

    @Test
    fun `a column's value a function returns reaches the targets its callers write the result into`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("pk_ret.sql"), RETURNS_PACKAGE)
        Files.writeString(sources.resolve("show_ret.sql"), RETURNS_CALLER)
        Files.writeString(sources.resolve("show_more.sql"), """
            create or replace package pk_ret2 as
              type t_rec is record (code varchar2(20));
              function f_rec(p_id number) return t_rec;
              function f_self(p_code varchar2, n number) return varchar2;
              function f_ping(p_code varchar2, n number) return varchar2;
              function f_pong(p_code varchar2, n number) return varchar2;
            end;
            /
            create or replace package body pk_ret2 as
              function f_rec(p_id number) return t_rec is r t_rec; begin select code into r from orders where order_id = p_id; return r; end;
              function f_self(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return f_self(p_code, n - 1); end if;
                return p_code;
              end;
              function f_ping(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return f_pong(p_code, n - 1); end if;
                return p_code;
              end;
              function f_pong(p_code varchar2, n number) return varchar2 is begin return f_ping(p_code, n); end;
            end;
            /
            create or replace procedure show_more is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              r pk_ret2.t_rec;
            begin
              for x in (select code from orders) loop
                l1 := pk_ret.f_cut(x.code);
                l2 := pk_ret2.f_self(x.code, 3);
                l3 := pk_ret2.f_ping(x.code, 3);
              end loop;
              l4 := pk_ret.f_short(1);
              l5 := pk_ret.f_anchored(1);
              r := pk_ret2.f_rec(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("ret.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // Column, target, class, length, functions, concatenated, the function (or the routine of the parameter), calls.
        assertThat(Fixture.query(db, RETURNED_PLACES)).containsExactly(
            "pk_ret.sql:67 l CODE IN_ARGUMENT FIXED_SIZE 5 - 0 PK_RET.TAKE(P_CODE IN VARCHAR2) 1 show_ret.sql:23",     // c15
            "show_more.sql:30 l2 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET2.F_SELF(P_CODE IN VARCHAR2,N IN NUMBER) 1 show_more.sql:30",
            "show_more.sql:31 l3 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET2.F_PING(P_CODE IN VARCHAR2,N IN NUMBER) 1 show_more.sql:31",
            "show_more.sql:33 l4 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_SHORT(P_ID IN NUMBER) 1 show_more.sql:33",       // r10
            "show_more.sql:34 l5 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_ANCHORED(P_ID IN NUMBER) 1 show_more.sql:34",    // r11
            "show_ret.sql:8 l1 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_PARAM(P_CODE IN VARCHAR2) 1 show_ret.sql:8",       // c1
            "show_ret.sql:9 l2 CODE RETURNED FIXED_SIZE 5 UPPER 0 PK_RET.F_UPPER(P_CODE IN VARCHAR2) 1 show_ret.sql:9",   // c2
            "show_ret.sql:10 l3 CODE RETURNED FIXED_SIZE 5 - 1 PK_RET.F_TAGGED(P_CODE IN VARCHAR2) 1 show_ret.sql:10",    // c3
            "show_ret.sql:12 l13 CODE RETURNED FIXED_SIZE 5 - 0 - 1 show_ret.sql:12",                                     // c13, nested
            "show_ret.sql:14 l4 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:14",           // c4
            "show_ret.sql:15 l5 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET_ROW(P_ID IN NUMBER) 1 show_ret.sql:15",       // c5
            "show_ret.sql:16 l6 NOTE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET_CUR(P_ID IN NUMBER) 1 show_ret.sql:16",       // c6
            "show_ret.sql:17 l7 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_TWO(P_ID IN NUMBER) 1 show_ret.sql:17",           // c7
            "show_ret.sql:17 l7 NOTE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_TWO(P_ID IN NUMBER) 1 show_ret.sql:17",
            "show_ret.sql:18 l8 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_CHAIN(P_ID IN NUMBER) 1 show_ret.sql:18",         // c8
            "show_ret.sql:19 l9 CODE RETURNED FIXED_SIZE 5 NVL 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:19",         // c9
            "show_ret.sql:20 l10 CODE RETURNED FIXED_SIZE 5 - 1 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:20",          // c10
            "show_ret.sql:22 l14 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:22",          // c14, in SQL
            "show_ret.sql:24 p_out CODE RETURNED CALLER_DECIDES - - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:24"     // c16
        )
        assertThat(Fixture.query(db, "SELECT f.path || ' ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id WHERE n.kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1")
            .map { it.substringBefore(": ") + ": " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "pk_ret.sql ARGUMENT_EXPRESSION_SUBSTR: 1",     // f_cut's substr(p_code, 1, 3), from show_more's f_cut(x.code)
            "show_more.sql RETURNED_NOT_SCALAR: 1",         // r := f_rec(1), a record
            "show_ret.sql ARGUMENT_ROUTINE_UNDECIDED: 1",   // c12: zpa leaves the overloaded f_over AMBIGUOUS
            "show_ret.sql EXPRESSION_OTHER_ROUTINE: 1",     // c14's item: order_id passed to f_get, as today
            "show_ret.sql RETURNED_EXPRESSION_SUBSTR: 1"    // c11
        )
    }

    @Test
    @Timeout(60)
    fun `a value that goes round through OUT parameters, concatenated on each turn, is followed once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        // p_a gives its OUT parameter a column and passes it to p_b, which writes into it what p_a gives it, concatenated.
        Files.writeString(sources.resolve("msg.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_msg as
              procedure p_a(o_msg out varchar2);
              procedure p_b(o_msg in out varchar2);
            end;
            /
            create or replace package body pk_msg as
              procedure p_a(o_msg out varchar2) is
              begin
                select code into o_msg from orders where rownum = 1;
                p_b(o_msg);
              end;
              procedure p_b(o_msg in out varchar2) is
                l varchar2(100);
              begin
                p_a(l);
                o_msg := upper(o_msg) || l;
              end;
            end;
            /
            create or replace procedure show_msg is
              l varchar2(5);
            begin
              pk_msg.p_a(l);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("msg.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // The caller's l takes the column as is and concatenated, each once: going round again adds nothing.
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || p.source || ' ' || p.class || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p WHERE p.start_line = 25 ORDER BY 1""")).containsExactly(
            "25 l OUT_ARGUMENT FIXED_SIZE - 0",
            "25 l OUT_ARGUMENT FIXED_SIZE - 1",
            "25 l OUT_ARGUMENT FIXED_SIZE UPPER 1"
        )
    }

    @Test
    @Timeout(60)
    fun `a cycle that mixes value functions and concatenations writes each place once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("mix.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_c as
              procedure p1(o out varchar2);
              procedure p2(o in out varchar2);
              procedure p3(o in out varchar2);
            end;
            /
            create or replace package body pk_c as
              procedure p1(o out varchar2) is
                l varchar2(100);
              begin
                select note into o from orders where rownum = 1;
                p2(o);
                p1(l);
                o := o || l;
              end;
              procedure p2(o in out varchar2) is
              begin
                o := upper(o);
                p3(o);
              end;
              procedure p3(o in out varchar2) is
                l varchar2(100);
              begin
                o := trim(o);
                p1(l);
                o := lower(l) || o;
              end;
            end;
            /
            create or replace procedure show_c is
              y varchar2(5);
            begin
              pk_c.p1(y);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("mix.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // TRIM,LOWER is reached with the concatenation at two points of the chain: one row.
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' ||
                   coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.target_written = 'y' ORDER BY 1""")).containsExactly(
            "y NOTE OUT_ARGUMENT FIXED_SIZE - 0",
            "y NOTE OUT_ARGUMENT FIXED_SIZE - 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE LOWER 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE LOWER,TRIM 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE TRIM,LOWER 1"
        )
        // No place row, the two l locals' included, is written twice.
        assertThat(Fixture.query(db, """
            SELECT count(*) FROM column_place GROUP BY file_id, start_line, start_col, end_line, end_col, column_id, source, target_entity_id,
                field, class, functions, concatenated, argument_parameter_id, call_path, call_line HAVING count(*) > 1""")).isEmpty()
    }

    @Test
    @Timeout(60)
    fun `a cycle that wraps the value in a function on each turn is followed once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("cycles.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace function f_rec(n number) return varchar2 is
              l varchar2(20);
            begin
              if n = 0 then
                select code into l from orders where rownum = 1;
                return l;
              end if;
              return nvl(f_rec(n - 1), 'x');
            end;
            /
            create or replace function f_var(n number) return varchar2 is
              l varchar2(20);
            begin
              if n = 0 then
                select note into l from orders where rownum = 1;
              else
                l := 'x' || f_var(n - 1);
              end if;
              return l;
            end;
            /
            create or replace package pk_cycle as
              function f_ping(p_code varchar2, n number) return varchar2;
              function f_pong(p_code varchar2, n number) return varchar2;
            end;
            /
            create or replace package body pk_cycle as
              function f_ping(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return lower(f_pong(p_code, n - 1)); end if;
                return p_code;
              end;
              function f_pong(p_code varchar2, n number) return varchar2 is
              begin
                return upper(f_ping(trim(p_code), n));
              end;
            end;
            /
            create or replace procedure p_rec(p_code varchar2, n number) is
              l varchar2(6);
            begin
              l := p_code;
              if n > 0 then p_rec(upper(p_code), n - 1); end if;
            end;
            /
            create or replace procedure show_cycles is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5);
            begin
              l1 := f_rec(3);
              l2 := f_var(3);
              for r in (select code from orders) loop
                l3 := pk_cycle.f_ping(r.code, 3);
                p_rec(r.code, 3);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("cycles.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // Each target once per column, through the path that does not go round the cycle.
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' ||
                   p.class || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.source IN ('RETURNED', 'IN_ARGUMENT') ORDER BY p.start_line, c.key""")).containsExactly(
            // f_var's l := 'x' || f_var(…): its own result written back into its local, once.
            "19 l NOTE RETURNED FIXED_SIZE - 1",
            "44 l CODE IN_ARGUMENT FIXED_SIZE - 0",       // p_rec's l := p_code; p_rec(upper(p_code)) only goes round
            "51 l1 CODE RETURNED FIXED_SIZE - 0",         // f_rec returns l; nvl(f_rec(…)) only goes round
            "52 l2 NOTE RETURNED FIXED_SIZE - 0",         // f_var returns l
            "54 l3 CODE RETURNED FIXED_SIZE - 0"          // f_ping returns p_code; through f_pong only goes round
        )
    }

    @Test
    fun `a value passed to a routine comes back out at the call that passed it, a column the routine reads at every call`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("contexts.sql"), """
            create or replace package pk_ctx as
              function f_direct(p varchar2) return varchar2;
              function f_held(p varchar2) return varchar2;
              procedure p_out(p_in varchar2, p_res out varchar2);
              procedure p_out_f(p_in varchar2, p_res out varchar2);
              function f_get(p_id number) return varchar2;
              function f_mixed(p varchar2, p_id number) return varchar2;
            end;
            /
            create or replace package body pk_ctx as
              function f_direct(p varchar2) return varchar2 is begin return p; end;
              function f_held(p varchar2) return varchar2 is l varchar2(100); begin l := p; return l; end;
              procedure p_out(p_in varchar2, p_res out varchar2) is begin p_res := p_in; end;
              procedure p_out_f(p_in varchar2, p_res out varchar2) is begin p_res := f_held(p_in); end;
              function f_get(p_id number) return varchar2 is l varchar2(20);
              begin select code into l from orders where order_id = p_id; return l; end;
              function f_mixed(p varchar2, p_id number) return varchar2 is l varchar2(40);
              begin
                if p_id > 0 then select note into l from orders where order_id = p_id; else l := p; end if;
                return l;
              end;
            end;
            /
            create or replace procedure show_ctx is
              a1 varchar2(5); a2 varchar2(5); b1 varchar2(5); b2 varchar2(5);
              o1 varchar2(5); o2 varchar2(5); q1 varchar2(5); q2 varchar2(5);
              g1 varchar2(5); g2 varchar2(5); m1 varchar2(5); m2 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                a1 := pk_ctx.f_direct(r.code);
                a2 := pk_ctx.f_direct(r.note);
                b1 := pk_ctx.f_held(r.code);
                b2 := pk_ctx.f_held(r.note);
                pk_ctx.p_out(r.code, o1);
                pk_ctx.p_out(r.note, o2);
                pk_ctx.p_out_f(r.code, q1);
                pk_ctx.p_out_f(r.note, q2);
                m1 := pk_ctx.f_mixed(r.code, 0);
                m2 := pk_ctx.f_mixed(r.note, 0);
              end loop;
              g1 := pk_ctx.f_get(1);
              g2 := pk_ctx.f_get(2);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("contexts.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || coalesce(' calls ' || p.call_count, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT') ORDER BY p.target_written, c.key""")).containsExactly(
            // Each call's target takes the column that call passed in, and the columns the routine reads itself.
            "a1 CODE RETURNED calls 1", "a2 NOTE RETURNED calls 1",
            "b1 CODE RETURNED calls 1", "b2 NOTE RETURNED calls 1",
            "g1 CODE RETURNED calls 1", "g2 CODE RETURNED calls 1",
            // Inside the routines (f_held's and f_mixed's l, p_out's and p_out_f's p_res), every column passed to them, as before.
            "l CODE IN_ARGUMENT calls 1", "l CODE IN_ARGUMENT calls 1", "l NOTE IN_ARGUMENT calls 1", "l NOTE IN_ARGUMENT calls 1",
            // m1: the column it passed, and the one f_mixed reads; m2: one place, whether passed or read inside.
            "m1 CODE RETURNED calls 1", "m1 NOTE RETURNED calls 1", "m2 NOTE RETURNED calls 1",
            "o1 CODE OUT_ARGUMENT", "o2 NOTE OUT_ARGUMENT",
            "p_res CODE IN_ARGUMENT calls 1", "p_res CODE RETURNED calls 1", "p_res NOTE IN_ARGUMENT calls 1", "p_res NOTE RETURNED calls 1",
            // Through two levels: p_out_f passes its parameter into f_held and gives the result back through its own OUT parameter.
            "q1 CODE OUT_ARGUMENT", "q2 NOTE OUT_ARGUMENT"
        )
    }

    @Test
    fun `a value kept beyond the call, the same function nested, and a cycle still come back out`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("kept.sql"), """
            create or replace package pk_keep as
              procedure keep(p varchar2);
              function last_kept return varchar2;
              function f_id(p varchar2) return varchar2;
              function f_outer(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_keep as
              g_last varchar2(100);
              procedure keep(p varchar2) is begin g_last := p; end;
              function last_kept return varchar2 is begin return g_last; end;
              function f_id(p varchar2) return varchar2 is begin return p; end;
              function f_outer(p varchar2) return varchar2 is
                l_held varchar2(100);
                procedure inner_set(x varchar2) is begin l_held := x; end;
              begin
                inner_set(p);
                return l_held;
              end;
            end;
            /
            create or replace package cy_a as function f(p varchar2, n number) return varchar2; end;
            /
            create or replace package cy_b as function g(p varchar2, n number) return varchar2; end;
            /
            create or replace package body cy_a as
              function f(p varchar2, n number) return varchar2 is l varchar2(30);
              begin
                if n = 0 then return p; end if;
                l := cy_b.g(p || 'a', n - 1);
                return 'x' || l;
              end;
            end;
            /
            create or replace package body cy_b as
              function g(p varchar2, n number) return varchar2 is
              begin return trim(cy_a.f(upper(p), n)); end;
            end;
            /
            create or replace procedure show_keep is
              k1 varchar2(5); k2 varchar2(5); k3 varchar2(5); k5 varchar2(5); k6 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                pk_keep.keep(r.note);
                k2 := pk_keep.f_outer(r.code);
                k3 := pk_keep.f_id(pk_keep.f_id(r.note));
                k5 := cy_a.f(r.code, 3);
              end loop;
              k1 := pk_keep.last_kept;
              k6 := pk_keep.f_id(pk_keep.f_id('x'));
            end;
            /
        """.trimIndent())
        val db = temp.resolve("kept.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || coalesce(p.functions, '-')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.target_written IN ('k1', 'k2', 'k3', 'k5', 'k6', 'l') ORDER BY p.target_written, c.key, p.functions""")).containsExactly(
            // A package variable written by keep, returned by last_kept: for every caller of last_kept.
            "k1 NOTE RETURNED -",
            // A variable of f_outer written by its nested inner_set: for every caller of f_outer.
            "k2 CODE RETURNED -",
            // f_id's result passed to f_id again: the column the inner call passed.
            "k3 NOTE RETURNED -",
            "k5 CODE RETURNED -",
            // Inside the cycle: what f returns at the bottom, through g's trim, without going round again.
            "l CODE RETURNED TRIM"
        )
    }

    @Test
    fun `a value comes back out through OUT parameters and the locals that hold it, to the call that passed it`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("leftovers.sql"), """
            create or replace package pk_fl as
              procedure keep(p varchar2);
              procedure out_kept(p_res out varchar2);
              procedure out_local(p_in varchar2, p_res out varchar2);
              procedure out_read(p_id number, p_res out varchar2);
              function f_via_out(p varchar2) return varchar2;
              function f_via_out_read(p_id number) return varchar2;
              procedure out_via_out(p_in varchar2, p_res out varchar2);
            end;
            /
            create or replace package body pk_fl as
              g_kept varchar2(100);
              procedure keep(p varchar2) is begin g_kept := p; end;
              procedure out_kept(p_res out varchar2) is begin p_res := g_kept; end;
              procedure out_local(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := l; end;
              procedure out_read(p_id number, p_res out varchar2) is begin select code into p_res from orders where order_id = p_id; end;
              function f_via_out(p varchar2) return varchar2 is l varchar2(100); begin out_local(p, l); return l; end;
              function f_via_out_read(p_id number) return varchar2 is l varchar2(100); begin out_read(p_id, l); return l; end;
              procedure out_via_out(p_in varchar2, p_res out varchar2) is begin out_local(p_in, p_res); end;
            end;
            /
            create or replace procedure show_fl is
              e1 varchar2(5); e2 varchar2(5); e3 varchar2(5); e4 varchar2(5); e5 varchar2(5);
              e6 varchar2(5); e7 varchar2(5); e8 varchar2(5); e9 varchar2(5); e10 varchar2(5);
              e11 varchar2(5); e12 varchar2(5);
              function inner_l(x varchar2) return varchar2 is l varchar2(100); begin l := x; return l; end;
              procedure inner_o(x varchar2, o out varchar2) is l varchar2(100); begin l := x; o := l; end;
              procedure inner_d(x varchar2, o out varchar2) is begin o := x; end;
            begin
              for r in (select code, note from orders) loop
                pk_fl.keep(r.note);
                e2 := inner_l(r.code);
                e3 := inner_l(r.note);
                inner_o(r.code, e4);
                inner_o(r.note, e5);
                inner_d(r.code, e6);
                inner_d(r.note, e7);
                pk_fl.out_local(r.code, e8);
                e9 := pk_fl.f_via_out(r.code);
                e10 := pk_fl.f_via_out(r.note);
                pk_fl.out_via_out(r.note, e12);
              end loop;
              pk_fl.out_kept(e1);
              e11 := pk_fl.f_via_out_read(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("leftovers.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class ||
                   coalesce(' ' || p.length, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT') ORDER BY p.start_line, p.target_written, c.key""")).containsExactly(
            // Inside the routines, every column that reaches a target across the calls.
            "13 g_kept NOTE IN_ARGUMENT FIXED_SIZE 100",
            "14 p_res NOTE IN_ARGUMENT CALLER_DECIDES",                                         // out_kept's, from the package variable
            "15 l CODE IN_ARGUMENT FIXED_SIZE 100", "15 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "15 p_res CODE IN_ARGUMENT CALLER_DECIDES", "15 p_res NOTE IN_ARGUMENT CALLER_DECIDES", // out_local's, from its local
            "17 l CODE OUT_ARGUMENT FIXED_SIZE 100", "17 l NOTE OUT_ARGUMENT FIXED_SIZE 100",     // f_via_out's, from out_local
            "18 l CODE OUT_ARGUMENT FIXED_SIZE 100",                                            // f_via_out_read's, read in out_read
            "19 p_res NOTE OUT_ARGUMENT CALLER_DECIDES",                                        // out_via_out's, given out_local's
            "26 l CODE IN_ARGUMENT FIXED_SIZE 100", "26 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "27 l CODE IN_ARGUMENT FIXED_SIZE 100", "27 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "27 o CODE IN_ARGUMENT CALLER_DECIDES", "27 o NOTE IN_ARGUMENT CALLER_DECIDES",
            "28 o CODE IN_ARGUMENT CALLER_DECIDES", "28 o NOTE IN_ARGUMENT CALLER_DECIDES",
            // Each call's target: the column that call passed in, never another call's.
            "32 e2 CODE RETURNED FIXED_SIZE 5",          // a nested function returning its own local
            "33 e3 NOTE RETURNED FIXED_SIZE 5",
            "34 e4 CODE OUT_ARGUMENT FIXED_SIZE 5",      // a nested procedure's OUT parameter given its local
            "35 e5 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "36 e6 CODE OUT_ARGUMENT FIXED_SIZE 5",      // given the parameter itself
            "37 e7 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "38 e8 CODE OUT_ARGUMENT FIXED_SIZE 5",      // a package procedure's OUT parameter given its local
            "39 e9 CODE RETURNED FIXED_SIZE 5",          // back through an OUT argument, then out by RETURN
            "40 e10 NOTE RETURNED FIXED_SIZE 5",
            "41 e12 NOTE OUT_ARGUMENT FIXED_SIZE 5",     // an OUT parameter passed as another routine's OUT argument
            // A column kept in a package variable, or read inside: for every caller.
            "43 e1 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "44 e11 CODE RETURNED FIXED_SIZE 5"
        )
    }

    @Test
    fun `a local given out through a value function or read inside reaches the callers, one within another expression is counted`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("held.sql"), """
            create or replace package pk_h as
              function f_upper(p varchar2) return varchar2;
              function f_nvl(p varchar2) return varchar2;
              function f_trim(p varchar2) return varchar2;
              function f_upper_p(p varchar2) return varchar2;
              procedure out_upper(p_in varchar2, p_res out varchar2);
              procedure out_read(p_id number, p_res out varchar2);
              function f_read(p_id number) return varchar2;
              procedure out_substr(p_in varchar2, p_res out varchar2);
              function f_substr(p varchar2) return varchar2;
              function f_lower(p varchar2) return varchar2;
              function f_rtrim(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_h as
              function f_upper(p varchar2) return varchar2 is l varchar2(100); begin l := p; return upper(l); end;
              function f_nvl(p varchar2) return varchar2 is l varchar2(100); begin l := p; return nvl(l, 'x'); end;
              function f_trim(p varchar2) return varchar2 is l varchar2(100); begin l := p; return trim(l); end;
              function f_upper_p(p varchar2) return varchar2 is begin return upper(p); end;
              procedure out_upper(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := upper(l); end;
              procedure out_read(p_id number, p_res out varchar2) is l varchar2(100);
              begin select code into l from orders where order_id = p_id; p_res := l; end;
              function f_read(p_id number) return varchar2 is l varchar2(100);
              begin select code into l from orders where order_id = p_id; return l; end;
              procedure out_substr(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := substr(l, 1, 3); end;
              function f_substr(p varchar2) return varchar2 is l varchar2(100); begin l := p; return substr(l, 1, 3); end;
              function f_lower(p varchar2) return varchar2 is l varchar2(100); begin l := p; return lower(l); end;
              function f_rtrim(p varchar2) return varchar2 is l varchar2(100); begin l := p; return rtrim(l); end;
            end;
            /
            create or replace procedure show_h is
              h1 varchar2(5); h2 varchar2(5); h3 varchar2(5); h4 varchar2(5); h5 varchar2(5);
              h6 varchar2(5); h7 varchar2(5); h8 varchar2(5); h9 varchar2(5); h10 varchar2(5);
              h11 varchar2(5); h12 varchar2(5); h13 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                h1 := pk_h.f_upper(r.code);
                h2 := pk_h.f_upper(r.note);
                h3 := pk_h.f_nvl(r.note);
                h4 := pk_h.f_trim(r.code);
                h5 := pk_h.f_upper_p(r.code);
                pk_h.out_upper(r.code, h6);
                pk_h.out_upper(r.note, h7);
                pk_h.out_substr(r.code, h10);
                h11 := pk_h.f_substr(r.code);
                h12 := pk_h.f_lower(r.note);
                h13 := pk_h.f_rtrim(r.code);
              end loop;
              pk_h.out_read(1, h8);
              h9 := pk_h.f_read(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("held.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || coalesce(' ' || p.length, '') ||
                   coalesce(' via ' || p.functions, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.target_written <> 'l' AND p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT') ORDER BY p.target_written, c.key""")).containsExactly(
            // Each call's target: the column that call passed in, through the function its routine applies.
            "h1 CODE RETURNED FIXED_SIZE 5 via UPPER", "h12 NOTE RETURNED FIXED_SIZE 5 via LOWER", "h13 CODE RETURNED FIXED_SIZE 5 via RTRIM",
            "h2 NOTE RETURNED FIXED_SIZE 5 via UPPER", "h3 NOTE RETURNED FIXED_SIZE 5 via NVL", "h4 CODE RETURNED FIXED_SIZE 5 via TRIM",
            "h5 CODE RETURNED FIXED_SIZE 5 via UPPER", "h6 CODE OUT_ARGUMENT FIXED_SIZE 5 via UPPER", "h7 NOTE OUT_ARGUMENT FIXED_SIZE 5 via UPPER",
            // A column read in the routine itself: for every caller, through RETURN or an OUT parameter.
            "h8 CODE OUT_ARGUMENT FIXED_SIZE 5", "h9 CODE RETURNED FIXED_SIZE 5",
            // Inside the routines: what their OUT parameters are given.
            "p_res CODE IN_ARGUMENT CALLER_DECIDES via UPPER", "p_res CODE IN_ARGUMENT CALLER_DECIDES", "p_res NOTE IN_ARGUMENT CALLER_DECIDES via UPPER"
        )
        // Within substr, given to an OUT parameter (h10) or returned (h11): not followed, counted where the routine writes it.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly("RETURNED_EXPRESSION_SUBSTR: 2")
    }

    @Test
    fun `what is not followed is counted by shape`() {
        val db = PlacesFixture.index(temp)
        assertThat(Fixture.query(db, "SELECT substr(message, 1, instr(message, ':') - 1) FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1"))
            .containsExactly("DYNAMIC_INTO")
        // log_it(r_ord.code) passes the column to a parameter log_it does nothing with: no place, nothing counted.
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

    companion object {
        /** A dictionary listing `ORDERS (ORDER_ID, CODE varchar2(20), NOTE varchar2(40))` and `ITEMS (ORDER_ID, SKU varchar2(15))`. */
        private val ORDERS_ITEMS: AtlasDictionary by lazy {
            fun text(length: Int) = ProjectExternalDatatype.of("VARCHAR2", null, length, LengthSemantics.BYTE, null, null)
            val id = ProjectExternalDatatype.of("NUMBER", null, null, null, 10, 0)
            AtlasDictionary.of("orders", listOf("SHOP"), listOf(
                "ORDERS" to listOf("ORDER_ID" to id, "CODE" to text(20), "NOTE" to text(40)),
                "ITEMS" to listOf("ORDER_ID" to id, "SKU" to text(15))
            ).map { (table, columns) ->
                DictionaryRelation(ProjectExternalRelation.of("SHOP", table, true, columns.mapIndexed { i, (c, t) -> ProjectExternalColumn.of(c, i + 1, t) }),
                    DictionaryRelationKind.TABLE)
            })
        }

        private const val RETURNED_PLACES = """
            SELECT f.path || ':' || p.start_line || ' ' || p.target_written || coalesce('.' || p.field, '') || ' ' ||
                   replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' || coalesce(p.length, '-') || ' ' ||
                   coalesce(p.functions, '-') || ' ' || p.concatenated || ' ' || coalesce(replace(g.key, 'db:SHOP.', ''), '-') || ' ' ||
                   coalesce(p.call_count, '-') || ' ' || coalesce(p.call_path || ':' || p.call_line, '-')
            FROM column_place p JOIN entity c ON c.id = p.column_id JOIN source_file f ON f.id = p.file_id
            LEFT JOIN entity g ON g.id = p.argument_routine_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT') ORDER BY f.path, p.start_line, p.start_col, c.key"""

        private val RETURNS_PACKAGE = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_ret as
              subtype t_short is varchar2(5);
              function f_param(p_code varchar2) return varchar2;
              function f_upper(p_code varchar2) return varchar2;
              function f_tagged(p_code varchar2) return varchar2;
              function f_get(p_id number) return varchar2;
              function f_get_row(p_id number) return varchar2;
              function f_get_cur(p_id number) return varchar2;
              function f_two(p_id number) return varchar2;
              function f_chain(p_id number) return varchar2;
              function f_cut(p_code varchar2) return varchar2;
              function f_short(p_id number) return t_short;
              function f_anchored(p_id number) return orders.code%type;
              function f_over(p_code varchar2) return varchar2;
              function f_over(p_code number) return varchar2;
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_ret as
              function f_param(p_code varchar2) return varchar2 is begin return p_code; end;
              function f_upper(p_code varchar2) return varchar2 is begin return upper(p_code); end;
              function f_tagged(p_code varchar2) return varchar2 is begin return 'C:' || p_code; end;
              function f_get(p_id number) return varchar2 is
                l orders.code%type;
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_get_row(p_id number) return varchar2 is
                r orders%rowtype;
              begin
                select * into r from orders where order_id = p_id;
                return r.code;
              end;
              function f_get_cur(p_id number) return varchar2 is
                cursor c is select note from orders where order_id = p_id;
                l varchar2(40);
              begin
                open c; fetch c into l; close c;
                return l;
              end;
              function f_two(p_id number) return varchar2 is
                l1 varchar2(20); l2 varchar2(40);
              begin
                select code, note into l1, l2 from orders where order_id = p_id;
                if p_id > 0 then return l1; end if;
                return l2;
              end;
              function f_chain(p_id number) return varchar2 is begin return f_get(p_id); end;
              function f_cut(p_code varchar2) return varchar2 is begin return substr(p_code, 1, 3); end;
              function f_short(p_id number) return t_short is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_anchored(p_id number) return orders.code%type is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_over(p_code varchar2) return varchar2 is begin return p_code; end;
              function f_over(p_code number) return varchar2 is begin return to_char(p_code); end;
              procedure take(p_code varchar2) is l varchar2(5); begin l := p_code; end;
            end;
            /
        """.trimIndent()

        private val RETURNS_CALLER = """
            create or replace procedure show_ret(p_out out varchar2) is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              l6 varchar2(5); l7 varchar2(5); l8 varchar2(5); l9 varchar2(5); l10 varchar2(5);
              l11 varchar2(5); l12 varchar2(5); l13 varchar2(5); l14 varchar2(5); l15 varchar2(5);
              function f_local(p varchar2) return varchar2 is begin return p; end;
            begin
              for r in (select code from orders) loop
                l1 := pk_ret.f_param(r.code);
                l2 := pk_ret.f_upper(r.code);
                l3 := pk_ret.f_tagged(r.code);
                l12 := pk_ret.f_over(r.code);
                l13 := f_local(r.code);
              end loop;
              l4 := pk_ret.f_get(1);
              l5 := pk_ret.f_get_row(1);
              l6 := pk_ret.f_get_cur(1);
              l7 := pk_ret.f_two(1);
              l8 := pk_ret.f_chain(1);
              l9 := nvl(pk_ret.f_get(1), '-');
              l10 := 'X' || pk_ret.f_get(1);
              l11 := substr(pk_ret.f_get(1), 1, 3);
              select pk_ret.f_get(order_id) into l14 from orders where rownum = 1;
              pk_ret.take(pk_ret.f_get(1));
              p_out := pk_ret.f_get(1);
              l15 := pk_ret.f_cut('abc');
              if pk_ret.f_get(1) = 'A' then null; end if;
            end;
            /
        """.trimIndent()

        private val INLINE_VIEWS = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));
            create table items (order_id number(10), sku varchar2(15 byte));

            create or replace package pk_iv as
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_iv as
              procedure take(p_code varchar2) is l varchar2(5); begin l := p_code; end;
            end;
            /
            create or replace procedure show_iv is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              l6 varchar2(5); l7 varchar2(5); l8 varchar2(5); l9 varchar2(5); l10 varchar2(5);
              l11 varchar2(5); l12 varchar2(5); l13 varchar2(5); n number;
              r_all orders%rowtype;
              cursor c_iv is select v.code from (select code from orders) v;
              r_iv c_iv%rowtype;
            begin
              select v.code into l1 from (select code from orders) v where rownum = 1;
              select code into l2 from (select code from orders) where rownum = 1;
              select x.c2 into l3 from (select code as c2 from orders) x where rownum = 1;
              with w as (select code from orders) select w.code into l4 from w where rownum = 1;
              with a as (select code from orders), b as (select code from a) select code into l5 from b where rownum = 1;
              select code into l6 from (select code from (select code from orders)) where rownum = 1;
              select u.v into l7 from (select code as v from orders union all select note from orders) u where rownum = 1;
              select up into l8 from (select upper(code) as up from orders) where rownum = 1;
              select nvl(v.code, 'x') into l9 from (select code from orders) v where rownum = 1;
              select tag into l10 from (select 'C:' || code as tag from orders) where rownum = 1;
              select r into l11 from (select replace(code, 'a', 'b') as r from orders) where rownum = 1;
              select * into r_all from (select * from orders) where rownum = 1;
              select v.code, i.sku into l12, l13 from (select order_id, code from orders) v join items i on i.order_id = v.order_id where rownum = 1;
              for z in (select v.code from (select code from orders) v) loop
                l1 := z.code;
                pk_iv.take(z.code);
              end loop;
              open c_iv; fetch c_iv into r_iv; close c_iv;
              l2 := r_iv.code;
              for q in (with w as (select code from orders) select code from w) loop
                l3 := q.code;
              end loop;
              for k in (select lit from (select 'k' as lit from dual)) loop
                l4 := k.lit;
              end loop;
              select m into l5 from (select max(code) as m from orders) where rownum = 1;
              select cnt into n from (select count(*) as cnt from orders);
            end;
            /
            create or replace procedure show_direct is
              l8 varchar2(5); l9 varchar2(5); l10 varchar2(5); l11 varchar2(5);
              r_all orders%rowtype;
              cursor c_d is select code from orders;
              r_iv c_d%rowtype;
            begin
              select upper(code) into l8 from orders where rownum = 1;
              select nvl(code, 'x') into l9 from orders where rownum = 1;
              select 'C:' || code into l10 from orders where rownum = 1;
              select replace(code, 'a', 'b') into l11 from orders where rownum = 1;
              select * into r_all from orders where rownum = 1;
              open c_d; fetch c_d into r_iv; close c_d;
            end;
            /
        """.trimIndent()

        private val MADE_OF = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_m as
              type order_cur is ref cursor return orders%rowtype;
              procedure take(p_code varchar2);
              procedure from_strong(p order_cur);
              procedure from_param(p sys_refcursor);
              function fmt(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_m as
              procedure take(p_code varchar2) is
                l varchar2(30);
                n number;
              begin
                l := decode(p_code, 'a', 'b', 'c');
                n := to_number(p_code) + 1;
                l := lower(p_code);
              end;
              procedure from_strong(p order_cur) is
                r p%rowtype;
                l varchar2(30);
              begin
                fetch p into r;
                l := r.code;
              end;
              procedure from_param(p sys_refcursor) is
                l varchar2(30);
              begin
                fetch p into l;
              end;
              function fmt(p varchar2) return varchar2 is begin return p; end;
            end;
            /
            create or replace procedure show_m is
              l varchar2(100);
              n number;
              c sys_refcursor;
              d sys_refcursor;
            begin
              for x in (select extractvalue(value(t), '/a') as v, rownum as rn, 'k' as lit
                          from table(xmlsequence(xmltype('<a>1</a>').extract('/a'))) t) loop
                l := x.v;
                n := x.rn;
                pk_m.take(x.lit);
              end loop;
              for y in (select code from unknown_tab) loop
                l := y.code;
                pk_m.take(y.code);
              end loop;
              for z in (select v.code from (select code from orders) v) loop
                l := z.code;
              end loop;
              for w in (select decode(code, 'a', 'b') as dc from orders) loop
                l := w.dc;
              end loop;
              select decode(code, 'a', 'b'), order_id * 2, pk_m.fmt(code), lower(code)
                into l, n, l, l from orders where rownum = 1;
              open c for select code from orders;
              fetch c into l;
              open d for 'select code from orders';
              fetch d into l;
            end;
            /
        """.trimIndent()

        private val CONCATENATIONS = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_cat as
              procedure p_param(p_code varchar2);
              procedure p_param_upper(p_code varchar2);
              procedure p_param_inner(p_code varchar2);
              procedure p_param_concat_fn(p_code varchar2);
              procedure p_param_into(p_code varchar2);
            end;
            /
            create or replace package body pk_cat as
              procedure p_param(p_code varchar2) is l varchar2(11); begin l := 'C:' || p_code; end;
              procedure p_param_upper(p_code varchar2) is l varchar2(12); begin l := upper(p_code) || '-' || nvl(p_code, '?'); end;
              procedure p_param_inner(p_code varchar2) is l varchar2(13); begin l := substr(p_code || 'x', 1, 5); end;
              procedure p_param_concat_fn(p_code varchar2) is l varchar2(14); begin l := concat(p_code, 'x'); end;
              procedure p_param_into(p_code varchar2) is l varchar2(15); begin select p_code || 'x' into l from dual; end;
            end;
            /
            create or replace procedure show_cat is
              l1 varchar2(16);
              l2 varchar2(17);
              l3 varchar2(18);
              l4 varchar2(19);
              l5 varchar2(20);
              l6 varchar2(10);
              r orders%rowtype;
              cursor c is select code || '/' as joined from orders;
            begin
              select code || '-' into l1 from orders where rownum = 1;
              select code || note into l2 from orders where rownum = 1;
              select upper(code || 'x') into l3 from orders where rownum = 1;
              select * into r from orders where rownum = 1;
              l4 := r.code || '#';
              open c; fetch c into l5; close c;
              update orders set note = note where rownum = 1 returning code || '!' into l6;
              for x in (select code from orders) loop
                pk_cat.p_param(x.code);
                pk_cat.p_param_upper(x.code);
                pk_cat.p_param_inner(x.code);
                pk_cat.p_param_concat_fn(x.code);
                pk_cat.p_param_into(x.code);
              end loop;
            end;
            /
            create or replace procedure fill_cat(o_text out varchar2) is
            begin
              select code || '+' into o_text from orders where rownum = 1;
            end;
            /
            create or replace procedure show_cat_more is
              l7 varchar2(21);
              l8 varchar2(22);
              r orders%rowtype;
            begin
              fill_cat(l7);
              select * into r from orders where rownum = 1;
              l8 := r.code || r.note;
            end;
            /
        """.trimIndent()
    }
}
