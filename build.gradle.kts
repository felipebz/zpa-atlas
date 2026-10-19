plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.diffplug.spotless") version "8.10.4" apply false
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

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            val lines = rootProject.file("LICENSE_HEADER").readLines().filter { it.isNotBlank() }
            licenseHeader(lines.joinToString("", prefix = "//\n", postfix = "//\n\n") { "// $it\n" })
        }
    }
}
