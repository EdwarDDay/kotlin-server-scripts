package net.edwardday.serverscript.scripthost

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.edwardday.serverscript.scripthost.KeyValuePair.Companion.toBuffer

private val logger = KotlinLogging.logger {}

object ManagementRequestState {
    fun handleGetValues(record: FCGIRecord.GetValues, maxConnections: Int): Flow<FCGIResponseMessage> {
        val builder = KeyValuePair.Builder()
        builder.addSource(record.source)
        val answers = builder.build().mapNotNull { pair ->
            when (pair.key) {
                "FCGI_MAX_CONNS" -> pair.copy(value = maxConnections.toString())
                "FCGI_MAX_REQS" -> pair.copy(value = "4")
                "FCGI_MPXS_CONNS" -> pair.copy(value = "1")
                else -> null
            }
        }.also { logger.info { "Answer GetValues with $it" } }
        return flowOf(FCGIResponseMessage(0, FCGIRecord.GetValuesResult(answers.toBuffer())))
    }

    fun handleUnknownType(type: UByte): Flow<FCGIResponseMessage> =
        flowOf(FCGIResponseMessage(0, FCGIRecord.UnknownType(type)))
}
