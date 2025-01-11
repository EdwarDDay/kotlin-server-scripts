@file:DependsOn("com.squareup.moshi:moshi-kotlin:1.15.2")

import com.squareup.moshi.*
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory


val moshi: Moshi = cache.getOrSet("MOSHI") { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> T.toJson(): String = moshi.adapter<T>().toJson(this)
