@file:Repository("https://s01.oss.sonatype.org/content/repositories/snapshots/")
@file:DependsOn("com.squareup.okhttp3:okhttp:5.0.0-SNAPSHOT")

import okhttp3.MediaType.Companion.toMediaType


setHeader("Content-Type", "application/json; charset=utf-8".toMediaType().toString())

writeOutput("""{"status":"OK"}""")
