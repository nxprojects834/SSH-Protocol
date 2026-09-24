package com.mdevz.sp.tunnel

import com.mdevz.sp.tunnel.config.ConnectionSnapshot

object TlsSniTransport {

    suspend fun establish(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot
    ): TunnelConnection {
        check(snapshot.tlsEnabled) {
            "TLS transport requires TLS to be enabled"
        }

        val connectHost =
            if (snapshot.proxyEnabled) {
                snapshot.proxyHost
            } else {
                snapshot.sshHost
            }

        val connectPort =
            if (snapshot.proxyEnabled) {
                snapshot.proxyPort
            } else {
                snapshot.sshPort
            }

        val sniHost =
            snapshot.sni
                .takeIf { it.isNotBlank() }
                ?: connectHost

        val verifyHost = sniHost

        require(connectHost.isNotBlank()) {
            "TLS connect host must not be blank"
        }

        require(connectPort in 1..65535) {
            "TLS connect port must be in 1..65535"
        }

        require(sniHost.isNotBlank()) {
            "TLS SNI host must not be blank"
        }

        require(verifyHost.isNotBlank()) {
            "TLS verify host must not be blank"
        }

        return TlsTunnelConnection.upgrade(
            transport = connection,
            connectHost = connectHost,
            connectPort = connectPort,
            sniHost = sniHost,
            verifyHost = verifyHost,
            tlsVersion = snapshot.tlsVersion
        )
    }
}
