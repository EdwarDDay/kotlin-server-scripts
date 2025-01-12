@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

import kotlinx.serialization.*
import kotlinx.serialization.json.*


val list = List(100) { it }

writeOutput(Json.encodeToString(list))
