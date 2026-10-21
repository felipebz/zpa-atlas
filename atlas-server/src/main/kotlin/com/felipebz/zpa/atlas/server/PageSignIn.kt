//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The client the page signs in with: [clientId] at the provider, and [clientSecret] for a confidential client; without
 * one, the client is public and PKCE alone protects the code.
 */
class PageClient(val clientId: String, val clientSecret: String? = null) {
    init {
        require(clientId.isNotBlank()) { "The client id is empty" }
        require(clientSecret == null || clientSecret.isNotEmpty()) { "The client secret is empty" }
    }

    override fun toString() = "PageClient(clientId=$clientId)"
}

internal class ProviderEndpoints(val authorization: String, val token: String, val endSession: String?)

@Serializable
private class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null
)

private val TokenJson = Json { ignoreUnknownKeys = true }

/**
 * The page's signed-in sessions, in memory: a restart ends them all. A session holds the tokens of one sign-in; the
 * browser holds only its id, in the cookie [COOKIE].
 */
internal class PageSessions(
    private val auth: AuthSettings,
    private val page: PageClient,
    private val identity: Identity,
    private val verifier: AccessTokenVerifier,
    private val http: HttpClient,
    private val maxEntries: Int = MAX_ENTRIES
) {
    class Session(tokens: Tokens, val name: String) {
        @Volatile
        var tokens: Tokens = tokens

        val lock = Mutex()
    }

    class Tokens(val access: String, val accessExpires: Instant, val refresh: String?, val refreshExpires: Instant?, val id: String?)

    private class Login(val nonce: String, val verifier: String, val returnTo: String, val started: Instant)

    // Least recently used first: past the maximum, the session unused the longest ends.
    private val sessions = BoundedMap<Session>(maxEntries, accessOrder = true)

    // Oldest first: past the maximum, the oldest sign-in in progress is dropped, and its user sees the "expired" page.
    private val logins = BoundedMap<Login>(maxEntries, accessOrder = false)

    val pendingLogins: Int get() = logins.size

    val openSessions: Int get() = sessions.size

    /** The session of [id], with an access token that has not expired, refreshed when it has; null when it ended. */
    suspend fun access(id: String): Session? {
        val session = sessions[id] ?: return null
        if (fresh(session.tokens)) return session
        return session.lock.withLock {
            val tokens = session.tokens
            if (fresh(tokens)) return session
            val refreshed = tokens.refresh?.takeIf { tokens.refreshExpires == null || tokens.refreshExpires.isAfter(Instant.now()) }?.let { refresh(it, tokens.id) }
            if (refreshed == null) {
                sessions.remove(id)
                null
            } else {
                session.tokens = refreshed
                session
            }
        }
    }

    fun end(id: String): Session? = sessions.remove(id)

    private fun fresh(tokens: Tokens) = tokens.accessExpires.isAfter(Instant.now().plusSeconds(5))

    private suspend fun refresh(refreshToken: String, idToken: String?): Tokens? {
        val response = tokenRequest("grant_type" to "refresh_token", "refresh_token" to refreshToken) ?: return null
        val access = response.accessToken ?: return null
        val verdict = verifier.verifyAccessToken(access)
        if (verdict !is TokenVerdict.Valid || auth.scope != null && auth.scope !in verdict.scopes) return null
        return tokens(response, verdict.jwt, response.idToken ?: idToken, refreshToken)
    }

    private fun tokens(response: TokenResponse, access: DecodedJWT, idToken: String?, previousRefresh: String? = null) = Tokens(
        access = response.accessToken!!,
        accessExpires = access.expiresAtAsInstant,
        refresh = response.refreshToken ?: previousRefresh,
        refreshExpires = response.refreshExpiresIn?.takeIf { it > 0 }?.let { Instant.now().plusSeconds(it) },
        id = idToken
    )

    private suspend fun tokenRequest(vararg form: Pair<String, String>): TokenResponse? {
        val secret = page.clientSecret
        val answer = try {
            http.submitForm(identity.endpoints().token, parameters {
                form.forEach { (name, value) -> append(name, value) }
                if (secret == null) append("client_id", page.clientId)
            }) {
                if (secret != null) basicAuth(page.clientId.encodeURLParameter(), secret.encodeURLParameter())
            }
        } catch (e: Exception) {
            throw ProviderUnavailableException(e)
        }
        if (answer.status != HttpStatusCode.OK) return null
        return try {
            TokenJson.decodeFromString(TokenResponse.serializer(), answer.bodyAsText())
        } catch (_: Exception) {
            null
        }
    }

    /** Starts a sign-in that returns to [returnTo]: the state to send, and the URL of the provider's sign-in page. */
    fun login(returnTo: String): Pair<String, String> {
        val now = Instant.now()
        val state = random()
        val login = Login(random(), random(), returnTo, now)
        val expired = now.minus(LOGIN_TTL)
        logins.put(state, login) { it.started.isBefore(expired) }
        val url = URLBuilder(identity.endpoints().authorization).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", page.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", listOfNotNull("openid", auth.scope).joinToString(" "))
            parameters.append("state", state)
            parameters.append("nonce", login.nonce)
            parameters.append("code_challenge", challenge(login.verifier))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
        return state to url
    }

    /** Ends the sign-in of [state] with [code]: the new session's id and where to go, or the reason it was refused. */
    suspend fun callback(state: String, code: String): SignIn {
        val login = logins.remove(state)?.takeIf { it.started.isAfter(Instant.now().minus(LOGIN_TTL)) }
            ?: return SignIn.Refused(HttpStatusCode.BadRequest, "The sign-in expired or was not started here. Sign in again.")
        val response = tokenRequest("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri, "code_verifier" to login.verifier)
        val access = response?.accessToken
        val idToken = response?.idToken
        if (access == null || idToken == null) return SignIn.Refused(HttpStatusCode.BadGateway, "The identity provider did not give the tokens of the sign-in.")
        val id = verifier.verifyIdToken(idToken, page.clientId, login.nonce)
        if (id !is TokenVerdict.Valid) return refused(id, "The identity provider's ID token was refused")
        val verdict = verifier.verifyAccessToken(access)
        if (verdict !is TokenVerdict.Valid) return refused(verdict, "The access token the identity provider gave is not one this server accepts")
        if (auth.scope != null && auth.scope !in verdict.scopes) {
            return SignIn.Refused(HttpStatusCode.Forbidden, "Your account is not allowed to read this index: the access token lacks the scope ${auth.scope}.")
        }
        val session = Session(tokens(response, verdict.jwt, idToken), displayName(id.jwt, verdict.jwt))
        val sessionId = random()
        sessions.put(sessionId, session) { false }
        return SignIn.Done(sessionId, login.returnTo)
    }

    private fun refused(verdict: TokenVerdict, what: String) = when (verdict) {
        is TokenVerdict.Unavailable -> SignIn.Refused(HttpStatusCode.ServiceUnavailable, "The identity provider cannot be reached. Try again later.")
        is TokenVerdict.Invalid -> SignIn.Refused(HttpStatusCode.Unauthorized, "$what: ${verdict.reason}.")
        is TokenVerdict.Valid -> error("not refused")
    }

    fun logoutUrl(ended: Session?): String {
        val endSession = identity.endpoints().endSession ?: return "${auth.base}/"
        return URLBuilder(endSession).apply {
            ended?.tokens?.id?.let { parameters.append("id_token_hint", it) }
            parameters.append("client_id", page.clientId)
            parameters.append("post_logout_redirect_uri", auth.base)
        }.buildString()
    }

    private val redirectUri = "${auth.base}$CALLBACK"

    sealed interface SignIn {
        class Done(val sessionId: String, val returnTo: String) : SignIn

        class Refused(val status: HttpStatusCode, val message: String) : SignIn
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val COOKIE = "atlas_session"
        const val LOGIN_COOKIE = "atlas_login"
        const val CALLBACK = "/auth/callback"
        private val LOGIN_TTL: Duration = Duration.ofMinutes(10)
        private val secureRandom = SecureRandom()
        private val base64 = Base64.getUrlEncoder().withoutPadding()

        private fun random(): String = ByteArray(32).also { secureRandom.nextBytes(it) }.let { base64.encodeToString(it) }

        private fun challenge(verifier: String) = base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun displayName(vararg tokens: DecodedJWT): String {
            for (claim in listOf("preferred_username", "name", "email")) {
                tokens.firstNotNullOfOrNull { it.getClaim(claim).asString()?.takeIf(String::isNotBlank) }?.let { return it }
            }
            return tokens.firstNotNullOfOrNull { it.subject } ?: "?"
        }

        /** [value] when it is a path on this server: one leading `/`, not `//` or `/\`, no control characters. */
        fun safeReturn(value: String?): String {
            if (value == null || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) return "/"
            return value.takeIf { v -> v.none { it < ' ' || it == '\u007f' } } ?: "/"
        }
    }
}

