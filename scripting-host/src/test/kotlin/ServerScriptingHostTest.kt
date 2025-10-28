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

import app.cash.burst.Burst
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals


@Burst
class ServerScriptingHostTest {

    @Test
    fun testScript(
        testCase: TestCases.Script,
    ) = runTest {
        val testData = readResource(testCase.fileName)

        val actual = executeWithUnixDomainSockets { executeScript(it, testData) }

        assertEquals(testData.body, actual)
    }

    @Test
    fun testCacheScript(
        testCase: TestCases.CacheScript,
    ) = runTest {
        val urls = testCase.fileNames.map { readResource(it) }
        val expected = when (testCase) {
            TestCases.CacheScript.CACHE_DATA -> {
                urls.mapIndexed { index, testData -> testData.body.map { it.replace("{counter}", "${index + 1}") } }
            }

            TestCases.CacheScript.CACHE_IMPORT_SCRIPT -> {
                urls.map(TestData::body)
            }
        }

        val actualResponses = executeWithUnixDomainSockets {
            executeScripts(it, urls)
        }

        assertEquals(expected, actualResponses)
    }

    @Test
    fun checkAllTestcasesCovered() {
        val path = Thread.currentThread().contextClassLoader.getResource("imports")!!.path
        val expected = File(path).parentFile.listFiles { _, name -> name.endsWith(".server.kts") }
            .orEmpty()
            .map { it.name.removeSuffix(".server.kts") }
            .toSet()

        val actual =
            (TestCases.Script.entries + TestCases.CacheScript.entries).flatMapTo(mutableSetOf(), TestCases::fileNames)

        assertEquals(expected, actual)
    }
}