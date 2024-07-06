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

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

data class KeyValuePair(val key: String, val value: String) {

    override fun toString(): String = "{$key=$value}"

    class Builder {
        private val source = Buffer()

        fun addSource(source: BufferedSource) {
            this.source.writeAll(source)
        }

        private fun readLength(): ByteArray {
            val lengthFirstByte = source.readByte().toInt()
            val length = if ((lengthFirstByte and 0x80) == 0) {
                lengthFirstByte
            } else {
                (lengthFirstByte and 0x7f) shl 24 or
                        (source.readByte().toInt() and 0xff) shl 16 or
                        (source.readByte().toInt() and 0xff) shl 8 or
                        source.readByte().toInt() and 0xff
            }
            return ByteArray(length)
        }

        private fun readContent(part: ByteArray) {
            source.readFully(part)
        }

        fun build(): List<KeyValuePair> {
            if (source.exhausted()) return emptyList()

            return buildList {
                while (!source.exhausted()) {
                    val key = readLength()
                    val value = readLength()
                    readContent(key)
                    readContent(value)
                    add(KeyValuePair(String(key), String(value)))
                }
            }
        }
    }

    companion object {
        private fun BufferedSink.writeLength(length: Int) {
            if (length <= 0x7f) writeByte(length) else writeInt(length or Int.MIN_VALUE)
        }

        fun List<KeyValuePair>.toBuffer(): Buffer {
            val buffer = Buffer()
            forEach { keyValuePair ->
                val keyByteArray = keyValuePair.key.toByteArray()
                buffer.writeLength(keyByteArray.size)
                val valueByteArray = keyValuePair.value.toByteArray()
                buffer.writeLength(valueByteArray.size)
                buffer.write(keyByteArray)
                buffer.write(valueByteArray)
            }
            return buffer
        }
    }
}
