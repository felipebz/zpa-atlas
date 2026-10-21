//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class, ExperimentalTime::class)

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.auth.oidc.OpenIdProviderMetadata
import io.ktor.server.auth.oidc.OpenIdTestKeys
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageSignInTest {
    private val temp: Path = Files.createTempDirectory("atlas-signin-test")
    private lateinit var index: AtlasIndex

    private val issuer = "https://id.example.com/realms/atlas"
    private val publicUrl = "https://atlas.example.com"
    private val audience = "https://atlas.example.com/mcp"
    private val clientId = "atlas-web"
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

    private class Provider {
        val requests = ArrayList<Pair<Parameters, String?>>()
        var answer: (Parameters) -> String = { "{}" }
    }

    private class Scenario(val builder: ApplicationTestBuilder, val provider: Provider, val browser: HttpClient)

    private fun server(
        auth: AuthSettings = AuthSettings(issuer, publicUrl, page = PageClient(clientId)),
        test: suspend Scenario.() -> Unit
    ) = testApplication {
        val provider = Provider()
        externalServices {
            hosts("https://id.example.com") {
                routing {
                    post("/realms/atlas/token") {
                        val form = call.receiveParameters()
                        provider.requests += form to call.request.header(HttpHeaders.Authorization)
                        call.respondText(provider.answer(form), ContentType.Application.Json)
                    }
                }
            }
        }
        val idp = createClient { }
        application {
            atlasModule(index, McpSettings(), auth, { a ->
                oidcIdentity(a) {
                    metadata = this@PageSignInTest.metadata
                    jwt { jwkProviderFactory = { keys.jwkProvider } }
                }
            }) { idp }
        }
        val browser = createClient {
            followRedirects = false
            defaultRequest { header(HttpHeaders.Host, "atlas.example.com") }
        }
        Scenario(this, provider, browser).test()
    }

    private fun tokens(access: String = keys.accessToken { subject = "ana" }, id: (String) -> String = { nonce -> idToken(nonce) }, refresh: String? = null): (Parameters) -> String =
        { tokenJson(access, id(currentNonce), refresh) }

    /** The nonce of the last sign-in started, which the fake provider puts in its ID token. */
    private var currentNonce = ""

    private fun idToken(nonce: String, clientAudience: String = clientId) = keys.idToken("ana") {
        audience = clientAudience
        this.nonce = nonce
        claim("preferred_username", "ana.souza")
    }

    private fun tokenJson(access: String, id: String?, refresh: String?) = buildJsonObject {
        put("access_token", access)
        put("token_type", "Bearer")
        id?.let { put("id_token", it) }
        refresh?.let { put("refresh_token", it) }
    }.toString()

    private fun query(location: String) = Url(location).parameters

    private suspend fun Scenario.start(returnTo: String? = "/entity.html?key=db:HR.COMPANY"): Pair<HttpResponse, String> {
        val response = browser.get("/auth/login" + (returnTo?.let { "?return=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: ""))
        val cookie = response.setCookie(PageSessions.LOGIN_COOKIE)!!
        currentNonce = query(response.headers[HttpHeaders.Location]!!)["nonce"]!!
        return response to cookie
    }

    private suspend fun Scenario.signIn(returnTo: String? = "/entity.html?key=db:HR.COMPANY", answer: (Parameters) -> String = tokens()): HttpResponse {
        provider.answer = answer
        val (login, cookie) = start(returnTo)
        val state = query(login.headers[HttpHeaders.Location]!!)["state"]!!
        return browser.get("/auth/callback?code=c1&state=$state") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
    }

    private fun HttpResponse.setCookie(name: String) = headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.substringBefore(";")

    private suspend fun Scenario.withSession(session: String, path: String) = browser.get(path) { header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$session") }

    @Test
    fun `the login redirects to the provider with PKCE, state, nonce, the scopes and the redirect URI`() = server(
        AuthSettings(issuer, publicUrl, scope = "atlas.read", page = PageClient(clientId))
    ) {
        val (response, cookie) = start()
        assertThat(response.status).isEqualTo(HttpStatusCode.Found)
        val location = Url(response.headers[HttpHeaders.Location]!!)
        assertThat(location.toString()).startsWith("$issuer/auth?")
        val q = location.parameters
        assertThat(q["response_type"]).isEqualTo("code")
        assertThat(q["client_id"]).isEqualTo(clientId)
        assertThat(q["redirect_uri"]).isEqualTo("https://atlas.example.com/auth/callback")
        assertThat(q["scope"]).isEqualTo("openid atlas.read")
        assertThat(q["code_challenge_method"]).isEqualTo("S256")
        assertThat(q["code_challenge"]).isNotBlank()
        assertThat(q["state"]).isEqualTo(cookie).hasSizeGreaterThan(40)
        assertThat(q["nonce"]).hasSizeGreaterThan(40).isNotEqualTo(q["state"])
        assertThat(response.headers.getAll(HttpHeaders.SetCookie)!!.single()).contains("Path=/auth", "HttpOnly", "SameSite=Lax", "Secure")
    }

    @Test
    fun `the code is exchanged with the PKCE verifier, and a public client sends its id`() = server {
        val (login, cookie) = start()
        val challenge = query(login.headers[HttpHeaders.Location]!!)["code_challenge"]
        provider.answer = tokens()
        val state = query(login.headers[HttpHeaders.Location]!!)["state"]!!
        browser.get("/auth/callback?code=c1&state=$state") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
        val (form, authorization) = provider.requests.single()
        assertThat(form["grant_type"]).isEqualTo("authorization_code")
        assertThat(form["code"]).isEqualTo("c1")
        assertThat(form["redirect_uri"]).isEqualTo("https://atlas.example.com/auth/callback")
        assertThat(form["client_id"]).isEqualTo(clientId)
        assertThat(authorization).isNull()
        val verifier = form["code_verifier"]!!
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))).isEqualTo(challenge)
    }

    @Test
    fun `a confidential client authenticates to the token endpoint with its secret`() = server(AuthSettings(issuer, publicUrl, page = PageClient(clientId, "s3cret"))) {
        signIn()
        val (form, authorization) = provider.requests.single()
        assertThat(form["client_id"]).isNull()
        assertThat(authorization).isEqualTo("Basic " + Base64.getEncoder().encodeToString("$clientId:s3cret".toByteArray()))
    }

    @Test
    fun `the return path is kept for a path of this server, and anything else returns to the root`() = server {
        assertThat(signIn("/entity.html?key=db:HR.COMPANY&lang=pt").headers[HttpHeaders.Location]).isEqualTo("https://atlas.example.com/entity.html?key=db:HR.COMPANY&lang=pt")
        for (bad in listOf("//evil.example.com", "https://evil.example.com", "/\\evil", "evil", "", null)) {
            assertThat(signIn(bad).headers[HttpHeaders.Location]).describedAs(bad).isEqualTo("https://atlas.example.com/")
        }
    }

    @Test
    fun `the callback creates a session that the api accepts and mcp does not`() = server {
        val callback = signIn()
        assertThat(callback.status).isEqualTo(HttpStatusCode.Found)
        val header = callback.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("${PageSessions.COOKIE}=") }
        val attributes = header.split(";").map { it.trim() }
        assertThat(attributes).contains("Path=/", "HttpOnly", "Secure", "SameSite=Lax")
        assertThat(attributes).noneMatch { it.startsWith("Max-Age") || it.startsWith("Expires") || it.startsWith("$") }
        val session = callback.setCookie(PageSessions.COOKIE)!!
        assertThat(session).hasSizeGreaterThan(40).doesNotContain(".")
        assertThat(withSession(session, "/api/run").status).isEqualTo(HttpStatusCode.OK)
        val mcp = browser.post("/mcp") {
            header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$session")
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        }
        assertThat(mcp.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(withSession("not-a-session", "/api/run").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `a callback whose access token lacks the audience creates no session`() = server {
        val callback = signIn(answer = tokens(access = keys.accessToken { audience = this@PageSignInTest.clientId }))
        assertThat(callback.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(callback.setCookie(PageSessions.COOKIE)).isNullOrEmpty()
        assertThat(callback.bodyAsText()).contains("Sign-in refused", "not one this server accepts").doesNotContain("eyJ")
    }

    @Test
    fun `a callback whose access token lacks the scope creates no session`() = server(AuthSettings(issuer, publicUrl, scope = "atlas.read", page = PageClient(clientId))) {
        val callback = signIn(answer = tokens(access = keys.accessToken { claim("scope", "openid profile") }))
        assertThat(callback.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(callback.setCookie(PageSessions.COOKIE)).isNullOrEmpty()
        assertThat(callback.bodyAsText()).contains("lacks the scope atlas.read")
        val allowed = signIn(answer = tokens(access = keys.accessToken { claim("scope", "openid atlas.read") }))
        assertThat(allowed.setCookie(PageSessions.COOKIE)).isNotEmpty()
    }

    @Test
    fun `a wrong state creates no session, and nor does a state started in another browser`() = server {
        provider.answer = tokens()
        val (login, cookie) = start()
        val state = query(login.headers[HttpHeaders.Location]!!)["state"]!!
        val wrong = browser.get("/auth/callback?code=c1&state=other") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
        assertThat(wrong.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(wrong.setCookie(PageSessions.COOKIE)).isNull()
        val unbound = browser.get("/auth/callback?code=c1&state=$state")
        assertThat(unbound.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(unbound.setCookie(PageSessions.COOKIE)).isNull()
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `a state is used once`() = server {
        provider.answer = tokens()
        val (login, cookie) = start()
        val state = query(login.headers[HttpHeaders.Location]!!)["state"]!!
        val first = browser.get("/auth/callback?code=c1&state=$state") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
        assertThat(first.setCookie(PageSessions.COOKIE)).isNotEmpty()
        val again = browser.get("/auth/callback?code=c1&state=$state") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
        assertThat(again.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `an ID token with another nonce or for another client creates no session`() = server {
        for (id in listOf<(String) -> String>({ idToken("another-nonce") }, { nonce -> idToken(nonce, clientAudience = "other-client") })) {
            val callback = signIn(answer = tokens(id = id))
            assertThat(callback.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(callback.setCookie(PageSessions.COOKIE)).isNullOrEmpty()
            assertThat(callback.bodyAsText()).contains("ID token was refused")
        }
    }

    @Test
    fun `an error from the provider is shown, escaped`() = server {
        val (login, cookie) = start()
        val state = query(login.headers[HttpHeaders.Location]!!)["state"]!!
        val response = browser.get("/auth/callback?error=%3Cscript%3E&state=$state") { header(HttpHeaders.Cookie, "${PageSessions.LOGIN_COOKIE}=$cookie") }
        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(response.bodyAsText()).contains("&lt;script&gt;").doesNotContain("<script>")
    }

    @Test
    fun `me gives the name with a session and 401 without one`() = server {
        val session = signIn().setCookie(PageSessions.COOKIE)!!
        val me = withSession(session, "/auth/me")
        assertThat(me.status).isEqualTo(HttpStatusCode.OK)
        assertThat(Json.parseToJsonElement(me.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("ana.souza")
        assertThat(browser.get("/auth/me").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `without the page client there is no auth route`() = server(AuthSettings(issuer, publicUrl)) {
        for (path in listOf("/auth/me", "/auth/login", "/auth/callback")) {
            assertThat(browser.get(path).status).describedAs(path).isEqualTo(HttpStatusCode.NotFound)
        }
        assertThat(browser.post("/auth/logout").status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `logout needs the origin or the referer of the public URL`() = server {
        val session = signIn().setCookie(PageSessions.COOKIE)!!
        for (headers in listOf(emptyMap(), mapOf(HttpHeaders.Origin to "https://evil.example.com"), mapOf(HttpHeaders.Referrer to "https://atlas.example.com.evil.com/"),
            mapOf(HttpHeaders.Origin to "null", HttpHeaders.Referrer to "https://atlas.example.com/"))) {
            val response = browser.post("/auth/logout") {
                header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$session")
                headers.forEach { (name, value) -> header(name, value) }
            }
            assertThat(response.status).describedAs(headers.toString()).isEqualTo(HttpStatusCode.Forbidden)
        }
        assertThat(withSession(session, "/api/run").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `logout ends the session and goes to the provider's end-session endpoint`() = server {
        for (header in listOf(HttpHeaders.Origin to "https://atlas.example.com", HttpHeaders.Referrer to "https://atlas.example.com/entity.html?key=x")) {
            val session = signIn().setCookie(PageSessions.COOKIE)!!
            val response = browser.post("/auth/logout") {
                header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$session")
                header(header.first, header.second)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.SeeOther)
            val location = Url(response.headers[HttpHeaders.Location]!!)
            assertThat(location.toString()).startsWith("$issuer/logout?")
            assertThat(location.parameters["id_token_hint"]).isNotBlank()
            assertThat(location.parameters["post_logout_redirect_uri"]).isEqualTo("https://atlas.example.com")
            assertThat(response.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("${PageSessions.COOKIE}=") }).contains("Max-Age=0")
            assertThat(withSession(session, "/api/run").status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(withSession(session, "/auth/me").status).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun `an expired access token is refreshed with the refresh token, and without one the session ends`() = server {
        val soon = keys.accessToken { subject = "ana"; expiresAt = Clock.System.now() + 2.seconds }
        val refreshed = signIn(answer = tokens(access = soon, refresh = "r1")).setCookie(PageSessions.COOKIE)!!
        provider.answer = { form -> tokenJson(keys.accessToken { subject = "ana" }, null, "r2").also { assertThat(form["refresh_token"]).isEqualTo("r1") } }
        assertThat(withSession(refreshed, "/api/run").status).isEqualTo(HttpStatusCode.OK)
        assertThat(provider.requests.last().first["grant_type"]).isEqualTo("refresh_token")
        assertThat(withSession(refreshed, "/auth/me").status).isEqualTo(HttpStatusCode.OK)

        val ending = signIn(answer = tokens(access = soon)).setCookie(PageSessions.COOKIE)!!
        assertThat(withSession(ending, "/api/run").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(withSession(ending, "/auth/me").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `a refresh the provider refuses ends the session`() = server {
        val soon = keys.accessToken { subject = "ana"; expiresAt = Clock.System.now() + 2.seconds }
        val session = signIn(answer = tokens(access = soon, refresh = "r1")).setCookie(PageSessions.COOKIE)!!
        provider.answer = { _ -> tokenJson(keys.accessToken { audience = "other" }, null, null) }
        assertThat(withSession(session, "/api/run").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `a bearer token still works on the api and on mcp beside the sign-in`() = server {
        val token = keys.accessToken()
        assertThat(browser.get("/api/run") { header(HttpHeaders.Authorization, "Bearer $token") }.status).isEqualTo(HttpStatusCode.OK)
        val session = signIn().setCookie(PageSessions.COOKIE)!!
        // A header is checked by itself: an invalid one is not saved by a valid session.
        val both = browser.get("/api/run") {
            header(HttpHeaders.Authorization, "Bearer nope")
            header(HttpHeaders.Cookie, "${PageSessions.COOKIE}=$session")
        }
        assertThat(both.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    /** [PageSessions] with room for [max] sign-ins and sessions, its token endpoint the fake provider's. */
    private fun bounded(max: Int, test: suspend (PageSessions, Provider) -> Unit) = testApplication {
        val provider = Provider()
        externalServices {
            hosts("https://id.example.com") {
                routing {
                    post("/realms/atlas/token") {
                        provider.requests += call.receiveParameters() to null
                        call.respondText(provider.answer(Parameters.Empty), ContentType.Application.Json)
                    }
                }
            }
        }
        application { }
        startApplication()
        val identity = Identity({ issuer }, { keys.jwkProvider }, { ProviderEndpoints("$issuer/auth", "$issuer/token", null) })
        val auth = AuthSettings(issuer, publicUrl, page = PageClient(clientId))
        test(PageSessions(auth, auth.page!!, identity, AccessTokenVerifier(identity, audience), createClient { }, max), provider)
    }

    private fun PageSessions.start(): String = Url(login("/").second).parameters.let { currentNonce = it["nonce"]!!; it["state"]!! }

    @Test
    fun `past the maximum the sign-ins in progress do not grow, and the oldest is dropped`() = bounded(3) { sessions, provider ->
        val states = (1..5).map { sessions.start() }
        assertThat(sessions.pendingLogins).isEqualTo(3)
        provider.answer = tokens()
        for (dropped in states.take(2)) {
            val refused = sessions.callback(dropped, "c1")
            assertThat(refused).isInstanceOf(PageSessions.SignIn.Refused::class.java)
            assertThat((refused as PageSessions.SignIn.Refused).message).contains("expired")
        }
        assertThat(provider.requests).isEmpty()
        sessions.start()
        assertThat(sessions.pendingLogins).isEqualTo(3)
    }

    @Test
    fun `a sign-in within the limit still finishes`() = bounded(3) { sessions, provider ->
        repeat(10) { sessions.start() }
        val state = sessions.start()
        provider.answer = tokens()
        assertThat(sessions.callback(state, "c1")).isInstanceOf(PageSessions.SignIn.Done::class.java)
        assertThat(sessions.pendingLogins).isEqualTo(2)
    }

    @Test
    fun `past the maximum the sessions do not grow, and the one unused the longest ends`() = bounded(2) { sessions, provider ->
        suspend fun signedIn(): String {
            val state = sessions.start()
            provider.answer = tokens()
            return (sessions.callback(state, "c1") as PageSessions.SignIn.Done).sessionId
        }
        val first = signedIn()
        val second = signedIn()
        assertThat(sessions.access(first)).isNotNull()
        signedIn()
        assertThat(sessions.openSessions).isEqualTo(2)
        assertThat(sessions.access(second)).isNull()
        assertThat(sessions.access(first)).isNotNull()
    }

    @Test
    fun `the page client id must not be the audience`() {
        assertThatThrownBy { AuthSettings(issuer, publicUrl, page = PageClient(audience)) }.hasMessageContaining("client id is the audience")
        assertThatThrownBy { AuthSettings(issuer, publicUrl, audience = "api://atlas", page = PageClient("api://atlas")) }.hasMessageContaining("client id is the audience")
        assertThat(PageClient("c", "s3cret").toString()).doesNotContain("s3cret")
    }
}
