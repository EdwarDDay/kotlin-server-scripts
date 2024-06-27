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