package io.github.mbaliga.fylz.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores user-supplied provider keys encrypted with an Android Keystore key. */
class ApiKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(providerId: String, apiKey: CharArray) {
        require(providerId.isNotBlank())
        require(apiKey.isNotEmpty())
        val plaintext = apiKey.concatToString().encodeToByteArray()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plaintext)
            val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString(providerId, payload).commit()) {
                "Unable to persist the encrypted provider key."
            }
        } finally {
            plaintext.fill(0)
            apiKey.fill('\u0000')
        }
    }

    @Synchronized
    fun read(providerId: String): CharArray? {
        val payload = preferences.getString(providerId, null) ?: return null
        val packed = Base64.decode(payload, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "Stored provider key is invalid." }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val plaintext = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }
        return try {
            plaintext.decodeToString().toCharArray()
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun remove(providerId: String) {
        preferences.edit().remove(providerId).commit()
    }

    fun has(providerId: String): Boolean = preferences.contains(providerId)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_byok_vault"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fylz.byok.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
