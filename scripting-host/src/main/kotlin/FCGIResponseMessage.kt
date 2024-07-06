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

import io.github.oshai.kotlinlogging.KotlinLogging
import okio.BufferedSink
import okio.IOException

private val logger = KotlinLogging.logger {}

class FCGIResponseMessage(
    private val requestId: Int,
    internal val record: FCGIRecord.FCGIResponseRecord,
) {

    override fun toString(): String = "Message(requestId=$requestId, record=$record)"

    @Throws(IOException::class)
    fun write(sink: BufferedSink) {
        logger.trace { "send message $this" }
        sink.writeByte(1) // version
        sink.writeByte(record.type)
        sink.writeShort(requestId)
        val contentLength = record.contentLength.toInt()
        sink.writeShort(contentLength)
        val paddingLength = (8 - (contentLength % 8)) % 8
        sink.writeByte(paddingLength)
        sink.writeByte(0) // reserved
        record.write(sink)
        repeat(paddingLength) { sink.writeByte(0) }
    }
}