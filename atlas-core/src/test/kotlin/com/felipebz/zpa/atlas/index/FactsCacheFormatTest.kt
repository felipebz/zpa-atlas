//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.PlacesFixture
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.squid.AstScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * The facts cache is reused by every build of the atlas whose cache has the same [FactsCache.FORMAT]: this checks that
 * what the cache keeps of the test fixtures is what was recorded for that format. The fixtures are the extraction's: the
 * HR sources with every Forms module of the test resources and the dynamic statements' sources, and the column places'
 * sources.
 */
class FactsCacheFormatTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the facts cache of the fixtures is the one recorded for its format`() {
        val zpa = System.getProperty("atlas.zpaVersion")
        val jar = Path.of(AstScanner::class.java.protectionDomain.codeSource.location.toURI())
        // What the cache keeps comes from zpa too: the digests are those of the published zpa-core the build declares, as
        // a clean checkout resolves it. A local build of zpa, or one installed in the local Maven repository, gives others.
        assumeTrue(
            zpa != null && Files.isRegularFile(jar) && jar.fileName.toString() == "zpa-core-$zpa.jar" && jar.invariantSeparatorsPathString.contains("/modules-2/"),
            "zpa-core is not the published $zpa (it is $jar): the format check needs the zpa the build declares"
        )
        val digest = digest()
        val history = HistoryFile.read(HISTORY)
        val recorded = history["${FactsCache.FORMAT} $zpa"]
        val next = "${FactsCache.FORMAT + 1} $zpa $digest"
        when {
            recorded == null -> fail<Unit>(
                "No digest is recorded for facts cache format ${FactsCache.FORMAT} with zpa $zpa (digest $digest). If only zpa changed, set " +
                    "FactsCache.FORMAT_DIGEST to \"$digest\" and add the line \"${FactsCache.FORMAT} $zpa $digest\" to $HISTORY; if what the atlas " +
                    "keeps in the cache changed too, raise FactsCache.FORMAT to ${FactsCache.FORMAT + 1}, set FactsCache.FORMAT_DIGEST to \"$digest\" " +
                    "and add the line \"$next\"."
            )
            recorded != digest -> fail<Unit>(
                "What the facts cache keeps of the fixtures changed (digest $digest, recorded $recorded for format ${FactsCache.FORMAT} with zpa $zpa). " +
                    "Raise FactsCache.FORMAT to ${FactsCache.FORMAT + 1}, set FactsCache.FORMAT_DIGEST to \"$digest\" and add the line \"$next\" to $HISTORY."
            )
        }
        assertThat(FactsCache.FORMAT_DIGEST).describedAs("FactsCache.FORMAT_DIGEST is the digest $HISTORY records for format ${FactsCache.FORMAT} with zpa $zpa")
            .isEqualTo(recorded)
    }

    private fun digest(): String {
        val hr = temp.resolve("hr-src")
        FormatFixtures.copyHrAndForms(hr)
        for (name in DYNAMIC) javaClass.getResourceAsStream("/$name")!!.use { Files.copy(it, hr.resolve("hr").resolve(name.substringAfterLast('/'))) }
        val places = temp.resolve("places-src")
        PlacesFixture.write(places)
        return sha256(
            cacheDigest(hr, temp.resolve("hr.db"), "HR", Fixture.dictionary) + " " +
                cacheDigest(places, temp.resolve("places.db"), "SHOP", PlacesFixture.dictionary)
        )
    }

    private fun cacheDigest(sources: Path, output: Path, schema: String, dictionary: AtlasDictionary): String {
        Indexer().index(IndexOptions(sources, output, compilationSchema = schema, dictionary = dictionary, threads = 2), incremental = true)
        return FactsCache.digest(FactsCache.read(FactsCache.pathFor(output)))
    }

    private companion object {
        const val HISTORY = "facts-cache-formats.txt"

        val DYNAMIC = listOf(
            "dynamic-reach/pack_dyn_reach.pks", "dynamic-reach/pack_dyn_reach.pkb", "dynamic-variables/pack_dyn_vars.pks", "dynamic-variables/pack_dyn_vars.pkb"
        )
    }
}
