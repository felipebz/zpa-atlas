//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class, ExperimentalTime::class)

package com.felipebz.zpa.atlas.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.NetworkException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.oidc.OpenIdProviderMetadata
import io.ktor.server.auth.oidc.OpenIdTestKeys
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthTest {
    private val temp: Path = Files.createTempDirectory("atlas-auth-test")
    private lateinit var index: AtlasIndex

    private val issuer = "https://id.example.com/realms/atlas"
    private val publicUrl = "https://atlas.example.com"
    private val audience = "https://atlas.example.com/mcp"
    private val challenge = """Bearer resource_metadata="https://atlas.example.com/.well-known/oauth-protected-resource/mcp""""
    private val keys = OpenIdTestKeys.rsa(issuer = issuer, audience = audience)

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private val metadata = OpenIdProviderMetadata(
        issuer = issuer, authorizationEndpoint = "$issuer/auth", tokenEndpoint = "$issuer/token", jwksUri = "$issuer/certs"
    )

    private fun server(
        auth: AuthSettings = AuthSettings(issuer, publicUrl),
        signing: OpenIdTestKeys = keys,
        keyProvider: JwkProvider? = null,
        test: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application {
            atlasModule(index, McpSettings(), auth, { a ->
                oidcIdentity(a) {
                    metadata = this@AuthTest.metadata
                    jwt { jwkProviderFactory = { keyProvider ?: signing.jwkProvider } }
                }
            }) { error("The bearer checks call no provider endpoint") }
        }
        test()
    }

    private fun ApplicationTestBuilder.http(token: String? = null) = createClient {
        install(SSE)
        defaultRequest {
            header(HttpHeaders.Host, "atlas.example.com")
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private suspend fun HttpClient.mcpPost(query: String = ""): HttpResponse = post("/mcp$query") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(initialize)
    }

    private fun valid() = keys.accessToken { subject = "ana" }

    private suspend fun ApplicationTestBuilder.assertRefused(token: String) {
        val client = http(token)
        for (response in listOf(client.mcpPost(), client.get("/api/run"))) {
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).isEqualTo(challenge)
        }
    }

    private fun segment(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val inOneHour get() = Date(System.currentTimeMillis() + 3_600_000)

    @Test
    fun `without a token, every method of mcp and the api answer 401 with the metadata location`() = server {
        val client = http()
        val responses = listOf(client.mcpPost(), client.get("/mcp"), client.delete("/mcp"), client.get("/api/run"), client.get("/api/entities?q=run"))
        assertThat(responses).allSatisfy {
            assertThat(it.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(it.headers[HttpHeaders.WWWAuthenticate]).isEqualTo(challenge)
        }
        assertThat(responses[3].contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        assertThat(Json.parseToJsonElement(responses[3].bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content).startsWith("Unauthorized")
    }

    @Test
    fun `a malformed Authorization header is no token`() = server {
        val client = http()
        for (value in listOf("Bearer", "Bearer  ", "Basic ${keys.accessToken()}", "bearer a b", "Token ${keys.accessToken()}", "Bearer not-a-jwt")) {
            val response = client.get("/api/run") { header(HttpHeaders.Authorization, value) }
            assertThat(response.status).describedAs(value).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).isEqualTo(challenge)
        }
    }

    @Test
    fun `a token signed by another key is refused`() = server {
        assertRefused(OpenIdTestKeys.rsa(issuer = issuer, audience = audience).accessToken())
    }

    @Test
    fun `a token of another issuer is refused`() = server {
        assertRefused(keys.accessToken { issuer = "https://evil.example.com" })
    }

    @Test
    fun `a token for another audience is refused`() = server {
        assertRefused(keys.accessToken { audience = "https://other.example.com/mcp" })
    }

    @Test
    fun `an expired token is refused`() = server {
        assertRefused(keys.accessToken { issuedAt = Clock.System.now() - 3.hours; expiresAt = Clock.System.now() - 2.hours })
    }

    @Test
    fun `a token without an expiry is refused`() = server {
        assertRefused(keys.accessToken { expiresAt = null })
    }

    @Test
    fun `an ID token, whose audience is the client, is refused`() = server {
        assertRefused(keys.idToken("ana") { audience = "atlas-web" })
    }

    @Test
    fun `a token that says it is not an access token is refused`() = server {
        assertRefused(keys.accessToken { claim("token_use", "id") })
    }

    @Test
    fun `a token with algorithm none is refused`() = server {
        val payload = """{"iss":"$issuer","aud":"$audience","exp":${inOneHour.time / 1000},"sub":"ana"}"""
        assertRefused(segment("""{"alg":"none","typ":"JWT","kid":"kid-1"}""") + "." + segment(payload) + ".")
    }

    @Test
    fun `a token with a shared-secret algorithm is refused`() = server {
        val token = JWT.create().withKeyId("kid-1").withIssuer(issuer).withAudience(audience).withExpiresAt(inOneHour)
            .sign(Algorithm.HMAC256("secret"))
        assertRefused(token)
    }

    @Test
    fun `a token in the query string is never accepted`() = server {
        val client = http()
        assertThat(client.mcpPost("?access_token=${valid()}").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.get("/api/run?access_token=${valid()}").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(http(valid()).get("/api/run?access_token=x").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `the path is read as the router reads it`() = server {
        val client = http()
        for (path in listOf("/%6dcp", "/%61pi/run", "/api//run", "/mcp/", "/mcp/x")) {
            assertThat(client.get(path).status).describedAs(path).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun `a valid token reaches the MCP tools and the api`() = server {
        val http = http(valid())
        assertThat(http.get("/api/run").status).isEqualTo(HttpStatusCode.OK)
        val client = Client(Implementation("atlas-test", "1"))
        client.connect(StreamableHttpClientTransport(http, "/mcp"))
        try {
            assertThat(client.serverVersion!!.name).isEqualTo("zpa-atlas")
            assertThat(client.listTools().tools.map { it.name }).contains("search_entities", "find_impact")
            val answer = client.callTool(CallToolRequest(CallToolRequestParams(name = "search_entities", arguments = buildJsonObject { put("name", "run_job") })))
            assertThat(answer.isError).isNotEqualTo(true)
            assertThat((answer.content.single() as TextContent).text).contains("RUN_JOB")
        } finally {
            client.close()
        }
    }

    @Test
    fun `the public host and origin are accepted by mcp, and another origin is not`() = server {
        val client = http(valid())
        val ok = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.Origin, "https://atlas.example.com")
            setBody(initialize)
        }
        assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
        val other = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.Origin, "https://evil.example.com")
            setBody(initialize)
        }
        assertThat(other.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun `a required scope gives 403 without it, and is read from scope and from scp`() = server(AuthSettings(issuer, publicUrl, scope = "atlas.read")) {
        val forbidden = """Bearer error="insufficient_scope", scope="atlas.read", resource_metadata="https://atlas.example.com/.well-known/oauth-protected-resource/mcp""""
        for (token in listOf(
            valid(),
            keys.accessToken { claim("scope", "openid atlas.write") },
            keys.accessToken { claim("scp", listOf("atlas.write")) },
            keys.accessToken { claim("scope", "atlas.read.more") }
        )) {
            val client = http(token)
            for (response in listOf(client.mcpPost(), client.get("/api/run"))) {
                assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
                assertThat(response.headers[HttpHeaders.WWWAuthenticate]).isEqualTo(forbidden)
            }
        }
        for (token in listOf(
            keys.accessToken { claim("scope", "openid atlas.read") },
            keys.accessToken { claim("scp", "atlas.read") },
            keys.accessToken { claim("scp", listOf("other", "atlas.read")) }
        )) {
            val client = http(token)
            assertThat(client.get("/api/run").status).isEqualTo(HttpStatusCode.OK)
            assertThat(client.mcpPost().status).isEqualTo(HttpStatusCode.OK)
        }
        for (response in listOf(http().get("/api/run"), http().mcpPost())) {
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).isEqualTo("$challenge, scope=\"atlas.read\"")
        }
    }

    @Test
    fun `an audience of its own replaces the resource`() = server(AuthSettings(issuer, publicUrl, audience = "api://atlas")) {
        assertThat(http(keys.accessToken { audience = "api://atlas" }).get("/api/run").status).isEqualTo(HttpStatusCode.OK)
        assertRefused(valid())
    }

    @Test
    fun `the metadata is served without a token at the resource path, and not at the root`() = server(AuthSettings(issuer, publicUrl, scope = "atlas.read")) {
        assertThat(http().get("/.well-known/oauth-protected-resource").status).isEqualTo(HttpStatusCode.NotFound)
        for (path in listOf("/.well-known/oauth-protected-resource/mcp")) {
            val response = http().get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
            assertThat(response.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
            val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(document.keys).containsExactlyInAnyOrder("resource", "authorization_servers", "scopes_supported", "bearer_methods_supported")
            assertThat(document["resource"]!!.jsonPrimitive.content).isEqualTo("https://atlas.example.com/mcp")
            assertThat(document["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly(issuer)
            assertThat(document["scopes_supported"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("atlas.read")
            assertThat(document["bearer_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("header")
        }
    }

    @Test
    fun `the metadata lists no scope when none is required`() = server {
        val document = Json.parseToJsonElement(http().get("/.well-known/oauth-protected-resource/mcp").bodyAsText()).jsonObject
        assertThat(document.keys).containsExactlyInAnyOrder("resource", "authorization_servers", "bearer_methods_supported")
    }

    @Test
    fun `the page loads without a token`() = server {
        val response = http().get("/")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).contains("zpa-atlas")
        assertThat(http().get("/entity.html").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `keys the provider cannot give answer 503 with a short message, not a stack trace`() = server(
        keyProvider = JwkProvider { throw NetworkException("down", IOException("connection refused")) }
    ) {
        for (response in listOf(http(valid()).get("/api/run"), http(valid()).mcpPost())) {
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            val text = response.bodyAsText()
            assertThat(Json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content).contains("identity provider")
            assertThat(text).doesNotContain("Exception", "at com.", "connection refused")
        }
    }

    @Test
    fun `a token is never in an answer`() = server {
        val token = valid()
        val response = http(token).get("/api/entities?q=run_job")
        assertThat(response.bodyAsText()).doesNotContain(token)
        assertThat(response.headers.entries().flatMap { it.value }).noneMatch { token in it }
    }

    @Test
    fun `the settings refuse what a client could not use`() {
        assertThatThrownBy { AuthSettings(issuer, "http://atlas.example.com") }.hasMessageContaining("https, or http for localhost")
        assertThatThrownBy { AuthSettings("http://id.example.com", publicUrl) }.hasMessageContaining("https, or http for localhost")
        assertThatThrownBy { AuthSettings(issuer, "https://atlas.example.com/atlas") }.hasMessageContaining("address alone")
        assertThatThrownBy { AuthSettings(issuer, publicUrl, scope = "a b") }.hasMessageContaining("one token")
        val local = AuthSettings("http://localhost:8180/realms/atlas", "http://localhost:8080/")
        assertThat(local.resource).isEqualTo("http://localhost:8080/mcp")
        assertThat(local.expectedAudience).isEqualTo("http://localhost:8080/mcp")
        assertThat(local.publicHost).isEqualTo("localhost")
    }

    @Test
    fun `an issuer that cannot be read stops the server before it serves`() {
        val auth = AuthSettings("http://127.0.0.1:1/realms/atlas", "http://localhost:8080")
        assertThatThrownBy { serve(index, "127.0.0.1", 0, McpSettings(), auth) }
            .isInstanceOf(AuthDiscoveryException::class.java)
            .hasMessageContaining("Cannot read the OpenID Connect configuration of http://127.0.0.1:1/realms/atlas")
    }
}
