//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class)

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.auth.oidc.OpenIdProviderMetadata
import io.ktor.server.auth.oidc.OpenIdTestKeys
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** `serve --projects`: several indexes, each under `/p/<name>/`, behind one list and one gate. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectsServerTest {
    private val temp: Path = Files.createTempDirectory("atlas-projects-test")
    private lateinit var hrIndex: Path
    private lateinit var billingIndex: Path
    private lateinit var billingSources: Path

    @BeforeAll
    fun build() {
        hrIndex = Fixture.index(temp.resolve("hr.db"))
        billingSources = Files.createDirectories(temp.resolve("billing-src"))
        Files.writeString(
            billingSources.resolve("invoices.sql"),
            "create table invoice (id number, amount number);\n" +
                "create or replace procedure close_invoice(p_id number) is\nbegin\n  update invoice set amount = 0 where id = p_id;\nend;\n/\n"
        )
        billingIndex = temp.resolve("billing.db")
        var tick = 0L
        Indexer({ Instant.ofEpochSecond(tick++) }).index(IndexOptions(billingSources, billingIndex, compilationSchema = "BILLING", threads = 1))
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun projects(pending: Path = temp.resolve("pending.db")) = listOf(
        ServedProject("hr", hrIndex, Fixture.sources),
        ServedProject("billing", billingIndex, billingSources),
        ServedProject("pending", pending, null)
    )

    private fun server(
        projects: List<ServedProject> = projects(),
        basePath: BasePath = BasePath.ROOT,
        test: suspend ApplicationTestBuilder.(HttpClient) -> Unit
    ) = testApplication {
        application { atlasProjectsModule(projects, McpSettings(), null, basePath) }
        test(createClient { followRedirects = false })
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())

    @Test
    fun `each project answers from its own index, the same answers as one served with --db`() = server { client ->
        assertThat(client.get("/p/hr/api/entities?q=run_job").bodyAsText()).contains("RUN_JOB").doesNotContain("CLOSE_INVOICE")
        assertThat(client.get("/p/billing/api/entities?q=close_invoice").bodyAsText()).contains("CLOSE_INVOICE").doesNotContain("RUN_JOB")
        assertThat(client.get("/p/billing/api/entities?q=run_job").json().jsonObject["result"]!!.jsonArray).isEmpty()
        assertThat(client.get("/p/billing/api/entity?key=db:HR.COMPANY").status).isEqualTo(HttpStatusCode.NotFound)

        val single = mutableMapOf<String, String>()
        val paths = listOf("run", "entities?q=run_job", "entity?key=db:HR.COMPANY", "query?roles=CALL&limit=5", "file?path=hr/pack_process.pkb")
        testApplication {
            application { atlasModule(AtlasIndex(hrIndex, Fixture.sources)) }
            val alone = createClient { }
            for (path in paths) single[path] = alone.get("/api/$path").bodyAsText()
        }
        for (path in paths) assertThat(client.get("/p/hr/api/$path").bodyAsText()).describedAs(path).isEqualTo(single[path])
    }

    @Test
    fun `the list of projects gives each one's state, and the page at the root shows it`() = server { client ->
        val list = client.get("/api/projects").json().jsonObject["projects"]!!.jsonArray.map { it.jsonObject }
        assertThat(list.map { it["name"]!!.jsonPrimitive.content }).containsExactly("billing", "hr", "pending")
        val hr = list[1]
        assertThat(hr["path"]!!.jsonPrimitive.content).isEqualTo("p/hr/")
        assertThat(hr["state"]!!.jsonPrimitive.content).isEqualTo("READY")
        assertThat(hr["files"]!!.jsonPrimitive.content.toInt()).isEqualTo(Fixture.query(hrIndex, "SELECT count(*) FROM source_file").single().toInt())
        assertThat(hr["finishedAt"]!!.jsonPrimitive.content).isNotBlank()
        assertThat(list[2]["state"]!!.jsonPrimitive.content).isEqualTo("NOT_INDEXED")
        assertThat(list[2]["finishedAt"]!!.toString()).isEqualTo("null")

        val root = client.get("/").bodyAsText()
        assertThat(root).contains("<html").doesNotContain("atlas-project")
        // The project pages are told their project; the bare folder goes to the folder.
        assertThat(client.get("/p/hr/").bodyAsText()).contains("<meta name=\"atlas-project\" content=\"hr\">")
        assertThat(client.get("/p/billing/entity.html").bodyAsText()).contains("<meta name=\"atlas-project\" content=\"billing\">")
        assertThat(client.get("/p/hr?lang=pt").headers[HttpHeaders.Location]).isEqualTo("/p/hr/?lang=pt")
        val asset = Regex("assets/[^\"]+\\.js").find(root)!!.value
        assertThat(client.get("/p/hr/$asset").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/$asset").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `the list gives a title and description only when a project has them, and marks the default`() = testApplication {
        val core = ServedProject("core", hrIndex, Fixture.sources, "Core \"platform\"", "The main codebase.")
        application { atlasProjectsModule(listOf(core, ServedProject("billing", billingIndex, billingSources)), McpSettings(), null, BasePath.ROOT, "core") }
        val client = createClient { }
        val list = client.get("/api/projects").json().jsonObject["projects"]!!.jsonArray.map { it.jsonObject }
        assertThat(list[0].keys).containsExactly("name", "path", "state", "finishedAt", "files", "error")
        assertThat(list[1].keys).containsExactly("name", "path", "state", "finishedAt", "files", "error", "title", "description", "default")
        assertThat(list[1]["default"].toString()).isEqualTo("true")
        assertThat(client.get("/p/core/").bodyAsText())
            .contains("<meta name=\"atlas-project\" content=\"core\"><meta name=\"atlas-project-title\" content=\"Core &quot;platform&quot;\">")
        assertThat(client.get("/p/billing/").bodyAsText()).doesNotContain("atlas-project-title")
    }

    @Test
    fun `an unknown project is not found, and nothing of a single index is served at the root`() = server { client ->
        val unknown = client.get("/p/nope/api/run")
        assertThat(unknown.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(unknown.bodyAsText()).isEqualTo("""{"error":"Unknown project: nope"}""")
        assertThat(client.get("/p/nope/").status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/run").status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/entity.html").status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `a project not indexed yet answers 503, then answers once its index is written, and is replaced as a re-index replaces it`() {
        val pending = temp.resolve("later/pending.db")
        server(projects(pending)) { client ->
            val waiting = client.get("/p/pending/api/run")
            assertThat(waiting.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            assertThat(waiting.bodyAsText()).contains("The project pending is not indexed yet: its index ", "pending.db does not exist")
            assertThat(client.get("/health").bodyAsText()).isEqualTo("billing: ok\nhr: ok\npending: not indexed\n")
            assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.OK)

            Files.createDirectories(pending.parent)
            Files.copy(billingIndex, pending)
            assertThat(client.get("/p/pending/api/entities?q=close_invoice").bodyAsText()).contains("CLOSE_INVOICE")
            // A re-index writes a new file and moves it over the old one: the next request reads the new one.
            val next = temp.resolve("later/next.db")
            Files.copy(hrIndex, next)
            Files.move(next, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            assertThat(client.get("/p/pending/api/entities?q=run_job").bodyAsText()).contains("RUN_JOB")
            assertThat(client.get("/health").bodyAsText()).isEqualTo("billing: ok\nhr: ok\npending: ok\n")
        }
    }

    @Test
    fun `an index that does not open fails the health check and is listed as unavailable`() {
        val broken = temp.resolve("broken.db")
        Files.writeString(broken, "not an index")
        server(listOf(ServedProject("hr", hrIndex, Fixture.sources), ServedProject("broken", broken, null))) { client ->
            assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            assertThat(client.get("/health").bodyAsText()).isEqualTo("broken: unavailable\nhr: ok\n")
            val list = client.get("/api/projects").json().jsonObject["projects"]!!.jsonArray
            assertThat(list[0].jsonObject["state"]!!.jsonPrimitive.content).isEqualTo("UNAVAILABLE")
            assertThat(list[0].jsonObject["error"]!!.jsonPrimitive.content).isNotBlank()
        }
    }

    @Test
    fun `a project without sources on this machine is served without them`() =
        server(listOf(ServedProject("hr", hrIndex, null))) { client ->
            assertThat(client.get("/p/hr/api/entity?key=db:HR.COMPANY").status).isEqualTo(HttpStatusCode.OK)
            val source = client.get("/p/hr/api/source?path=hr/pack_process.pkb&line=1")
            assertThat(source.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(source.bodyAsText()).isEqualTo("""{"error":"Source not available"}""")
        }

    @Test
    fun `under a base path the projects are below it, and nothing outside it is answered`() = server(basePath = BasePath.parse("/atlas")) { client ->
        assertThat(client.get("/atlas/p/hr/api/run").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/atlas/api/projects").bodyAsText()).contains("\"path\":\"p/hr/\"")
        assertThat(client.get("/atlas/p/hr/").bodyAsText()).contains("content=\"hr\"")
        assertThat(client.get("/atlas/").bodyAsText()).contains("<html")
        assertThat(client.get("/atlas").headers[HttpHeaders.Location]).isEqualTo("/atlas/")
        assertThat(client.get("/atlas/health").status).isEqualTo(HttpStatusCode.OK)
        for (outside in listOf("/p/hr/api/run", "/api/projects", "/health")) assertThat(client.get(outside).status).describedAs(outside).isEqualTo(HttpStatusCode.NotFound)
    }

    private val issuer = "https://id.example.com/realms/atlas"
    private val keys = OpenIdTestKeys.rsa(issuer = issuer, audience = "https://atlas.example.com/atlas/mcp")
    private val metadata = OpenIdProviderMetadata(issuer = issuer, authorizationEndpoint = "$issuer/auth", tokenEndpoint = "$issuer/token", jwksUri = "$issuer/certs")

    /** The projects under `/atlas` with authentication and the page sign-in; the provider answers [tokens] at its token endpoint. */
    private fun signedServer(tokens: () -> String = { "{}" }, test: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        externalServices {
            hosts("https://id.example.com") {
                routing { post("/realms/atlas/token") { call.respondText(tokens(), ContentType.Application.Json) } }
            }
        }
        val idp = createClient { }
        val basePath = BasePath.parse("/atlas")
        application {
            atlasProjectsModule(projects(), McpSettings(), AuthSettings(issuer, "https://atlas.example.com/atlas", page = PageClient("atlas-web"), basePath = basePath), { a ->
                oidcIdentity(a) {
                    metadata = this@ProjectsServerTest.metadata
                    jwt { jwkProviderFactory = { keys.jwkProvider } }
                }
            }, basePath) { idp }
        }
        test(createClient { followRedirects = false; defaultRequest { header(HttpHeaders.Host, "atlas.example.com") } })
    }

    @Test
    fun `one gate covers every project's api and the list, with the same token rules`() = signedServer { client ->
        for (path in listOf("/atlas/p/hr/api/run", "/atlas/p/billing/api/entities?q=x", "/atlas/api/projects", "/atlas/p/nope/api/run", "/atlas/p/hr/%61pi/run")) {
            val refused = client.get(path)
            assertThat(refused.status).describedAs(path).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(refused.headers[HttpHeaders.WWWAuthenticate])
                .isEqualTo("""Bearer resource_metadata="https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp"""")
        }
        val token = keys.accessToken { subject = "ana" }
        for (path in listOf("/atlas/p/hr/api/run", "/atlas/api/projects")) {
            assertThat(client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
        }
        assertThat(client.get("/atlas/p/hr/api/run") { header(HttpHeaders.Authorization, "Bearer ${keys.accessToken { audience = "atlas-web" }}") }.status)
            .isEqualTo(HttpStatusCode.Unauthorized)
        // The pages hold no data and stay open.
        assertThat(client.get("/atlas/p/hr/entity.html").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/atlas/").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `a sign-in started from a project page returns to it, and its session reaches the project's api`() {
        var nonce = ""
        signedServer({
            buildJsonObject {
                put("access_token", keys.accessToken { subject = "ana" })
                put("token_type", "Bearer")
                put("id_token", keys.idToken("ana") { audience = "atlas-web"; this.nonce = nonce })
            }.toString()
        }) { client ->
            val returnTo = "/atlas/p/billing/entity.html?key=db:BILLING.INVOICE&lang=pt"
            val login = client.get("/atlas/auth/login?return=" + java.net.URLEncoder.encode(returnTo, Charsets.UTF_8))
            val provider = Url(login.headers[HttpHeaders.Location]!!).parameters
            nonce = provider["nonce"]!!
            val loginCookie = login.headers.getAll(HttpHeaders.SetCookie)!!.single().substringAfter("=").substringBefore(";")
            val callback = client.get("/atlas/auth/callback?code=c1&state=${provider["state"]}") {
                header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$loginCookie")
            }
            assertThat(callback.headers[HttpHeaders.Location]).isEqualTo("https://atlas.example.com$returnTo")
            val session = callback.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith(PageSessions.COOKIE) }
            assertThat(session).contains("Path=/atlas;")
            val id = session.substringAfter("=").substringBefore(";")
            assertThat(client.get("/atlas/p/billing/api/run") { header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$id") }.status).isEqualTo(HttpStatusCode.OK)
            assertThat(client.get("/atlas/auth/me") { header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$id") }.bodyAsText()).isEqualTo("""{"name":"ana"}""")
        }
    }
}
