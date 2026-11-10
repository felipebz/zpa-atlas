//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.FileSchemas
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import java.nio.file.Path

/** What an [Indexer] run indexes and how: the sources, the index written, the dictionary and how the sources compile. */
data class IndexOptions(
    val sources: Path,
    val output: Path,
    val extensions: List<String> = DEFAULT_EXTENSIONS,
    /** Exact dictionary name of the schema unqualified units compile in, or null. */
    val compilationSchema: String? = null,
    /**
     * `--schema-paths`: `pattern=SCHEMA,…`, the schema of the files each glob matches, relative to [sources] with `/`
     * separators; the first match wins. Blank for none.
     */
    val schemaPaths: String = "",
    val dictionary: AtlasDictionary = AtlasDictionary.NONE,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    /** `auto` (per file: strict UTF-8, else windows-1252) or a charset name forced for every file. */
    val encoding: String = SourceDecoding.AUTO,
    /**
     * How the sources are compiled (`PLSQL_CCFLAGS`, target database version): the branch of each `$IF` that is kept.
     * Both lexing passes (declarations and AST) get this same value, so they agree on which branch exists.
     */
    val conditionalCompilation: ConditionalCompilationSettings = ConditionalCompilationSettings.NONE,
    /** Where [conditionalCompilation]'s database version comes from; null without one. */
    val databaseVersionOrigin: DatabaseVersionOrigin? = null,
    /**
     * `BYTE` or `CHAR`: the unit of a PL/SQL length written without one when the dictionary does not say how the unit
     * declaring it was compiled.
     */
    val nlsLengthSemantics: String = "BYTE",
    /** The readers of Forms modules: a file one of them claims is read by it, whatever [extensions] says. */
    val readers: FormsModuleReaders = FormsModuleReaders.BUILT_IN
) {
    /** The schema each file compiles in, from [compilationSchema] and [schemaPaths]: zpa's rule, for the whole run. */
    val fileSchemas: FileSchemas by lazy { FileSchemas.of(sources, compilationSchema, schemaPaths) }

    companion object {
        /** The default of zpa-cli `--extensions`. */
        val DEFAULT_EXTENSIONS = "sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps".split(',')
    }
}

/** What an [Indexer] run did: how many files it indexed and how long it took. */
data class IndexSummary(
    val files: Int,
    val failedFiles: Int,
    val parseMillis: Long,
    val writeMillis: Long,
    val totalMillis: Long,
    /** What `--incremental` did; null without it. */
    val incremental: IncrementalReport? = null,
    /** The files left out because a symbolic link leads them outside the sources folder, by relative path. */
    val outsideRoot: List<String> = emptyList()
)
