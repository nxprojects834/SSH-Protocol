package com.mdevz.sp.core.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

class CredentialCipher {

    private val keyStore =
        KeyStore.getInstance(KEYSTORE).apply {
            load(null)
        }

    fun encrypt(
        plaintext: String
    ): String {
        val cipher =
            Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey()
        )

        val ciphertext =
            cipher.doFinal(
                plaintext.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

        val iv =
            Base64.getEncoder()
                .encodeToString(cipher.iv)

        val data =
            Base64.getEncoder()
                .encodeToString(ciphertext)

        return "$iv:$data"
    }

    fun decrypt(
        encoded: String
    ): String {
        val separator =
            encoded.indexOf(':')

        require(separator > 0) {
            "Invalid encrypted credential format"
        }

        val iv =
            Base64.getDecoder().decode(
                encoded.substring(
                    0,
                    separator
                )
            )

        val ciphertext =
            Base64.getDecoder().decode(
                encoded.substring(
                    separator + 1
                )
            )

        val cipher =
            Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(
                GCM_TAG_BITS,
                iv
            )
        )

        return String(
            cipher.doFinal(ciphertext),
            StandardCharsets.UTF_8
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val existing =
            keyStore.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE
            )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE =
            "AndroidKeyStore"

        private const val KEY_ALIAS =
            "com.mdevz.sp.profile.credentials"

        private const val TRANSFORMATION =
            "AES/GCM/NoPadding"

        private const val GCM_TAG_BITS = 128
    }
}
