import com.hierynomus.gradle.license.tasks.LicenseCheck
import com.hierynomus.gradle.license.tasks.LicenseFormat

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.github.hierynomus.license") version "0.16.1" apply false
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
    apply(plugin = "com.github.hierynomus.license")

    tasks.withType<LicenseFormat>().configureEach {
        header = File(this.project.rootDir, "LICENSE_HEADER")
        include("**/*.kt")
        mapping("kt", "DOUBLESLASH_STYLE")
    }

    tasks.withType<LicenseCheck>().configureEach {
        header = File(this.project.rootDir, "LICENSE_HEADER")
        include("**/*.kt")
        mapping("kt", "DOUBLESLASH_STYLE")
    }
}
