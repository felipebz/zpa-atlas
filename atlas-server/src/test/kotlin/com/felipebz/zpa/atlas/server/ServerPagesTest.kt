//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The web pages, their assets and their labels. */
class ServerPagesTest {
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `every served code has a label in each page catalog`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("no-such-index.db"), null)) }
        val codes = json(client.get("/api/codes").bodyAsText())
        // The catalog key of each list's codes, as the pages look them up.
        val prefixes = mapOf(
            "roles" to "role", "statuses" to "status", "entityKinds" to "kind", "unitKinds" to "unitKind", "origins" to "origin",
            "statementKinds" to "statement", "valueKinds" to "valueKind", "groupDimensions" to "query.dimension", "reasons" to "reason",
            "parseStatuses" to "parse", "relationKinds" to "relation", "formsDataTypes" to "formsDataType", "formsItemKinds" to "formsItemKind",
            "formsOperations" to "formsOperation", "formsOperationStates" to "formsOperationState"
        )
        assertThat(codes.keys).isEqualTo(prefixes.keys)
        for (catalog in listOf("en", "pt")) {
            val text = Files.readString(Path.of("web/src/i18n/$catalog.ts"))
            // A row's statement kind reads as a phrase of its own ("in an update statement"), so each kind needs one too.
            val phrases = codes.getValue("statementKinds").jsonArray.map { "usage.inStatement.${it.jsonObject.getValue("code").jsonPrimitive.content}" }
            val missing = (prefixes.flatMap { (list, prefix) ->
                codes.getValue(list).jsonArray.map { "$prefix.${it.jsonObject.getValue("code").jsonPrimitive.content}" }
            } + phrases).filter { "\"$it\":" !in text }
            assertThat(missing).describedAs("labels missing from $catalog.ts").isEmpty()
        }
    }

    private fun references(html: String) = Regex("""(?:src|href)="\./(assets/[^"]+)"""").findAll(html).map { "/" + it.groupValues[1] }.toList()

    @Test
    fun `the web pages are served with every script and stylesheet they reference`() = api {
        for (page in listOf("/", "/entity.html", "/file.html", "/query.html", "/impact.html")) {
            val response = client.get(page)
            assertThat(response.status).describedAs(page).isEqualTo(HttpStatusCode.OK)
            val assets = references(response.bodyAsText())
            assertThat(assets).describedAs("what $page references").anyMatch { it.endsWith(".js") }.anyMatch { it.endsWith(".css") }
            for (asset in assets) {
                val served = client.get(asset)
                assertThat(served.status).describedAs(asset).isEqualTo(HttpStatusCode.OK)
                assertThat(served.contentType().toString()).describedAs(asset)
                    .matches(if (asset.endsWith(".js")) "(text|application)/javascript.*" else "text/css.*")
            }
        }
        assertThat(client.get("/").bodyAsText()).contains("zpa-atlas")
    }

    @Test
    fun `the English and the Portuguese text reach the browser`() = api {
        val pending = ArrayDeque(listOf("/", "/entity.html", "/file.html", "/query.html", "/impact.html")
            .flatMap { references(client.get(it).bodyAsText()) }.filter { it.endsWith(".js") })
        val scripts = LinkedHashMap<String, String>()
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            if (path in scripts) continue
            val body = client.get(path).bodyAsText()
            scripts[path] = body
            Regex("""\./([\w.-]+\.js)""").findAll(body).forEach { pending += "/assets/" + it.groupValues[1] }
        }
        assertThat(scripts.filterValues { "Used by" in it }).describedAs("scripts holding the English text").isNotEmpty
        assertThat(scripts.filterValues { "Quem usa" in it }).describedAs("scripts holding the Portuguese text").isNotEmpty
    }
}
