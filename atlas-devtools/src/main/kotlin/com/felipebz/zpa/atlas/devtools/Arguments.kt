//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.beust.jcommander.ParametersDelegate
import com.felipebz.zpa.atlas.cli.OracleOptions

class MainArguments {
    @Parameter(names = ["--help", "-h"], help = true, description = "Display help information")
    var help: Boolean = false
}

@Parameters(commandNames = ["compare-deps"], commandDescription = "Compare the index with the database's ALL_DEPENDENCIES")
class CompareArguments {
    @Parameter(names = ["--db"], description = "Index database", required = true)
    var db: String = ""

    @Parameter(names = ["--out"], description = "Folder for the CSV files", required = true)
    var out: String = ""

    @ParametersDelegate
    val oracle = OracleOptions()

    @Parameter(names = ["--dependencies"], description = "Read the database facts from a file written by --save-dependencies instead of Oracle")
    var dependencies: String = ""

    @Parameter(names = ["--save-dependencies"], description = "Also write the database facts read from Oracle to this file")
    var saveDependencies: String = ""

    @Parameter(names = ["--sources"], description = "Folder of the indexed sources (default: the folder recorded in the index)")
    var sources: String = ""

    @Parameter(names = ["--default-owner"], description = "Owner of units without a written owner or compilation schema")
    var defaultOwner: String = ""

    @Parameter(names = ["--builtins"], description = "File with the built-in names (one per line) that replace the default list")
    var builtins: String = ""
}

@Parameters(commandNames = ["timings"], commandDescription = "Time the query API for the 5 most used entities of an index, the column with the most view columns over it and the file with the most occurrences")
class TimingsArguments {
    @Parameter(description = "<file.db>", required = true)
    var database: List<String> = ArrayList()

    @Parameter(names = ["--sources"], description = "Folder of the indexed sources, for the file view (default: the folder recorded in the index)")
    var sources: String = ""
}

@Parameters(commandNames = ["same-rows"], commandDescription = "Check that two indexes have the same rows in every table")
class SameRowsArguments {
    @Parameter(description = "<first.db> <second.db>", required = true)
    var databases: List<String> = ArrayList()
}
