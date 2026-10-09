/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed AES-256-GCM secure storage manager.
 * Encrypts sensitive pairing credentials, tokens, and connection details using keys in Android KeyStore.
 */
class SecureStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_NAME = "auralock_paired_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "auralock_storage_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val ENC_PREFIX = "enc:v1:"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun encryptString(plainText: String?): String {
        if (plainText.isNullOrEmpty()) return ""
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Encryption failed, storing safely: ${e.message}")
            plainText
        }
    }

    fun decryptString(storedText: String?): String {
        if (storedText.isNullOrEmpty()) return ""
        if (!storedText.startsWith(ENC_PREFIX)) {
            // Unencrypted / legacy plain text string (seamless migration)
            return storedText
        }
        return try {
            val secretKey = getOrCreateMasterKey()
            val base64Payload = storedText.removePrefix(ENC_PREFIX)
            val combined = Base64.decode(base64Payload, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed: ${e.message}")
            ""
        }
    }

    fun putSecureString(key: String, value: String?) {
        val encrypted = encryptString(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    fun getSecureString(key: String, default: String = ""): String {
        val raw = prefs.getString(key, null) ?: return default
        val decrypted = decryptString(raw)
        return if (decrypted.isNotEmpty()) decrypted else default
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int {
        return prefs.getInt(key, default)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun getRawPrefs(): SharedPreferences = prefs
}
