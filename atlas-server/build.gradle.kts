import com.github.gradle.node.npm.task.NpmTask

val ktorVersion = "3.6.0"
val mcpVersion = "0.15.0"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    id("com.github.node-gradle.node") version "7.1.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // The MCP SDK is built against an older Ktor: the BOM keeps every Ktor module, the SDK's included, on one version.
    implementation(platform("io.ktor:ktor-bom:$ktorVersion"))
    testImplementation(platform("io.ktor:ktor-bom:$ktorVersion"))
    api(project(":atlas-query"))
    api("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-oidc:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.auth0:java-jwt:4.6.2")
    implementation("com.auth0:jwks-rsa:0.24.1")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(testFixtures(project(":atlas-core")))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

val webDir = layout.projectDirectory.dir("web")
val webBundle = layout.buildDirectory.dir("web-bundle")
val webSources = webDir.asFileTree.matching {
    include("src/**", "*.html", "*.css", "*.json", "vite.config.ts")
    exclude("node_modules/**")
}

val webTestSources = webDir.asFileTree.matching { include("test/**") }

val webInstall = tasks.register<NpmTask>("webInstall") {
    description = "Installs the web page's dependencies from package-lock.json (npm ci)."
    npmCommand.set(listOf("ci"))
    args.set(listOf("--no-audit", "--no-fund"))
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json")).withPropertyName("packageFiles")
    inputs.property("nodeVersion", node.version)
    outputs.dir(webDir.dir("node_modules"))
}

val webTypecheck = tasks.register<NpmTask>("webTypecheck") {
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
