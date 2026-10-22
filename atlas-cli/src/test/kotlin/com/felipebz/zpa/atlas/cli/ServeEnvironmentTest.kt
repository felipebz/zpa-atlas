//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.Parameter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ServeEnvironmentTest {
    @TempDir
    lateinit var temp: Path

    private fun database(): Path = Files.createFile(temp.resolve("atlas.db"))

    private fun sources(): Path = Files.createDirectory(temp.resolve("src"))

    /** `serve` with [args] and the variables [environment]: its exit code and what it would start. */
    private fun serve(environment: Map<String, String>, vararg args: String): Pair<Int, ServeLaunch?> {
        var launched: ServeLaunch? = null
        val code = execute(arrayOf("serve", *args), { environment[it] }) { launched = it }
        return code to launched
    }

    @Test
    fun `a string, an int and a list come from the environment`() {
        val db = database()
        val src = sources()
        val (code, launch) = serve(mapOf(
            "ZPA_ATLAS_DB" to db.toString(), "ZPA_ATLAS_SOURCES" to src.toString(), "ZPA_ATLAS_HOST" to "0.0.0.0",
            "ZPA_ATLAS_PORT" to "9090", "ZPA_ATLAS_MCP_ALLOWED_HOST" to "atlas.example.com,atlas.internal"
        ))
        assertThat(code).isEqualTo(0)
        assertThat(launch!!.database).isEqualTo(db)
        assertThat(launch.sources).isEqualTo(src)
        assertThat(launch.host).isEqualTo("0.0.0.0")
        assertThat(launch.port).isEqualTo(9090)
        assertThat(launch.mcp.allowedHosts).containsExactly("atlas.example.com", "atlas.internal")
        assertThat(launch.auth).isNull()
    }

    @Test
    fun `the command line wins over the environment`() {
        val db = database()
        val environment = mapOf("ZPA_ATLAS_DB" to "/nowhere.db", "ZPA_ATLAS_PORT" to "9090", "ZPA_ATLAS_MCP_ALLOWED_HOST" to "a.example.com", "ZPA_ATLAS_SOURCES" to sources().toString())
        val (code, launch) = serve(environment, "--db", db.toString(), "--port", "7070", "--mcp-allowed-host", "b.example.com")
        assertThat(code).isEqualTo(0)
        assertThat(launch!!.database).isEqualTo(db)
        assertThat(launch.port).isEqualTo(7070)
        assertThat(launch.mcp.allowedHosts).containsExactly("b.example.com")
    }

    @Test
    fun `a blank variable is unset`() {
        val (missing, _) = serve(mapOf("ZPA_ATLAS_DB" to "  "))
        assertThat(missing).isEqualTo(2)
        val (code, launch) = serve(mapOf("ZPA_ATLAS_DB" to database().toString(), "ZPA_ATLAS_SOURCES" to sources().toString(), "ZPA_ATLAS_PORT" to "", "ZPA_ATLAS_HOST" to " "))
        assertThat(code).isEqualTo(0)
        assertThat(launch!!.port).isEqualTo(8080)
        assertThat(launch.host).isEqualTo("127.0.0.1")
    }

    @Test
    fun `index and stats do not read the variables`() {
        val asked = ArrayList<String>()
        val environment = { name: String -> asked += name; "/nowhere" }
        execute(arrayOf("index", "--sources", temp.resolve("missing").toString(), "--out", temp.resolve("out.db").toString()), environment) { }
        execute(arrayOf("stats", temp.resolve("missing.db").toString()), environment) { }
        assertThat(asked).noneMatch { it.startsWith("ZPA_ATLAS_") }
        assertThat(Files.exists(temp.resolve("out.db"))).isFalse()
    }

    private val oidc = mapOf(
        "ZPA_ATLAS_OIDC_ISSUER" to "https://id.example.com/realms/atlas", "ZPA_ATLAS_PUBLIC_URL" to "https://atlas.example.com",
        "ZPA_ATLAS_OIDC_CLIENT_ID" to "atlas-web"
    )

    @Test
    fun `the page client is confidential with the default secret variable set, and public without it`() {
        val base = oidc + mapOf("ZPA_ATLAS_DB" to database().toString(), "ZPA_ATLAS_SOURCES" to sources().toString())
        val (code, confidential) = serve(base + ("ZPA_ATLAS_OIDC_CLIENT_SECRET" to "s3cret"))
        assertThat(code).isEqualTo(0)
        assertThat(confidential!!.auth!!.issuer).isEqualTo("https://id.example.com/realms/atlas")
        assertThat(confidential.auth!!.page!!.clientId).isEqualTo("atlas-web")
        assertThat(confidential.auth!!.page!!.clientSecret).isEqualTo("s3cret")
        val (_, public) = serve(base)
        assertThat(public!!.auth!!.page!!.clientSecret).isNull()
        val (_, blank) = serve(base + ("ZPA_ATLAS_OIDC_CLIENT_SECRET" to ""))
        assertThat(blank!!.auth!!.page!!.clientSecret).isNull()
    }

    @Test
    fun `a secret variable named on purpose must be set`() {
        val base = oidc + mapOf("ZPA_ATLAS_DB" to database().toString(), "ZPA_ATLAS_SOURCES" to sources().toString())
        assertThat(serve(base, "--oidc-client-secret-env", "MY_SECRET").first).isEqualTo(2)
        assertThat(serve(base + ("ZPA_ATLAS_OIDC_CLIENT_SECRET_ENV" to "MY_SECRET")).first).isEqualTo(2)
        val (code, launch) = serve(base + ("MY_SECRET" to "other") + ("ZPA_ATLAS_OIDC_CLIENT_SECRET" to "default"), "--oidc-client-secret-env", "MY_SECRET")
        assertThat(code).isEqualTo(0)
        assertThat(launch!!.auth!!.page!!.clientSecret).isEqualTo("other")
    }

    @Test
    fun `the base path comes from the environment, a bad one stops serve, and the public URL must carry it`() {
        val base = mapOf("ZPA_ATLAS_DB" to database().toString(), "ZPA_ATLAS_SOURCES" to sources().toString())
        val (code, launch) = serve(base + ("ZPA_ATLAS_BASE_PATH" to "/tools/atlas/"))
        assertThat(code).isEqualTo(0)
        assertThat(launch!!.basePath.path).isEqualTo("/tools/atlas")
        assertThat(serve(base).second!!.basePath.path).isEmpty()
        assertThat(serve(base, "--base-path", "atlas").first).isEqualTo(2)
        assertThat(serve(base + ("ZPA_ATLAS_BASE_PATH" to "/atlas/../x")).first).isEqualTo(2)
        val signedIn = base + oidc + ("ZPA_ATLAS_BASE_PATH" to "/atlas")
        assertThat(serve(signedIn).first).isEqualTo(2)
        val (withPath, auth) = serve(signedIn + ("ZPA_ATLAS_PUBLIC_URL" to "https://atlas.example.com/atlas"))
        assertThat(withPath).isEqualTo(0)
        assertThat(auth!!.auth!!.resource).isEqualTo("https://atlas.example.com/atlas/mcp")
    }

    @Test
    fun `the help names the variable of every option of serve, and none is the start script's`() {
        val options = ServeArguments::class.java.declaredFields.mapNotNull { it.getAnnotation(Parameter::class.java) }
        assertThat(options).isNotEmpty.allSatisfy { option ->
            val name = environmentName(option.names.single())
            assertThat(name).isNotEqualTo("ZPA_ATLAS_OPTS")
            assertThat(option.description).endsWith("[env $name]")
        }
    }
}
