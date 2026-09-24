package com.mdevz.sp.ssh

import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import com.mdevz.sp.tunnel.TunnelConnection

interface SshClient {
    suspend fun connect(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot
    )

    suspend fun openDirectTcpIp(
        host: String,
        port: Int
    ): SshChannel

    fun disconnect()

    val isConnected: Boolean
}
