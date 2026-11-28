//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * A parameter passed the same name from a procedure's local and from an anonymous block's local, which has no entity:
 * two groups, each listing its own site, through the HTTP API and through MCP.
 */
class ValueSitesServerTest {
    private val parameter = "db:SHOP.DEPOT.STORE(P_TAG IN VARCHAR2).P_TAG"

    private val index: AtlasIndex by lazy {
        val temp = Files.createTempDirectory("atlas-value-sites")
        Runtime.getRuntime().addShutdownHook(Thread { temp.toFile().deleteRecursively() })
        val sources = temp.resolve("src").also { it.toFile().mkdirs() }
        sources.resolve("depot.sql").writeText(
            """
            create or replace package depot as
              procedure store(p_tag varchar2);
            end;
            /
            create or replace procedure tag_one is
              w_tag varchar2(10);
            begin
              depot.store(w_tag);
            end;
            /
            declare
              w_tag varchar2(10);
            begin
              depot.store(w_tag);
            end;
            /
            """.trimIndent() + "\n"
        )
        val db = temp.resolve("depot.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", threads = 1))
        AtlasIndex(db, sources)
    }

    /** Each group as `entity count sites`, and the group's own fields to ask its sites with. */
    private fun groups(groups: JsonArray) = groups.map { it.jsonObject }.onEach {
        assertThat(it["value"]?.jsonPrimitive?.content).isEqualTo("W_TAG")
    }

    private fun JsonObject.entityKey() = (this["entity"] as? JsonObject)?.get("key")?.jsonPrimitive?.content

    @Test
    fun `the API lists the sites of each group, as many as its count`() = api(index) {
        val groups = groups(json(client.get("/api/values") { parameter("key", parameter) }.bodyAsText())["result"]!!.jsonArray)
        val seen = groups.map { group ->
            val sites = json(client.get("/api/value-sites") {
                parameter("key", parameter); parameter("kind", "NAME"); parameter("value", "W_TAG")
                group.entityKey()?.let { parameter("entity", it) }
            }.bodyAsText())["result"]!!.jsonObject["rows"]!!.jsonArray
            "${group.entityKey()} ${group["count"]} ${sites.size}"
        }
        assertThat(seen).containsExactlyInAnyOrder("db:SHOP.TAG_ONE().W_TAG 1 1", "null 1 1")
    }

    @Test
    fun `MCP lists the sites of each group, as many as its count`() = mcp(index) { client ->
        val groups = groups(client.call("get_values", args { put("key", parameter) }).json().jsonObject["result"]!!.jsonObject["groups"]!!.jsonArray)
        val seen = groups.map { group ->
            val sites = client.call("find_value_sites", args {
                put("key", parameter); put("value_kind", "NAME"); put("value", "W_TAG")
                group.entityKey()?.let { put("entity", it) }
            }).json().jsonObject["result"]!!.jsonObject["rows"]!!.jsonArray
            "${group.entityKey()} ${group["count"]} ${sites.size}"
        }
        assertThat(seen).containsExactlyInAnyOrder("db:SHOP.TAG_ONE().W_TAG 1 1", "null 1 1")
    }
}
