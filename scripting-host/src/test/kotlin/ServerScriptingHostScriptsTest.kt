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