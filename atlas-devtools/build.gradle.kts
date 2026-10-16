plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // The shared Oracle options and JDBC driver lookup live in the published CLI.
    implementation(project(":atlas-cli"))
    implementation(project(":atlas-query"))
    implementation("org.jcommander:jcommander:3.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
}

application {
    mainClass.set("com.felipebz.zpa.atlas.devtools.MainKt")
    applicationName = "atlas-devtools"
}

// Oracle-backed tests: never part of `check`/`build`; they need a database (see README.md).
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "integrationTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestImplementation"("org.assertj:assertj-core:3.27.7")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    // The driver is never a runtime dependency of the tools: users put it in the `drivers` folder.
    "integrationTestRuntimeOnly"("com.oracle.database.jdbc:ojdbc17:23.26.3.0.0")
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
