//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.forms.FormsDataType
import com.felipebz.zpa.atlas.forms.FormsItemKind
import com.felipebz.zpa.atlas.highlight.CodeHighlighter
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.model.*
import com.felipebz.zpa.atlas.query.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The HTTP API's run tab width, files, sources, colors, gzip and code lists. */
class ServerFilesTest {
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `the run answer carries the tab width the page shows code with, 8 unless the server is given another`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null), tabWidth = 2) }
        assertThat(json(client.get("/api/run").bodyAsText())["tabWidth"]!!.jsonPrimitive.content).isEqualTo("2")
    }

    @Test
    fun `the run answer's tab width is 8 by default`() = api {
        assertThat(json(client.get("/api/run").bodyAsText())["tabWidth"]!!.jsonPrimitive.content).isEqualTo("8")
    }

    @Test
    fun `a file answer lists the objects the file declares, each with the members it declares directly, in file order`() = api {
        suspend fun objects(path: String) = json(client.get("/api/file") { parameter("path", path) }.bodyAsText())["result"]!!.jsonObject["objects"]!!.jsonArray.map { o ->
            val obj = o.jsonObject
            val head = "${obj["unitKind"]!!.jsonPrimitive.content} ${obj["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content} ${obj["line"]!!.jsonPrimitive.content}"
            listOf(head) + obj["members"]!!.jsonArray.map { m ->
                "  ${m.jsonObject["entity"]!!.jsonObject["kind"]!!.jsonPrimitive.content} ${m.jsonObject["entity"]!!.jsonObject["name"]!!.jsonPrimitive.content} ${m.jsonObject["line"]!!.jsonPrimitive.content}"
            }
        }.flatten()
        assertThat(objects("hr/pack_calls.pks")).containsExactly(
            "PACKAGE db:HR.PACK_CALLS 1", "  TYPE T_NUMS 2", "  PACKAGE_VARIABLE G_TAB 3", "  FUNCTION F_PUB 4", "  PROCEDURE P_PUB 5",
            "  PROCEDURE P_PUB 6", "  PROCEDURE P_MIX 7", "  PROCEDURE P_MIX 8"
        )
        // A body lists its routines, private ones included; a subprogram nested in a routine is not the package's.
        val body = objects("hr/pack_calls.pkb")
        assertThat(body).startsWith("PACKAGE_BODY db:HR.PACK_CALLS 1", "  PROCEDURE P_PRIV 2", "  PROCEDURE P_OVER 7", "  PROCEDURE P_OVER 8",
            "  PROCEDURE P_MIX 10", "  PROCEDURE P_PUB 12", "  PROCEDURE P_PUB 13", "  FUNCTION F_PUB 15", "  PROCEDURE P_MIX 20")
        assertThat(body).noneMatch { it.contains("LOCAL_P") }
        // A type and a type body, each its own object; the index has no entities for a type's attributes and methods.
        assertThat(objects("hr/ty_shapes.typ")).containsExactly("TYPE db:HR.TY_SHAPE 1", "TYPE db:HR.TY_CIRCLE 3", "TYPE_BODY db:HR.TY_CIRCLE 5")
        assertThat(json(client.get("/api/file") { parameter("path", "hr/pack_calls.pks") }.bodyAsText())["result"]!!.jsonObject["parseStatus"]!!.jsonPrimitive.content)
            .isEqualTo("OK")
    }

    @Test
    fun `a found line takes its colors from the whole file, so a line inside a comment opened above it is a comment`() {
        val sources = Files.createDirectories(temp.resolve("notes/src"))
        val file = sources.resolve("notes_pkg.pkb")
        Files.writeString(file, "create or replace package body notes_pkg as\n  /* The ledger note:\n     ledgerTag goes here\n     and here */\n  procedure p is begin null; end;\nend;\n/\n")
        val db = temp.resolve("notes/atlas.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", threads = 1))
        val colorer = CodeColorer { text, ccflags, version ->
            CodeHighlighter.spans(text, ccflags, version)?.map { listOf(it.startLine, it.startCol, it.endLine, it.endCol, it.highlight.ordinal) }
        }
        val comment = CodeColor.COMMENT.ordinal
        fun search(colors: Boolean): JsonObject {
            var page: JsonObject? = null
            testApplication {
                application { atlasModule(AtlasIndex(db, sources, colorer = colorer)) }
                page = json(client.get("/api/text") { parameter("text", "ledgerTag"); if (colors) parameter("colors", "true") }.bodyAsText())["result"]!!.jsonObject
            }
            return page!!
        }
        fun colorsOf(page: JsonObject) = page["rows"]!!.jsonArray.single().jsonObject["colors"]!!
        val colored = search(colors = true)
        assertThat(colored["total"]!!.jsonPrimitive.long).isEqualTo(1)
        assertThat(colored["files"]!!.jsonPrimitive.long).isEqualTo(1)
        // The whole line is the middle of the comment that opens on line 2 and closes on line 4.
        assertThat(colorsOf(colored)).isEqualTo(JsonArray(listOf(JsonArray(listOf(0, "     ledgerTag goes here".length, comment).map { JsonPrimitive(it) }))))
        assertThat(colorsOf(search(colors = false))).isEqualTo(JsonNull)
        // A file changed since indexing: its line is read alone, the comment that opened above it is not known.
        Files.writeString(file, Files.readString(file) + "-- edited\n")
        val stale = search(colors = true)
        assertThat(stale["rows"]!!.jsonArray.single().jsonObject["stale"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(colorsOf(stale).jsonArray.map { it.jsonArray[2].jsonPrimitive.int }).doesNotContain(comment)
    }

    @Test
    fun `a file's colors come only when asked, with the run's conditional compilation, and a colorer that fails gives none`() {
        val asked = mutableListOf<Pair<String?, String?>>()
        val span = listOf(1, 0, 1, 6, 0)
        fun served(colorer: CodeColorer, colors: Boolean): JsonElement? {
            var result: JsonElement? = null
            testApplication {
                application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), Fixture.sources, colorer = colorer)) }
                val response = client.get("/api/file") { parameter("path", "hr/pack_calls.pks"); if (colors) parameter("colors", "true") }
                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                result = json(response.bodyAsText())["result"]!!.jsonObject["colors"]
            }
            return result
        }
        val working = CodeColorer { text, ccflags, version -> asked += ccflags to version; if (text.startsWith("create or replace package pack_calls")) listOf(span) else null }
        assertThat(served(working, colors = false)).isEqualTo(JsonNull)
        assertThat(asked).isEmpty()
        assertThat(served(working, colors = true)).isEqualTo(JsonArray(listOf(JsonArray(span.map { JsonPrimitive(it) }))))
        assertThat(asked).containsExactly(null to null)
        // A text the lexer cannot read: the file is served, without colors.
        assertThat(served(CodeColorer { _, _, _ -> null }, colors = true)).isEqualTo(JsonNull)
        assertThat(served(CodeColorer { _, _, _ -> throw IllegalStateException("cannot lex") }, colors = true)).isEqualTo(JsonNull)
    }

    @Test
    fun `large answers are gzipped for clients that accept it, small ones and other clients get plain bytes`() = api {
        val plain = client.get("/api/file") { parameter("path", "hr/pack_calls.pkb") }
        assertThat(plain.headers[HttpHeaders.ContentEncoding]).isNull()
        val plainBytes = plain.bodyAsBytes()
        assertThat(plainBytes.size.toLong()).isGreaterThan(COMPRESSION_MINIMUM_BYTES)
        val gzipped = client.get("/api/file") { parameter("path", "hr/pack_calls.pkb"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertThat(gzipped.headers[HttpHeaders.ContentEncoding]).isEqualTo("gzip")
        val raw = gzipped.bodyAsBytes()
        assertThat(java.util.zip.GZIPInputStream(raw.inputStream()).readBytes()).isEqualTo(plainBytes)
        assertThat(raw.size).isLessThan(plainBytes.size)

        val small = client.get("/api/entity") { parameter("key", "db:HR.TY_SHAPE_REFS") }
        assertThat(small.bodyAsBytes().size.toLong()).isLessThan(COMPRESSION_MINIMUM_BYTES)
        val smallGzip = client.get("/api/entity") { parameter("key", "db:HR.TY_SHAPE_REFS"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertThat(smallGzip.headers[HttpHeaders.ContentEncoding]).isNull()
    }

    @Test
    fun `the code lists are served in the writer's order, with its reserved flags, without an index`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("no-such-index.db"), null)) }
        val response = client.get("/api/codes")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val codes = json(response.bodyAsText())
        assertThat(codes).doesNotContainKey("run")
        fun served(field: String) = codes[field]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content to it.jsonObject["reserved"]!!.jsonPrimitive.content.toBooleanStrict() }
        assertThat(served("roles")).isEqualTo(Role.entries.map { it.name to it.reserved })
        assertThat(served("statuses")).isEqualTo(Status.entries.map { it.name to it.reserved })
        assertThat(served("entityKinds")).isEqualTo(EntityKind.entries.map { it.name to it.reserved })
        assertThat(served("unitKinds")).isEqualTo(UnitKind.entries.map { it.name to it.reserved })
        assertThat(served("origins")).isEqualTo(Origin.entries.map { it.name to it.reserved })
        assertThat(served("statementKinds")).isEqualTo(StatementKind.entries.map { it.name to false })
        assertThat(served("valueKinds")).isEqualTo(ValueKind.entries.map { it.name to false })
        assertThat(served("groupDimensions")).isEqualTo(GroupDimension.entries.map { it.name to false })
        assertThat(served("reasons")).isEqualTo(Reason.entries.map { it.name to false })
        assertThat(served("parseStatuses")).isEqualTo(ParseStatus.entries.map { it.name to false })
        assertThat(served("relationKinds")).isEqualTo(RelationKind.entries.map { it.name to it.reserved })
        assertThat(served("formsDataTypes")).isEqualTo(FormsDataType.entries.map { it.name to false })
        assertThat(served("formsItemKinds")).isEqualTo(FormsItemKind.entries.map { it.name to false })
        assertThat(served("formsOperations")).isEqualTo(FormsBlockOperation.entries.map { it.name to false })
        assertThat(served("formsOperationStates")).isEqualTo(FormsOperationState.entries.map { it.name to false })
        assertThat(served("entityKinds").filter { it.second }.map { it.first }).contains("LABEL").doesNotContain("SYNONYM")
        // Every other endpoint needs the index, which is missing.
        assertThat(client.get("/api/run").status).isNotEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `source is 404 without a sources folder`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null)) }
        assertThat(client.get("/api/source") { parameter("path", "hr/proc_standalone.prc"); parameter("line", "1") }.status)
            .isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/file") { parameter("path", "hr/proc_standalone.prc") }.status).isEqualTo(HttpStatusCode.NotFound)
    }
}
