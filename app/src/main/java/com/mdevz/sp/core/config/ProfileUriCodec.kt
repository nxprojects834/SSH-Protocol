package com.mdevz.sp.core.config

import android.net.Uri
import android.util.Base64
import com.mdevz.sp.core.model.PayloadMode
import com.mdevz.sp.core.model.SshProfile
import org.json.JSONObject

object ProfileUriCodec {
    const val SCHEME =
        "sp"

    private const val VERSION =
        2

    private const val PREFIX =
        "sp://"

    private const val MAX_ENCODED_LENGTH =
        1_048_576

    private const val MAX_DECODED_LENGTH =
        786_432

    fun encode(
        profile: SshProfile
    ): String {
        val errors =
            ProfileValidation.validate(
                profile
            )

        require(
            errors.isEmpty()
        ) {
            errors.joinToString(
                "\n"
            ) {
                "${it.field}: ${it.message}"
            }
        }

        val json =
            JSONObject()
                .put(
                    "v",
                    VERSION
                )
                .put(
                    "name",
                    profile.name
                )
                .put(
                    "sshHost",
                    profile.sshHost
                )
                .put(
                    "sshPort",
                    profile.sshPort
                )
                .put(
                    "username",
                    profile.username
                )
                .put(
                    "password",
                    profile.password
                )
                .put(
                    "payloadEnabled",
                    profile.payloadEnabled
                )
            .put(
                "proxyEnabled",
                profile.proxyEnabled
            )
                .put(
                    "payloadMode",
                    profile.payloadMode.name
                )
                .put(
                    "payload",
                    profile.payload
                )
                .put(
                    "proxyHost",
                    profile.proxyHost
                )
                .put(
                    "proxyPort",
                    profile.proxyPort
                )
                .put(
                    "tlsEnabled",
                    profile.tlsEnabled
                )
                .put(
                    "sni",
                    profile.sni
                )
                .put(
                    "tlsVersion",
                    profile.tlsVersion
                )
                .put(
                    "localSocksPort",
                    profile.localSocksPort
                )
                .put(
                    "keepAliveSeconds",
                    profile.keepAliveSeconds
                )
                .put(
                    "socketSendBufferSize",
                    profile.socketSendBufferSize
                )
                .put(
                    "socketReceiveBufferSize",
                    profile.socketReceiveBufferSize
                )
                .put(
                    "tcpNoDelay",
                    profile.tcpNoDelay
                )
                .put(
                    "cpuWakeLockEnabled",
                    profile.cpuWakeLockEnabled
                )
                .put(
                    "connectTimeoutMillis",
                    profile.connectTimeoutMillis
                )
                .put(
                    "readTimeoutMillis",
                    profile.readTimeoutMillis
                )
                .put(
                    "autoReconnect",
                    profile.autoReconnect
                )
                .put(
                    "maxReconnect",
                    profile.maxReconnect
                )

        val data =
            json.toString()
                .toByteArray(
                    Charsets.UTF_8
                )

        require(
            data.size <=
                MAX_DECODED_LENGTH
        ) {
            "SSHProtocol profile is too large"
        }

        return try {
            PREFIX +
                Base64.encodeToString(
                    data,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                )
        } finally {
            data.fill(0)
        }
    }

