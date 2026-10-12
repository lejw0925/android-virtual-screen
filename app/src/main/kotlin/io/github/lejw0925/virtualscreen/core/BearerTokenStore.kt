package io.github.lejw0925.virtualscreen.core

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bearer token persisted encrypted with an Android Keystore AES key. */
class BearerTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val alias = "android-virtual-screen-mcp-token"

    @Synchronized
    fun token(): String {
        volatileToken?.let { return it }
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null)
        val iv = preferences.getString(KEY_IV, null)
        if (encrypted != null && iv != null) {
            runCatching { return decrypt(encrypted, iv).also { volatileToken = it } }
        }
        return rotate()
    }

    @Synchronized
    fun rotate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val value = Base64.encodeToString(bytes, Base64.NO_WRAP)
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        }.onFailure {
            // A device may expose a broken Keystore during early boot. Keep the
            // token process-local until the next successful rotation; never log it.
            preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
        }
        volatileToken = value
        return value
    }

    @Volatile private var volatileToken: String? = null

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.DEFAULT)).toString(StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    companion object {
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
    }
}
