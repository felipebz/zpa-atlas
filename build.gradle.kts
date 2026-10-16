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
        systemProperty("atlas.fixture", rootDir.resolve("atlas-core/src/testFixtures/resources/fixture").absolutePath)
    }
}
