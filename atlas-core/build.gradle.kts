val zpaVersion = providers.gradleProperty("zpaVersion").get()

plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
