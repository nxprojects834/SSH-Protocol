package com.mdevz.sp.core.model

data class SshProfile(
    val name: String,

    val sshHost: String,
    val sshPort: Int = 22,
    val username: String,
    val password: String,

    val payloadEnabled: Boolean = false,
    val payloadMode: PayloadMode = PayloadMode.NORMAL,
    val payload: String = "",

    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 0,

    val tlsEnabled: Boolean = false,
    val sni: String = "",
    val tlsVersion: String = "default",

    val localSocksPort: Int = 1080,
    val keepAliveSeconds: Int = 30,
    val socketSendBufferSize: Int = 16_384,
    val socketReceiveBufferSize: Int = 32_768,
    val tcpNoDelay: Boolean = true,
    val cpuWakeLockEnabled: Boolean = false,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 0,

    val autoReconnect: Boolean = true,
    val maxReconnect: Int = 0
)
