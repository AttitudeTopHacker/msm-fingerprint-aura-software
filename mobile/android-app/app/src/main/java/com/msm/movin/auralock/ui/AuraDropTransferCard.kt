/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msm.movin.auralock.model.AuraDropTransferState

@Composable
fun getFileIconForName(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "svg", "bmp" -> Icons.Default.Image
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> Icons.Default.VideoFile
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> Icons.Default.AudioFile
        "pdf", "doc", "docx", "txt", "md", "csv", "xlsx" -> Icons.Default.Description
        "zip", "tar", "gz", "7z", "rar", "deb", "rpm", "apk" -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
fun AuraDropTransferCard(
    transferState: AuraDropTransferState,
    isMinimized: Boolean = false,
    onToggleMinimize: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!transferState.isTransferring && !transferState.isCompleted && !transferState.isError) {
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = transferState.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "animatedTransferProgress"
    )

    val isFinished = transferState.isCompleted
    val isErr = transferState.isError

    val borderColors = when {
        isErr -> listOf(Color(0xFFFF3366), Color(0xFFFF6B6B))
        isFinished -> listOf(Color(0xFF00FF88), Color(0xFF00D2FF))
        else -> listOf(
            Color(0xFF00FF88).copy(alpha = glowAlpha),
            Color(0xFF00D2FF).copy(alpha = glowAlpha),
            Color(0xFF9D4EDD).copy(alpha = glowAlpha)
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0xFF00FF88).copy(alpha = 0.15f),
                spotColor = Color(0xFF00D2FF).copy(alpha = 0.25f)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1424)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, Brush.horizontalGradient(borderColors))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Status & Minimize/Close Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isErr -> Color(0xFFFF3366).copy(alpha = 0.2f)
                                    isFinished -> Color(0xFF00FF88).copy(alpha = 0.2f)
                                    else -> Color(0xFF00D2FF).copy(alpha = 0.2f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isErr -> Icons.Default.ErrorOutline
                                isFinished -> Icons.Default.CheckCircle
                                transferState.isIncoming -> Icons.Default.FileDownload
                                else -> Icons.Default.FileUpload
                            },
                            contentDescription = null,
                            tint = when {
                                isErr -> Color(0xFFFF3366)
                                isFinished -> Color(0xFF00FF88)
                                transferState.isIncoming -> Color(0xFF00D2FF)
                                else -> Color(0xFF00FF88)
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Text(
                            text = when {
                                isErr -> "Transfer Failed"
                                isFinished -> "Transfer Complete! 🎉"
                                transferState.isIncoming -> "Receiving File..."
                                else -> "Sending to PC..."
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isFinished) "Saved to storage" else if (transferState.isIncoming) "from PC" else "to PC",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Percentage Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isFinished) Color(0xFF00FF88).copy(alpha = 0.15f)
                                else Color(0xFF00D2FF).copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            color = if (isFinished) Color(0xFF00FF88) else Color(0xFF00D2FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    // Minimize Button
                    IconButton(
                        onClick = onToggleMinimize,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Minimize Transfer",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Close Button (if finished)
                    if (isFinished || isErr) {
                        IconButton(
                            onClick = { onDismiss?.invoke() },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // File Details Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = getFileIconForName(transferState.activeFileName),
                    contentDescription = null,
                    tint = Color(0xFF00D2FF),
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transferState.activeFileName.ifBlank { "Sharing File..." },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = transferState.getFormattedProgress(),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        val speed = transferState.getFormattedSpeed()
                        if (speed.isNotBlank()) {
                            Text(
                                text = "⚡ $speed",
                                color = Color(0xFF00FF88),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Glowing Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E293B))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF00FF88),
                                    Color(0xFF00D2FF),
                                    Color(0xFF9D4EDD)
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun AuraDropMinimizedPill(
    transferState: AuraDropTransferState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!transferState.isTransferring && !transferState.isCompleted && !transferState.isError) {
        return
    }

    val animatedProgress by animateFloatAsState(
        targetValue = transferState.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "animatedPillProgress"
    )

    val isFinished = transferState.isCompleted
    val isErr = transferState.isError

    Surface(
        onClick = onExpand,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(30.dp),
                ambientColor = Color(0xFF00FF88).copy(alpha = 0.2f),
                spotColor = Color(0xFF00D2FF).copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF0D1424),
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    if (isErr) Color(0xFFFF3366) else Color(0xFF00FF88),
                    if (isErr) Color(0xFFFF6B6B) else Color(0xFF00D2FF)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Mini Circular Indicator
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFinished) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF00FF88),
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (isErr) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFFF3366),
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.5.dp,
                            color = Color(0xFF00FF88),
                            trackColor = Color(0xFF1E293B)
                        )
                        Icon(
                            imageVector = if (transferState.isIncoming) Icons.Default.FileDownload else Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = Color(0xFF00D2FF),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                // File name & Status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isFinished) "Transfer Complete! 🎉" else transferState.activeFileName.ifBlank { "Transferring..." },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isFinished) "Tap to expand details" else "${(animatedProgress * 100).toInt()}% • ${transferState.getFormattedProgress()}",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            // Expand Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand Transfer Details",
                        tint = Color(0xFF00D2FF),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
