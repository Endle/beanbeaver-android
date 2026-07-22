package com.beanbeaver.app.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The GitHub access token at rest, encrypted with an AndroidKeyStore AES-256-GCM
 * key that never leaves secure hardware; the ciphertext (`iv:cipher`, base64)
 * lives in a private SharedPreferences. This is the current Android best-practice
 * analog of iOS's Keychain (`Keychain.swift`) now that Jetpack Security's
 * EncryptedSharedPreferences is deprecated — and it adds no dependency.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Store (or, for null/empty, remove) the token. */
    fun set(token: String?) {
        if (token.isNullOrEmpty()) {
            prefs.edit().remove(KEY_VALUE).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(token.toByteArray())
        prefs.edit().putString(KEY_VALUE, b64(cipher.iv) + ":" + b64(ciphertext)).apply()
    }

    fun get(): String? {
        val stored = prefs.getString(KEY_VALUE, null) ?: return null
        val parts = stored.split(":")
        if (parts.size != 2) return null
        return try {
            val iv = unb64(parts[0])
            val ciphertext = unb64(parts[1])
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            // Key rotated / data corrupt — treat as "no token" rather than crash.
            null
        }
    }

    fun clear() = set(null)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS = "beanbeaver_github"
        private const val KEY_VALUE = "token_enc"
        private const val ALIAS = "beanbeaver_gh_token"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
