package com.mdevz.sp.ssh

import android.content.Context
import java.util.Base64
import org.json.JSONObject

class SharedPreferencesKnownHostStore(
    context: Context
) : KnownHostStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun find(host: String, port: Int): HostKeyRecord? {
        val raw = prefs.getString(storageKey(host, port), null)
            ?: return null

        return try {
            val json =
                JSONObject(raw)

            val keyBase64 =
                json.getString(
                    "key"
                )

            val key =
                Base64.getDecoder()
                    .decode(
                        keyBase64
                    )

            HostKeyRecord(
                host = host,
                port = port,
                algorithm =
                    json.getString(
                        "algorithm"
                    ),
                keyBase64 =
                    keyBase64,
                fingerprint =
                    HostKeyFingerprint.sha256(
                        key
                    )
            )
        } catch (failure: Exception) {
            throw CorruptKnownHostException(
                host = host,
                port = port,
                cause = failure
            )
        }
    }

    override fun trust(record: HostKeyRecord) {
        val json = JSONObject()
            .put("host", record.host)
            .put("port", record.port)
            .put("algorithm", record.algorithm)
            .put("key", record.keyBase64)
            .put("fingerprint", record.fingerprint)

        val committed =
            prefs.edit()
                .putString(
                    storageKey(
                        record.host,
                        record.port
                    ),
                    json.toString()
                )
                .commit()

        if (!committed) {
            throw IllegalStateException(
                "Unable to persist SSH host key"
            )
        }
    }

    override fun remove(host: String, port: Int) {
        prefs.edit()
            .remove(storageKey(host, port))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ssh_known_hosts"

        fun record(
            host: String,
            port: Int,
            algorithm: String,
            key: ByteArray
        ): HostKeyRecord = HostKeyRecord(
            host = host,
            port = port,
            algorithm = algorithm,
            keyBase64 = Base64.getEncoder().encodeToString(key),
            fingerprint = HostKeyFingerprint.sha256(key)
        )

        private fun storageKey(host: String, port: Int): String =
            "$host:$port"
    }
}
