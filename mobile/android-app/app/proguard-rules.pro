#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#

# Keep biometric and cryptographic classes
-keep class com.msm.movin.auralock.crypto.** { *; }
-keep class com.msm.movin.auralock.biometrics.** { *; }
-keep class com.msm.movin.auralock.network.** { *; }
-keep class com.msm.movin.auralock.model.** { *; }

# Keep ML Kit and CameraX
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**
