//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.PlacesFixture
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryKey
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.model.KeyKind
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The MCP column places and referencing keys tools, on projects built for them. */
class McpPlacesTest {
    private val temp: Path get() = ServerFixture.temp

    /**
     * The places project, with its note column read into two more direct places, of two classes, and built into an OUT message by
     * concatenation, which 149 calls take: 150 concatenated places.
     */
    private fun messagePlaces(): Path {
        val folder = temp.resolve("messages")
        if (Files.exists(folder.resolve("places.db"))) return folder.resolve("places.db")
        PlacesFixture.write(folder.resolve("src"))
        Files.writeString(folder.resolve("src/pk_msg.sql"), """
            create or replace package pk_msg as
              procedure build_msg(p_id number, p_message out varchar2);
            end;
            /
            create or replace package body pk_msg as
              procedure build_msg(p_id number, p_message out varchar2) is
              begin
                select 'Order ' || note || ' noted' into p_message from orders where order_id = p_id;
              end;
            end;
            /
            create or replace procedure note_reads is
              l_note varchar2(40);
              l_same orders.note%type;
            begin
              select note into l_note from orders where order_id = 1;
              select note into l_same from orders where order_id = 2;
            end;
            /
        """.trimIndent() + "\n")
        Files.writeString(folder.resolve("src/many_msgs.prc"), "create or replace procedure many_msgs is\n  m varchar2(200);\nbegin\n" +
            (1..149).joinToString("") { "  pk_msg.build_msg($it, m);\n" } + "end;\n/\n")
        val output = folder.resolve("places.db")
        Indexer().index(IndexOptions(folder.resolve("src"), output, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        return output
    }

    /** An answer's counts by verdict, the places it lists, the concatenated places and the next offset, on one line. */
    private fun pageLine(page: JsonObject, listed: String = "total"): String {
        val counts = page["counts"]!!.jsonArray.joinToString(",") { "${it.jsonObject["name"]!!.jsonPrimitive.content} ${it.jsonObject["count"]!!.jsonPrimitive.content}" }
        return "[$counts] $listed ${page[listed]!!.jsonPrimitive.content} concatenated ${page["concatenatedCount"]!!.jsonPrimitive.content} " +
            "next ${page["nextOffset"]?.jsonPrimitive?.contentOrNull ?: "-"}"
    }

    @Test
    fun `a column's places where its value is one part of a concatenation are listed when asked, and always counted`() {
        val output = messagePlaces()
        mcp(AtlasIndex(output, output.parent.resolve("src"))) { client ->
            val note = "db:SHOP.ORDERS.NOTE"
            suspend fun places(query: String) =
                Json.parseToJsonElement(this.client.get("/api/places?key=$note&verdicts=SMALLER,CALLER_DECIDES,UNKNOWN,FITS,FOLLOWS$query").bodyAsText()).jsonObject["result"]!!.jsonObject
            // NOTE counts 200 characters: 40 bytes are smaller, and the 149 messages of 200 bytes may or may not hold it.
            val direct = places("")
            assertThat(pageLine(direct, "places")).isEqualTo("[SMALLER 1,CALLER_DECIDES 0,UNKNOWN 0,FITS 0,FOLLOWS 2] places 3 concatenated 150 next -")
            val joined = places("&concatenated=true")
            assertThat(pageLine(joined, "places")).isEqualTo("[SMALLER 1,CALLER_DECIDES 1,UNKNOWN 149,FITS 0,FOLLOWS 2] places 153 concatenated 150 next -")
            assertThat(direct["groups"]!!.jsonArray.flatMap { it.jsonObject["rows"]!!.jsonArray }).allSatisfy {
                assertThat(it.jsonObject["concatenated"]!!.jsonPrimitive.content).isEqualTo("false")
            }
            // The MCP lists the direct places but FOLLOWS by default, and always says how many concatenated places there are and how to ask.
            val byDefault = client.call("find_column_places", args { put("key", note) }).json().jsonObject["result"]!!.jsonObject
            assertThat(pageLine(byDefault)).isEqualTo("[SMALLER 1,CALLER_DECIDES 0,UNKNOWN 0,FITS 0,FOLLOWS 2] total 1 concatenated 150 next -")
            assertThat(byDefault["otherPlaces"]!!.jsonPrimitive.content)
                .isEqualTo("150 place(s) where the value is one part of a concatenation: ask again with concatenated true")
            val asked = client.call("find_column_places", args { put("key", note); put("concatenated", true) }).json().jsonObject["result"]!!.jsonObject
            // Cut for size: the next page goes on right after the last row kept.
            val kept = asked["rows"]!!.jsonArray.size
            assertThat(pageLine(asked)).isEqualTo("[SMALLER 1,CALLER_DECIDES 1,UNKNOWN 149,FITS 0,FOLLOWS 2] total 151 concatenated 150 next $kept")
            assertThat(asked).doesNotContainKey("otherPlaces")
            // A size given, and the verdicts asked for: 250 characters are more than the 200 bytes of the messages too.
            val wider = client.call("find_column_places", args {
                put("key", note); put("concatenated", true); put("size", 250); putJsonArray("verdicts") { add("SMALLER") }
            }).json().jsonObject["result"]!!.jsonObject
            assertThat(pageLine(wider)).startsWith("[SMALLER 150,CALLER_DECIDES 1,UNKNOWN 0,FITS 0,FOLLOWS 2] total 150")
            assertThat(wider["size"]!!.jsonPrimitive.content).isEqualTo("250")
            // get_entity: the direct places by class, and the concatenated ones as a total.
            val entity = client.call("get_entity", args { put("key", note) }).json().jsonObject["result"]!!.jsonObject
            assertThat(entity["placesByClass"]!!.jsonArray.map { "${it.jsonObject["value"]!!.jsonPrimitive.content} ${it.jsonObject["count"]!!.jsonPrimitive.content}" })
                .containsExactly("FIXED_SIZE 1", "ANCHORED_TO_COLUMN 2")
            assertThat(entity["concatenatedPlaces"]!!.jsonPrimitive.content).isEqualTo("150")
        }
    }

    @Test
    fun `find_column_places reaches every place of a column, past what one answer holds, by verdict`() {
        // The places project, and a procedure that reads the column into 300 fixed-size variables.
        val folder = temp.resolve("places")
        PlacesFixture.write(folder.resolve("src"))
        Files.writeString(folder.resolve("src/many_reads.prc"), "create or replace procedure many_reads is\n  l_x varchar2(12);\nbegin\n" +
            (1..300).joinToString("") { "  select code into l_x from orders where order_id = $it;\n" } + "end;\n/\n")
        val output = folder.resolve("places.db")
        Indexer().index(IndexOptions(folder.resolve("src"), output, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        mcp(AtlasIndex(output, folder.resolve("src")), McpSettings(maxAnswerChars = 20_000)) { client ->
            val pages = ArrayList<JsonObject>()
            var offset: Int? = 0
            while (offset != null) {
                val page = client.call("find_column_places", args {
                    put("key", "db:SHOP.ORDERS.CODE"); put("offset", offset!!); put("concatenated", true)
                    putJsonArray("verdicts") { listOf("SMALLER", "CALLER_DECIDES", "UNKNOWN", "FITS", "FOLLOWS").forEach { add(it) } }
                }).json().jsonObject
                pages += page
                offset = page["result"]!!.jsonObject["nextOffset"]?.jsonPrimitive?.content?.toInt()
            }
            assertThat(pages).allSatisfy { assertThat(it.toString().length).isLessThanOrEqualTo(20_000) }
            assertThat(pages).anySatisfy { assertThat(it).containsKey("cutForSize") }
            val rows = pages.flatMap { it["result"]!!.jsonObject["rows"]!!.jsonArray.map { row -> row.jsonObject } }
            assertThat(rows.map { "${it["path"]!!.jsonPrimitive.content}:${it["line"]!!.jsonPrimitive.content}:${it["targetWritten"]!!.jsonPrimitive.content}" })
                .hasSize(320).doesNotHaveDuplicates()
            assertThat(pages.first()["result"]!!.jsonObject["total"]!!.jsonPrimitive.content).isEqualTo("320")
            // By verdict: the order of the counts.
            val order = listOf("SMALLER", "CALLER_DECIDES", "UNKNOWN", "FITS", "FOLLOWS")
            assertThat(rows.map { order.indexOf(it["verdict"]!!.jsonPrimitive.content) }).isSorted()
            // Twelve bytes, its unit not written and the index's default, as the column's twelve bytes: it fits.
            val fixed = rows.first { it["targetWritten"]!!.jsonPrimitive.content == "l_x" }
            assertThat(listOf("length", "unit", "unitSource", "declaredType", "verdict").map { fixed[it]!!.jsonPrimitive.content })
                .containsExactly("12", "BYTE", "OPTION", "varchar2(12)", "FITS")
            // An OUT parameter target: its callers' arguments decide the size, the row says which mode.
            val caller = rows.single { it["targetWritten"]!!.jsonPrimitive.content == "p_code" }
            assertThat(listOf("placeClass", "parameterMode", "verdict").map { caller[it]!!.jsonPrimitive.content }).containsExactly("CALLER_DECIDES", "OUT", "CALLER_DECIDES")
            // get_entity of the column counts them by class.
            val entity = client.call("get_entity", args { put("key", "db:SHOP.ORDERS.CODE") }).json().jsonObject["result"]!!.jsonObject
            assertThat(entity["placesByClass"]!!.jsonArray.map { "${it.jsonObject["value"]!!.jsonPrimitive.content} ${it.jsonObject["count"]!!.jsonPrimitive.content}" })
                .containsExactly("FIXED_SIZE 311", "UNKNOWN 1", "CALLER_DECIDES 1", "ANCHORED_ELSEWHERE 1", "ANCHORED_TO_COLUMN 6")
        }
    }

    @Test
    fun `find_referencing_keys reaches every foreign key to a key, past what one answer holds, and says they may be more`() {
        // 600 foreign keys of tables the dictionary did not read reference the fixture's KY_ORDERS primary key, and the
        // loading user did not see every table.
        val fixture = Fixture.dictionary
        val far = (1..600).map { DictionaryKey("FAR", "FK_FAR_$it", KeyKind.FOREIGN_KEY, "FAR", "T$it", listOf("ORDER_ID", "LINE_NO"), true, "NO ACTION", "HR", "PK_KY_ORDERS") }
        val dictionary = AtlasDictionary.of("fixture", listOf("HR"), fixture.relations, fixture.synonyms, fixture.objects, fixture.keys + far, fixture.notNull,
            seesEveryTable = false)
        val many = AtlasIndex(Fixture.index(temp.resolve("many.db"), dictionary), Fixture.sources)
        mcp(many, McpSettings(maxAnswerChars = 20_000)) { client ->
            suspend fun pages(key: String): List<JsonObject> {
                val result = ArrayList<JsonObject>()
                var offset: Int? = 0
                while (offset != null) {
                    val page = client.call("find_referencing_keys", args { put("key", key); put("offset", offset!!) }).json().jsonObject
                    result += page
                    offset = page["result"]!!.jsonObject["nextOffset"]?.jsonPrimitive?.content?.toInt()
                }
                return result
            }
            fun rows(pages: List<JsonObject>) = pages.flatMap { it["result"]!!.jsonObject["rows"]!!.jsonArray.map { row -> row.jsonObject } }
            val table = pages("db:HR.KY_ORDERS")
            // Each page fits the cap: some are cut for size, and paging on from nextOffset reads each key once.
            assertThat(table).allSatisfy { assertThat(it.toString().length).isLessThanOrEqualTo(20_000) }
            assertThat(table).anySatisfy { assertThat(it).containsKey("cutForSize") }
            val names = rows(table).map { "${it["owner"]!!.jsonPrimitive.content}.${it["name"]!!.jsonPrimitive.content}" }
            assertThat(names).doesNotHaveDuplicates().hasSize(603)
                .contains("FIN.FK_PAYMENTS_ORDER", "HR.FK_KY_LINES_CODE", "HR.FK_KY_LINES_ORDER", "FAR.FK_FAR_1", "FAR.FK_FAR_600")
            assertThat(table.first()["result"]!!.jsonObject["total"]!!.jsonPrimitive.content).isEqualTo("603")
            assertThat(table.first()["result"]!!.jsonObject["incomplete"]).isEqualTo(strings("TABLES_NOT_VISIBLE"))
            // A column: the foreign keys to the keys it is in, each with its column that references it.
            val column = rows(pages("db:HR.KY_ORDERS.LINE_NO"))
            assertThat(column).hasSize(602).allSatisfy { row ->
                assertThat(row["columns"]!!.jsonArray.map { it.jsonObject["references"]!!.jsonPrimitive.content }).contains("LINE_NO")
            }
            // get_entity cuts referencedBy, names the referenced key instead of repeating it, and says there may be more.
            val entity = client.call("get_entity", args { put("key", "db:HR.KY_ORDERS") }).json().jsonObject
            assertThat(entity["truncated"]!!.jsonObject["referencedBy"]!!.jsonPrimitive.content).isEqualTo("603")
            val result = entity["result"]!!.jsonObject
            assertThat(result["referencedBy"]!!.jsonArray.first().jsonObject["references"]).isEqualTo(JsonPrimitive("HR.PK_KY_ORDERS"))
            assertThat(result["referencedByIncomplete"]).isEqualTo(strings("TABLES_NOT_VISIBLE"))
            // A table with no primary or unique key cannot be referenced: nothing to say.
            assertThat(client.call("get_entity", args { put("key", "db:HR.KY_LINES") }).json().jsonObject["result"]!!.jsonObject)
                .doesNotContainKey("referencedByIncomplete")
        }
    }
}
