val zpaVersion = providers.gradleProperty("zpaVersion").get()

plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        // The project API of zpa-core is experimental; the indexer is one of its consumers by design.
        optIn.add("com.felipebz.zpa.api.annotations.ZpaExperimentalApi")
    }
}

dependencies {
    api("com.felipebz.zpa:zpa-core:$zpaVersion")
    api("com.felipebz.zpa:zpa-oracle-metadata:$zpaVersion")
    implementation(libs.sqlite.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val versions = mapOf("atlasVersion" to project.version.toString(), "zpaVersion" to zpaVersion)
    inputs.properties(versions)
    filesMatching("zpa-atlas-version.properties") {
        expand(versions)
    }
}

tasks.test {
    useJUnitPlatform()
}
