package net.edwardday.serverscript.scripthost

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerScriptingHostCacheTest {

    @Test
    fun testCacheWithUds() = runTest {
        val (url, response) = readResource("cache_data")

        val urls = List(5) { url }
        val actualResponses = executeWithUds {
            executeScripts(it, urls)
        }

        repeat(5) { index ->
            val counter = index + 1
            val expected = response.map { it.replace("{counter}", "$counter") }

            val actual = actualResponses[index]

            assertEquals(expected, actual)
        }
    }
}