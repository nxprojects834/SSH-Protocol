package com.mdevz.sp.tunnel

import com.mdevz.sp.tunnel.config.ConnectionSnapshot
import java.io.IOException

object HttpConnectTransport {

    private const val MAX_LINE_BYTES = 8_192
    private const val MAX_HEADER_BYTES = 32_768

    suspend fun establish(
        connection: TunnelConnection,
        snapshot: ConnectionSnapshot
    ): TunnelConnection {
        check(snapshot.proxyEnabled) {
            "HTTP CONNECT requires proxy to be enabled"
        }

        check(!snapshot.payloadEnabled) {
            "HTTP CONNECT must not precede custom payload mode"
        }

        require(snapshot.sshHost.isNotBlank()) {
            "SSH target host must not be blank"
        }

        require(snapshot.sshPort in 1..65535) {
            "SSH target port must be in 1..65535"
        }

        require(
            '\r' !in snapshot.sshHost &&
                '\n' !in snapshot.sshHost
        ) {
            "SSH target host contains invalid characters"
        }

        check(connection.isOpen) {
            "Cannot perform HTTP CONNECT on a closed transport"
        }

        val authority =
            "${snapshot.sshHost}:${snapshot.sshPort}"

        val request =
            buildString {
                append("CONNECT ")
                append(authority)
                append(" HTTP/1.1\r\n")
                append("Host: ")
                append(authority)
                append("\r\n")
                append("Proxy-Connection: Keep-Alive\r\n")
                append("\r\n")
            }

        try {
            connection.write(
                request.toByteArray(
                    Charsets.ISO_8859_1
                )
            )
            connection.flush()

            val statusLine =
                readLine(
                    connection = connection,
                    remainingHeaderBytes =
                        intArrayOf(MAX_HEADER_BYTES)
                )

            validateStatusLine(statusLine)

            var remainingHeaderBytes =
                MAX_HEADER_BYTES -
                    statusLine.toByteArray(
                        Charsets.ISO_8859_1
                    ).size -
                    2

            while (true) {
                val remaining =
                    intArrayOf(remainingHeaderBytes)

                val headerLine =
                    readLine(
                        connection = connection,
                        remainingHeaderBytes =
                            remaining
                    )

                remainingHeaderBytes =
                    remaining[0]

                if (headerLine.isEmpty()) {
                    break
                }

                if (
                    headerLine.indexOf(':') <= 0
                ) {
                    throw IOException(
                        "Malformed HTTP CONNECT response header"
                    )
                }
            }

            return connection
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    private suspend fun readLine(
        connection: TunnelConnection,
        remainingHeaderBytes: IntArray
    ): String {
        val bytes = ArrayList<Byte>()
        val singleByte = ByteArray(1)
        var sawCarriageReturn = false

        while (true) {
            if (remainingHeaderBytes[0] <= 0) {
                throw IOException(
                    "HTTP CONNECT response headers exceed limit"
                )
            }

            val count =
                connection.read(
                    buffer = singleByte,
                    offset = 0,
                    length = 1
                )

            if (count < 0) {
                throw IOException(
                    "Proxy closed connection during HTTP CONNECT"
                )
            }

            if (count == 0) {
                continue
            }

            remainingHeaderBytes[0] =
                remainingHeaderBytes[0] - 1

            val value = singleByte[0]

            if (sawCarriageReturn) {
                if (value == '\n'.code.toByte()) {
                    return bytes
                        .toByteArray()
                        .toString(
                            Charsets.ISO_8859_1
                        )
                }

                bytes.add(
                    '\r'.code.toByte()
                )
                sawCarriageReturn = false
            }

            if (value == '\r'.code.toByte()) {
                sawCarriageReturn = true
            } else {
                bytes.add(value)
            }

            if (bytes.size > MAX_LINE_BYTES) {
                throw IOException(
                    "HTTP CONNECT response line exceeds limit"
                )
            }
        }
    }

    private fun validateStatusLine(
        statusLine: String
    ) {
        val parts =
            statusLine.split(
                ' ',
                limit = 3
            )

        if (parts.size < 2) {
            throw IOException(
                "Malformed HTTP CONNECT status line"
            )
        }

        if (
            parts[0] != "HTTP/1.0" &&
                parts[0] != "HTTP/1.1"
        ) {
            throw IOException(
                "Unsupported HTTP CONNECT response version"
            )
        }

        val statusCode =
            parts[1].toIntOrNull()
                ?: throw IOException(
                    "Malformed HTTP CONNECT status code"
                )

        if (statusCode != 200) {
            throw IOException(
                "HTTP CONNECT rejected with status $statusCode"
            )
        }
    }

    private fun ArrayList<Byte>.toByteArray():
        ByteArray {
        val result = ByteArray(size)

        for (index in indices) {
            result[index] = this[index]
        }

        return result
    }
}
