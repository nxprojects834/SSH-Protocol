package com.mdevz.sp.ssh

import java.io.IOException

class UnknownHostKeyException(
    val record: HostKeyRecord
) : IOException(
    "Unknown SSH host key for ${record.host}:${record.port} " +
        "(${record.algorithm}, ${record.fingerprint})"
)

class HostKeyMismatchException(
    val expected: HostKeyRecord,
    val received: HostKeyRecord
) : IOException(
    "SSH host key mismatch for ${received.host}:${received.port}: " +
        "expected ${expected.fingerprint}, received ${received.fingerprint}"
)

class CorruptKnownHostException(
    val host: String,
    val port: Int,
    cause: Throwable
) : IOException(
    "Stored SSH host key is corrupt for $host:$port",
    cause
)

class SshAuthenticationException(
    message: String
) : IOException(message)
