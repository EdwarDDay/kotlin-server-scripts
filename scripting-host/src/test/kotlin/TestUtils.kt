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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import net.edwardday.serverscript.scripthost.KeyValuePair.Companion.toBuffer
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun readResource(resourceName: String): TestData {
    val contextClassLoader = Thread.currentThread().contextClassLoader
    val scriptUrl = contextClassLoader.getResource("$resourceName.server.kts")!!.file
    fun readFileText(suffix: String): String? = contextClassLoader.getResourceAsStream("$resourceName.$suffix")
        ?.bufferedReader()?.use(BufferedReader::readText)

    val body = readFileText("body")!!
    val header = readFileText("header")
    val status = readFileText("status")?.toInt() ?: 0
    val content = buildString {
        if (header != null) {
            append(header)
            append('\n')
        }
        append('\n')
        append(body)
    }
    return TestData(
        url = scriptUrl,
        body = content.lines(),
        status = status,
    )
}

data class TestData(
    val url: String,
    val body: List<String>,
    val status: Int,
)

@OptIn(ExperimentalPathApi::class)
suspend fun <T> executeWithUnixDomainSockets(block: suspend CoroutineScope.(SocketChannel) -> T): T {
    return coroutineScope {
        val directory = Files.createTempDirectory("sockets")
        val socket = directory.absolutePathString().let { if (it.endsWith('/')) it else "$it/" } + "fastcgi.socket"
        val hostJob = launch { readFromSocket(socket, 2) }
        yield()
        val udsSocketAddress = UnixDomainSocketAddress.of(socket)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            assertTrue { channel.connect(udsSocketAddress) }
            block(channel)
        }.also {
            hostJob.cancel()
            directory.deleteRecursively()
        }
    }
}

suspend fun executeScripts(
    channel: SocketChannel,
    urls: List<String>,
    expectedStatus: Int,
): List<List<String>> {
    val result = urls.mapIndexed { index, url ->
        val keepConnection: Byte = if (index < urls.lastIndex) 1 else 0
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
                keepConnection, // flags
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
        assertEquals(expectedStatus, buffer.getInt()) // app status
        assertEquals(0, buffer.get()) // protocol status RequestComplete
        repeat(3) { buffer.get() } // skip

        yield()
        assertFalse(buffer.hasRemaining())

        response.split("\n")
    }

    return result
}

suspend fun executeScript(channel: SocketChannel, url: String, expectedStatus: Int): List<String> =
    executeScripts(channel, listOf(url), expectedStatus).single()
