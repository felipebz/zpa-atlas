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
        // f_return returns its parameter: counted once, for the value going in; what each function returns into n or l
        // is counted once too, as an expression of the column.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1").map { it.substringBefore(" ") })
            .containsExactly("ARGUMENT_RETURNED:", "EXPRESSION_OTHER:")
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE message LIKE 'ARGUMENT_RETURNED%'").single()).startsWith("ARGUMENT_RETURNED: 1 ")
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
        // x.up is read through UPPER, which the index follows; r_unknown's relation is not known but its field is named:
        // the column is told by name, and p_rec writes it nowhere. Neither is counted; nor is r_anchor, whose type the
        // index does not resolve at all.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_COLUMN_NOT_KNOWN_CURSOR: 1",                      // r_spec, a cursor declared with no query
            "ARGUMENT_COLUMN_NOT_KNOWN_EXPRESSION_CONCATENATION: 1",    // x.joined, code || note
            "ARGUMENT_COLUMN_NOT_KNOWN_EXPRESSION_NVL: 1",              // y.n, nvl(note, code)
            "ARGUMENT_COLUMN_NOT_KNOWN_OTHER: 1",                       // z.lit, a literal
            "ARGUMENT_COLUMN_NOT_KNOWN_STAR: 1",                        // s.code, select * from a table not known
            "ARGUMENT_EXPRESSION_CASE: 1",
            "ARGUMENT_EXPRESSION_CONCATENATION: 1",
            "ARGUMENT_EXPRESSION_LENGTH: 1",
            "ARGUMENT_EXPRESSION_OTHER: 1",                             // replace(p_code, …)
            "ARGUMENT_EXPRESSION_SUBSTR: 1",
            "ARGUMENT_EXPRESSION_TO_CHAR: 1"
        )
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
}
