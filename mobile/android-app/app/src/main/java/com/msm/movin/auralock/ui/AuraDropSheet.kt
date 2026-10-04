/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.msm.movin.auralock.model.AuraDropFileItem
import com.msm.movin.auralock.model.AuraDropTransferState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraDropSheet(
    isConnected: Boolean,
    pcName: String,
    transferState: AuraDropTransferState,
    receivedFiles: List<AuraDropFileItem>,
    latestReceivedClipboard: String?,
    onSendFile: (Uri) -> Unit,
    onSendClipboardText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var clipboardInput by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendFile(uri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0E17),
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF00FF88), Color(0xFF00D2FF))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = null,
                            tint = Color(0xFF07090E),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AuraDrop",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isConnected) "Connected to $pcName" else "PC Disconnected",
                            color = if (isConnected) Color(0xFF00FF88) else Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Active Transfer Card
            AnimatedVisibility(
                visible = transferState.isTransferring,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131C2E)),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF00FF88), Color(0xFF00D2FF))))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (transferState.isIncoming) "Receiving File..." else "Sending to PC...",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "${(transferState.progress * 100).toInt()}%",
                                color = Color(0xFF00FF88),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = transferState.activeFileName,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { transferState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00FF88),
                            trackColor = Color(0xFF1E293B)
                        )
                    }
                }
            }

            // Quick Send Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send File Button
                Button(
                    onClick = {
                        if (isConnected) {
                            try {
                                filePickerLauncher.launch("*/*")
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Connect to PC first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D2FF),
                        contentColor = Color(0xFF07090E)
                    )
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send File", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                // Send Photo/Media Button
                Button(
                    onClick = {
                        if (isConnected) {
                            try {
                                filePickerLauncher.launch("image/*")
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Could not open gallery: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Connect to PC first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF88),
                        contentColor = Color(0xFF07090E)
                    )
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Photos", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shared Clipboard Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101622)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF1E293B))))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "📋 Send Text / Link to PC",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = clipboardInput,
                            onValueChange = { clipboardInput = it },
                            placeholder = { Text("Type or paste message...", color = Color(0xFF64748B), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00D2FF),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (clipboardInput.isNotBlank()) {
                                    onSendClipboardText(clipboardInput)
                                    clipboardInput = ""
                                    Toast.makeText(context, "Sent to PC!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF00FF88))
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF07090E))
                        }
                    }
                }
            }

            // Received Clipboard Banner
            if (!latestReceivedClipboard.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C221F)),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF00FF88), Color(0xFF00D2FF))))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Received from PC:", color = Color(0xFF00FF88), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = latestReceivedClipboard,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("AuraLock Text", latestReceivedClipboard)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to phone clipboard!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF00FF88))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Received Files Section
            Text(
                text = "Received Files (${receivedFiles.size})",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (receivedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No files received yet from PC",
                        color = Color(0xFF475569),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(receivedFiles) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    item.localUri?.let { path ->
                                        openFile(context, File(path))
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF101622)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Color(0xFF00D2FF),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.getFormattedSize(),
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = "Open",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openFile(context: Context, file: File) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "File not found on disk", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open File"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
