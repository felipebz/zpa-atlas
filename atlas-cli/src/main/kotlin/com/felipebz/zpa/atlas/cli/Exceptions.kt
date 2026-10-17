//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

/** Invalid usage or configuration (exit code 2). */
class CliValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A failure of an external resource reported with a sanitized message and no stack trace (exit code 3). */
class CliExecutionException(message: String) : RuntimeException(message)
