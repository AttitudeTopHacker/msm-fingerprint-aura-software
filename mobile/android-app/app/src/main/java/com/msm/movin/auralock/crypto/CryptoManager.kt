/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CryptoManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "auralock_biometric_key_v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Generates a hardware-backed cryptographic keypair inside Android KeyStore / TEE StrongBox.
     * The private key is strictly locked behind user biometric authentication.
     */
    fun getOrCreateBiometricKeyPair(): KeyPair {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )

            val specBuilder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                specBuilder.setUserAuthenticationParameters(
                    0, // 0 timeout means key requires fresh biometric auth for EVERY sign operation
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                specBuilder.setInvalidatedByBiometricEnrollment(true)
            }

            try {
                kpg.initialize(specBuilder.build())
                return kpg.generateKeyPair()
            } catch (e: Exception) {
                // If device keystore initialization fails (e.g. no enrolled biometrics),
                // throw a descriptive exception to enforce security requirements
                throw IllegalStateException("Hardware biometric initialization failed: ${e.message}. Please ensure fingerprint/biometrics are set up in Android Settings.", e)
            }
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    /**
     * Force-regenerates a fresh cryptographic keypair inside Android KeyStore / TEE StrongBox.
     * Useful when biometric enrollment changes or keys need to be refreshed.
     */
    fun regenerateBiometricKeyPair(): KeyPair {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {}
        return getOrCreateBiometricKeyPair()
    }

    /**
     * Initializes the signature object to be passed to BiometricPrompt.CryptoObject
     */
    fun getInitializedSignature(): Signature {
        var entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry == null) {
            getOrCreateBiometricKeyPair()
            entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("Biometric key not found. Please register first.")
        }

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        try {
            signature.initSign(entry.privateKey)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Fingerprint database changed in Android Settings, regenerate hardware key cleanly
            regenerateBiometricKeyPair()
            val freshEntry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            signature.initSign(freshEntry.privateKey)
        }
        return signature
    }

    /**
     * Signs the challenge payload using the biometrically unlocked signature object
     */
    fun signChallenge(signature: Signature, challengeBytes: ByteArray): ByteArray {
        signature.update(challengeBytes)
        return signature.sign()
    }

    fun getPublicKeyHex(): String {
        val pair = getOrCreateBiometricKeyPair()
        return pair.public.encoded.joinToString("") { "%02x".format(it) }
    }
}
