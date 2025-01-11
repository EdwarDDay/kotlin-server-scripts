/*
 * Copyright 2024 Eduard Wolf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.edwardday.serverscript.scripthost

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerScriptingHostCacheTest {

    @Test
    fun testCacheWithUnixDomainSockets() = runTest {
        val testData = readResource("cache_data")

        val urls = List(5) { testData }
        val actualResponses = executeWithUnixDomainSockets {
            executeScripts(it, urls)
        }

        repeat(5) { index ->
            val counter = index + 1
            val expected = testData.body.map { it.replace("{counter}", "$counter") }

            val actual = actualResponses[index]

            assertEquals(expected, actual)
        }
    }

    @Test
    fun testSharedCacheWithUnixDomainSockets() = runTest {
        val testData1 = readResource("cache_import_script_1")
        val testData2 = readResource("cache_import_script_2")

        val urls = listOf(testData1, testData2)
        val actualResponses = executeWithUnixDomainSockets {
            executeScripts(it, urls)
        }

        assertEquals(testData1.body, actualResponses[0])
        assertEquals(testData2.body, actualResponses[1])
    }
}