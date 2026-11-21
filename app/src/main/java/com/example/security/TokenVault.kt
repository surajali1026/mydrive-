package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Hardware-backed Android Keystore AES-256-GCM vault for encrypting OAuth tokens,
 * session JWTs, and PKCE verifiers at rest on device.
 */
class TokenVault(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyAlias = "mydrive_token_vault_aes256_gcm"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? {
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveSecret(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun readSecret(key: String): String? {
        val encrypted = prefs.getString(key, null) ?: return null
        return decrypt(encrypted)
    }

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAllSessionSecrets() {
        prefs.edit().remove(KEY_JWT_TOKEN).remove(KEY_PKCE_STATE).remove(KEY_PKCE_VERIFIER).remove(KEY_PKCE_PROVIDER).apply()
    }

    fun saveJwtToken(token: String) = saveSecret(KEY_JWT_TOKEN, token)
    fun readJwtToken(): String? = readSecret(KEY_JWT_TOKEN)

    fun savePendingOAuth(providerId: String, state: String, codeVerifier: String) {
        saveSecret(KEY_PKCE_PROVIDER, providerId)
        saveSecret(KEY_PKCE_STATE, state)
        saveSecret(KEY_PKCE_VERIFIER, codeVerifier)
    }

    fun consumePendingOAuth(): Triple<String, String, String>? {
        val provider = readSecret(KEY_PKCE_PROVIDER)
        val state = readSecret(KEY_PKCE_STATE)
        val verifier = readSecret(KEY_PKCE_VERIFIER)
        removeSecret(KEY_PKCE_PROVIDER)
        removeSecret(KEY_PKCE_STATE)
        removeSecret(KEY_PKCE_VERIFIER)
        return if (provider != null && state != null && verifier != null) {
            Triple(provider, state, verifier)
        } else {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val PREFS_NAME = "mydrive_encrypted_vault_prefs"
        private const val KEY_JWT_TOKEN = "session_jwt"
        private const val KEY_PKCE_PROVIDER = "pkce_provider"
        private const val KEY_PKCE_STATE = "pkce_state"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"

        /**
         * Streaming SHA-256 hash computation for gallery deduplication and APK verification.
         */
        fun computeSha256(inputStream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Salted PBKDF2 password hashing with API 24-25 compatibility fallback.
         */
        fun hashPassword(password: String, saltBase64: String? = null): String {
            val salt = if (saltBase64 != null) {
                Base64.decode(saltBase64, Base64.NO_WRAP)
            } else {
                ByteArray(16).also { SecureRandom().nextBytes(it) }
            }
            val spec = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
            val factory = runCatching {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            }.getOrElse {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            }
            val hash = factory.generateSecret(spec).encoded
            return "${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(hash, Base64.NO_WRAP)}"
        }

        fun verifyPassword(password: String, storedHash: String): Boolean {
            val parts = storedHash.split(":")
            if (parts.size != 2) return false
            val recomputed = hashPassword(password, parts[0])
            return MessageDigest.isEqual(recomputed.toByteArray(), storedHash.toByteArray())
        }
    }
}
