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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEmpty
import net.edwardday.serverscript.scriptdefinition.ServerScriptDefinition
import net.edwardday.serverscript.scriptdefinition.script.Cache
import net.edwardday.serverscript.scriptdefinition.script.ServerScript
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.refineConfigurationBeforeEvaluate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import java.nio.file.Path as NioPath

private val logger = KotlinLogging.logger {}

private val scriptingHost = BasicJvmScriptingHost()

class RequestState(
    record: FCGIRecord.BeginRequest,
    private val cache: Cache,
) {
    private val responseHeaders: MutableMap<String, List<String>> = mutableMapOf()
    val keepConnection: Boolean = record.keepConnection

    private val stdIn: Buffer = Buffer()
    private var stdInDone: Boolean = false
    private val data: Buffer = Buffer()
    private var dataStarted: Boolean = false
    private var dataDone: Boolean = false

    private var paramsBuilder: KeyValuePair.Builder? = KeyValuePair.Builder()
    private var params: Map<String, List<String>>? = null

    fun handleRecord(record: FCGIRecord.FCGIRequestRecord): Flow<FCGIRecord.FCGIResponseRecord> {
        logger.trace { "handle record: $record" }
        return when (record) {
            is FCGIRecord.Params -> {
                paramsBuilder?.let { paramsBuilder ->
                    val recordSource = record.source
                    if (recordSource.exhausted()) {
                        params = buildMap<String, List<String>> {
                            paramsBuilder.build().forEach { (key, value) ->
                                merge(key, listOf(value)) { old, new -> old + new }
                            }
                        }.also {
                            logger.trace { "read params from request: $it" }
                        }
                        this.paramsBuilder = null
                        logger.trace { "finished params from request" }
                        runScriptIfPossible()
                    } else {
                        paramsBuilder.addSource(recordSource)
                        emptyFlow()
                    }
                } ?: kotlin.run {
                    logger.warn { "params from request already finished but got new param record" }
                    emptyFlow()
                }
            }

            is FCGIRecord.Stdin -> {
                if (!stdInDone) {
                    val recordSource = record.source
                    if (recordSource.exhausted()) {
                        stdInDone = true
                        logger.trace { "finished stdin from request" }
                        runScriptIfPossible()
                    } else {
                        stdIn.writeAll(recordSource)
                        emptyFlow()
                    }
                } else {
                    emptyFlow()
                }
            }

            // handled add constructor level
            is FCGIRecord.BeginRequest -> emptyFlow()
            is FCGIRecord.Data -> {
                dataStarted = true
                if (!dataDone) {
                    val recordSource = record.source
                    if (recordSource.exhausted()) {
                        dataDone = true
                        logger.trace { "finished data from request" }
                        runScriptIfPossible()
                    } else {
                        data.writeAll(recordSource)
                        emptyFlow()
                    }
                } else {
                    emptyFlow()
                }
            }

            FCGIRecord.AbortRequest -> {
                logger.debug { "abort request" }
                runScriptIfPossible().onEmpty {
                    logger.debug { "send end request because no script ran" }
                    emit(
                        FCGIRecord.EndRequest(
                            appStatus = 400, // TODO set proper app status
                            protocolStatus = FCGIRecord.EndRequest.ProtocolStatus.RequestComplete,
                        )
                    )
                }
            }

            is FCGIRecord.FCGIManageRecord -> emptyFlow()
        }
    }

    inner class ServerScriptImpl(
        override val cache: Cache,
        private val workingDirectory: Path,
        private val writeOutput: (String) -> Unit,
        private val writeError: (String) -> Unit,
    ) : ServerScript {
        override val parameters: Map<String, List<String>> get() = this@RequestState.params ?: emptyMap()
        private var canSetHeaders = true

        override fun path(name: String): NioPath {
            val directPath = name.toPath(normalize = true)
            return if (directPath.isAbsolute) {
                directPath.toNioPath()
            } else {
                workingDirectory.resolve(name).toNioPath()
            }
        }

        override fun getHeaders(key: String): List<String> = responseHeaders[key] ?: emptyList()
        override fun setHeaders(key: String, value: List<String>) {
            responseHeaders[key] = value
            if (!canSetHeaders) {
                logger.error { "Tried to set header ($key=$value) but headers were already send" }
            }
        }

        override fun readInputLine(): String? = stdIn.readUtf8Line()

        override fun writeOutput(output: String) {
            writeOutput.invoke(output)
            canSetHeaders = false
        }

        override fun writeError(output: String) {
            writeError.invoke(output)
        }
    }

    private fun runScriptIfPossible(): Flow<FCGIRecord.FCGIResponseRecord> {
        return if (stdInDone && params != null && (!dataStarted || dataDone)) {
            val scriptFile: Path? = params!!.findScriptPath()
            if (scriptFile != null) {
                val scriptSource = scriptFile.toFile().toScriptSource()
                logger.debug { "execute script $scriptFile" }
                executeScript(scriptSource, FileSystem.SYSTEM.canonicalize(scriptFile).parent!!)
            } else {
                flowOf(FCGIRecord.EndRequest(3, FCGIRecord.EndRequest.ProtocolStatus.RequestComplete))
            }
        } else {
            logger.trace {
                val reason = when {
                    stdInDone -> "stdin not read"
                    params == null -> "params not read"
                    else -> "started and didn't finish data reading"
                }
                "didn't run script yet, because $reason"
            }
            emptyFlow()
        }
    }

    private fun executeScript(
        scriptSource: SourceCode,
        workingDirectory: Path,
    ): Flow<FCGIRecord.FCGIResponseRecord> {
        return callbackFlow {
            val haveToWriteHeaders = AtomicBoolean(true)
            fun writeOutput(output: String) {
                val stdOutBuffer = Buffer()
                if (haveToWriteHeaders.compareAndSet(/* expectedValue = */ true, /* newValue = */ false)) {
                    responseHeaders.forEach { (key, values) ->
                        values.forEach { value ->
                            stdOutBuffer.writeUtf8(key)
                            stdOutBuffer.writeUtf8CodePoint(':'.code)
                            stdOutBuffer.writeUtf8(value)
                            stdOutBuffer.writeUtf8CodePoint('\n'.code)
                        }
                    }
                    stdOutBuffer.writeUtf8CodePoint('\n'.code)
                }
                stdOutBuffer.writeUtf8(output)
                while (!stdOutBuffer.exhausted()) {
                    val out = stdOutBuffer.readBuffer(stdOutBuffer.size.coerceAtMost(DEFAULT_MAX_RECORD_SIZE))
                    trySendBlocking(FCGIRecord.Stdout(out))
                }
            }

            var wroteError = false
            fun writeError(output: String) {
                val stdErrorBuffer = Buffer()
                stdErrorBuffer.writeUtf8(output)
                if (!stdErrorBuffer.exhausted()) {
                    wroteError = true
                    do {
                        val out = stdErrorBuffer.readBuffer(stdErrorBuffer.size.coerceAtMost(DEFAULT_MAX_RECORD_SIZE))
                        trySendBlocking(FCGIRecord.Stderr(out))
                    } while (!stdErrorBuffer.exhausted())
                }
            }

            val result = scriptingHost.evalWithTemplate<ServerScriptDefinition>(
                script = scriptSource,
                evaluation = {
                    refineConfigurationBeforeEvaluate { context ->
                        val sourceLocationId = context.compiledScript.sourceLocationId
                        ScriptEvaluationConfiguration(context.evaluationConfiguration) {
                            implicitReceivers(
                                ServerScriptImpl(
                                    cache = cache.getOrSet(sourceLocationId, ::CacheImpl),
                                    workingDirectory = workingDirectory,
                                    writeOutput = ::writeOutput,
                                    writeError = ::writeError,
                                )
                            )
                        }.asSuccess()
                    }
                },
            )
            val appStatus: Int = when (result) {
                is ResultWithDiagnostics.Success -> when (val returnValue = result.value.returnValue) {
                    is ResultValue.Error -> {
                        logger.error(returnValue.error) {
                            "script execution threw error (wrapping exception ${returnValue.wrappingException})"
                        }
                        1
                    }

                    ResultValue.NotEvaluated -> {
                        logger.error { "script execution wasn't evaluated" }
                        error("scripts should always get evaluated")
                    }

                    is ResultValue.Unit,
                    is ResultValue.Value,
                        -> {
                        logger.debug { "script execution successful" }
                        0
                    }
                }

                is ResultWithDiagnostics.Failure -> {
                    val exception = result.reports.firstNotNullOfOrNull(ScriptDiagnostic::exception)
                    logger.error(exception) { "script compilation failed with result: $result" }
                    2
                }
            }
            // if nothing emitted yet
            if (haveToWriteHeaders.get()) {
                if (appStatus != 0) {
                    send(FCGIRecord.Stdout(Buffer().writeUtf8("Status:500 Internal Server Error\n\n")))
                } else {
                    send(FCGIRecord.Stdout(Buffer().writeUtf8("\n")))
                }
            }
            // empty as end marker
            send(FCGIRecord.Stdout(Buffer()))
            if (wroteError) {
                // empty as end marker
                send(FCGIRecord.Stderr(Buffer()))
            }
            send(
                FCGIRecord.EndRequest(
                    appStatus = appStatus,
                    protocolStatus = FCGIRecord.EndRequest.ProtocolStatus.RequestComplete,
                )
            )
            close()
        }.flowOn(Dispatchers.IO)
    }
}

