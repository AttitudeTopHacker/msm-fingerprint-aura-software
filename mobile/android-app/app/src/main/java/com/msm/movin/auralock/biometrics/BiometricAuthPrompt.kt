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

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptForUnlock(
        signature: Signature,
        pcName: String,
        onSuccess: (Signature) -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedSig = result.cryptoObject?.signature
                    if (authedSig != null) {
                        onSuccess(authedSig)
                    } else {
                        onError("Biometric signature initialization failed")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Finger not recognized; system keeps prompt open
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $pcName")
            .setSubtitle("Touch sensor to unlock (60s)")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
    }
}
