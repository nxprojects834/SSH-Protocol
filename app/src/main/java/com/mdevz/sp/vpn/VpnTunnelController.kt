package com.mdevz.sp.vpn

interface VpnTunnelController {

    suspend fun start(
        localSocksPort: Int
    ): VpnTunnelSession

    






    suspend fun stop()

    






    suspend fun shutdown()

    





    fun trafficBytes(): VpnTrafficBytes

    val isRunning: Boolean
}

data class VpnTrafficBytes(
    val rxBytes: Long,
    val txBytes: Long
)
