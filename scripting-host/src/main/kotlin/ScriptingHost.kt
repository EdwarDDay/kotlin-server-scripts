package net.edwardday.serverscript.scripthost

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.withLoggingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.slf4j.MDCContext
import net.edwardday.serverscript.scriptdefinition.script.Cache
import okio.Buffer
import okio.BufferedSource
import okio.IOException
import okio.buffer
import okio.use
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.io.path.setPosixFilePermissions
import kotlin.system.exitProcess

private const val DEFAULT_MAX_CONNECTIONS = 4

private val logger = KotlinLogging.logger {}

suspend fun main(vararg args: String) {
    if (args.size !in 1..2) {
        logger.error { "usage: <app> <fast cgi socket> [max connections=$DEFAULT_MAX_CONNECTIONS]" }
        exitProcess(status = 1)
    } else {
        val input = args[0]
        try {
            readFromSocket(input, args.getOrNull(1)?.toInt() ?: DEFAULT_MAX_CONNECTIONS)
        } finally {
            logger.info { "stop kss process" }
        }
    }
}

suspend fun readFromSocket(socket: String, maxConnections: Int) {
    logger.info { "start kss process" }
    logger.debug { "reading from $socket with $maxConnections max connections" }
    val socketAddress = UnixDomainSocketAddress.of(socket)
    runInterruptible { Files.deleteIfExists(socketAddress.path) }

    val globalState = GlobalState(maxConnections)
    try {
        runInterruptible {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(socketAddress)
        }.use { serverChannel ->
            logger.debug { "set socket permissions" }
            socketAddress.path.setPosixFilePermissions(
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                ),
            )
            listenToConnections(maxConnections, serverChannel, socketAddress, globalState)
        }
    } finally {
        runInterruptible {
            Files.deleteIfExists(socketAddress.path)
        }
    }
}

private suspend fun listenToConnections(
    maxConnections: Int,
    serverChannel: ServerSocketChannel,
    socketAddress: UnixDomainSocketAddress,
    globalState: GlobalState,
) {
    coroutineScope {
        List(maxConnections) { index ->
            withLoggingContext("Connection Index" to index.toString()) {
                launch(MDCContext()) {
                    handleRequests(
                        serverChannel,
                        socketAddress,
                        globalState.connectionStates[index]
                    )
                }
            }
        }.joinAll()
    }
}

private suspend fun handleRequests(
    serverChannel: ServerSocketChannel,
    socketAddress: UnixDomainSocketAddress,
    connectionState: GlobalState.ConnectionState,
): Nothing {
    while (true) {
        val socketChannel = try {
            runInterruptible(Dispatchers.IO, block = serverChannel::accept)
        } catch (e: Exception) {
            throw IllegalStateException("cannot accept socket of ${socketAddress.path.absolutePathString()}", e)
        }
        logger.debug { "open connection" }
        val source = socketChannel.source().buffer()
        val sink = socketChannel.sink().buffer()
        while (true) {
            try {
                val message = runInterruptible { FCGIRequestMessage.read(source) }
                val result = connectionState.handleMessage(message)
                val close = result.fold(false) { close, responseMessage ->
                    when (responseMessage) {
                        is GlobalState.HandleResult.CloseConnection -> true
                        is GlobalState.HandleResult.Message -> {
                            runInterruptible {
                                responseMessage.message.write(sink)
                                sink.flush()
                            }
                            close
                        }
                    }
                }
                if (close) {
                    logger.debug { "close connection" }
                    // when connection closed
                    runInterruptible {
                        sink.close()
                        source.close()
                    }
                    break
                }
            } catch (e: IOException) {
                logger.error(e) { "exception during connection" }
                try {
                    runInterruptible {
                        sink.close()
                        source.close()
                    }
                } catch (_: Exception) {
                }
                break
            }
        }
    }
}

class GlobalState(private val maxConnections: Int) {
    private val cache: Cache = CacheImpl()
    val connectionStates = List(maxConnections) { ConnectionState() }

    sealed class HandleResult {
        data object CloseConnection : HandleResult()
        data class Message(val message: FCGIResponseMessage) : HandleResult()
    }

    inner class ConnectionState internal constructor() {
        private val requests = mutableListOf<RequestState?>()

        private fun getRequestState(request: FCGIRequestMessage): RequestState? {
            val index = request.requestId - 1
            return if (request.record is FCGIRecord.BeginRequest) {
                repeat(index + 1 - requests.size) { requests.add(null) }
                RequestState(request.record, cache).also {
                    requests[index] = it
                }
            } else {
                requests.getOrNull(index)
            }
        }

        fun handleMessage(message: FCGIRequestMessage): Flow<HandleResult> {
            return if (message.requestId == 0) {
                if (message.record is FCGIRecord.GetValues) {
                    ManagementRequestState.handleGetValues(message.record, maxConnections)
                        .map(HandleResult::Message)
                } else {
                    logger.error { "can't handle ${message.record::class.simpleName} with request id 0" }
                    emptyFlow()
                }
            } else if (message.record is FCGIRecord.ReadUnknownType) {
                logger.error { "can't handle unknown fcgi record type (${message.record.type}) for request ${message.requestId}" }
                ManagementRequestState.handleUnknownType(message.record.type).map(HandleResult::Message)
            } else {
                val requestState = getRequestState(message)
                if (requestState != null) {
                    val responses = requestState.handleRecord(message.record)
                    responses.map { HandleResult.Message(FCGIResponseMessage(message.requestId, it)) }
                        .transform { response ->
                            emit(response)
                            if (response.message.record is FCGIRecord.EndRequest) {
                                requests[message.requestId - 1] = null
                                if (!requestState.keepConnection && requests.all { request -> request == null }) {
                                    emit(HandleResult.CloseConnection)
                                }
                            }
                        }
                } else {
                    logger.error { "received $message without a begin request" }
                    emptyFlow()
                }
            }
        }
    }
}

fun BufferedSource.readBuffer(contentLength: Long): Buffer = Buffer().write(this, contentLength)
