//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The text search over `hr/text_tags.sql`, where `vFixTag` is in a comment (line 6), a string (10) and an inactive
 * conditional branch (12), and `scripts/text_tags.sql`, where it is in a string outside any container (line 1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextSearchTest {
    private val temp: Path = Files.createTempDirectory("atlas-text-search-test")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun search(text: String, path: String? = null, container: String? = null, containerName: String? = null, after: Long? = null, limit: Int = 100) =
        index.searchText(text, path, container, containerName, after, limit).result

    private fun TextPage.lines() = rows.map { "${it.path}:${it.line} ${it.entity?.key ?: "-"}" }

    @Test
    fun `a text is found in a comment, a string and an inactive branch, with the line and its innermost container`() {
        val page = search("vFixTag")
        assertThat(page.lines()).containsExactly(
            "hr/text_tags.sql:6 db:HR.TEXT_TAGS",
            "hr/text_tags.sql:10 db:HR.TEXT_TAGS.BUILD()@BODY",
            "hr/text_tags.sql:12 db:HR.TEXT_TAGS.BUILD()@BODY",
            "scripts/text_tags.sql:1 -"
        )
        assertThat(page.total).isEqualTo(4)
        assertThat(page.wholeIndex).isTrue()
        assertThat(page.nextAfter).isNull()
        assertThat(page.rows[1].text).isEqualTo("    v := '<vFixTag>' || '1' || '</vFixTag>';")
        assertThat(page.rows.map { it.stale }).containsOnly(false)
    }

    @Test
    fun `case does not matter, and star is a character like any other`() {
        assertThat(search("VFIXTAG").lines()).isEqualTo(search("vfixtag").lines())
        assertThat(search("'<vfixtag>'").lines()).containsExactly("hr/text_tags.sql:10 db:HR.TEXT_TAGS.BUILD()@BODY", "scripts/text_tags.sql:1 -")
        assertThat(search("vTP*est").total).isZero()
        assertThat(search("'<vFixTag>' || '1'").total).isEqualTo(1)
    }

    @Test
    fun `a text shorter than three characters is refused, and one across a line break is not found`() {
        assertThatThrownBy { search("vT") }.isInstanceOf(InvalidQueryException::class.java)
            .hasMessage("The text must have at least 3 characters: the text index cannot find a shorter one")
        assertThat(search("</vFixTag>';\n    \$if").total).isZero()
    }

    @Test
    fun `a path, a container and a container name narrow the search, a container with its contents`() {
        assertThat(search("vFixTag", path = "scripts/").lines()).containsExactly("scripts/text_tags.sql:1 -")
        assertThat(search("vFixTag", path = "*tags.sql").total).isEqualTo(4)
        assertThat(search("vFixTag", path = "hr/text_tags.sql").wholeIndex).isFalse()
        assertThat(search("vFixTag", container = "db:HR.TEXT_TAGS").lines()).containsExactly(
            "hr/text_tags.sql:6 db:HR.TEXT_TAGS", "hr/text_tags.sql:10 db:HR.TEXT_TAGS.BUILD()@BODY", "hr/text_tags.sql:12 db:HR.TEXT_TAGS.BUILD()@BODY"
        )
        assertThat(search("vFixTag", container = "db:HR.TEXT_TAGS.BUILD()@BODY").total).isEqualTo(2)
        // A specification's subprogram declaration is in the subprogram, as its parameters' occurrences are.
        assertThat(search("procedure build", container = "db:HR.TEXT_TAGS.BUILD()").lines()).containsExactly("hr/text_tags.sql:2 db:HR.TEXT_TAGS.BUILD()")
        assertThat(search("vFixTag", containerName = "text_tags.build").total).isEqualTo(2)
        assertThat(search("vFixTag", containerName = "text_tags").total).isEqualTo(3)
        assertThat(search("vFixTag", containerName = "hr.text_*").total).isEqualTo(3)
        assertThat(search("vFixTag", container = "db:HR.TEXT_TAGS", path = "scripts/").total).isZero()
        assertThatThrownBy { search("vFixTag", container = "db:NO.SUCH") }.hasMessage("Unknown container: db:NO.SUCH")
    }

    @Test
    fun `pages go on from nextAfter, each line once`() {
        val all = search("vFixTag").rows
        val seen = ArrayList<TextHit>()
        var after: Long? = null
        do {
            val page = search("vFixTag", after = after, limit = 1)
            assertThat(page.total).isEqualTo(4)
            seen += page.rows
            after = page.nextAfter
        } while (after != null)
        assertThat(seen).isEqualTo(all)
        assertThatThrownBy { search("vFixTag", limit = 0) }.hasMessage("limit must be between 1 and 500")
    }

    @Test
    fun `without sources on the server the stale mark is unknown`() {
        val bare = AtlasIndex(index.database, null)
        assertThat(bare.searchText("vFixTag").result.rows.map { it.stale }).containsOnlyNulls()
    }
}
