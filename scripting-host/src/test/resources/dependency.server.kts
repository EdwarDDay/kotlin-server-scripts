@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

import kotlinx.serialization.*
import kotlinx.serialization.json.*


val list = List(100) { it }

writeOutput(Json.encodeToString(list))
