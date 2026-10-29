//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class, ExperimentalTime::class)

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.oidc.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.ExperimentalTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasePathTest {
    private val temp: Path = Files.createTempDirectory("atlas-base-path-test")
    private lateinit var index: AtlasIndex

    private val atlas = BasePath.parse("/atlas")
    private val issuer = "https://id.example.com/realms/atlas"
    private val publicUrl = "https://atlas.example.com/atlas"
    private val audience = "https://atlas.example.com/atlas/mcp"
    private val clientId = "atlas-web"
    private val challenge = """Bearer resource_metadata="https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp""""
    private val keys = OpenIdTestKeys.rsa(issuer = issuer, audience = audience)
    private val metadata = OpenIdProviderMetadata(
        issuer = issuer, authorizationEndpoint = "$issuer/auth", tokenEndpoint = "$issuer/token", jwksUri = "$issuer/certs",
        endSessionEndpoint = "$issuer/logout"
    )

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `a base path is the same with or without its trailing slash, and may have several segments`() {
        assertThat(BasePath.parse("/atlas/")).isEqualTo(atlas)
        assertThat(atlas.of("/api")).isEqualTo("/atlas/api")
        assertThat(BasePath.parse("/tools/atlas").of("/mcp")).isEqualTo("/tools/atlas/mcp")
        assertThat(BasePath.parse("/tools/atlas/").path).isEqualTo("/tools/atlas")
        for (root in listOf(null, "", "/")) assertThat(BasePath.parse(root)).describedAs(root).isEqualTo(BasePath.ROOT)
        assertThat(BasePath.ROOT.of("/api")).isEqualTo("/api")
    }

    @Test
    fun `a value that is not a plain path is refused`() {
        for (value in listOf(
            "atlas", "atlas/", "//atlas", "/atlas//", "/tools//atlas", "/./atlas", "/atlas/.", "/atlas/..", "/tools/../atlas",
            "/atl%61s", "/atlas?x=1", "/atlas#top", "/tools\\atlas", "/my atlas", "/atlas\t", "/at\u0001las", "/at\u007flas"
        )) {
            assertThatThrownBy { BasePath.parse(value) }.describedAs(value).isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageStartingWith("The base path must")
        }
    }

    @Test
    fun `the public URL's path must be the base path`() {
        assertThatThrownBy { AuthSettings(issuer, "https://atlas.example.com/other", basePath = atlas) }
            .hasMessageContaining("/other").hasMessageContaining("base path /atlas")
        assertThatThrownBy { AuthSettings(issuer, "https://atlas.example.com", basePath = atlas) }
            .hasMessageContaining("(none)").hasMessageContaining("base path /atlas")
        assertThatThrownBy { AuthSettings(issuer, "https://atlas.example.com/tools/atlas", basePath = atlas) }.hasMessageContaining("/tools/atlas")
        val settings = AuthSettings(issuer, "https://atlas.example.com/atlas/", basePath = atlas)
        assertThat(settings.resource).isEqualTo(audience)
        assertThat(settings.expectedAudience).isEqualTo(audience)
        assertThat(settings.metadataUrl).isEqualTo("https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp")
        assertThat(settings.publicHost).isEqualTo("atlas.example.com")
        assertThat(AuthSettings(issuer, "https://atlas.example.com/tools/atlas", basePath = BasePath.parse("/tools/atlas")).resource)
            .isEqualTo("https://atlas.example.com/tools/atlas/mcp")
    }

    private fun open(basePath: BasePath, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { atlasModule(index, basePath = basePath) }
        test()
    }

    private val runJob = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"
    private val argument = "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE"
    private val leaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"
    private val crud = mapOf("container" to "db:HR.PACK_PROCESS", "roles" to "SQL_SELECT,SQL_INSERT,SQL_UPDATE,SQL_DELETE")

    /** One request of every `/api` route. */
    private val apiRequests: List<Pair<String, Map<String, String>>> = listOf(
        "/api/run" to emptyMap(),
        "/api/top-packages" to emptyMap(),
        "/api/codes" to emptyMap(),
        "/api/entities" to mapOf("q" to "exec"),
        "/api/entity" to mapOf("key" to runJob),
        "/api/occurrences" to mapOf("key" to runJob, "scope" to "SELF,IMPLEMENTATIONS", "roles" to "CALL"),
        "/api/impact" to mapOf("key" to leaf, "stop" to "ENTRY_POINT", "triggers" to "false"),
        "/api/impact/graph" to mapOf("key" to leaf),
        "/api/query" to crud,
        "/api/query/summary" to crud,
        "/api/query/groups" to crud + ("groupBy" to "CONTAINER,ENTITY"),
        "/api/values" to mapOf("key" to argument),
        "/api/value-sites" to mapOf("key" to argument, "kind" to "NUMBER", "value" to "1.5"),
        "/api/source" to mapOf("path" to "hr/pack_calls.pkb", "line" to "32", "context" to "1"),
        "/api/text" to mapOf("text" to "vFixTag", "containerName" to "text_tags"),
        "/api/file" to mapOf("path" to "hr/pack_calls.pkb")
    )

    private suspend fun ApplicationTestBuilder.answers(prefix: String): List<Pair<HttpStatusCode, String>> = apiRequests.map { (path, parameters) ->
        val response = client.get(prefix + path) { parameters.forEach { (name, value) -> parameter(name, value) } }
        response.status to response.bodyAsText()
    }

    @Test
    fun `under a base path every api route answers as at the root, and the root answers 404`() {
        lateinit var root: List<Pair<HttpStatusCode, String>>
        open(BasePath.ROOT) { root = answers("") }
        assertThat(root).allSatisfy { assertThat(it.first).isEqualTo(HttpStatusCode.OK) }
        for (basePath in listOf(atlas, BasePath.parse("/tools/atlas"))) {
            open(basePath) {
                assertThat(answers(basePath.path)).describedAs(basePath.path).isEqualTo(root)
                assertThat(answers("")).describedAs(basePath.path).allSatisfy { (status, body) ->
                    assertThat(status).isEqualTo(HttpStatusCode.NotFound)
                    assertThat(body).isEmpty()
                }
            }
        }
    }

    @Test
    fun `under a base path health and the pages answer there, and nothing at the root`() = open(atlas) {
        assertThat(client.get("/atlas/health").status).isEqualTo(HttpStatusCode.OK)
        val home = client.get("/atlas/")
        assertThat(home.status).isEqualTo(HttpStatusCode.OK)
        assertThat(home.bodyAsText()).contains("zpa-atlas")
        assertThat(client.get("/atlas/text.html").status).isEqualTo(HttpStatusCode.OK)
        for (path in listOf("/", "/index.html", "/text.html", "/health", "/mcp", "/atlas2/", "/atlasx/health", "/other/atlas/health")) {
            val response = client.get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.NotFound)
            assertThat(response.bodyAsText()).describedAs(path).isEmpty()
        }
    }

    @Test
    fun `the bare base path redirects to it with a trailing slash, keeping the query string`() = open(atlas) {
        val browser = createClient { followRedirects = false }
        val plain = browser.get("/atlas")
        assertThat(plain.status).isEqualTo(HttpStatusCode.Found)
        assertThat(plain.headers[HttpHeaders.Location]).isEqualTo("/atlas/")
        assertThat(browser.get("/atlas?lang=pt&q=run%20job").headers[HttpHeaders.Location]).isEqualTo("/atlas/?lang=pt&q=run%20job")
    }

    @Test
    fun `mcp answers under a base path with closed tool schemas, and not at the root`() = open(BasePath.parse("/tools/atlas")) {
        val http = createClient { install(SSE); defaultRequest { header(HttpHeaders.Host, "localhost:8080") } }
        suspend fun post(body: String, session: String? = null) = http.post("/tools/atlas/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            session?.let { header("Mcp-Session-Id", it) }
            setBody(body)
        }
        val session = post(initialize).headers["Mcp-Session-Id"]!!
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        val listing = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session)
        val tools = Json.parseToJsonElement(listing.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools).isNotEmpty
        assertThat(tools.map { it.jsonObject["inputSchema"]!!.jsonObject["additionalProperties"] }).containsOnly(JsonPrimitive(false))
        val client = Client(Implementation("atlas-test", "1"))
        client.connect(StreamableHttpClientTransport(http, "/tools/atlas/mcp"))
        try {
            assertThat(client.serverVersion!!.name).isEqualTo("zpa-atlas")
            val answer = client.callTool(CallToolRequest(CallToolRequestParams(name = "search_entities", arguments = buildJsonObject { put("name", "run_job") })))
            assertThat((answer.content.single() as TextContent).text).contains("RUN_JOB")
        } finally {
            client.close()
        }
        for (path in listOf("/mcp", "/atlas/mcp", "/tools/mcp")) {
            val response = http.post(path) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody(initialize)
            }
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private class Provider {
        var answer: (Parameters) -> String = { "{}" }
    }

    private class Scenario(val builder: ApplicationTestBuilder, val provider: Provider, val browser: HttpClient)

    private fun signedIn(auth: AuthSettings = AuthSettings(issuer, publicUrl, page = PageClient(clientId), basePath = atlas), test: suspend Scenario.() -> Unit) =
        testApplication {
            val provider = Provider()
            externalServices {
                hosts("https://id.example.com") {
                    routing {
                        post("/realms/atlas/token") { call.respondText(provider.answer(call.receiveParameters()), ContentType.Application.Json) }
                    }
                }
            }
            val idp = createClient { }
            application {
                atlasModule(index, McpSettings(), auth, { a ->
                    oidcIdentity(a) {
                        metadata = this@BasePathTest.metadata
                        jwt { jwkProviderFactory = { keys.jwkProvider } }
                    }
                }, auth.basePath) { idp }
            }
            val browser = createClient {
                followRedirects = false
                defaultRequest { header(HttpHeaders.Host, "atlas.example.com") }
            }
            Scenario(this, provider, browser).test()
        }

    private suspend fun Scenario.mcpPost(path: String, token: String? = null): HttpResponse = browser.post(path) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(initialize)
    }

    @Test
    fun `the token check covers the api and mcp under the base path, in every form the router reads`() = signedIn {
        val refused = listOf(
            "/atlas/api/run", "/atlas/mcp", "/atlas/%6dcp", "/atlas/%61pi/run", "/atlas/api//run", "/atlas/mcp/", "/atlas/mcp/x",
            "/%61tlas/api/run", "/%61tlas/mcp", "/atlas//api/run"
        )
        for (path in refused) {
            val response = browser.get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).describedAs(path).isEqualTo(challenge)
        }
        assertThat(mcpPost("/atlas/mcp").status).isEqualTo(HttpStatusCode.Unauthorized)
        val token = keys.accessToken { subject = "ana" }
        assertThat(browser.get("/atlas/api/run") { header(HttpHeaders.Authorization, "Bearer $token") }.status).isEqualTo(HttpStatusCode.OK)
        assertThat(mcpPost("/atlas/mcp", token).status).isEqualTo(HttpStatusCode.OK)
        for (path in listOf("/api/run", "/mcp")) {
            for (bearer in listOf(null, token)) {
                val response = browser.get(path) { bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
                assertThat(response.status).describedAs("$path $bearer").isEqualTo(HttpStatusCode.NotFound)
                assertThat(response.bodyAsText()).isEmpty()
            }
            assertThat(mcpPost("/mcp", token).status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `the metadata is under the base path and names the resource the client used`() = signedIn {
        for (path in listOf("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource/atlas/mcp")) {
            assertThat(browser.get(path).status).describedAs(path).isEqualTo(HttpStatusCode.NotFound)
        }
        val response = browser.get("/atlas/.well-known/oauth-protected-resource/mcp")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertThat(document["resource"]!!.jsonPrimitive.content).isEqualTo("https://atlas.example.com/atlas/mcp")
        assertThat(document["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly(issuer)
        assertThat(mcpPost("/atlas/mcp").headers[HttpHeaders.WWWAuthenticate]).isEqualTo(challenge)
    }

    private fun idToken(nonce: String) = keys.idToken("ana") {
        audience = clientId
        this.nonce = nonce
        claim("preferred_username", "ana.souza")
    }

    private fun HttpResponse.setCookieHeader(name: String) = headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$name=") }

    private fun HttpResponse.cookieValue(name: String) = setCookieHeader(name).substringAfter("=").substringBefore(";")

    /** Signs in from the page at [returnTo]: the callback's answer. */
    private suspend fun Scenario.signIn(returnTo: String?): HttpResponse {
        val login = browser.get("/atlas/auth/login" + (returnTo?.let { "?return=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""))
        assertThat(login.status).isEqualTo(HttpStatusCode.Found)
        val location = Url(login.headers[HttpHeaders.Location]!!).parameters
        assertThat(location["redirect_uri"]).isEqualTo("https://atlas.example.com/atlas/auth/callback")
        assertThat(login.setCookieHeader(PageSessions.LOGIN_COOKIE)).contains("Path=/atlas/auth;")
        val nonce = location["nonce"]!!
        provider.answer = {
            buildJsonObject {
                put("access_token", keys.accessToken { subject = "ana" })
                put("token_type", "Bearer")
                put("id_token", idToken(nonce))
            }.toString()
        }
        val state = location["state"]!!
        return browser.get("/atlas/auth/callback?code=c1&state=$state") {
            header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=${login.cookieValue(PageSessions.LOGIN_COOKIE)}")
        }
    }

    @Test
    fun `a session cookie of a server at the host's root, sent beside this server's, does not hide it`() = signedIn {
        val session = signIn("/atlas/").cookieValue(PageSessions.COOKIE)
        for (header in listOf("${PageSessions.COOKIE}=$session; ${PageSessions.COOKIE}=another-servers-id", "${PageSessions.COOKIE}=another-servers-id; ${PageSessions.COOKIE}=$session")) {
            assertThat(browser.get("/atlas/api/run") { header(HttpHeaders.Cookie, header) }.status).describedAs(header).isEqualTo(HttpStatusCode.OK)
            assertThat(browser.get("/atlas/auth/me") { header(HttpHeaders.Cookie, header) }.status).describedAs(header).isEqualTo(HttpStatusCode.OK)
        }
        assertThat(browser.get("/atlas/api/run") { header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=another-servers-id") }.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `after sign-in the page returns where it was under the base path, never doubled, and anything outside it goes home`() = signedIn {
        val cases = mapOf(
            "/atlas/entity.html?key=db:HR.COMPANY&lang=pt" to "https://atlas.example.com/atlas/entity.html?key=db:HR.COMPANY&lang=pt",
            "/atlas/" to "https://atlas.example.com/atlas/",
            "/atlas" to "https://atlas.example.com/atlas/",
            "/atlas?lang=pt" to "https://atlas.example.com/atlas/?lang=pt",
            "/entity.html?key=db:HR.COMPANY" to "https://atlas.example.com/atlas/",
            "/atlas2/entity.html" to "https://atlas.example.com/atlas/",
            "/other/app" to "https://atlas.example.com/atlas/",
            "/atlas//evil.example.com" to "https://atlas.example.com/atlas/",
            "/atlas/../other/x" to "https://atlas.example.com/atlas/",
            "/atlas/%2e%2e/other" to "https://atlas.example.com/atlas/",
            "/atlas/./entity.html?lang=pt" to "https://atlas.example.com/atlas/entity.html?lang=pt",
            "//evil.example.com" to "https://atlas.example.com/atlas/",
            "https://evil.example.com" to "https://atlas.example.com/atlas/"
        )
        for ((from, to) in cases) {
            assertThat(signIn(from).headers[HttpHeaders.Location]).describedAs(from).isEqualTo(to)
        }
        assertThat(signIn(null).headers[HttpHeaders.Location]).isEqualTo("https://atlas.example.com/atlas/")
    }

    @Test
    fun `a return target is judged by the path the browser resolves, dot segments in any spelling`() {
        val outside = listOf(
            "/atlas/..", "/atlas/../", "/atlas/../other/x", "/atlas/%2e%2e/other", "/atlas/%2E%2E/other", "/atlas/.%2e/other",
            "/atlas/%2e./other", "/atlas/.%2E/other", "/atlas/x/../../other", "/atlas/..\\other", "/atlas\\..\\other",
            "/atlas/../atlas2/x", "/atlas/x/%2e%2e/%2e%2e?lang=pt"
        )
        for (target in outside) assertThat(PageSessions.safeReturn(target, atlas)).describedAs(target).isEqualTo("/")
        val inside = mapOf(
            "/atlas/./entity.html" to "/entity.html",
            "/atlas/%2e/entity.html?key=db:HR.COMPANY&lang=pt" to "/entity.html?key=db:HR.COMPANY&lang=pt",
            "/atlas/x/../entity.html?q=a/../b" to "/entity.html?q=a/../b",
            "/other/../atlas/text.html" to "/text.html",
            "/atlas/." to "/",
            "/atlas/x/.." to "/",
            "/atlas/..%2fx" to "/..%2fx"
        )
        for ((target, kept) in inside) assertThat(PageSessions.safeReturn(target, atlas)).describedAs(target).isEqualTo(kept)
        assertThat(PageSessions.safeReturn("/x/../entity.html?lang=pt")).isEqualTo("/x/../entity.html?lang=pt")
    }

    @Test
    fun `the session cookie is scoped to the base path, and sign-out returns to it`() = signedIn {
        val callback = signIn("/atlas/")
        val attributes = callback.setCookieHeader(PageSessions.COOKIE).split(";").map { it.trim() }
        assertThat(attributes).contains("Path=/atlas", "HttpOnly", "Secure", "SameSite=Lax")
        val session = callback.cookieValue(PageSessions.COOKIE)
        val cookie = "${PageSessions.COOKIE}=$session"
        assertThat(browser.get("/atlas/api/run") { header(HttpHeaders.Cookie, cookie) }.status).isEqualTo(HttpStatusCode.OK)
        assertThat(browser.get("/atlas/auth/me") { header(HttpHeaders.Cookie, cookie) }.status).isEqualTo(HttpStatusCode.OK)
        for (path in listOf("/auth/login", "/auth/me", "/auth/callback")) {
            assertThat(browser.get(path).status).describedAs(path).isEqualTo(HttpStatusCode.NotFound)
        }
        val elsewhere = browser.post("/atlas/auth/logout") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Referrer, "https://atlas.example.com/other/page")
        }
        assertThat(elsewhere.status).isEqualTo(HttpStatusCode.Forbidden)
        for (sameSite in listOf(HttpHeaders.Origin to "https://atlas.example.com", HttpHeaders.Referrer to "https://atlas.example.com/atlas/entity.html?key=x")) {
            val fresh = "${PageSessions.COOKIE}=${signIn("/atlas/").cookieValue(PageSessions.COOKIE)}"
            val logout = browser.post("/atlas/auth/logout") {
                header(HttpHeaders.Cookie, fresh)
                header(sameSite.first, sameSite.second)
            }
            assertThat(logout.status).describedAs(sameSite.first).isEqualTo(HttpStatusCode.SeeOther)
            val location = Url(logout.headers[HttpHeaders.Location]!!)
            assertThat(location.toString()).startsWith("$issuer/logout?")
            assertThat(location.parameters["post_logout_redirect_uri"]).isEqualTo("https://atlas.example.com/atlas")
            assertThat(logout.setCookieHeader(PageSessions.COOKIE)).contains("Path=/atlas;").contains("Max-Age=0")
            assertThat(browser.get("/atlas/api/run") { header(HttpHeaders.Cookie, fresh) }.status).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }
}
