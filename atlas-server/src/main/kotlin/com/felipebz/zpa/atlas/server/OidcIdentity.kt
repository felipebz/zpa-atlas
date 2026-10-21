//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class)

package com.felipebz.zpa.atlas.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.oidc.Oidc
import io.ktor.server.auth.oidc.OidcDiscoveryException
import io.ktor.server.auth.oidc.OidcProviderConfig
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

class AuthDiscoveryException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * The identity of [AuthSettings.issuer], from its discovery document (read now, and refreshed by the plugin), with its
 * signing keys cached and rate limited by the plugin.
 *
 * @throws AuthDiscoveryException when the discovery document cannot be read
 */
internal fun Application.oidcIdentity(auth: AuthSettings): Identity = oidcIdentity(auth) {}

internal fun Application.oidcIdentity(auth: AuthSettings, configure: OidcProviderConfig.() -> Unit): Identity {
    val oidc = install(Oidc) {
        initialDiscoveryAttempts = 3
        initialDiscoveryRetryDelay = 2.seconds
    }
    val provider = try {
        runBlocking {
            oidc.identityProvider("atlas") {
                issuer = auth.issuer
                configure()
            }
        }
    } catch (e: OidcDiscoveryException) {
        throw AuthDiscoveryException("Cannot read the OpenID Connect configuration of ${auth.issuer}: ${e.cause?.message ?: e.message}", e)
    }
    return Identity(
        issuer = { provider.currentMetadata().issuer },
        keys = { provider.currentJwkProvider() },
        endpoints = { provider.currentMetadata().let { ProviderEndpoints(it.authorizationEndpoint, it.tokenEndpoint, it.endSessionEndpoint) } }
    )
}
