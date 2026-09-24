package com.mdevz.sp.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TcpTunnelConnection private constructor(
    private val socket: Socket
) : TunnelConnection {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override val isOpen: Boolean
        get() = socket.isConnected &&
            !socket.isClosed &&
            !socket.isInputShutdown &&
            !socket.isOutputShutdown

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int = withContext(Dispatchers.IO) {
        require(offset >= 0)
        require(length >= 0)
        require(offset + length <= buffer.size)

        coroutineContext.ensureActive()
        input.read(buffer, offset, length)
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            output.write(data)
        }
    }

    override suspend fun flush() {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            output.flush()
        }
    }

    override fun inputStream(): InputStream = input

    override fun outputStream(): OutputStream = output

    override fun underlyingSocket(): Socket = socket

    override fun close() {
        runCatching { socket.shutdownOutput() }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    companion object {

        suspend fun connect(
            host: String,
            port: Int,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int = 0,
            sendBufferSize: Int = 16_384,
            receiveBufferSize: Int = 32_768,
            tcpNoDelay: Boolean = true,
            protectSocket: ((Socket) -> Boolean)? = null
        ): TcpTunnelConnection =
            withContext(Dispatchers.IO) {

                require(host.isNotBlank()) {
                    "TCP host cannot be blank"
                }

                require(port in 1..65535) {
                    "TCP port must be 1..65535"
                }

                require(connectTimeoutMillis > 0) {
                    "Connect timeout must be greater than zero"
                }

                require(readTimeoutMillis >= 0) {
                    "Read timeout cannot be negative"
                }

                require(sendBufferSize > 0) {
                    "Send buffer size must be greater than zero"
                }

                require(receiveBufferSize > 0) {
                    "Receive buffer size must be greater than zero"
                }

                suspendCancellableCoroutine {
                    continuation ->

                    val socket =
                        Socket()

                    continuation.invokeOnCancellation {
                        runCatching {
                            socket.close()
                        }
                    }

                    try {
                        if (protectSocket != null) {
                            socket.bind(
                                InetSocketAddress(0)
                            )

                            if (!protectSocket(socket)) {
                                throw IllegalStateException(
                                    "VpnService failed to protect TCP socket"
                                )
                            }
                        }

                        socket.sendBufferSize =
                            sendBufferSize
                        socket.receiveBufferSize =
                            receiveBufferSize
                        socket.tcpNoDelay = tcpNoDelay
                        socket.keepAlive = true
                        socket.soTimeout =
                            readTimeoutMillis

                        if (
                            !continuation.isActive
                        ) {
                            runCatching {
                                socket.close()
                            }
                            return@suspendCancellableCoroutine
                        }

                        socket.connect(
                            InetSocketAddress(
                                host,
                                port
                            ),
                            connectTimeoutMillis
                        )

                        if (
                            !continuation.isActive
                        ) {
                            runCatching {
                                socket.close()
                            }
                            return@suspendCancellableCoroutine
                        }

                        continuation.resume(
                            TcpTunnelConnection(
                                socket
                            )
                        )
                    } catch (
                        failure: Throwable
                    ) {
                        runCatching {
                            socket.close()
                        }

                        if (
                            continuation.isActive
                        ) {
                            continuation
                                .resumeWithException(
                                    failure
                                )
                        }
                    }
                }
            }
    }
}
