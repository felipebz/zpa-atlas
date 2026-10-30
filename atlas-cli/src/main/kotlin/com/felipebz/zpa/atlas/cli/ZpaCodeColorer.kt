//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.felipebz.zpa.atlas.highlight.CodeHighlighter
import com.felipebz.zpa.atlas.query.CodeColor
import com.felipebz.zpa.atlas.query.CodeColorer

/**
 * The server's colorer: zpa's lexer, through the indexer's [CodeHighlighter]. The query module does not depend on zpa;
 * the command that starts the server hands this over.
 */
internal val ZpaCodeColorer = CodeColorer { text, ccflags, databaseVersion ->
    CodeHighlighter.spans(text, ccflags, databaseVersion)?.map {
        listOf(it.startLine, it.startCol, it.endLine, it.endCol, CodeColor.valueOf(it.highlight.name).ordinal)
    }
}
