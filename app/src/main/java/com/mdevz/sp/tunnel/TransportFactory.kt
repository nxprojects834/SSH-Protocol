package com.mdevz.sp.tunnel

import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import java.net.Socket

object TransportFactory {

    suspend fun connectTcp(
        snapshot: ConnectionSnapshot,
        protectSocket: (Socket) -> Boolean
    ): TunnelConnection {
        val connection =
            DirectTransport.connect(
                snapshot = snapshot,
                protectSocket = protectSocket
            )

        return if (
            snapshot.proxyEnabled &&
                !snapshot.payloadEnabled
        ) {
            HttpConnectTransport.establish(
                connection = connection,
                snapshot = snapshot
            )
        } else {
            connection
        }
    }

    suspend fun wrapTls(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot
    ): TunnelConnection =
        TlsSniTransport.establish(
            connection = connection,
            snapshot = snapshot
        )
}
