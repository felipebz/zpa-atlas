//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

/** The defaults and maximums of the tools' size arguments (`limit`, `list_limit`, `max_depth`, `context`). */
internal object McpLimits {
    // The limits keep an answer within what an agent reads at once: a typical page under about 10,000 tokens, the
    // largest under 25,000 (the size Claude Code refuses by default).
    const val SEARCH_DEFAULT = 20
    const val SEARCH_MAX = 100
    const val LIST_DEFAULT = 30
    const val LIST_MAX = 100
    const val FIND_DEFAULT = 20
    const val FIND_MAX = 40
    const val IMPACT_DEFAULT = 100
    const val IMPACT_MAX = 200
    const val IMPACT_DEPTH_DEFAULT = 10
    const val IMPACT_DEPTH_MAX = 50
    const val GROUP_DEFAULT = 50
    const val GROUP_MAX = 100
    const val VALUES_DEFAULT = 50
    const val VALUES_MAX = 200
    const val REFERENCING_DEFAULT = 100
    const val REFERENCING_MAX = 500
    const val PLACES_DEFAULT = 100
    const val PLACES_MAX = 500
    const val SOURCE_CONTEXT_DEFAULT = 3
    const val SOURCE_CONTEXT_MAX = 50
    const val TEXT_DEFAULT = 50
    const val TEXT_MAX = 200
}
