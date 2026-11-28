//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.index.RunProgress
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The log of the dictionary load ([OracleMetadataLoader], [KeptDictionary]) as lines of the run's progress. */
internal object DictionaryLog {
    /** Until closed, what the dictionary load logs is a line of [step], and not written to the console. */
    fun lines(step: RunProgress.Step): AutoCloseable {
        val logger = Logger.getLogger(OracleMetadataLoader::class.java.packageName)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (isLoggable(record)) step.note(record.message)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val parents = logger.useParentHandlers
        logger.addHandler(handler)
        logger.useParentHandlers = false
        return AutoCloseable {
            logger.removeHandler(handler)
            logger.useParentHandlers = parents
        }
    }
}
