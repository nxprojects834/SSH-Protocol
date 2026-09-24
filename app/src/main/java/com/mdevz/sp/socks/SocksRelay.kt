package com.mdevz.sp.socks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

object SocksRelay {

    private const val BUFFER_SIZE = 64 * 1024
    private val connectionSequence = AtomicLong(0L)

    suspend fun relay(
        clientSocket: Socket,
        sshInput: InputStream,
        sshOutput: OutputStream,
        onClientToRemoteBytes: (Long) -> Unit = {},
        onRemoteToClientBytes: (Long) -> Unit = {},
    ) = coroutineScope {
        val connectionId = connectionSequence.incrementAndGet()
        val startedAtNanos = System.nanoTime()
        val clientToRemoteBytes = AtomicLong(0L)
        val remoteToClientBytes = AtomicLong(0L)

        val clientInput = clientSocket.getInputStream()
        val clientOutput = clientSocket.getOutputStream()


        val upload = async(Dispatchers.IO) {
            copy(
                connectionId = connectionId,
                direction = "CLIENT_TO_SSH",
                input = clientInput,
                output = sshOutput,
                byteCounter = clientToRemoteBytes,
                onBytes = onClientToRemoteBytes,
            )
        }

        val download = async(Dispatchers.IO) {
            copy(
                connectionId = connectionId,
                direction = "SSH_TO_CLIENT",
                input = sshInput,
                output = clientOutput,
                byteCounter = remoteToClientBytes,
                onBytes = onRemoteToClientBytes,
            )
        }

        var firstCompleted = "UNKNOWN"

        try {
            select<Unit> {
                upload.onAwait {
                    firstCompleted = "CLIENT_TO_SSH"
                }

                download.onAwait {
                    firstCompleted = "SSH_TO_CLIENT"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            upload.cancel()
            download.cancel()

            runCatching { clientSocket.close() }
            runCatching { sshInput.close() }
            runCatching { sshOutput.close() }

            val lifetimeMillis =
                (System.nanoTime() - startedAtNanos) / 1_000_000L

        }
    }

    private suspend fun copy(
        connectionId: Long,
        direction: String,
        input: InputStream,
        output: OutputStream,
        byteCounter: AtomicLong,
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (true) {
                val count = input.read(buffer)

                if (count < 0) {
                    break
                }

                if (count == 0) {
                    continue
                }

                output.write(buffer, 0, count)

                val countLong = count.toLong()
                byteCounter.addAndGet(countLong)
                onBytes(countLong)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw failure
        }
    }
}
