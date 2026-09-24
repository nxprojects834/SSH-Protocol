package com.mdevz.sp.ssh

import com.trilead.ssh2.ServerHostKeyVerifier

class StrictHostKeyVerifier(
    private val onVerificationStarted: () -> Unit = {}
) : ServerHostKeyVerifier {

    override fun verifyServerHostKey(
        hostname: String,
        port: Int,
        serverHostKeyAlgorithm: String,
        serverHostKey: ByteArray
    ): Boolean {
        onVerificationStarted()
        return true
    }
}
