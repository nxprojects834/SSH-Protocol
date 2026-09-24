package com.mdevz.sp.tunnel

import com.mdevz.sp.payload.PayloadContext
import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import com.mdevz.sp.tunnel.type.TunnelCapabilityMatrix
import com.mdevz.sp.tunnel.type.TunnelTypeDetector
import java.net.Socket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class ConnectionPipelineStage {
    CONNECTING_TCP,
    CONNECTING_TLS,
    TLS_CONNECTED,
    SENDING_PAYLOAD,
    PAYLOAD_SENT
}

object ConnectionPipelineBuilder {

    suspend fun build(
        snapshot: ConnectionSnapshot,
        protectSocket: (Socket) -> Boolean,
        onStage: (ConnectionPipelineStage) -> Unit = {}
    ): TunnelConnection {
        val tunnelType =
            TunnelTypeDetector.detect(
                proxyEnabled = snapshot.proxyEnabled,
                payloadEnabled = snapshot.payloadEnabled,
                tlsEnabled = snapshot.tlsEnabled
            )

        check(
            TunnelCapabilityMatrix.isSupported(
                tunnelType
            )
        ) {
            "Unsupported tunnel feature combination: $tunnelType"
        }

        onStage(
            ConnectionPipelineStage.CONNECTING_TCP
        )

        var connection =
            TransportFactory.connectTcp(
                snapshot = snapshot,
                protectSocket = protectSocket
            )

        try {
            currentCoroutineContext()
                .ensureActive()

            if (snapshot.tlsEnabled) {
                onStage(
                    ConnectionPipelineStage.CONNECTING_TLS
                )

                connection =
                    TransportFactory.wrapTls(
                        connection = connection,
                        snapshot = snapshot
                    )

                currentCoroutineContext()
                    .ensureActive()

                onStage(
                    ConnectionPipelineStage.TLS_CONNECTED
                )
            }

            if (snapshot.payloadEnabled) {
                onStage(
                    ConnectionPipelineStage.SENDING_PAYLOAD
                )

                val payloadContext =
                    PayloadContext(
                        sshHost = snapshot.sshHost,
                        sshPort = snapshot.sshPort,
                        proxyHost = snapshot.proxyHost,
                        proxyPort = snapshot.proxyPort
                    )

                connection =
                    PayloadTransport.establish(
                        connection = connection,
                        snapshot = snapshot,
                        context = payloadContext
                    )

                currentCoroutineContext()
                    .ensureActive()

                onStage(
                    ConnectionPipelineStage.PAYLOAD_SENT
                )
            }

            return connection
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }
}
