package com.mdevz.sp.tunnel.type

import com.mdevz.sp.core.model.SshProfile

object TunnelTypeDetector {
    fun detect(profile: SshProfile): TunnelType {
        return detect(
            proxyEnabled = profile.proxyEnabled,
            payloadEnabled = profile.payloadEnabled,
            tlsEnabled = profile.tlsEnabled
        )
    }

    fun detect(
        proxyEnabled: Boolean,
        payloadEnabled: Boolean,
        tlsEnabled: Boolean
    ): TunnelType {
        return when {
            !proxyEnabled && !payloadEnabled && !tlsEnabled ->
                TunnelType.SSH_ONLY

            proxyEnabled && !payloadEnabled && !tlsEnabled ->
                TunnelType.SSH_PROXY

            !proxyEnabled && !payloadEnabled && tlsEnabled ->
                TunnelType.SSH_SNI

            proxyEnabled && payloadEnabled && !tlsEnabled ->
                TunnelType.SSH_PROXY_PAYLOAD

            !proxyEnabled && payloadEnabled && tlsEnabled ->
                TunnelType.SSH_PAYLOAD_SNI

            proxyEnabled && payloadEnabled && tlsEnabled ->
                TunnelType.SSH_PROXY_PAYLOAD_SNI

            else ->
                TunnelType.INVALID
        }
    }
}
