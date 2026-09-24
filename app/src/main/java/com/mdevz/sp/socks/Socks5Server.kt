package com.mdevz.sp.socks

import com.mdevz.sp.ssh.SshClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class Socks5Server(
    private val sshClient: SshClient,
    private val listenPort: Int = 1080,
    private val onClientToRemoteBytes: (Long) -> Unit = {},
    private val onRemoteToClientBytes: (Long) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {

    private val running = AtomicBoolean(false)

    private val clients =
        Collections.synchronizedSet(mutableSetOf<Socket>())

    private var scope: CoroutineScope? = null
    private var scopeJob: Job? = null
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean
        get() = running.get()

    suspend fun start() {
        if (!sshClient.isConnected) {
            throw IllegalStateException(
                "SOCKS5 cannot start before SSH authentication succeeds"
            )
        }

        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException(
                "SOCKS5 server is already running"
            )
        }

        try {
            val socket =
                withContext(Dispatchers.IO) {
                    ServerSocket(
                        listenPort,
                        50,
                        InetAddress.getByName(
                            "127.0.0.1"
                        )
                    ).apply {
                        reuseAddress = true
                    }
                }

            serverSocket = socket

            val supervisor =
                SupervisorJob()

            val newScope =
                CoroutineScope(
                    supervisor +
                        Dispatchers.IO
                )

            scopeJob = supervisor
            scope = newScope

            acceptJob =
                newScope.launch {
                    acceptLoop(socket)
                }
        } catch (failure: Throwable) {
            running.set(false)

            runCatching {
                serverSocket?.close()
            }

            serverSocket = null

            scopeJob?.cancel()
            scopeJob = null
            scope = null

            throw SocksBindException(
                "Unable to bind SOCKS5 server on 127.0.0.1:$listenPort",
                failure
            )
        }
    }

    suspend fun stop() {
        if (!running.getAndSet(false)) {
            return
        }

        val accept =
            acceptJob

        val workers =
            scopeJob

        acceptJob = null
        scopeJob = null
        scope = null

        withContext(Dispatchers.IO) {
            runCatching {
                serverSocket?.close()
            }

            serverSocket = null

            val snapshot =
                synchronized(clients) {
                    clients.toList()
                }

            snapshot.forEach {
                runCatching {
                    it.close()
                }
            }
        }

        accept?.cancelAndJoin()
        workers?.cancelAndJoin()

        clients.clear()
    }

    private suspend fun acceptLoop(
        server: ServerSocket
    ) {
        while (
            running.get() &&
            scope?.isActive == true
        ) {
            val client =
                try {
                    withContext(
                        Dispatchers.IO
                    ) {
                        server.accept()
                    }
                } catch (
                    failure: SocketException
                ) {
                    if (running.get()) {
                        onError(failure)
                        running.set(false)
                    }

                    break
                } catch (
                    failure: IOException
                ) {
                    if (running.get()) {
                        onError(failure)
                    }

                    continue
                }

            if (!running.get()) {
                runCatching {
                    client.close()
                }
                break
            }

            clients += client

            val workerScope =
                scope

            if (workerScope == null) {
                clients -= client

                runCatching {
                    client.close()
                }

                break
            }

            workerScope.launch {
                try {
                    handleClient(client)
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (
                    failure: Throwable
                ) {
                    if (
                        running.get() &&
                        !isExpectedClientClosure(
                            failure
                        ) &&
                        !isExpectedTcpOnlyRejection(
                            failure
                        )
                    ) {
                        onError(failure)
                    }
                } finally {
                    clients -= client

                    runCatching {
                        client.close()
                    }
                }
            }
        }
    }

    private suspend fun handleClient(
        client: Socket
    ) {
        client.tcpNoDelay = true

        val input =
            client.getInputStream()

        val output =
            client.getOutputStream()

        Socks5Protocol.negotiateNoAuth(
            input,
            output
        )

        val request =
            try {
                Socks5Protocol
                    .readConnectRequest(
                        input
                    )
            } catch (
                failure: Socks5Exception
            ) {
                runCatching {
                    Socks5Protocol
                        .writeFailure(
                            output,
                            failure.replyCode
                        )
                }

                throw failure
            }

        if (!sshClient.isConnected) {
            Socks5Protocol.writeFailure(
                output,
                Socks5Protocol
                    .REPLY_GENERAL_FAILURE
            )

            throw Socks5Exception(
                "SSH disconnected before direct-tcpip channel creation"
            )
        }

        val channel =
            try {
                sshClient.openDirectTcpIp(
                    request.host,
                    request.port
                )
            } catch (
                failure: Throwable
            ) {
                val replyCode =
                    mapChannelFailure(
                        failure
                    )

                runCatching {
                    Socks5Protocol
                        .writeFailure(
                            output,
                            replyCode
                        )
                }

                throw Socks5Exception(
                    "SSH direct-tcpip failed for ${request.host}:${request.port}",
                    replyCode,
                    failure
                )
            }

        try {
            Socks5Protocol.writeSuccess(
                output
            )

            SocksRelay.relay(
                clientSocket = client,
                sshInput = channel.input,
                sshOutput = channel.output,
                onClientToRemoteBytes =
                    onClientToRemoteBytes,
                onRemoteToClientBytes =
                    onRemoteToClientBytes
            )
        } finally {
            runCatching {
                channel.close()
            }
        }
    }

    private fun isExpectedClientClosure(
        failure: Throwable
    ): Boolean =
        when (failure) {
            is EOFException ->
                true

            is SocketException -> {
                val message =
                    failure.message
                        .orEmpty()
                        .lowercase()

                message == "socket closed" ||
                    "broken pipe" in message ||
                    "connection reset" in message
            }

            else ->
                false
        }

    private fun isExpectedTcpOnlyRejection(
        failure: Throwable
    ): Boolean =
        failure is Socks5Exception &&
            failure.replyCode ==
                Socks5Protocol
                    .REPLY_COMMAND_NOT_SUPPORTED

    private fun mapChannelFailure(
        failure: Throwable
    ): Int {
        val message =
            failure.message
                .orEmpty()
                .lowercase()

        return when {
            "refused" in message ->
                Socks5Protocol
                    .REPLY_CONNECTION_REFUSED

            "unreachable" in message ->
                Socks5Protocol
                    .REPLY_HOST_UNREACHABLE

            else ->
                Socks5Protocol
                    .REPLY_GENERAL_FAILURE
        }
    }
}

class SocksBindException(
    message: String,
    cause: Throwable
) : IOException(
    message,
    cause
)
