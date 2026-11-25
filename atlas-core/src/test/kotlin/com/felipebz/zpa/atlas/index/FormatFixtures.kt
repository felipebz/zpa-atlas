//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

/** The sources the format checks of the kept files ([FactsCacheFormatTest], [TextIndexFormatTest]) are built from. */
internal object FormatFixtures {
    private val FORMS = listOf(
        "calc_desk_fmb.xml", "data_desk_fmb.xml", "data_plain_fmb.xml", "order_desk_fmb.xml", "order_lite_fmb.xml", "order_read_fmb.xml",
        "pack_desk_fmb.xml", "pack_plain_fmb.xml", "pack_desk.sql", "route_desk_fmb.xml", "route_plain_fmb.xml", "route_view_fmb.xml",
        "shop_desk_fmb.xml", "shop_plain_fmb.xml", "shop_desk.sql", "doag/demo0002_fmb.xml", "doag/demo0004_fmb.xml", "doag/modernize_fmb.xml"
    )

    /**
     * Copies the HR sources to [root]/hr and every Forms module of the test resources to [root]/forms. The digests are
     * of these bytes: a working copy checked out before the resources were kept LF may still have CRLF line ends.
     */
    fun copyHrAndForms(root: Path) {
        val hr = Fixture.sources.resolve("hr")
        Files.walk(hr).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach {
                it.copyTo(root.resolve("hr").resolve(hr.relativize(it).toString()).also { target -> target.parent.createDirectories() })
            }
        }
        val forms = root.resolve("forms").createDirectories()
        for (name in FORMS) FormatFixtures::class.java.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, forms.resolve(name.substringAfterLast('/'))) }
        Files.walk(root).use { paths ->
            val crlf = paths.filter { Files.isRegularFile(it) && Files.readAllBytes(it).contains('\r'.code.toByte()) }.map { root.relativize(it).toString() }.toList()
            assertThat(crlf).describedAs("Test resources with CRLF line ends, which git checks out LF: check them out again").isEmpty()
        }
    }
}
