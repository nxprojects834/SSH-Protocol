package com.mdevz.sp.ssh

data class HostKeyRecord(
    val host: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val fingerprint: String
)
