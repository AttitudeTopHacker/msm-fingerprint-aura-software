/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.msm.movin.auralock.model.ParsedQrData
import com.msm.movin.auralock.network.AuraLockClient
import kotlinx.coroutines.launch

@Composable
fun ManualPairDialog(
    initialIp: String,
    onConfirm: (ParsedQrData) -> Unit,
    onSwitchToQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var ipText by remember { mutableStateOf(initialIp.trim()) }
    var pinText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("47890") }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredPcInfo by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // Auto-discover PC IP via UDP beacon on Wi-Fi when dialog opens
    LaunchedEffect(Unit) {
        if (ipText.isBlank()) {
            isDiscovering = true
            try {
                val discovered = AuraLockClient.discoverPcIp(2000)
                if (discovered != null) {
                    discoveredPcInfo = discovered
                    ipText = discovered.first
                    portText = discovered.second.toString()
                }
            } catch (_: Throwable) {}
            isDiscovering = false
        }
    }

    val cleanPin = pinText.filter { it.isDigit() }
    val cleanIp = ipText.trim()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Manual PC Pairing",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter your PC's IP and the 6-digit PIN shown in terminal or Desktop Control Center.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                // Discovered Beacon Banner
                if (discoveredPcInfo != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF064E3B).copy(alpha = 0.5f))
                            .border(1.dp, Color(0xFF00FF88).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Found PC on Wi-Fi: ${discoveredPcInfo?.first}:${discoveredPcInfo?.second}",
                                color = Color(0xFF00FF88),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // PC IP Address Input
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("PC Wi-Fi IP Address") },
                    placeholder = { Text("e.g. 192.168.1.5", color = Color(0xFF64748B)) },
                    leadingIcon = {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF38BDF8))
                    },
                    trailingIcon = {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF38BDF8)
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isDiscovering = true
                                        val discovered = AuraLockClient.discoverPcIp(2000)
                                        if (discovered != null) {
                                            discoveredPcInfo = discovered
                                            ipText = discovered.first
                                            portText = discovered.second.toString()
                                        } else {
                                            android.widget.Toast.makeText(context, "No PC auto-beacon found on local Wi-Fi. Enter IP manually.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        isDiscovering = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Scan Network", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF38BDF8),
                        unfocusedLabelColor = Color(0xFF94A3B8),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 6-Digit PIN Input
                OutlinedTextField(
                    value = pinText,
                    onValueChange = {
                        val digits = it.filter { c -> c.isDigit() }
                        if (digits.length <= 8) pinText = digits
                    },
                    label = { Text("6-Digit Pairing PIN") },
                    placeholder = { Text("e.g. 123456", color = Color(0xFF64748B)) },
                    leadingIcon = {
                        Icon(Icons.Default.Pin, contentDescription = null, tint = Color(0xFFFACC15))
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                try {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString() ?: ""
                                        val digits = text.filter { it.isDigit() }
                                        if (digits.isNotBlank()) {
                                            pinText = digits.take(8)
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste PIN", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = Color(0xFF38BDF8),
                        unfocusedLabelColor = Color(0xFF94A3B8),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Connect Button
                Button(
                    onClick = {
                        if (cleanIp.isNotBlank() && cleanPin.isNotBlank()) {
                            onConfirm(
                                ParsedQrData(
                                    ip = cleanIp,
                                    port = portText.toIntOrNull() ?: 47890,
                                    pin = cleanPin,
                                    pcName = if (discoveredPcInfo != null) "AuraLock-PC" else "PC ($cleanIp)"
                                )
                            )
                        }
                    },
                    enabled = cleanIp.isNotBlank() && cleanPin.length >= 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7)
                    )
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect & Start Pairing", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Switch to Camera QR
                OutlinedButton(
                    onClick = onSwitchToQr,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF06B6D4)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Or Scan Camera QR Code", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF64748B), fontSize = 13.sp)
                }
            }
        }
    }
}
