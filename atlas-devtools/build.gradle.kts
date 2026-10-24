plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // The shared Oracle options and JDBC driver lookup live in the published CLI.
    implementation(project(":atlas-cli"))
    implementation(project(":atlas-query"))
    // The database version read by the CLI is a zpa-core type, printed as the indexer does.
    implementation(project(":atlas-core"))
    implementation(libs.jcommander)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.jdk14)

    testImplementation(testFixtures(project(":atlas-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.felipebz.zpa.atlas.devtools.MainKt")
    applicationName = "atlas-devtools"
    // sqlite-jdbc loads its native library: allowed explicitly, as JDK 24+ warns about it
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.startScripts {
    doLast {
        val script = windowsScript
        val lines = script.readLines()
        check(lines.count { it.startsWith("set CLASSPATH=") } == 1) { "The Windows start script has no single classpath line to shorten" }
        script.writeText(lines.joinToString("\r\n", postfix = "\r\n") { if (it.startsWith("set CLASSPATH=")) "set CLASSPATH=%APP_HOME%\\lib\\*" else it })
    }
}

// Oracle-backed tests: never part of `check`/`build`; they need a database (see README.md).
val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "integrationTestImplementation"(platform(libs.junit.bom))
    "integrationTestImplementation"(libs.junit.jupiter)
    "integrationTestImplementation"(libs.assertj)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    // The driver is never a runtime dependency of the tools: users put it in the `drivers` folder.
    "integrationTestRuntimeOnly"(libs.ojdbc)
}

tasks.register<Test>("integrationTest") {
    description = "Runs the Oracle integration test (needs ATLAS_IT_ORACLE_URL, ATLAS_IT_ORACLE_USER, ATLAS_IT_ORACLE_PASSWORD)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
