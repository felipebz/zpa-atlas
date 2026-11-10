//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.SizesFixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path

/** The HTTP API's column places endpoint, on the sizes fixture with more programs reading the column. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerPlacesTest {
    private val temp: Path get() = ServerFixture.temp

    /**
     * The sizes fixture with two overloads of one package reading the column, an anonymous block fetching it, and 20
     * procedures reading it into a wider variable.
     */
    private val placesIndex: AtlasIndex by lazy {
        val overloads = """
            create or replace package pk_ov as
              procedure put_code(p_id number);
              procedure put_code(p_name varchar2);
            end;
            /
            create or replace package body pk_ov as
              procedure put_code(p_id number) is
                v varchar2(5);
              begin
                select code into v from orders where order_id = p_id;
              end;
              procedure put_code(p_name varchar2) is
                v varchar2(5);
              begin
                select code into v from orders where note = p_name;
              end;
            end;
            /
        """.trimIndent() + "\n"
        val script = "declare\n  cursor c is select code from orders;\n  v varchar2(3);\nbegin\n  open c;\n  fetch c into v;\n  close c;\nend;\n/\n"
        val many = (1..20).joinToString("") { "create or replace procedure pr_%02d is\n  v varchar2(30);\nbegin\n  select code into v from orders;\nend;\n/\n".format(it) }
        val folder = temp.resolve("sizes")
        AtlasIndex(SizesFixture.index(folder, more = mapOf("pk_ov.sql" to overloads, "script.sql" to script, "many.sql" to many)), folder.resolve("src"))
    }

    /** `/api/places` of `ORDERS.CODE` with [parameters]: its result. */
    private suspend fun ApplicationTestBuilder.places(vararg parameters: Pair<String, String>): JsonObject =
        json(client.get("/api/places") { parameter("key", "db:SHOP.ORDERS.CODE"); parameters.forEach { (name, value) -> parameter(name, value) } }.bodyAsText())["result"]!!.jsonObject

    /** The counts, the places and routines shown of all, the concatenated count and the sizes counted in the index's unit. */
    private fun placesLine(page: JsonObject): String {
        fun value(name: String) = page[name]!!.jsonPrimitive.content
        val counts = page["counts"]!!.jsonArray.joinToString(",") { "${it.jsonObject["name"]!!.jsonPrimitive.content} ${it.jsonObject["count"]!!.jsonPrimitive.content}" }
        return "[$counts] ${value("places")} of ${value("totalPlaces")} in ${value("routines")} of ${value("totalRoutines")} " +
            "concatenated ${value("concatenatedCount")} ${value("optionUnit")} ${value("optionSized")}"
    }

    /** Each group of [page]: its routine, package, parameters, smaller and places counts, and its rows' targets with their verdicts. */
    private fun groupLines(page: JsonObject): List<String> = page["groups"]!!.jsonArray.map { group ->
        val g = group.jsonObject
        fun value(name: String) = g[name]!!.jsonPrimitive.contentOrNull
        val rows = g["rows"]!!.jsonArray.joinToString(" ") { "${it.jsonObject["targetWritten"]!!.jsonPrimitive.content}:${it.jsonObject["verdict"]!!.jsonPrimitive.content}" }
        listOfNotNull(value("routine"), value("packageName"), value("parameters"), "${value("smaller")}/${value("places")}", rows).joinToString(" ")
    }

    @Test
    fun `the places of a column come by routine, those with more smaller places first, a page of routines at a time`() = testApplication {
        application { atlasModule(placesIndex) }
        val first = places()
        assertThat(placesLine(first)).isEqualTo("[SMALLER 5,CALLER_DECIDES 1,UNKNOWN 0,FITS 24,FOLLOWS 1] 30 of 31 in 24 of 24 concatenated 1 BYTE 27")
        assertThat(first["size"]!!.jsonPrimitive.content).isEqualTo("10")
        // Overloads show their parameters, and only they do; a block without a routine is grouped by its file.
        assertThat(groupLines(first).take(5)).containsExactly(
            "SHOW_LV PK_LV 2/7 c1:SMALLER c2:FITS c3:FITS c6:FITS c7:FITS r.code:SMALLER p_out:CALLER_DECIDES",
            "PUT_CODE PK_OV (P_ID IN NUMBER) 1/1 v:SMALLER",
            "PUT_CODE PK_OV (P_NAME IN VARCHAR2) 1/1 v:SMALLER",
            "script.sql 1/1 v:SMALLER",
            "PR_01 0/1 v:FITS")
        assertThat(groupLines(first)).hasSize(15).last().isEqualTo("PR_11 0/1 v:FITS")
        assertThat(listOf("nextOffset", "groupsLeft").map { first[it]!!.jsonPrimitive.content }).containsExactly("15", "9")
        val second = places("offset" to "15")
        assertThat(groupLines(second)).hasSize(9).first().isEqualTo("PR_12 0/1 v:FITS")
        assertThat(second["nextOffset"]).isEqualTo(JsonNull)
        assertThat(second["groupsLeft"]!!.jsonPrimitive.content).isEqualTo("0")
    }

    @Test
    fun `the places of a column are filtered by verdict, words, source and concatenation, the counts by all but the verdict`() = testApplication {
        application { atlasModule(placesIndex) }
        val smaller = places("verdicts" to "SMALLER")
        assertThat(placesLine(smaller)).isEqualTo("[SMALLER 5,CALLER_DECIDES 1,UNKNOWN 0,FITS 24,FOLLOWS 1] 5 of 31 in 4 of 24 concatenated 1 BYTE 27")
        // Every word must be in the target, the routine, the package or the file.
        val words = places("text" to "PK_LV r.code", "verdicts" to "SMALLER,FITS,FOLLOWS")
        assertThat(groupLines(words)).containsExactly("SHOW_LV PK_LV 1/1 r.code:SMALLER")
        assertThat(placesLine(words)).isEqualTo("[SMALLER 1,CALLER_DECIDES 0,UNKNOWN 0,FITS 0,FOLLOWS 0] 1 of 1 in 1 of 1 concatenated 0 BYTE 1")
        assertThat(groupLines(places("source" to "FETCH"))).containsExactly("script.sql 1/1 v:SMALLER")
        // The concatenated place joins the list, with the room its size leaves.
        val joined = places("concatenated" to "true", "text" to "c4", "verdicts" to "FITS")
        assertThat(groupLines(joined)).containsExactly("SHOW_LV PK_LV 0/1 c4:FITS")
        assertThat(joined["groups"]!!.jsonArray[0].jsonObject["rows"]!!.jsonArray[0].jsonObject["room"]!!.jsonPrimitive.content).isEqualTo("20")
        // A size given: the same places against 20 bytes.
        val wider = places("size" to "20")
        assertThat(placesLine(wider)).isEqualTo("[SMALLER 8,CALLER_DECIDES 1,UNKNOWN 0,FITS 21,FOLLOWS 1] 30 of 31 in 24 of 24 concatenated 1 BYTE 27")
        assertThat(listOf("size", "sizeGiven").map { wider[it]!!.jsonPrimitive.content }).containsExactly("20", "true")
        assertThat(places("text" to "nothing like it")["groups"]!!.jsonArray).isEmpty()
        for (bad in listOf("verdicts" to "LARGER", "source" to "SELECT", "size" to "0")) {
            val response = client.get("/api/places") { parameter("key", "db:SHOP.ORDERS.CODE"); parameter(bad.first, bad.second) }
            assertThat(response.status).describedAs(bad.first).isEqualTo(HttpStatusCode.BadRequest)
        }
    }
}
