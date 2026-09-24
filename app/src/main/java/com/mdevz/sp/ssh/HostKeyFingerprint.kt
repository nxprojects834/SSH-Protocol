package com.mdevz.sp.ssh

import java.util.Base64
import java.security.MessageDigest

object HostKeyFingerprint {
    fun sha256(keyBlob: ByteArray): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(keyBlob)

        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
