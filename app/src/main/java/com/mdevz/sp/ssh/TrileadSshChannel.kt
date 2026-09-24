package com.mdevz.sp.ssh

import com.trilead.ssh2.LocalStreamForwarder
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class TrileadSshChannel(
    private val forwarder: LocalStreamForwarder
) : SshChannel {

    private val open = AtomicBoolean(true)

    override val input: InputStream
        get() = forwarder.inputStream

    override val output: OutputStream
        get() = forwarder.outputStream

    override val isOpen: Boolean
        get() = open.get()

    override fun close() {
        if (open.compareAndSet(true, false)) {
            forwarder.close()
        }
    }
}
