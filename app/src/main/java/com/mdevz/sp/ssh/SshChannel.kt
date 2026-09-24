package com.mdevz.sp.ssh

import java.io.InputStream
import java.io.OutputStream

interface SshChannel {
    val input: InputStream
    val output: OutputStream
    val isOpen: Boolean
    fun close()
}
