//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.auth0.jwk.JwkProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Makes `/mcp` and `/api` accept only the access tokens an OpenID Connect provider issues for this server.
 *
 * @param issuer the provider's issuer URL: its discovery document, at `<issuer>/.well-known/openid-configuration`,
 *     names its signing keys, and an access token's `iss` must be this
 * @param publicUrl the server as clients reach it, such as `https://atlas.example.com`: no path. `http` is allowed
 *     for a loopback host only. The resource a token is for is this plus `/mcp`
 * @param audience the value an access token carries in `aud`; the resource by default. It must not be the id of a
 *     client that signs users in: an ID token's `aud` is that, and the audience is what refuses it
 * @param scope a scope an access token must carry, in `scope` or `scp`; none required by default
 */
data class AuthSettings(val issuer: String, val publicUrl: String, val audience: String? = null, val scope: String? = null) {
    val base: String = publicUrl.trimEnd('/')

    val resource: String = "$base/mcp"

    val metadataUrl: String = "$base/$METADATA_PATH/mcp"

    val expectedAudience: String = audience ?: resource

    init {
        require(issuer.isNotBlank()) { "The issuer URL is empty" }
        val issuerUri = uri("The issuer URL", issuer)
        require(issuerUri.scheme == "https" || issuerUri.scheme == "http" && isLoopback(issuerUri.host)) {
            "The issuer URL must be https, or http for localhost: $issuer"
        }
        val publicUri = uri("The public URL", publicUrl)
        require(publicUri.scheme == "https" || publicUri.scheme == "http" && isLoopback(publicUri.host)) {
            "The public URL must be https, or http for localhost: $publicUrl"
        }
        require(publicUri.host != null && publicUri.rawPath.orEmpty().trim('/').isEmpty() && publicUri.rawQuery == null && publicUri.rawFragment == null && publicUri.rawUserInfo == null) {
            "The public URL is the server's address alone, such as https://atlas.example.com: $publicUrl"
        }
        require(audience == null || audience.isNotBlank()) { "The audience is empty" }
        require(scope == null || scope.isNotBlank() && scope.none { it.isWhitespace() || it == '"' }) { "The scope is one token, without spaces or quotes" }
    }

    val publicHost: String = URI(publicUrl).host.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }

    private fun uri(what: String, value: String): URI = try {
        URI(value)
    } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("$what is not a URL: $value")
    }

    companion object {
        const val METADATA_PATH = ".well-known/oauth-protected-resource"

        private fun isLoopback(host: String?) = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    }
}

internal class Identity(val issuer: () -> String, val keys: () -> JwkProvider)

internal typealias IdentityInstaller = Application.(AuthSettings) -> Identity

internal fun Application.atlasAuth(auth: AuthSettings, identity: IdentityInstaller) {
    install(BearerGate) {
        this.auth = auth
        this.identity = identity(auth)
    }
    val metadata = buildJsonObject {
        put("resource", auth.resource)
        put("authorization_servers", JsonArray(listOf(JsonPrimitive(auth.issuer))))
        auth.scope?.let { put("scopes_supported", JsonArray(listOf(JsonPrimitive(it)))) }
        put("bearer_methods_supported", JsonArray(listOf(JsonPrimitive("header"))))
    }.toString()
    routing {
        get("/${AuthSettings.METADATA_PATH}/mcp") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(metadata, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
