//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Runs [test] against the HTTP API served from [index]. */
internal fun api(index: AtlasIndex = ServerFixture.index, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { atlasModule(index) }
    test()
}

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
