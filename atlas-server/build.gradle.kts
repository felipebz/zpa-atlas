import com.github.gradle.node.npm.task.NpmTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    alias(libs.plugins.node.gradle)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // The MCP SDK is built against an older Ktor: the BOM keeps every Ktor module, the SDK's included, on one version.
    api(project(":atlas-query"))
    api(libs.ktor.server.core)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.auth.oidc)
    implementation(libs.ktor.client.cio)
    implementation(libs.java.jwt)
    implementation(libs.jwks.rsa)
    implementation(libs.mcp.server)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.mcp.client)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":atlas-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// ---- the web page: TypeScript modules in `web/`, bundled by Vite into the jar's `web/` folder ------------------------
node {
    download.set(true)
    version.set("24.11.0")
    nodeProjectDir.set(layout.projectDirectory.dir("web"))
}

val WEB_GROUP = "web"
val webDir = layout.projectDirectory.dir("web")
val webBundle = layout.buildDirectory.dir("web-bundle")
val webSources = webDir.asFileTree.matching {
    include("src/**", "*.html", "*.css", "*.json", "vite.config.ts")
    exclude("node_modules/**")
}

val webTestSources = webDir.asFileTree.matching { include("test/**") }

val webInstall = tasks.register<NpmTask>("webInstall") {
    group = WEB_GROUP
    description = "Installs the web page's dependencies from package-lock.json (npm ci)."
    npmCommand.set(listOf("ci"))
    args.set(listOf("--no-audit", "--no-fund"))
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json")).withPropertyName("packageFiles")
    inputs.property("nodeVersion", node.version)
    outputs.dir(webDir.dir("node_modules"))
}

val webTypecheck = tasks.register<NpmTask>("webTypecheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Type-checks the web page (tsc --noEmit); an error fails the build."
    dependsOn(webInstall)
    npmCommand.set(listOf("run", "typecheck"))
    inputs.files(webSources).withPropertyName("webSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(webTestSources).withPropertyName("webTestSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("nodeVersion", node.version)
    val stamp = layout.buildDirectory.file("web-typecheck.stamp")
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText("ok") }
}

val webTest = tasks.register<NpmTask>("webTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the web page's unit tests (vitest run); a failure fails the build."
    dependsOn(webInstall)
    npmCommand.set(listOf("run", "test"))
    inputs.files(webSources).withPropertyName("webSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(webTestSources).withPropertyName("webTestSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("nodeVersion", node.version)
    val stamp = layout.buildDirectory.file("web-test.stamp")
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText("ok") }
}

val webBuild = tasks.register<NpmTask>("webBuild") {
    group = WEB_GROUP
    description = "Bundles the web page with Vite into build/web-bundle."
    dependsOn(webInstall)
    npmCommand.set(listOf("run", "build"))
    inputs.files(webSources).withPropertyName("webSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("nodeVersion", node.version)
    outputs.dir(webBundle)
    outputs.cacheIf { true }
}

tasks.processResources {
    from(webBuild) { into("web") }
    val versions = mapOf("atlasVersion" to project.version.toString())
    inputs.properties(versions)
    filesMatching("zpa-atlas-server.properties") {
        expand(versions)
    }
}

tasks.check {
    dependsOn(webTypecheck, webTest)
}

sonar {
    properties {
        fun existing(key: String) = (properties[key] as Collection<*>).map { file(it!!) }.filter { it.exists() }
        val pages = webDir.asFileTree.matching { include("*.html", "*.css") }.files.sorted()
        properties["sonar.sources"] = existing("sonar.sources") + webDir.dir("src").asFile + pages
        properties["sonar.tests"] = existing("sonar.tests") + webDir.dir("test").asFile
    }
}
