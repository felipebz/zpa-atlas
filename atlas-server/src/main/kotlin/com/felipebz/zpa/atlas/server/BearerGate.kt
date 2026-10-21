//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.NetworkException
import com.auth0.jwk.RateLimitReachedException
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.request.path
import io.ktor.server.response.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

internal class BearerGateConfig {
    lateinit var auth: AuthSettings
    lateinit var identity: Identity
}

/**
 * Answers 401, 403 or 503 to a request to `/mcp` or `/api` that does not carry an access token this server accepts,
 * before the route that would answer it sees the request. The token is read from the `Authorization` header alone:
 * a query parameter is never looked at. It is never logged.
 */
internal val BearerGate = createApplicationPlugin("BearerGate", ::BearerGateConfig) {
    val auth = pluginConfig.auth
    val verifier = AccessTokenVerifier(pluginConfig.identity, auth.expectedAudience)
    val resourceMetadata = "resource_metadata=\"${auth.metadataUrl}\""
    onCall { call ->
        if (!isProtected(call)) return@onCall
        when (val verdict = verifier.verify(call.request.headers.getAll(HttpHeaders.Authorization))) {
            is TokenVerdict.Valid -> if (auth.scope != null && auth.scope !in verdict.scopes) {
                call.application.log.debug("Access token refused: scope {} missing", auth.scope)
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer error=\"insufficient_scope\", scope=\"${auth.scope}\", $resourceMetadata")
                call.respondJson(ApiError("The access token lacks the scope ${auth.scope}"), HttpStatusCode.Forbidden)
            }
            is TokenVerdict.Invalid -> {
                call.application.log.debug("Access token refused: {}", verdict.reason)
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer $resourceMetadata" + (auth.scope?.let { ", scope=\"$it\"" } ?: ""))
                call.respondJson(ApiError("Unauthorized: an access token from ${auth.issuer} is required"), HttpStatusCode.Unauthorized)
            }
            is TokenVerdict.Unavailable -> {
                call.application.log.warn("Access token not checked: {}", verdict.reason)
                call.respondJson(ApiError("The identity provider cannot be reached to check the access token; try again later"), HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

private fun isProtected(call: ApplicationCall): Boolean {
    val first = call.request.path().split('/').firstOrNull { it.isNotEmpty() } ?: return false
    val name = try { first.decodeURLPart() } catch (_: Exception) { return true }
    return name == "mcp" || name == "api"
}

internal sealed interface TokenVerdict {
    class Valid(val scopes: Set<String>) : TokenVerdict

    class Invalid(val reason: String) : TokenVerdict

    class Unavailable(val reason: String) : TokenVerdict
}

/**
 * Checks a JWT access token against [identity]: a signature of an algorithm in [ALGORITHMS] by a key of the issuer's
 * JWKS, `iss`, `aud` (one of [audience]), an `exp` that has not passed (a minute of clock skew is allowed), and that
 * it is not an ID token, by its `typ` header or `token_use` claim when the provider sets them.
 */
internal class AccessTokenVerifier(private val identity: Identity, private val audience: String) {
    suspend fun verify(authorization: List<String>?): TokenVerdict {
        val token = bearer(authorization) ?: return TokenVerdict.Invalid("no bearer token")
        val jwt = try {
            JWT.decode(token)
        } catch (_: JWTDecodeException) {
            return TokenVerdict.Invalid("not a JWT")
        }
        val name = jwt.algorithm
        if (name == null || name !in ALGORITHMS) return TokenVerdict.Invalid("algorithm $name is not accepted")
        val jwk = try {
            withContext(Dispatchers.IO) { identity.keys().get(jwt.keyId) }
        } catch (e: NetworkException) {
            return TokenVerdict.Unavailable("the JWKS cannot be read: ${e.message}")
        } catch (e: RateLimitReachedException) {
            return TokenVerdict.Unavailable("the JWKS request rate limit is exhausted")
        } catch (e: SigningKeyNotFoundException) {
            // Without a cause, the JWKS was read and has no key of that id; with one, what it answered is unusable.
            return if (e.cause == null) TokenVerdict.Invalid("no key ${jwt.keyId} in the JWKS") else TokenVerdict.Unavailable("the JWKS holds no usable keys: ${e.cause?.message}")
        } catch (e: JwkException) {
            return TokenVerdict.Invalid("key ${jwt.keyId}: ${e.message}")
        } catch (e: RuntimeException) {
            return TokenVerdict.Unavailable("the JWKS cannot be read: ${e.message}")
        }
        val algorithm = algorithm(name, jwk) ?: return TokenVerdict.Invalid("key ${jwt.keyId} cannot verify $name")
        val verified = try {
            JWT.require(algorithm)
                .withIssuer(identity.issuer())
                .withAnyOfAudience(audience)
                .acceptLeeway(CLOCK_SKEW_SECONDS)
                .build()
                .verify(jwt)
        } catch (e: JWTVerificationException) {
            return TokenVerdict.Invalid("verification failed: ${e.javaClass.simpleName}")
        }
        if (verified.expiresAt == null) return TokenVerdict.Invalid("no exp")
        val tokenUse = verified.getClaim("token_use").asString()
        if (tokenUse != null && tokenUse.lowercase() !in ACCESS_TOKEN_USES) return TokenVerdict.Invalid("token_use $tokenUse is not an access token")
        val type = verified.type?.lowercase()?.removePrefix("application/")
        if (type != null && type !in ACCESS_TOKEN_TYPES) return TokenVerdict.Invalid("typ $type is not an access token")
        return TokenVerdict.Valid(scopes(verified))
    }

    private fun bearer(headers: List<String>?): String? {
        val header = headers?.singleOrNull() ?: return null
        val parts = header.trim().split(' ').filter { it.isNotEmpty() }
        return parts.takeIf { it.size == 2 && it[0].equals("Bearer", ignoreCase = true) }?.get(1)
    }

    private fun algorithm(name: String, jwk: Jwk): Algorithm? {
        if (jwk.usage != null && jwk.usage != "sig") return null
        if (jwk.algorithm != null && jwk.algorithm != name) return null
        val key = try { jwk.publicKey } catch (_: Exception) { return null }
        return when {
            jwk.type == "RSA" && key is RSAPublicKey -> when (name) {
                "RS256" -> Algorithm.RSA256(key, null)
                "RS384" -> Algorithm.RSA384(key, null)
                "RS512" -> Algorithm.RSA512(key, null)
                else -> null
            }
            jwk.type == "EC" && key is ECPublicKey -> {
                val curve = jwk.additionalAttributes["crv"] as? String
                when {
                    name == "ES256" && (curve == null || curve == "P-256") -> Algorithm.ECDSA256(key, null)
                    name == "ES384" && (curve == null || curve == "P-384") -> Algorithm.ECDSA384(key, null)
                    name == "ES512" && (curve == null || curve == "P-521") -> Algorithm.ECDSA512(key, null)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun scopes(jwt: DecodedJWT): Set<String> {
        val found = LinkedHashSet<String>()
        for (name in listOf("scope", "scp")) {
            val claim = jwt.getClaim(name)
            claim.asString()?.let { found += it.split(' ').filter(String::isNotEmpty) }
            claim.asList(String::class.java)?.let { list -> list.forEach { found += it.split(' ').filter(String::isNotEmpty) } }
        }
        return found
    }

    companion object {
        val ALGORITHMS = setOf("RS256", "RS384", "RS512", "ES256", "ES384", "ES512")

        private const val CLOCK_SKEW_SECONDS = 60L
        private val ACCESS_TOKEN_USES = setOf("access_token", "access")
        private val ACCESS_TOKEN_TYPES = setOf("jwt", "at+jwt", "bearer")
    }
}
