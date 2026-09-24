package com.mdevz.sp.tunnel.type

object TunnelCapabilityMatrix {
    private val supportedTypes =
        setOf(
            TunnelType.SSH_ONLY,
            TunnelType.SSH_PROXY,
            TunnelType.SSH_SNI,
            TunnelType.SSH_PROXY_PAYLOAD,
            TunnelType.SSH_PAYLOAD_SNI,
            TunnelType.SSH_PROXY_PAYLOAD_SNI
        )

    fun isSupported(type: TunnelType): Boolean =
        type in supportedTypes
}
