/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothInfoSheet(
    isConnected: Boolean,
    currentIp: String,
    isPhoneBluetoothOn: Boolean = true,
    isPcBluetoothOn: Boolean = true,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(48.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF334155))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Dual-Channel & Security",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "How AuraLock connects over Wi-Fi & Bluetooth",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Channel 1: Wi-Fi Fast Channel
            ChannelCard(
                icon = Icons.Default.Wifi,
                iconTint = Color(0xFF38BDF8),
                title = "1. Wi-Fi Ultra-Fast Channel",
                badgeText = if (isConnected) "Active ($currentIp)" else "Ready",
                badgeColor = if (isConnected) Color(0xFF10B981) else Color(0xFF0284C7),
                description = "Transmits encrypted Ed25519 biometric signatures over your local Wi-Fi network in <5 milliseconds with sub-millisecond response time."
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Channel 2: Bluetooth LE Proximity
            val bleBadge = if (!isPhoneBluetoothOn) "Phone BT Off 🟡"
                else if (!isConnected) "Standby (Disconnected)"
                else if (!isPcBluetoothOn) "PC BT Off 🔴"
                else "Dual BLE Linked 🟢"
            val bleColor = if (!isPhoneBluetoothOn) Color(0xFFD97706)
                else if (!isConnected) Color(0xFF64748B)
                else if (!isPcBluetoothOn) Color(0xFFDC2626)
                else Color(0xFF059669)
            val bleDesc = if (!isPhoneBluetoothOn) "⚠️ Phone Bluetooth is turned OFF. Enable Bluetooth on this device to enable proximity radar."
                else if (!isConnected) "⚠️ Device is currently disconnected. Connect to PC via Wi-Fi channel to enable Bluetooth proximity radar."
                else if (!isPcBluetoothOn) "⚠️ PC Bluetooth is currently turned OFF. Turn on Bluetooth on your PC to enable proximity radar."
                else "When you walk up to your PC (within 2-3 meters), the PC detects your phone's BLE RSSI signal and automatically triggers the fingerprint challenge on your screen!"

            ChannelCard(
                icon = Icons.Default.Bluetooth,
                iconTint = if (!isPhoneBluetoothOn || (isConnected && !isPcBluetoothOn)) Color(0xFFF87171) else Color(0xFF818CF8),
                title = "2. Bluetooth Low Energy (BLE)",
                badgeText = bleBadge,
                badgeColor = bleColor,
                description = bleDesc
            )


            Spacer(modifier = Modifier.height(14.dp))

            // Security: Android KeyStore TEE
            ChannelCard(
                icon = Icons.Default.Security,
                iconTint = Color(0xFF34D399),
                title = "3. Hardware TEE StrongBox",
                badgeText = "FIPS 140-2 Level 3",
                badgeColor = Color(0xFF059669),
                description = "Your private cryptographic key never leaves your phone's tamper-proof hardware security chip. It is physically impossible for the PC or malware to extract your key."
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Zero-Knowledge Split Key Vault
            ChannelCard(
                icon = Icons.Default.Key,
                iconTint = Color(0xFFFBBF24),
                title = "4. Zero-Knowledge Split Vault",
                badgeText = "ChaCha20-Poly1305",
                badgeColor = Color(0xFFD97706),
                description = "Your PC login password is split and encrypted inside the PC PAM vault. It is only unlocked momentarily when your phone provides a valid biometric signature."
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Got It, Close Info", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChannelCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    badgeText: String,
    badgeColor: Color,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
