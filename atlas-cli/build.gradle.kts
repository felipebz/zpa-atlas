plugins {
    kotlin("jvm")
    application
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
    implementation(project(":atlas-core"))
    implementation(project(":atlas-server"))
    implementation("org.jcommander:jcommander:3.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.felipebz.zpa.atlas.cli.MainKt")
    applicationName = "zpa-atlas"
}

tasks.test {
    useJUnitPlatform()
}
