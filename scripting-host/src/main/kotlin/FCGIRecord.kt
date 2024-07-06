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
import okio.IOException

sealed interface FCGIRecord {

    sealed interface FCGIManageRecord : FCGIRecord
    sealed interface FCGIAppRecord : FCGIRecord

    sealed interface FCGIResponseRecord : FCGIRecord {
        val type: Int
        val contentLength: UShort
        fun write(sink: BufferedSink)
    }

    sealed interface FCGIRequestRecord : FCGIRecord


    class BeginRequest(
        val role: Role,
        val flags: UByte,
    ) : FCGIRequestRecord, FCGIAppRecord {
        val keepConnection: Boolean get() = (flags.toInt() and 1) != 0

        override fun toString(): String = "BeginRequest(role=$role, keepConnection=$keepConnection)"

        enum class Role {
            Responder,
            Authorizer,
            Filter,
            ;

            companion object {
                fun readRole(source: BufferedSource): Role {
                    return when (val role = source.readShort().toInt()) {
                        1 -> Responder
                        2 -> Authorizer
                        3 -> Filter
                        else -> throw IOException("unknown role $role")
                    }
                }
            }
        }

        companion object {
            fun read(source: BufferedSource): BeginRequest {
                val result = BeginRequest(
                    role = Role.readRole(source),
                    flags = source.readByte().toUByte(),
                )
                source.skip(5) // reserved
                return result
            }
        }
    }

    data object AbortRequest : FCGIRequestRecord, FCGIAppRecord {
        fun read(source: BufferedSource): AbortRequest {
            source.buffer.clear()
            return AbortRequest
        }
    }

    class EndRequest(
        internal val appStatus: Int,
        private val protocolStatus: ProtocolStatus,
    ) : FCGIResponseRecord, FCGIAppRecord {
        override val type: Int get() = 3
        override val contentLength: UShort get() = 8u

        override fun toString(): String = "EndRequest(appStatus=$appStatus, protocolStatus=$protocolStatus)"

        override fun write(sink: BufferedSink) {
            sink.writeInt(appStatus)
            sink.writeByte(protocolStatus.status.toInt())
            repeat(3) { sink.writeByte(0) }
        }

        enum class ProtocolStatus(val status: UByte) {
            RequestComplete(0u),
            CantMpxConn(1u),
            Overload(2u),
            UnknownRole(3u),
            ;
        }

        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun read(source: BufferedSource): Nothing {
                throw IOException("should not read end request record type")
            }
        }
    }

    class Params(
        val source: BufferedSource,
    ) : FCGIRequestRecord, FCGIAppRecord {

        override fun toString(): String = "Params(sourceBufferSize=${source.buffer.size})"

        companion object {
            fun read(source: BufferedSource): Params = Params(source)
        }
    }

    class Stdin(
        val source: BufferedSource,
    ) : FCGIRequestRecord, FCGIAppRecord {

        override fun toString(): String {
            return "Stdin(sourceBufferSize=${source.buffer.size})"
        }

        companion object {
            fun read(source: BufferedSource): Stdin = Stdin(source)
        }
    }

    class Stdout(
        private val sink: Buffer,
    ) : FCGIResponseRecord, FCGIAppRecord {

        override val type: Int get() = 6
        override val contentLength: UShort get() = sink.size.toUShort()

        override fun toString(): String = "Stdout(sinkSize=${sink.size})"

        override fun write(sink: BufferedSink) {
            sink.writeAll(this.sink)
        }

        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun read(source: BufferedSource): Nothing {
                throw IOException("should not read end request record type")
            }
        }
    }

    class Stderr(
        private val sink: Buffer,
    ) : FCGIResponseRecord, FCGIAppRecord {

        override val type: Int get() = 7
        override val contentLength: UShort get() = sink.size.toUShort()

        override fun toString() = "Stderr(sinkSize=${sink.size})"

        override fun write(sink: BufferedSink) {
            sink.writeAll(this.sink)
        }

        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun read(source: BufferedSource): Nothing {
                throw IOException("should not read end request record type")
            }
        }
    }

    class Data(
        val source: BufferedSource,
    ) : FCGIRequestRecord, FCGIAppRecord {

        override fun toString(): String {
            return "Data(sourceBufferSize=${source.buffer.size})"
        }

        companion object {
            fun read(source: BufferedSource): Data = Data(source)
        }
    }

    class GetValues(
        val source: BufferedSource,
    ) : FCGIRequestRecord, FCGIManageRecord {

        override fun toString(): String {
            return "GetValues(sourceBufferSize=${source.buffer.size})"
        }

        companion object {
            fun read(source: BufferedSource): GetValues = GetValues(source)
        }
    }

    class GetValuesResult(
        private val sink: Buffer,
    ) : FCGIResponseRecord, FCGIManageRecord {

        override val type: Int get() = 10
        override val contentLength: UShort get() = sink.size.toUShort()

        override fun toString(): String = "GetValuesResult(sinkBufferSize=${sink.size})"

        override fun write(sink: BufferedSink) {
            sink.writeAll(this.sink)
        }

        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun read(source: BufferedSource): Nothing {
                throw IOException("should not read end request record type")
            }
        }
    }

    class UnknownType(private val unknownType: UByte) : FCGIResponseRecord, FCGIManageRecord {

        override val type: Int get() = 11
        override val contentLength: UShort get() = 8u

        override fun toString(): String = "UnknownType(unknownType=$unknownType)"

        override fun write(sink: BufferedSink) {
            sink.writeByte(unknownType.toInt())
            repeat(7) { sink.writeByte(0) }
        }

        companion object {
            @Suppress("UNUSED_PARAMETER")
            fun read(source: BufferedSource): Nothing {
                throw IOException("should not read end request record type")
            }
        }
    }

    class ReadUnknownType(val type: UByte) : FCGIRequestRecord, FCGIManageRecord {

        override fun toString(): String {
            return "ReadUnknownType(type=$type)"
        }

        companion object {
            fun read(source: BufferedSource, type: UByte): ReadUnknownType {
                source.buffer.clear()
                return ReadUnknownType(type)
            }
        }
    }

    companion object {
        fun read(source: BufferedSource, type: UByte): FCGIRequestRecord {
            return when (type.toUInt()) {
                1u -> BeginRequest.read(source)
                2u -> AbortRequest.read(source)
                3u -> EndRequest.read(source)
                4u -> Params.read(source)
                5u -> Stdin.read(source)
                6u -> Stdout.read(source)
                7u -> Stderr.read(source)
                8u -> Data.read(source)
                9u -> GetValues.read(source)
                10u -> GetValuesResult.read(source)
                11u -> UnknownType.read(source)
                else -> ReadUnknownType.read(source, type)
            }
        }
    }
}