//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class)

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.oidc.OpenIdProviderMetadata
import io.ktor.server.auth.oidc.OpenIdTestKeys
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthTest {
    private val temp: Path = Files.createTempDirectory("atlas-health-test")
    private lateinit var database: Path

    @BeforeAll
    fun build() {
        database = Fixture.index(temp.resolve("fixture.db"))
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun server(index: AtlasIndex, auth: AuthSettings? = null, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            val issuer = auth?.issuer ?: "https://unused"
            val keys = OpenIdTestKeys.rsa(issuer = issuer, audience = "unused")
            atlasModule(index, McpSettings(), auth, { a ->
                oidcIdentity(a) {
                    metadata = OpenIdProviderMetadata(issuer = issuer, authorizationEndpoint = "$issuer/auth", tokenEndpoint = "$issuer/token", jwksUri = "$issuer/certs")
                    jwt { jwkProviderFactory = { keys.jwkProvider } }
                }
            }) { error("No provider call") }
        }
        test()
    }

    @Test
    fun `health is 200 with a good index, by GET and HEAD, and says nothing of its contents`() = server(AtlasIndex(database, Fixture.sources)) {
        val response = client.get("/health")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).isEqualTo("ok\n")
        val head = client.head("/health")
        assertThat(head.status).isEqualTo(HttpStatusCode.OK)
        assertThat(head.bodyAsText()).isEmpty()
    }

    @Test
    fun `health is 503 with a missing index or one of another schema version`() {
        val stale = temp.resolve("stale.db")
        Files.copy(database, stale)
        DriverManager.getConnection("jdbc:sqlite:$stale").use { it.createStatement().execute("UPDATE run SET schema_version = 1") }
        for (index in listOf(AtlasIndex(temp.resolve("missing.db"), null), AtlasIndex(stale, null))) {
            server(index) {
                val response = client.get("/health")
                assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
                assertThat(response.bodyAsText()).isEqualTo("unavailable\n")
                assertThat(client.head("/health").status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            }
        }
        assertThat(Files.exists(temp.resolve("missing.db"))).isFalse()
    }

    @Test
    fun `with authentication, health needs no token while the api still does`() = server(
        AtlasIndex(database, Fixture.sources),
        AuthSettings("https://id.example.com/realms/atlas", "https://atlas.example.com", scope = "atlas.read")
    ) {
        assertThat(client.get("/health").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.head("/health").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/api/run").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.get("/%68ealth").status).isEqualTo(HttpStatusCode.OK)
        for (path in listOf("/health/api/run", "/health/../api/run", "/health%2F..%2Fapi%2Frun", "/health/mcp")) {
            assertThat(client.get(path).status).describedAs(path).isIn(HttpStatusCode.NotFound, HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)
        }
    }
}
