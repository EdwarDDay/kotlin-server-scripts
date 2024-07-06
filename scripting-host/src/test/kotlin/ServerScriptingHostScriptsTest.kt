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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class ServerScriptingHostScriptsTest(private val testCaseName: String) {

    @Test
    fun testScript() = runTest {
        val (url, expected) = readResource(testCaseName)

        val actual = executeWithUds { executeScript(it, url) }

        assertEquals(expected, actual)
    }


    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "script:{0}")
        fun data() = listOf(
            "simple_script", "custom_headers", "known_status", "unknown_status",
            "big_output", "dependency", "multiple_output",
        )
    }
}