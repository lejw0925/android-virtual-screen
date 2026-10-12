package io.github.lejw0925.virtualscreen.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext fallback: tunnel credentials must survive only in Keystore ciphertext. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_credentials",Context.MODE_PRIVATE)
    private val alias = "virtual-screen-credentials-v1"
    @Synchronized fun put(name: String,value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name,Base64.encodeToString(payload,Base64.NO_WRAP)).commit()) { "CREDENTIAL_SAVE_FAILED" }
    }
    @Synchronized fun get(name: String): String? {
        val encoded = prefs.getString(name,null) ?: return null
        val payload = Base64.decode(encoded,Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,payload.copyOfRange(0,12))) }
        return cipher.doFinal(payload.copyOfRange(12,payload.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun remove(name: String) { prefs.edit().remove(name).commit() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
}
