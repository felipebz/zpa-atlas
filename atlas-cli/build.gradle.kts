plugins {
    kotlin("jvm")
    application
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
    implementation(project(":atlas-core"))
    implementation(project(":atlas-server"))
    implementation("org.jcommander:jcommander:3.0")
    // Project files: YAML read as nodes, so that every key and value is checked with a message of its own.
    implementation("com.charleskorn.kaml:kaml:0.79.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.felipebz.zpa.atlas.cli.MainKt")
    applicationName = "zpa-atlas"
    // sqlite-jdbc loads its native library: allowed explicitly, as JDK 24+ warns about it
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.startScripts {
    doLast {
        val script = windowsScript
        val lines = script.readLines()
        check(lines.count { it.startsWith("set CLASSPATH=") } == 1) { "The Windows start script has no single classpath line to shorten" }
        script.writeText(lines.joinToString("\r\n", postfix = "\r\n") { if (it.startsWith("set CLASSPATH=")) "set CLASSPATH=%APP_HOME%\\lib\\*" else it })
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * `THIRD-PARTY.md`: every JAR of the distribution's `lib/` (the runtime classpath plus this project's JAR), with its
 * license and where its source code is, read from the Maven POMs (parents followed for inherited licenses and URLs).
 * ZPA and FLR are LGPL-3.0-or-later: their entries point to `licenses/LGPL-3.0.txt`.
 */
val thirdPartyNotice = tasks.register("thirdPartyNotice") {
    description = "Writes THIRD-PARTY.md, the license of every JAR in the distribution's lib/ directory."
    val runtime = configurations.runtimeClasspath
    val jar = tasks.jar.flatMap { it.archiveFileName }
    val output = layout.buildDirectory.file("third-party/THIRD-PARTY.md")
    inputs.files(runtime)
    inputs.property("jar", jar)
    outputs.file(output)
    // Taken at configuration time: Task.project is deprecated at execution time.
    val configurations = project.configurations
    val dependencies = project.dependencies
    doLast {
        class Pom(val name: String?, val url: String?, val scm: String?, val licenses: List<Pair<String, String?>>, val parent: String?)

        fun pom(coordinates: String): Pom {
            val file = configurations.detachedConfiguration(dependencies.create("$coordinates@pom"))
                .apply { isTransitive = false }.singleFile
            val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            fun children(node: org.w3c.dom.Node, name: String) =
                (0 until node.childNodes.length).map { node.childNodes.item(it) }.filter { it.nodeName == name }
            fun text(node: org.w3c.dom.Node, vararg path: String): String? {
                var current = node
                for (name in path) current = children(current, name).firstOrNull() ?: return null
                return current.textContent.trim().ifEmpty { null }
            }
            val licenses = children(root, "licenses").flatMap { children(it, "license") }.map { (text(it, "name") ?: "?") to text(it, "url") }
            val parent = children(root, "parent").firstOrNull()?.let { "${text(it, "groupId")}:${text(it, "artifactId")}:${text(it, "version")}" }
            return Pom(text(root, "name"), text(root, "url"), text(root, "scm", "url"), licenses, parent)
        }

        val lgpl = "GNU Lesser General Public License, version 3 or later ([licenses/LGPL-3.0.txt](licenses/LGPL-3.0.txt), " +
            "with [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt))"
        val apache = "Apache License, Version 2.0 ([LICENSE](LICENSE))"
        val atlasUrl = "https://github.com/felipebz/zpa-atlas"
        val rows = ArrayList<List<String>>()
        rows += listOf(jar.get(), apache, atlasUrl)
        for (artifact in runtime.get().incoming.artifacts) {
            val fileName = artifact.file.name
            when (val id = artifact.id.componentIdentifier) {
                is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> rows += listOf(fileName, apache, atlasUrl)
                is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> {
                    val chain = generateSequence(pom("${id.group}:${id.module}:${id.version}")) { it.parent?.let(::pom) }.take(5).toList()
                    val url = chain.firstNotNullOfOrNull { it.scm ?: it.url } ?: error("No project URL in the POM of $id")
                    val license = if (id.group == "com.felipebz.zpa" || id.group == "com.felipebz.flr") {
                        lgpl
                    } else {
                        chain.firstOrNull { it.licenses.isNotEmpty() }?.licenses
                            ?.joinToString(", ") { (name, link) -> if (link != null) "[$name]($link)" else name }
                            ?: error("No license in the POM of $id")
                    }
                    rows += listOf(fileName, license, url)
                }
                else -> error("Unexpected runtime artifact $id")
            }
        }
        val text = buildString {
            append("# Third-party libraries\n\n")
            append("Every JAR in `lib/`, with its license and where its source code can be obtained. Generated from the runtime ")
            append("classpath of `atlas-cli` by `./gradlew :atlas-cli:thirdPartyNotice` (part of every distribution build).\n\n")
            append("The JARs are kept separate so that any of them, ZPA and FLR included, can be replaced by another version.\n\n")
            append("| JAR | License | Source code |\n|---|---|---|\n")
            rows.sortedBy { it[0] }.forEach { (file, license, url) -> append("| `$file` | $license | $url |\n") }
        }
        output.get().asFile.writeText(text)
    }
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
            from(thirdPartyNotice)
            from(rootProject.file("licenses")) {
                into("licenses")
            }
        }
    }
}
