package com.mdevz.sp.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import kotlin.coroutines.coroutineContext

class TlsTunnelConnection private constructor(
    private val transport: TunnelConnection,
    private val sslSocket: SSLSocket
) : TunnelConnection {

    private val input: InputStream = sslSocket.inputStream
    private val output: OutputStream = sslSocket.outputStream

    override val isOpen: Boolean
        get() = transport.isOpen &&
            sslSocket.isConnected &&
            !sslSocket.isClosed

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int = withContext(Dispatchers.IO) {
        require(offset >= 0)
        require(length >= 0)
        require(offset + length <= buffer.size)

        coroutineContext.ensureActive()
        input.read(buffer, offset, length)
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            output.write(data)
        }
    }

    override suspend fun flush() {
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            output.flush()
        }
    }

    override fun inputStream(): InputStream = input

    override fun outputStream(): OutputStream = output

    



    override fun underlyingSocket(): Socket? =
        transport.underlyingSocket()

    override fun close() {
        runCatching { sslSocket.close() }
        transport.close()
    }

    companion object {

        suspend fun upgrade(
            transport: TunnelConnection,
            connectHost: String,
            connectPort: Int,
            sniHost: String,
            verifyHost: String,
            tlsVersion: String = "default"
        ): TlsTunnelConnection = withContext(Dispatchers.IO) {

            require(transport.isOpen) {
                "Cannot start TLS on a closed transport"
            }

            require(connectHost.isNotBlank()) {
                "TLS connect host cannot be blank"
            }

            require(connectPort in 1..65535) {
                "TLS connect port must be 1..65535"
            }

            val physicalSocket = transport.underlyingSocket()
                ?: throw IllegalArgumentException(
                    "TLS upgrade currently requires a socket-backed transport"
                )

            val provider = Conscrypt.newProvider()

            



            val sslContext = SSLContext.getInstance("TLS", provider)

            




            sslContext.init(null, null, null)


            val sslSocket = sslContext.socketFactory.createSocket(
                physicalSocket,
                verifyHost,
                connectPort,
                false
            ) as SSLSocket

            try {
                sslSocket.useClientMode = true

                val parameters = SSLParameters().apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(sniHost))
                }

                sslSocket.sslParameters = parameters

                val protocol =
                    when (tlsVersion) {
                        "default" -> null
                        "tls_1_0" -> "TLSv1"
                        "tls_1_1" -> "TLSv1.1"
                        "tls_1_2" -> "TLSv1.2"
                        "tls_1_3" -> "TLSv1.3"
                        else ->
                            throw IllegalArgumentException(
                                "Unsupported TLS version: $tlsVersion"
                            )
                    }

                if (protocol != null) {
                    require(
                        protocol in
                            sslSocket.supportedProtocols
                    ) {
                        "TLS version not supported: $protocol"
                    }

                    sslSocket.enabledProtocols =
                        arrayOf(protocol)
                }

                



                coroutineContext.ensureActive()

                



                sslSocket.startHandshake()

                coroutineContext.ensureActive()

                TlsTunnelConnection(
                    transport = transport,
                    sslSocket = sslSocket
                )
            } catch (t: Throwable) {
                runCatching { sslSocket.close() }
                transport.close()
                throw t
            }
        }
    }
}
