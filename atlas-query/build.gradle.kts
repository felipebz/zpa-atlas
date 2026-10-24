plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    // Tests build the fixture index with the indexer; the main code never depends on it.
    testImplementation(testFixtures(project(":atlas-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
