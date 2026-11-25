package com.zapp.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts login details with a key held in the Android Keystore, so they never sit in the
 * database as plain text. Values written by older versions (no prefix) are read as-is.
 */
object Secrets {
    private const val PREFIX = "enc1:"
    private const val ALIAS = "zapp_credentials"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
            cachedKey = key
            return key
        }
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** Returns the encrypted form, or the original value if the Keystore is unavailable. */
    fun encrypt(value: String?): String? {
        if (value.isNullOrEmpty() || isEncrypted(value)) return value
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            value
        }
    }

    /** Returns the plain value. Unreadable values (for example after a device restore) come back empty. */
    fun decrypt(value: String?): String? {
        if (value == null || !isEncrypted(value)) return value
        return try {
            val bytes = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val body = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
