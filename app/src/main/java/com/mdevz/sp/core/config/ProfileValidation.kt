package com.mdevz.sp.core.config

import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.tunnel.type.TunnelCapabilityMatrix
import com.mdevz.sp.tunnel.type.TunnelTypeDetector

data class ValidationError(
    val field: String,
    val message: String
)

object ProfileValidation {

    fun validate(profile: SshProfile): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        if (profile.name.isBlank()) {
            errors += ValidationError("name", "Profile name is required")
        }

        if (profile.sshHost.isBlank()) {
            errors += ValidationError("sshHost", "SSH host is required")
        }

        if (profile.sshPort !in 1..65535) {
            errors += ValidationError("sshPort", "SSH port must be 1..65535")
        }

        if (profile.username.isBlank()) {
            errors += ValidationError("username", "SSH username is required")
        }

        if (profile.password.isEmpty()) {
            errors += ValidationError(
                "password",
                "Password is required for password authentication"
            )
        }

        if (profile.connectTimeoutMillis <= 0) {
            errors += ValidationError(
                "connectTimeoutMillis",
                "Connect timeout must be greater than zero"
            )
        }

        if (profile.readTimeoutMillis < 0) {
            errors += ValidationError(
                "readTimeoutMillis",
                "Read timeout cannot be negative"
            )
        }

        if (profile.localSocksPort !in 1..65535) {
            errors += ValidationError(
                "localSocksPort",
                "SOCKS port must be 1..65535"
            )
        }

        if (
            profile.keepAliveSeconds != 0 &&
            profile.keepAliveSeconds < 5
        ) {
            errors += ValidationError(
                "keepAliveSeconds",
                "Keepalive must be 0 (disabled) or at least 5 seconds"
            )
        }

        if (profile.maxReconnect < 0) {
            errors += ValidationError(
                "maxReconnect",
                "Max reconnect cannot be negative"
            )
        }

        if (profile.socketSendBufferSize <= 0) {
            errors += ValidationError(
                "socketSendBufferSize",
                "Send buffer size must be greater than zero"
            )
        }

        if (profile.socketReceiveBufferSize <= 0) {
            errors += ValidationError(
                "socketReceiveBufferSize",
                "Receive buffer size must be greater than zero"
            )
        }

        if (profile.proxyEnabled) {
            if (profile.proxyHost.isBlank()) {
                errors += ValidationError(
                    "proxyHost",
                    "Proxy/transport host is required when proxy is enabled"
                )
            }

            if (profile.proxyPort !in 1..65535) {
                errors += ValidationError(
                    "proxyPort",
                    "Proxy/transport port must be 1..65535 when proxy is enabled"
                )
            }
        }

        if (profile.payloadEnabled && profile.payload.isBlank()) {
            errors += ValidationError(
                "payload",
                "Payload is required when payload mode is enabled"
            )
        }

        if (profile.tlsEnabled && profile.sni.isNotBlank()) {
            if (!isValidServerName(profile.sni)) {
                errors += ValidationError(
                    "sni",
                    "SNI must be a valid DNS server name"
                )
            }
        }

        val tunnelType =
            TunnelTypeDetector.detect(
                profile
            )

        if (!TunnelCapabilityMatrix.isSupported(tunnelType)) {
            errors += ValidationError(
                "tunnelType",
                "Unsupported tunnel feature combination"
            )
        }

        return errors
    }

    private fun isValidServerName(value: String): Boolean {
        if (value.length > 253) return false
        if (value.endsWith(".")) return false

        val labels = value.split('.')
        if (labels.isEmpty()) return false

        return labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
