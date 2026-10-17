//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A bare `json` datatype is a user type `JSON` when one exists and the database predates the built-in type. */
class JsonTypeTest {
    @TempDir
    lateinit var temp: Path

    private fun index(
        name: String, files: List<String>, version: String? = null, written: Map<String, String> = emptyMap(), dictionary: AtlasDictionary = AtlasDictionary.NONE
    ): Path {
        val sources = Files.createDirectories(temp.resolve("src-$name").resolve("hr"))
        for (file in files) Files.copy(Fixture.sources.resolve("hr/$file"), sources.resolve(file))
        for ((file, text) in written) Files.writeString(sources.resolve(file), text)
        val output = temp.resolve("$name.db")
        Indexer().index(IndexOptions(sources.parent, output, compilationSchema = "HR", dictionary = dictionary, threads = 1,
            conditionalCompilation = ConditionalCompilationSettings.of(null, version)))
        return output
    }

    private fun typeUses(db: Path) = Fixture.query(db,
        "SELECT f.path || ' ' || o.start_line || ':' || o.start_col || ' ' || e.key FROM occurrence o JOIN entity e ON e.id = o.entity_id " +
            "JOIN source_file f ON f.id = o.file_id WHERE o.roles = 'TYPE_USE' ORDER BY o.id")

    @Test
    fun `every bare json names the user type, not json(object)`() {
        assertThat(typeUses(index("user", listOf("json_type.typ", "json_holder.typ", "pack_json.pks", "pack_json.pkb")))).containsExactly(
            // An attribute of another object type.
            "hr/json_holder.typ 2:6 db:HR.JSON",
            // Parameter and return types, a local variable; `b json(object)` at 13:6 is the built-in type.
            "hr/pack_json.pkb 2:21 db:HR.JSON", "hr/pack_json.pkb 2:34 db:HR.JSON",
            "hr/pack_json.pkb 7:16 db:HR.JSON",
            "hr/pack_json.pkb 12:6 db:HR.JSON",
            // A collection element, parameter and return types in the specification.
            "hr/pack_json.pks 2:29 db:HR.JSON",
            "hr/pack_json.pks 3:21 db:HR.JSON", "hr/pack_json.pks 3:34 db:HR.JSON",
            "hr/pack_json.pks 5:16 db:HR.JSON"
        )
    }

    @Test
    fun `without a type JSON a bare json is the built-in type`() {
        val db = index("builtin", listOf("pack_json.pks", "pack_json.pkb"), written = mapOf("json_sql.prc" to """
            create or replace procedure json_sql(v out varchar2) is
              l json := json('{"a": 1}');
            begin
              select json('x'), cast(null as json) into v, l from dual;
            end;
            /
            """.trimIndent()))
        assertThat(typeUses(db)).isEmpty()
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE name = 'JSON'")).isEmpty()
        assertThat(Fixture.query(db, "SELECT kind FROM coverage_note WHERE message LIKE '%JSON%' OR message LIKE '%json%'")).isEmpty()
    }

    @Test
    fun `from Oracle 21 on a bare json is always the built-in type`() {
        val files = listOf("json_type.typ", "json_holder.typ", "pack_json.pks", "pack_json.pkb")
        assertThat(typeUses(index("v21", files, "21"))).isEmpty()
        assertThat(typeUses(index("v19", files, "19"))).hasSize(9)
    }

    /** PL/JSON 2.4: the type is `PLJSON`, and `JSON` is a private synonym of the schema. */
    private val plJson = mapOf(
        "pljson.typ" to """
            create or replace type pljson as object (
              n number,
              constructor function pljson return self as result,
              constructor function pljson(p varchar2) return self as result,
              constructor function pljson(a number, b number) return self as result
            )
            /
            """.trimIndent(),
        "json_holder2.typ" to """
            create or replace type json_holder2 as object (
              doc json
            )
            /
            """.trimIndent(),
        "pack_pljson.pks" to """
            create or replace package pack_pljson is
              type json_list is table of json;
              function get_val(obj in json, path in varchar2) return json;
              procedure run;
            end;
            /
            """.trimIndent(),
        "pack_pljson.pkb" to """
            create or replace package body pack_pljson is
              function get_val(obj in json, path in varchar2) return json is
              begin
                return obj;
              end;

              procedure run is
                v json;
                b json(object);
                s varchar2(100);
              begin
                v := json();
                v := json('{}');
                v := new json('{}');
                v := json(5, 6);
                select json('x') into v from dual;
                select cast(null as json) into v from dual;
              end;
            end;
            /
            """.trimIndent()
    )

    private val synonymDictionary = AtlasDictionary.of(
        "pljson", listOf("HR"), emptyList(),
        listOf(DictionarySynonym("HR", "JSON", "HR", "PLJSON", "TYPE")),
        mapOf("HR" to mapOf("JSON" to "SYNONYM", "PLJSON" to "TYPE"))
    )

    /** The occurrences of `PLJSON` written `json`, as `file line:col roles`. */
    private fun jsonSpellings(db: Path) = Fixture.query(db,
        "SELECT f.path || ' ' || o.start_line || ':' || o.start_col || ' ' || o.roles FROM occurrence o JOIN entity e ON e.id = o.entity_id " +
            "JOIN source_file f ON f.id = o.file_id WHERE e.key = 'db:HR.PLJSON' AND lower(o.written) = 'json' ORDER BY o.id")

    @Test
    fun `a private synonym JSON of the PL-JSON type is followed for every bare json and constructor`() {
        val db = index("pljson", emptyList(), "10.2", plJson, synonymDictionary)
        assertThat(jsonSpellings(db)).containsExactly(
            "hr/json_holder2.typ 2:6 TYPE_USE",
            // Parameter and return types, a local variable; `b json(object)` at 9:6 is the built-in type.
            "hr/pack_pljson.pkb 2:26 TYPE_USE", "hr/pack_pljson.pkb 2:57 TYPE_USE",
            "hr/pack_pljson.pkb 8:6 TYPE_USE",
            // json(), json(x), new json(x), json(a, b), json(x) in SQL, cast(… as json) in SQL.
            "hr/pack_pljson.pkb 12:9 CALL", "hr/pack_pljson.pkb 13:9 CALL", "hr/pack_pljson.pkb 14:13 CALL", "hr/pack_pljson.pkb 15:9 CALL",
            "hr/pack_pljson.pkb 16:11 CALL", "hr/pack_pljson.pkb 17:24 TYPE_USE",
            // A collection element, parameter and return types in the specification.
            "hr/pack_pljson.pks 2:29 TYPE_USE", "hr/pack_pljson.pks 3:26 TYPE_USE", "hr/pack_pljson.pks 3:57 TYPE_USE"
        )
        assertThat(Fixture.query(db, "SELECT kind FROM entity WHERE key = 'db:HR.JSON'")).containsExactly("SYNONYM")
        assertThat(Fixture.query(db, "SELECT t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id " +
            "WHERE f.key = 'db:HR.JSON' AND r.kind = 'SYNONYM_FOR'")).containsExactly("db:HR.PLJSON")
    }

    @Test
    fun `from Oracle 21 on the PL-JSON spellings of json are the built-in type and function`() {
        assertThat(jsonSpellings(index("pljson21", emptyList(), "21", plJson, synonymDictionary))).isEmpty()
    }
}
