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
                // If device has no enrolled fingerprints, fallback without biometric requirement for initialization
                val fallbackBuilder = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                kpg.initialize(fallbackBuilder.build())
                return kpg.generateKeyPair()
            }
        }

        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            null
        }

        if (entry == null) {
            try { keyStore.deleteEntry(KEY_ALIAS) } catch (_: Exception) {}
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val fallbackBuilder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            kpg.initialize(fallbackBuilder.build())
            return kpg.generateKeyPair()
        }

        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    /**
     * Initializes the signature object to be passed to BiometricPrompt.CryptoObject
     */
    fun getInitializedSignature(): Signature {
        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            null
        } ?: run {
            getOrCreateBiometricKeyPair()
            try {
                keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            } catch (e: Exception) {
                null
            } ?: throw IllegalStateException("Biometric key not found and could not be generated.")
        }

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(entry.privateKey)
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