private fun Map<String, List<String>>.findScriptPath(): Path? {
    val scriptFileName = get("SCRIPT_FILENAME")?.singleOrNull()
    val scriptFileNamePath = scriptFileName?.toPath()?.takeIf(FileSystem.SYSTEM::exists)
    val documentRoot = get("DOCUMENT_ROOT")?.singleOrNull()
    val scriptName = get("SCRIPT_NAME")?.singleOrNull()

    return when {
        scriptFileNamePath != null -> {
            logger.debug { "resolved script via SCRIPT_FILENAME=$scriptFileName" }
            scriptFileNamePath
        }

        documentRoot != null && scriptName != null -> {
            val scriptNamePath = "$documentRoot$scriptName".toPath().takeIf(FileSystem.SYSTEM::exists)
            if (scriptNamePath != null) {
                logger.debug { "resolved script via DOCUMENT_ROOT/SCRIPT_NAME=$documentRoot$scriptName" }
                scriptNamePath
            } else {
                logger.warn { "couldn't resolve script via SCRIPT_FILENAME($scriptFileName)/DOCUMENT_ROOT($documentRoot)/SCRIPT_NAME($scriptName)" }
                null
            }
        }

        else -> {
            logger.warn { "couldn't resolve script via SCRIPT_FILENAME($scriptFileName)/DOCUMENT_ROOT($documentRoot)/SCRIPT_NAME($scriptName)" }
            null
        }
    }
}

private const val DEFAULT_MAX_RECORD_SIZE = 8184L
