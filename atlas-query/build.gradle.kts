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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    // Tests build the fixture index with the indexer; the main code never depends on it.
    testImplementation(testFixtures(project(":atlas-core")))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
