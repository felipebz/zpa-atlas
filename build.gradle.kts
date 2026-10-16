plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
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
        // -Datlas.updateGolden=true rewrites the golden files instead of comparing with them.
        systemProperty("atlas.updateGolden", System.getProperty("atlas.updateGolden") ?: "false")
        systemProperty("atlas.fixture", rootDir.resolve("atlas-core/src/testFixtures/resources/fixture").absolutePath)
    }
}
