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
