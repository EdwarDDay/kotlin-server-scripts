package net.edwardday.serverscript.scripthost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import net.edwardday.serverscript.scripthost.KeyValuePair.Companion.toBuffer
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerScriptingHostTest {

    @Test
    fun testSimpleScript() = runTest {
        val (url, expected) = readResource("simple_script")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testCustomHeaders() = runTest {
        val (url, expected) = readResource("custom_headers")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testKnownStatus() = runTest {
        val (url, expected) = readResource("known_status")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testUnknownStatus() = runTest {
        val (url, expected) = readResource("unknown_status")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testBigOutput() = runTest {
        val (url, expected) = readResource("big_output")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testDependency() = runTest {
        val (url, expected) = readResource("dependency")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testMultipleOutput() = runTest {
        val (url, expected) = readResource("multiple_output")

        val actual = executeScript(url)

        assertEquals(expected, actual)
    }

    @Test
    fun testCache() = runTest {
        val (url, response) = readResource("cache_data")

        val urls = List(5) { url }
        val actualResponses = executeScripts(urls)

        repeat(5) { index ->
            val counter = index + 1
            val expected = response.map { it.replace("{counter}", "$counter") }

            val actual = actualResponses[index]

            assertEquals(expected, actual)
        }
    }


    companion object {

        private fun readResource(resourceName: String): Pair<String, List<String>> {
            val contextClassLoader = Thread.currentThread().contextClassLoader
            val scriptUrl = contextClassLoader.getResource("$resourceName.server.kts")!!.file
            val body = contextClassLoader.getResourceAsStream("$resourceName.body")!!.bufferedReader()
                .use(BufferedReader::readText)
            val header = contextClassLoader.getResourceAsStream("$resourceName.header")?.bufferedReader()
                ?.use(BufferedReader::readText)
            val content = buildString {
                if (header != null) {
                    append(header)
                    append('\n')
                }
                append('\n')
                append(body)
            }
            return scriptUrl to content.lines()
        }

        private suspend fun executeScripts(urls: List<String>): List<List<String>> {
            return coroutineScope {
                val socket = "test.socket"
                val hostJob = launch { main(socket, "1") }
                yield()
                runInterruptible { Thread.sleep(10) }

                val socketAddress = UnixDomainSocketAddress.of(socket)
                val result = urls.map { url ->
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                        assertTrue { channel.connect(socketAddress) }

                        val record = FCGIRecord.BeginRequest(FCGIRecord.BeginRequest.Role.Responder, 0u)
                        FCGIRequestMessage(1u, 1, record)
                        val buffer = ByteBuffer.allocate(8192)
                        fun putMessageInBuffer(
                            type: Byte,
                            content: ByteArray,
                        ) {
                            buffer.put(1) // version
                            buffer.put(type) // type begin request
                            buffer.putShort(1) // request id
                            buffer.putShort(content.size.toShort()) // content length
                            buffer.put(0) // padding length
                            buffer.put(0) // skipped
                            buffer.put(content)
                            // no padding
                        }
                        // Begin request
                        putMessageInBuffer(
                            type = 1,
                            content = byteArrayOf(
                                0, 1, // role responder
                                0, // flags
                                0, 0, 0, 0, 0, // unused
                            )
                        )

                        // params
                        putMessageInBuffer(
                            type = 4,
                            content = listOf(KeyValuePair("SCRIPT_FILENAME", url)).toBuffer().readByteArray(),
                        )

                        // params end
                        putMessageInBuffer(
                            type = 4,
                            content = byteArrayOf(),
                        )

                        // stdin end
                        putMessageInBuffer(
                            type = 5,
                            content = byteArrayOf(),
                        )

                        buffer.flip()
                        runInterruptible { channel.write(buffer) }

                        suspend fun readIntoBuffer() {
                            yield()
                            if (!buffer.hasRemaining()) {
                                buffer.clear()
                                runInterruptible(Dispatchers.IO) { channel.read(buffer) }
                                buffer.flip()
                            }
                        }
                        // read response
                        val response = buildString {
                            while (true) {
                                readIntoBuffer()
                                assertEquals(1, buffer.get()) // version
                                assertEquals(6, buffer.get()) // type data
                                assertEquals(1, buffer.getShort()) // request id
                                val contentLength = buffer.getShort()
                                val paddingLength = buffer.get()
                                buffer.get() // skipped
                                if (contentLength > 0) {
                                    val content = ByteArray(contentLength.toInt())
                                    buffer.get(content)
                                    append(String(content))
                                    repeat(paddingLength.toInt()) { buffer.get() } // skip padding
                                } else {
                                    assertEquals(0, paddingLength)
                                    break
                                }
                            }
                        }

                        readIntoBuffer()
                        // end response
                        assertEquals(1, buffer.get()) // version
                        assertEquals(3, buffer.get()) // type end
                        assertEquals(1, buffer.getShort()) // request id
                        assertEquals(8, buffer.getShort()) // content length
                        assertEquals(0, buffer.get()) // padding length
                        buffer.get() // skipped
                        assertEquals(0, buffer.getInt()) // app status
                        assertEquals(0, buffer.get()) // protocol status RequestComplete
                        repeat(3) { buffer.get() } // skip

                        yield()
                        assertFalse(buffer.hasRemaining())

                        response.split("\n")
                    }
                }

                hostJob.cancel()

                result
            }
        }

        private suspend fun executeScript(url: String): List<String> = executeScripts(listOf(url)).single()
    }
}