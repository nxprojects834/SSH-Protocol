package com.mdevz.sp.socks

import com.mdevz.sp.core.util.readExactly
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

object Socks5Protocol {

    const val VERSION = 0x05

    const val AUTH_NO_AUTH = 0x00
    const val AUTH_NO_ACCEPTABLE = 0xFF

    const val COMMAND_CONNECT = 0x01

    const val ADDRESS_IPV4 = 0x01
    const val ADDRESS_DOMAIN = 0x03
    const val ADDRESS_IPV6 = 0x04

    const val REPLY_SUCCEEDED = 0x00
    const val REPLY_GENERAL_FAILURE = 0x01
    const val REPLY_CONNECTION_NOT_ALLOWED = 0x02
    const val REPLY_NETWORK_UNREACHABLE = 0x03
    const val REPLY_HOST_UNREACHABLE = 0x04
    const val REPLY_CONNECTION_REFUSED = 0x05
    const val REPLY_TTL_EXPIRED = 0x06
    const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
    const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

    fun negotiateNoAuth(
        input: InputStream,
        output: OutputStream
    ) {
        val version = input.readByteOrThrow()
        if (version != VERSION) {
            throw Socks5Exception(
                "Unsupported SOCKS version: $version",
                REPLY_GENERAL_FAILURE
            )
        }

        val methodCount = input.readByteOrThrow()
        if (methodCount <= 0) {
            throw Socks5Exception(
                "SOCKS5 client supplied no authentication methods",
                REPLY_CONNECTION_NOT_ALLOWED
            )
        }

        val methods = input.readExactly(methodCount)
        val supportsNoAuth = methods.any {
            (it.toInt() and 0xFF) == AUTH_NO_AUTH
        }

        if (!supportsNoAuth) {
            output.write(
                byteArrayOf(
                    VERSION.toByte(),
                    AUTH_NO_ACCEPTABLE.toByte()
                )
            )
            output.flush()

            throw Socks5Exception(
                "SOCKS5 client does not support NO AUTH",
                REPLY_CONNECTION_NOT_ALLOWED
            )
        }

        output.write(
            byteArrayOf(
                VERSION.toByte(),
                AUTH_NO_AUTH.toByte()
            )
        )
        output.flush()
    }

    fun readConnectRequest(input: InputStream): Socks5Request {
        val version = input.readByteOrThrow()
        if (version != VERSION) {
            throw Socks5Exception(
                "Invalid SOCKS5 request version: $version"
            )
        }

        val command = input.readByteOrThrow()
        val reserved = input.readByteOrThrow()
        val addressType = input.readByteOrThrow()

        if (command != COMMAND_CONNECT) {
            throw Socks5Exception(
                "Unsupported SOCKS5 command: $command",
                REPLY_COMMAND_NOT_SUPPORTED
            )
        }

        val host = when (addressType) {
            ADDRESS_IPV4 -> {
                val raw = input.readExactly(4)
                InetAddress.getByAddress(raw).hostAddress
                    ?: throw Socks5Exception(
                        "Invalid IPv4 address",
                        REPLY_ADDRESS_TYPE_NOT_SUPPORTED
                    )
            }

            ADDRESS_DOMAIN -> {
                val length = input.readByteOrThrow()
                if (length == 0) {
                    throw Socks5Exception(
                        "SOCKS5 domain is empty",
                        REPLY_ADDRESS_TYPE_NOT_SUPPORTED
                    )
                }

                val raw = input.readExactly(length)
                String(raw, StandardCharsets.US_ASCII)
            }

            ADDRESS_IPV6 -> {
                val raw = input.readExactly(16)
                InetAddress.getByAddress(raw).hostAddress
                    ?: throw Socks5Exception(
                        "Invalid IPv6 address",
                        REPLY_ADDRESS_TYPE_NOT_SUPPORTED
                    )
            }

            else -> throw Socks5Exception(
                "Unsupported SOCKS5 address type: $addressType",
                REPLY_ADDRESS_TYPE_NOT_SUPPORTED
            )
        }

        val portBytes = input.readExactly(2)
        val port =
            ((portBytes[0].toInt() and 0xFF) shl 8) or
                (portBytes[1].toInt() and 0xFF)

        if (port !in 1..65535) {
            throw Socks5Exception(
                "Invalid destination port: $port",
                REPLY_CONNECTION_NOT_ALLOWED
            )
        }

        return Socks5Request(
            host = host,
            port = port
        )
    }

    fun writeSuccess(output: OutputStream) {
        writeReply(output, REPLY_SUCCEEDED)
    }

    fun writeFailure(
        output: OutputStream,
        replyCode: Int
    ) {
        writeReply(output, replyCode)
    }

    private fun writeReply(
        output: OutputStream,
        replyCode: Int
    ) {
        output.write(
            byteArrayOf(
                VERSION.toByte(),
                replyCode.toByte(),
                0x00,
                ADDRESS_IPV4.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00
            )
        )
        output.flush()
    }

    private fun InputStream.readByteOrThrow(): Int {
        val value = read()
        if (value < 0) {
            throw EOFException("Unexpected EOF in SOCKS5 message")
        }
        return value
    }
}
