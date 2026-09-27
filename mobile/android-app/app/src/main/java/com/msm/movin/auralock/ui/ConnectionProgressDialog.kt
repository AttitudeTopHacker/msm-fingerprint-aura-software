/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.msm.movin.auralock.model.ConnectionProgressState
import com.msm.movin.auralock.model.ConnectionStep

@Composable
fun ConnectionProgressDialog(
    state: ConnectionProgressState,
    onCancel: () -> Unit,
    onDismissError: () -> Unit
) {
    if (!state.isConnecting && state.errorMessage == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "SpinnerRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Dialog(
        onDismissRequest = {
            if (state.errorMessage != null) onDismissError() else onCancel()
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F172A)
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (state.errorMessage != null) Color(0xFFEF4444).copy(alpha = 0.6f)
                else Color(0xFF06B6D4).copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                if (state.errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF06B6D4).copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(52.dp)
                                .rotate(rotation),
                            color = Color(0xFF38BDF8),
                            strokeWidth = 3.5.dp,
                            trackColor = Color(0xFF1E293B)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (state.errorMessage != null) "Connection Failed"
                    else if (state.pcName.isNotBlank()) "Pairing with ${state.pcName}"
                    else "Connecting to PC...",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    textAlign = TextAlign.Center
                )

                if (state.targetIp.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Target: ${state.targetIp}",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Step-by-Step Progress Checklist
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                        .padding(14.dp)
                ) {
                    StepItem(
                        title = "1. Discovering PC Endpoint",
                        currentStep = state.step,
                        targetStep = ConnectionStep.DISCOVERING_PC,
                        hasError = state.errorMessage != null && state.step == ConnectionStep.DISCOVERING_PC
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StepItem(
                        title = "2. Hardware TEE StrongBox Init",
                        currentStep = state.step,
                        targetStep = ConnectionStep.INITIALIZING_KEYSTORE,
                        hasError = state.errorMessage != null && state.step == ConnectionStep.INITIALIZING_KEYSTORE
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StepItem(
                        title = "3. Ephemeral Key Exchange",
                        currentStep = state.step,
                        targetStep = ConnectionStep.EXCHANGING_KEYS,
                        hasError = state.errorMessage != null && state.step == ConnectionStep.EXCHANGING_KEYS
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StepItem(
                        title = "4. 6-Digit PIN Handshake",
                        currentStep = state.step,
                        targetStep = ConnectionStep.VERIFYING_PIN,
                        hasError = state.errorMessage != null && state.step == ConnectionStep.VERIFYING_PIN
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StepItem(
                        title = "5. Biometric PAM Tunnel Active",
                        currentStep = state.step,
                        targetStep = ConnectionStep.SECURE_TUNNEL_ACTIVE,
                        hasError = state.errorMessage != null && state.step == ConnectionStep.SECURE_TUNNEL_ACTIVE
                    )
                }

                if (state.errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.errorMessage,
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onDismissError,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Dismiss & Try Again", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Spacer(modifier = Modifier.height(20.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel Connection", color = Color(0xFF94A3B8))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(
    title: String,
    currentStep: ConnectionStep,
    targetStep: ConnectionStep,
    hasError: Boolean
) {
    val isDone = currentStep.ordinal > targetStep.ordinal
    val isCurrent = currentStep == targetStep && !hasError

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        when {
            hasError -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
            isDone -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(18.dp)
                )
            }
            isCurrent -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF38BDF8)
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF334155))
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = when {
                hasError -> Color(0xFFEF4444)
                isDone -> Color(0xFFE2E8F0)
                isCurrent -> Color(0xFF38BDF8)
                else -> Color(0xFF64748B)
            }
        )
    }
}
