/*
 * Copyright (C) 2018 Square, Inc.
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

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.TimeUnit
import kotlin.math.min
import okio.AsyncTimeout
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

fun ReadableByteChannel.source(): Source {
    val timeout = AsyncTimeout()
    timeout.timeout(100, TimeUnit.MILLISECONDS)
    return timeout.source(ByteChannelSource(this, timeout))
}

/*
 * Copy from
 * https://github.com/square/okio/blob/b284bc07803e4f9658a1e65c55d57abe28c85c1e/samples/src/jvmMain/java/okio/samples/ByteChannelSource.java
 * and converted to kotlin
 */
/**
 * Creates a [Source] around a [ReadableByteChannel] and efficiently reads data using an [Buffer.UnsafeCursor].
 *
 * This is a basic example showing another use for the [Buffer.UnsafeCursor]. Using the [ByteBuffer.wrap] along with
 * access to [Buffer] segments, a [ReadableByteChannel] can be given direct access to [Buffer] data without having to
 * copy the data.
 */
internal class ByteChannelSource(private val channel: ReadableByteChannel, private val timeout: Timeout) : Source {
    private val cursor: Buffer.UnsafeCursor = Buffer.UnsafeCursor()

    @Throws(IOException::class)
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(channel.isOpen) { "closed" }
        sink.readAndWriteUnsafe(cursor).use { _ ->
            timeout.throwIfReached()
            val oldSize = sink.size
            val length = min(8192, byteCount).toInt()
            cursor.expandBuffer(length)
            val read = channel.read(ByteBuffer.wrap(cursor.data, cursor.start, length))
            return if (read == -1) {
                cursor.resizeBuffer(oldSize)
                -1
            } else {
                cursor.resizeBuffer(oldSize + read)
                read.toLong()
            }
        }
    }

    override fun timeout(): Timeout = timeout

    @Throws(IOException::class)
    override fun close() {
        channel.close()
    }
}

fun WritableByteChannel.sink(): Sink {
    val timeout = AsyncTimeout()
    timeout.timeout(100, TimeUnit.MILLISECONDS)
    return timeout.sink(ByteChannelSink(this, timeout))
}

/*
 * Copy from
 * https://github.com/square/okio/blob/b284bc07803e4f9658a1e65c55d57abe28c85c1e/samples/src/jvmMain/java/okio/samples/ByteChannelSink.java
 * and converted to kotlin
 */
/**
 * Creates a [Sink] around a [WritableByteChannel] and efficiently writes data using an [Buffer.UnsafeCursor].
 *
 * This is a basic example showing another use for the [Buffer.UnsafeCursor]. Using the [ByteBuffer.wrap] along with
 * access to [Buffer] segments, a [WritableByteChannel] can be given direct access to [Buffer] data without having to
 * copy the data.
 */
internal class ByteChannelSink(private val channel: WritableByteChannel, private val timeout: Timeout) : Sink {
    private val cursor: Buffer.UnsafeCursor = Buffer.UnsafeCursor()

    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
        check(channel.isOpen) { "closed" }
        if (byteCount == 0L) return
        var remaining = byteCount
        while (remaining > 0) {
            timeout.throwIfReached()
            source.readUnsafe(cursor).use { _ ->
                cursor.seek(0)
                val length = min(cursor.end - cursor.start, remaining.toInt())
                val written = channel.write(ByteBuffer.wrap(cursor.data, cursor.start, length))
                remaining -= written.toLong()
                source.skip(written.toLong())
            }
        }
    }

    override fun flush() {}
    override fun timeout(): Timeout = timeout

    @Throws(IOException::class)
    override fun close() {
        channel.close()
    }
}
