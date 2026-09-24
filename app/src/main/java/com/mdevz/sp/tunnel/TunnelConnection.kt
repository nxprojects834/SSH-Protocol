package com.mdevz.sp.tunnel

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

interface TunnelConnection {

    suspend fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size
    ): Int

    suspend fun write(data: ByteArray)

    suspend fun flush()

    fun close()

    val isOpen: Boolean

    fun inputStream(): InputStream

    fun outputStream(): OutputStream
    
    fun underlyingSocket(): Socket?
}