    fun decode(
        value: String
    ): SshProfile {
        val trimmed =
            value.trim()

        require(
            trimmed.length <=
                PREFIX.length +
                    MAX_ENCODED_LENGTH
        ) {
            "SSHProtocol profile URI is too large"
        }

        val uri =
            Uri.parse(
                trimmed
            )

        require(
            uri.scheme == SCHEME &&
                uri.encodedAuthority
                    ?.isNotBlank() == true &&
                uri.path.isNullOrEmpty() &&
                uri.query == null &&
                uri.fragment == null
        ) {
            "Invalid SSHProtocol profile URI"
        }

        val encoded =
            uri.encodedAuthority
                ?: throw IllegalArgumentException(
                    "Invalid SSHProtocol profile URI"
                )

        require(
            encoded.isNotBlank() &&
                encoded.length <=
                    MAX_ENCODED_LENGTH
        ) {
            "Invalid SSHProtocol profile URI"
        }

        val data =
            try {
                Base64.decode(
                    encoded,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                )
            } catch (
                failure: Throwable
            ) {
                throw IllegalArgumentException(
                    "Invalid SSHProtocol profile URI",
                    failure
                )
            }

        require(
            data.size <=
                MAX_DECODED_LENGTH
        ) {
            data.fill(0)
            "SSHProtocol profile is too large"
        }

        val json =
            try {
                JSONObject(
                    data.toString(
                        Charsets.UTF_8
                    )
                )
            } catch (
                failure: Throwable
            ) {
                throw IllegalArgumentException(
                    "Invalid SSHProtocol profile URI",
                    failure
                )
            } finally {
                data.fill(0)
            }

        require(
            json.getInt(
                "v"
            ) in 1..VERSION
        ) {
            "Unsupported SSHProtocol profile URI"
        }

        val decodedVersion =
            json.getInt(
                "v"
            )

        val proxyEnabled =
            if (decodedVersion == 1) {
                json.getBoolean(
                    "payloadEnabled"
                ) &&
                    json.optString(
                        "proxyHost",
                        ""
                    ).isNotBlank() &&
                    json.optInt(
                        "proxyPort",
                        0
                    ) in 1..65535
            } else {
                json.getBoolean(
                    "proxyEnabled"
                )
            }

        val profile =
            try {
                SshProfile(
                    name =
                        json.getString(
                            "name"
                        ),
                    sshHost =
                        json.getString(
                            "sshHost"
                        ),
                    sshPort =
                        json.getInt(
                            "sshPort"
                        ),
                    username =
                        json.getString(
                            "username"
                        ),
                    password =
                        json.getString(
                            "password"
                        ),
                    payloadEnabled =
                        json.getBoolean(
                            "payloadEnabled"
                        ),
                    proxyEnabled =
                        proxyEnabled,
                    payloadMode =
                        enumValueOf<PayloadMode>(
                            json.getString(
                                "payloadMode"
                            )
                        ),
                    payload =
                        json.getString(
                            "payload"
                        ),
                    proxyHost =
                        json.getString(
                            "proxyHost"
                        ),
                    proxyPort =
                        json.getInt(
                            "proxyPort"
                        ),
                    tlsEnabled =
                        json.getBoolean(
                            "tlsEnabled"
                        ),
                    sni =
                        json.getString(
                            "sni"
                        ),
                    tlsVersion =
                        json.getString(
                            "tlsVersion"
                        ),
                    localSocksPort =
                        json.getInt(
                            "localSocksPort"
                        ),
                    keepAliveSeconds =
                        json.getInt(
                            "keepAliveSeconds"
                        ),
                    socketSendBufferSize =
                        json.getInt(
                            "socketSendBufferSize"
                        ),
                    socketReceiveBufferSize =
                        json.getInt(
                            "socketReceiveBufferSize"
                        ),
                    tcpNoDelay =
                        json.getBoolean(
                            "tcpNoDelay"
                        ),
                    cpuWakeLockEnabled =
                        json.getBoolean(
                            "cpuWakeLockEnabled"
                        ),
                    connectTimeoutMillis =
                        json.getInt(
                            "connectTimeoutMillis"
                        ),
                    readTimeoutMillis =
                        json.getInt(
                            "readTimeoutMillis"
                        ),
                    autoReconnect =
                        json.getBoolean(
                            "autoReconnect"
                        ),
                    maxReconnect =
                        json.getInt(
                            "maxReconnect"
                        )
                )
            } catch (
                failure: Throwable
            ) {
                throw IllegalArgumentException(
                    "Invalid SSHProtocol profile URI",
                    failure
                )
            }

        val errors =
            ProfileValidation.validate(
                profile
            )

        require(
            errors.isEmpty()
        ) {
            errors.joinToString(
                "\n"
            ) {
                "${it.field}: ${it.message}"
            }
        }

        return profile
    }

    fun isProfileUri(
        value: String?
    ): Boolean {
        if (
            value.isNullOrBlank()
        ) {
            return false
        }

        val trimmed =
            value.trim()

        if (
            trimmed.length >
                PREFIX.length +
                    MAX_ENCODED_LENGTH
        ) {
            return false
        }

        val uri =
            runCatching {
                Uri.parse(
                    trimmed
                )
            }.getOrNull()
                ?: return false

        val encoded =
            uri.encodedAuthority
                ?: return false

        return uri.scheme == SCHEME &&
            encoded.isNotBlank() &&
            encoded.length <=
                MAX_ENCODED_LENGTH &&
            uri.path.isNullOrEmpty() &&
            uri.query == null &&
            uri.fragment == null
    }
}
