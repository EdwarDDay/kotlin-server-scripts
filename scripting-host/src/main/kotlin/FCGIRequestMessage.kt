package net.edwardday.serverscript.scripthost

import io.github.oshai.kotlinlogging.KotlinLogging
import okio.BufferedSource
import okio.IOException

private val logger = KotlinLogging.logger {}

class FCGIRequestMessage(
    version: UByte,
    val requestId: Int,
    val record: FCGIRecord.FCGIRequestRecord,
) {
    init {
        if (version.toInt() != 1) {
            throw IOException("unknown version $version")
        }
    }

    override fun toString(): String = "Message(requestId=$requestId, record=$record)"

    companion object {
        @Throws(IOException::class)
        fun read(source: BufferedSource): FCGIRequestMessage {
            source.require(8)
            val version = source.readByte().toUByte()
            val type = source.readByte().toUByte()
            val requestId = source.readShort().toUShort().toInt()
            val contentLength = source.readShort().toUShort().toLong()
            val paddingLength = source.readByte().toUByte().toLong()
            source.skip(1) // reserved
            source.require(contentLength + paddingLength)
            val record = FCGIRecord.read(source.readBuffer(contentLength), type)
            source.skip(paddingLength)
            return FCGIRequestMessage(version, requestId, record).also {
                logger.trace { "receive message $it" }
            }
        }
    }
}
