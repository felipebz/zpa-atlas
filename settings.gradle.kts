rootProject.name = "zpa-atlas"

include(":atlas-core")
include(":atlas-query")
include(":atlas-server")
include(":atlas-cli")
include(":atlas-devtools")

// Opt-in local development against checkouts of zpa (and flr): -PzpaLocalPath=../zpa [-PflrLocalPath=../flr].
// Never required: by default zpa-core and zpa-oracle-metadata come from the snapshot repository, as in zpa-cli.
providers.gradleProperty("flrLocalPath").orNull?.let { includeBuild(it) }
providers.gradleProperty("zpaLocalPath").orNull?.let { includeBuild(it) }
