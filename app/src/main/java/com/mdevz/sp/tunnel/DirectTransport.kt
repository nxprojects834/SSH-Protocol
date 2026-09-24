package com.mdevz.sp.tunnel

import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import java.net.Socket

object DirectTransport {

    suspend fun connect(
        snapshot: ConnectionSnapshot,
        protectSocket: (Socket) -> Boolean
    ): TunnelConnection {
        val host =
            if (snapshot.proxyEnabled) {
                snapshot.proxyHost
            } else {
                snapshot.sshHost
            }

        val port =
            if (snapshot.proxyEnabled) {
                snapshot.proxyPort
            } else {
                snapshot.sshPort
            }

        require(host.isNotBlank()) {
            "Transport host must not be blank"
        }

        require(port in 1..65535) {
            "Transport port must be in 1..65535"
        }

        return TcpTunnelConnection.connect(
            host = host,
            port = port,
            connectTimeoutMillis =
                snapshot.connectTimeoutMillis,
            readTimeoutMillis =
                snapshot.readTimeoutMillis,
            sendBufferSize =
                snapshot.socketSendBufferSize,
            receiveBufferSize =
                snapshot.socketReceiveBufferSize,
            tcpNoDelay =
                snapshot.tcpNoDelay,
            protectSocket = protectSocket
        )
    }
}
