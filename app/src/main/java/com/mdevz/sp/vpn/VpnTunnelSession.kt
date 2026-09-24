package com.mdevz.sp.vpn

data class VpnTunnelSession(
    val mtu: Int,
    val ipv4Address: String,
    val socksPort: Int
)
