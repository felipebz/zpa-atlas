//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The whole Doag corpus of `frmf2xml` exports: the `*_fmb.xml` of `forms/doag` in the test fixtures (MIT, see its LICENSE). */
internal object DoagCorpus {
    /** Copies the corpus's modules into [target] and returns it: the test fixtures come in a JAR, which is not a folder to index. */
    fun frm2xml(target: Path): Path {
        target.createDirectories()
        val uri = javaClass.getResource("/forms/doag")!!.toURI()
        if (uri.scheme == "jar") {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { jar -> copy(jar.provider().getPath(uri), target) }
        } else {
            copy(Path.of(uri), target)
        }
        return target
    }

    private fun copy(folder: Path, target: Path) {
        for (file in folder.listDirectoryEntries("*_fmb.xml")) Files.copy(file, target.resolve(file.name))
    }
}
