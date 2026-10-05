/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.model

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

data class AuraDropFileItem(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val isIncoming: Boolean = true,
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val localUri: String? = null,
    val senderName: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedSize(): String {
        return formatBytes(fileSize)
    }
}

data class AuraDropTransferState(
    val isTransferring: Boolean = false,
    val activeFileId: String? = null,
    val activeFileName: String = "",
    val isIncoming: Boolean = true,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val progress: Float = 0f,
    val speedMBps: Float = 0f,
    val statusMessage: String = "",
    val isCompleted: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null
) {
    fun getFormattedProgress(): String {
        if (totalBytes <= 0) return "${(progress * 100).toInt()}%"
        return "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"
    }

    fun getFormattedSpeed(): String {
        return if (speedMBps > 0.05f) String.format("%.1f MB/s", speedMBps) else ""
    }
}