internal class ProviderUnavailableException(cause: Throwable) : RuntimeException(cause.message, cause)

internal fun Application.pageSignIn(auth: AuthSettings, sessions: PageSessions) {
    val secure = URI(auth.publicUrl).scheme == "https"
    val origin = URI(auth.publicUrl).let { "${it.scheme}://${it.rawAuthority}" }

    fun ApplicationCall.cookie(name: String, value: String, path: String, maxAge: Int? = null) = response.headers.append(
        HttpHeaders.SetCookie,
        renderSetCookieHeader(
            name, value, CookieEncoding.RAW, maxAge = maxAge, path = path, secure = secure, httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"), includeEncoding = false
        )
    )

    suspend fun ApplicationCall.refuse(status: HttpStatusCode, message: String) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>zpa-atlas</title></head><body>" +
                "<h1>Sign-in refused</h1><p>${escapeHtml(message)}</p><p><a href=\"/\">zpa-atlas</a></p></body></html>",
            ContentType.Text.Html.withParameter("charset", "utf-8"), status
        )
    }

    routing {
        get("/auth/login") {
            val (state, url) = sessions.login(PageSessions.safeReturn(call.request.queryParameters["return"]))
            call.cookie(PageSessions.LOGIN_COOKIE, state, "/auth", maxAge = 600)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondRedirect(url)
        }
        get(PageSessions.CALLBACK) {
            val query = call.request.queryParameters
            val bound = call.request.cookies[PageSessions.LOGIN_COOKIE]
            call.cookie(PageSessions.LOGIN_COOKIE, "", "/auth", maxAge = 0)
            query["error"]?.let { return@get call.refuse(HttpStatusCode.Forbidden, "The identity provider refused the sign-in: $it.") }
            val state = query["state"]
            val code = query["code"]
            if (state == null || code == null || bound != state) {
                return@get call.refuse(HttpStatusCode.BadRequest, "The sign-in expired or was not started here. Sign in again.")
            }
            query["iss"]?.let { if (it != auth.issuer) return@get call.refuse(HttpStatusCode.BadRequest, "The sign-in answer came from another issuer.") }
            val result = try {
                sessions.callback(state, code)
            } catch (e: ProviderUnavailableException) {
                call.application.log.warn("Sign-in not finished: the token endpoint cannot be reached: {}", e.message)
                PageSessions.SignIn.Refused(HttpStatusCode.ServiceUnavailable, "The identity provider cannot be reached. Try again later.")
            }
            when (result) {
                is PageSessions.SignIn.Refused -> {
                    call.application.log.debug("Sign-in refused: {}", result.message)
                    call.refuse(result.status, result.message)
                }
                is PageSessions.SignIn.Done -> {
                    call.cookie(PageSessions.COOKIE, result.sessionId, "/")
                    call.respondRedirect("${auth.base}${result.returnTo}")
                }
            }
        }
        post("/auth/logout") {
            val requestOrigin = call.request.headers[HttpHeaders.Origin]
            val referer = call.request.headers[HttpHeaders.Referrer]
            val sameSite = if (requestOrigin != null) requestOrigin == origin else referer != null && (referer == auth.base || referer.startsWith("${auth.base}/"))
            if (!sameSite) return@post call.respondJson(ApiError("Sign out from the page of this server"), HttpStatusCode.Forbidden)
            val ended = call.request.cookies[PageSessions.COOKIE]?.let { sessions.end(it) }
            call.cookie(PageSessions.COOKIE, "", "/", maxAge = 0)
            call.response.header(HttpHeaders.Location, sessions.logoutUrl(ended))
            call.respondText("", status = HttpStatusCode.SeeOther)
        }
        get("/auth/me") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val session = try {
                call.request.cookies[PageSessions.COOKIE]?.let { sessions.access(it) }
            } catch (_: ProviderUnavailableException) {
                null
            }
            if (session == null) call.respondJson(ApiError("Not signed in"), HttpStatusCode.Unauthorized) else call.respondJson(SignedIn(session.name))
        }
    }
}

/**
 * A map of at most [max] entries, in insertion order, or in access order with [accessOrder]: past [max], the first
 * entry in that order is dropped. [put] also drops the entries at the front that [stale] says are dead, so it never
 * scans the whole map.
 */
internal class BoundedMap<V>(private val max: Int, accessOrder: Boolean) {
    private val map = LinkedHashMap<String, V>(16, 0.75f, accessOrder)

    val size: Int get() = synchronized(map) { map.size }

    operator fun get(key: String): V? = synchronized(map) { map[key] }

    fun remove(key: String): V? = synchronized(map) { map.remove(key) }

    fun put(key: String, value: V, stale: (V) -> Boolean) = synchronized(map) {
        val entries = map.values.iterator()
        while (entries.hasNext() && stale(entries.next())) entries.remove()
        map[key] = value
        if (map.size > max) map.remove(map.keys.first())
    }
}

@Serializable
internal class SignedIn(val name: String)

private fun escapeHtml(text: String) = buildString {
    for (c in text) append(
        when (c) {
            '<' -> "&lt;"
            '>' -> "&gt;"
            '&' -> "&amp;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> c
        }
    )
}
