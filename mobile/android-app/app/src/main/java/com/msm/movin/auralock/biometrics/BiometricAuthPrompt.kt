/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.biometrics

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.Signature

class BiometricAuthPrompt(private val activity: FragmentActivity) {

    private var activePrompt: BiometricPrompt? = null

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    fun cancelAuthentication() {
        try {
            activePrompt?.cancelAuthentication()
        } catch (_: Throwable) {}
        activePrompt = null
    }

    fun promptForUnlock(
        signature: Signature,
        pcName: String,
        onSuccess: (Signature) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelAuthentication()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    activePrompt = null
                    val authedSig = result.cryptoObject?.signature
                    if (authedSig != null) {
                        onSuccess(authedSig)
                    } else {
                        onError("Biometric signature initialization failed")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    activePrompt = null
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Finger not recognized; system keeps prompt open
                }
            }
        )

        activePrompt = prompt
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $pcName")
            .setSubtitle("Touch sensor to unlock (60s)")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
    }
}
