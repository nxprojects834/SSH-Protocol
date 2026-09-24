package com.mdevz.sp.payload

data class PayloadContext(
    val sshHost: String,
    val sshPort: Int,
    val proxyHost: String = "",
    val proxyPort: Int = 0
) {
    init {
        require(sshHost.isNotBlank()) {
            "SSH host cannot be blank"
        }

        require(sshPort in 1..65535) {
            "SSH port must be 1..65535"
        }
    }

    val hostPort: String
        get() = "$sshHost:$sshPort"
}
