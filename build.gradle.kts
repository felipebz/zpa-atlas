plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.diffplug.spotless") version "8.10.4" apply false
    id("org.sonarqube") version "7.5.0.8588"
}

sonar {
    properties {
        property("sonar.projectKey", "com.felipebz.zpa:zpa-atlas")
        property("sonar.projectName", "ZPA Atlas")
    }
}

allprojects {
    group = "com.felipebz.zpa"
    version = providers.gradleProperty("version").get()

    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            setUrl("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }

    tasks.withType<Test>().configureEach {
        // sqlite-jdbc loads its native library: allowed explicitly, as JDK 24+ warns about it
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // -Datlas.updateGolden=true rewrites the golden files instead of comparing with them.
        systemProperty("atlas.updateGolden", System.getProperty("atlas.updateGolden") ?: "false")
        systemProperty("atlas.fixture", rootDir.resolve("atlas-core/src/testFixtures/resources/fixture").absolutePath)
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.15"
    }

    tasks.withType<Test>().matching { it.name == "test" }.configureEach {
        finalizedBy(tasks.named("jacocoTestReport"))
    }
    tasks.withType<JacocoReport>().configureEach {
        reports.xml.required.set(true)
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            val lines = rootProject.file("LICENSE_HEADER").readLines().filter { it.isNotBlank() }
            licenseHeader(lines.joinToString("", prefix = "//\n", postfix = "//\n\n") { "// $it\n" })
        }
    }
}
