package com.mdevz.sp.ssh

import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import com.mdevz.sp.tunnel.TunnelConnection
import com.trilead.ssh2.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TrileadSshClient(
    private val knownHostStore: KnownHostStore,
    private val onStage: (SshStage) -> Unit = {}
) : SshClient {

    @Volatile
    private var ssh: Connection? = null

    private val connected = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = connected.get()

    override suspend fun connect(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot
    ) = withContext(Dispatchers.IO) {
        check(!connected.get()) {
            "SSH client is already connected"
        }

        val client =
            Connection(snapshot.sshHost, snapshot.sshPort)
        client.setPreconnectedTransport(
            connection.inputStream(),
            connection.outputStream()
        ) {
            connection.close()
        }

        try {
            onStage(SshStage.CONNECTING)

            try {
                client.connect(
                    StrictHostKeyVerifier(
                        onVerificationStarted = {
                            onStage(SshStage.VERIFYING_HOST_KEY)
                        }
                    ),
                    0,
                    snapshot.readTimeoutMillis,
                    snapshot.connectTimeoutMillis
                )
            } catch (failure: Throwable) {
                throw unwrapHostKeyFailure(failure)
            }

            onStage(SshStage.AUTHENTICATING)

            val authenticated =
                client.authenticateWithPassword(
                    snapshot.username,
                    snapshot.password
                )

            if (!authenticated) {
                throw SshAuthenticationException(
                    "SSH username/password authentication failed"
                )
            }

            ssh = client
            connected.set(true)
        } catch (failure: Throwable) {
            connected.set(false)

            runCatching {
                client.close()
            }

            throw failure
        }
    }

    override suspend fun openDirectTcpIp(
        host: String,
        port: Int
    ): SshChannel = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) {
            "direct-tcpip host must not be blank"
        }

        require(port in 1..65535) {
            "direct-tcpip port must be in 1..65535"
        }

        val client = ssh
            ?: throw IOException(
                "SSH connection is not established"
            )

        if (!connected.get()) {
            throw IOException(
                "SSH connection is not authenticated"
            )
        }

        TrileadSshChannel(
            client.createLocalStreamForwarder(
                host,
                port
            )
        )
    }

    suspend fun sendKeepAlive() =
        withContext(Dispatchers.IO) {
            val client = ssh
                ?: throw IOException(
                    "SSH connection is not established"
                )

            client.sendIgnorePacket()
        }

    override fun disconnect() {
        connected.set(false)

        val client = ssh
        ssh = null

        if (client != null) {
            runCatching {
                client.close()
            }
        }
    }

    private fun unwrapHostKeyFailure(
        failure: Throwable
    ): Throwable {
        var current: Throwable? = failure

        while (current != null) {
            when (current) {
                is UnknownHostKeyException ->
                    return current

                is HostKeyMismatchException ->
                    return current
            }

            current = current.cause
        }

        return failure
    }
}
