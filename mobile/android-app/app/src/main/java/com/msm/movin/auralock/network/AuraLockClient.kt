/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.network

import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

data class ChallengeEvent(
    val challengeId: String,
    val nonceHex: String,
    val timestampEpoch: Long,
    val pcName: String
)

data class IncomingFileOfferEvent(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val senderName: String
)

data class FileTransferProgressEvent(
    val fileId: String,
    val fileName: String,
    val isIncoming: Boolean,
    val progress: Float,
    val bytesTransferred: Long,
    val totalBytes: Long
)

data class PairingResult(
    val success: Boolean,
    val message: String
)

class AuraLockClient(
    private val host: String,
    private val port: Int
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    private var heartbeatJob: Job? = null

    private val incomingFiles = mutableMapOf<String, Pair<File, OutputStream>>()
    private val incomingSizes = mutableMapOf<String, Long>()
    private val incomingBytesReceived = mutableMapOf<String, Long>()
    private val incomingLastProgressTime = mutableMapOf<String, Long>()
    private val incomingLastActivityTime = mutableMapOf<String, Long>()

    private fun pruneIdleIncomingTransfers() {
        val now = System.currentTimeMillis()
        val expiredIds = incomingLastActivityTime.filter { now - it.value > IDLE_TRANSFER_TIMEOUT_MS }.keys
        for (id in expiredIds) {
            val pair = incomingFiles.remove(id)
            incomingSizes.remove(id)
            incomingBytesReceived.remove(id)
            incomingLastProgressTime.remove(id)
            incomingLastActivityTime.remove(id)
            if (pair != null) {
                try {
                    pair.second.close()
                    pair.first.delete()
                    Log.w(TAG, "Pruned timed out idle incoming transfer: $id (${pair.first.name})")
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Sanitizes incoming filename against path traversal, null bytes, and malicious characters.
     */
    private fun sanitizeFileName(name: String): String {
        // Strip directory separators and get basename
        var clean = name.replace("\\", "/").substringAfterLast("/")
        // Remove null bytes and ASCII control chars
        clean = clean.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        // Reject path traversal sequences
        clean = clean.replace("..", "_")
        // Strip leading dots to prevent creating hidden files
        clean = clean.trimStart('.', ' ', '\t')
        // Strip illegal filesystem characters for Linux/Android/Windows
        clean = clean.replace(Regex("[/:*?\"<>|]"), "_").trim()
        if (clean.isBlank()) {
            clean = "received_file_${System.currentTimeMillis()}.bin"
        }
        return clean.take(255)
    }

    private fun getUniqueTargetFile(directory: File, rawFileName: String): File {
        val safeName = sanitizeFileName(rawFileName)
        val canonicalDir = directory.canonicalFile
        var candidate = File(canonicalDir, safeName).canonicalFile

        // Strictly verify that candidate is inside target directory (path traversal check)
        if (!candidate.path.startsWith(canonicalDir.path + File.separator) && candidate != canonicalDir) {
            val fallbackName = "safe_file_${System.currentTimeMillis()}.bin"
            candidate = File(canonicalDir, fallbackName).canonicalFile
        }

        if (!candidate.exists()) return candidate
        val dotIndex = safeName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val extension = if (dotIndex > 0) safeName.substring(dotIndex) else ""
        var count = 1
        while (candidate.exists()) {
            candidate = File(canonicalDir, "$baseName ($count)$extension").canonicalFile
            count++
        }
        return candidate
    }

    companion object {
        private const val TAG = "AuraLockClient"
        private const val MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L * 1024L // 5 GB
        private const val MAX_CHUNK_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val MAX_CONCURRENT_TRANSFERS = 3
        private const val IDLE_TRANSFER_TIMEOUT_MS = 45_000L // 45s idle transfer timeout

        suspend fun discoverPcIp(timeoutMs: Int = 2000): Pair<String, Int>? = withContext(Dispatchers.IO) {
            try {
                val udpSocket = java.net.DatagramSocket()
                udpSocket.broadcast = true
                udpSocket.soTimeout = timeoutMs

                val query = "{\"type\":\"Discover\"}".toByteArray()
                val broadcastAddr = java.net.InetAddress.getByName("255.255.255.255")
                val packet = java.net.DatagramPacket(query, query.size, broadcastAddr, 47892)
                udpSocket.send(packet)

                val buf = ByteArray(1024)
                val recvPacket = java.net.DatagramPacket(buf, buf.size)
                udpSocket.receive(recvPacket)
                val respStr = String(recvPacket.data, 0, recvPacket.length)
                val json = JSONObject(respStr)
                if (json.optString("type") == "AuraLockBeacon") {
                    val pcIp = recvPacket.address.hostAddress ?: "127.0.0.1"
                    val port = json.optInt("port", 47890)
                    udpSocket.close()
                    return@withContext Pair(pcIp, port)
                }
                udpSocket.close()
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun connect(phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}"): Boolean = withContext(Dispatchers.IO) {
        try {
            var targetHost = host
            var targetPort = port

            var sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 0
            sock.sendBufferSize = 512 * 1024
            sock.receiveBufferSize = 512 * 1024

            val connected = try {
                sock.connect(InetSocketAddress(targetHost, targetPort), 3500)
                true
            } catch (e: Exception) {
                // If direct host failed, try UDP LAN auto-discovery in case PC IP changed
                val discovered = discoverPcIp(1500)
                if (discovered != null) {
                    targetHost = discovered.first
                    targetPort = discovered.second
                    sock = Socket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.soTimeout = 0
                    sock.sendBufferSize = 512 * 1024
                    sock.receiveBufferSize = 512 * 1024
                    sock.connect(InetSocketAddress(targetHost, targetPort), 3500)
                    true
                } else {
                    false
                }
            }

            if (!connected) {
                return@withContext false
            }

            socket = sock
            writer = PrintWriter(BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), 256 * 1024), true)
            reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8), 256 * 1024)
            isRunning = true

            // Send identify immediately so PC registers connected phone
            sendIdentify(phoneName)

            // Start background heartbeat
            startHeartbeat()

            true
        } catch (e: Exception) {
            Log.w(TAG, "Connection failed: ${e.message}")
            false
        }
    }

    private var lastKnownPcBtStatus = false

    @Synchronized
    fun sendRaw(jsonStr: String): Boolean {
        return try {
            val sock = socket
            if (sock == null || !sock.isConnected || sock.isClosed) {
                return false
            }
            val w = writer ?: return false
            w.println(jsonStr)
            w.flush()
            !w.checkError()
        } catch (e: Exception) {
            Log.w(TAG, "sendRaw error: ${e.message}")
            false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning && socket?.isConnected == true && socket?.isClosed == false) {
                delay(12000)
                if (isRunning && socket?.isConnected == true && socket?.isClosed == false) {
                    sendPing()
                }
            }
        }
    }

    private fun sendIdentify(phoneName: String) {
        try {
            val json = JSONObject().apply {
                put("type", "Identify")
                put("payload", JSONObject().apply {
                    put("phone_name", phoneName)
                })
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendIdentify error: ${e.message}")
        }
    }

    suspend fun sendPairingRequest(
        phoneName: String,
        pubKeyHex: String,
        pin: String
    ): PairingResult = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext PairingResult(false, "Socket is not connected to PC")
        val prevTimeout = try { sock.soTimeout } catch (_: Throwable) { 0 }
        try {
            // Set 12-second socket read timeout for pairing handshake so it never hangs indefinitely
            try { sock.soTimeout = 12000 } catch (_: Throwable) {}

            val json = JSONObject().apply {
                put("type", "PairingReq")
                put("payload", JSONObject().apply {
                    put("phone_name", phoneName)
                    put("phone_ed25519_pub_hex", pubKeyHex)
                    put("phone_ephem_pub_hex", pubKeyHex)
                    put("pin", pin)
                })
            }
            val sent = sendRaw(json.toString())
            if (!sent) {
                return@withContext PairingResult(false, "Failed to send pairing request over socket")
            }
            
            var attempts = 0
            while (attempts < 30) {
                attempts++
                val respLine = reader?.readLine() ?: return@withContext PairingResult(false, "PC closed connection during pairing handshake")
                if (respLine.isBlank()) continue
                
                val respJson = try { JSONObject(respLine) } catch (_: Throwable) { continue }
                val type = respJson.optString("type")
                if (type == "PairingResp") {
                    val payload = respJson.optJSONObject("payload")
                    val success = payload?.optBoolean("success", false) == true
                    val msg = payload?.optString("message") ?: if (success) "Paired successfully" else "Pairing rejected by PC"
                    return@withContext PairingResult(success, msg)
                } else if (type == "BluetoothStatus") {
                    val payload = respJson.optJSONObject("payload")
                    lastKnownPcBtStatus = payload?.optBoolean("is_active", false) ?: false
                }
            }
            PairingResult(false, "Timed out waiting for Pairing response from PC")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "sendPairingRequest timeout: ${e.message}")
            PairingResult(false, "Pairing handshake timed out (12s). Verify that PC and Phone are on the same Wi-Fi and 6-digit PIN matches.")
        } catch (e: Exception) {
            Log.w(TAG, "sendPairingRequest error: ${e.message}")
            PairingResult(false, "Handshake error: ${e.message}")
        } finally {
            try { sock.soTimeout = prevTimeout } catch (_: Throwable) {}
        }
    }

    suspend fun listenForEvents(
        onChallenge: (ChallengeEvent) -> Unit,
        onIncomingFileOffer: (IncomingFileOfferEvent) -> Unit,
        onFileProgress: (FileTransferProgressEvent) -> Unit,
        onFileReceived: (fileId: String, fileName: String, file: File) -> Unit,
        onClipboardReceived: (text: String, senderName: String) -> Unit,
        onPcBluetoothStatus: (Boolean) -> Unit = {},
        onLockStateChanged: (Boolean) -> Unit = {},
        onChallengeCancelled: (String) -> Unit = {},
        onUnpaired: () -> Unit = {},
        fallbackStorageDir: File? = null,
        onFileTransferCancelled: (fileId: String, fileName: String, message: String) -> Unit = { _, _, _ -> },
        onDisconnect: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Report last known PC bluetooth status immediately if available
            withContext(Dispatchers.Main) {
                onPcBluetoothStatus(lastKnownPcBtStatus)
            }
            while (isRunning && socket?.isConnected == true && socket?.isClosed == false) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue

                val json = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    continue
                }
                val type = json.optString("type")
                when (type) {
                    "BluetoothStatus" -> {
                        val payload = json.optJSONObject("payload")
                        val isActive = payload?.optBoolean("is_active", false) ?: false
                        withContext(Dispatchers.Main) {
                            onPcBluetoothStatus(isActive)
                        }
                    }
                    "LockStateChanged" -> {
                        val payload = json.optJSONObject("payload")
                        val isLocked = payload?.optBoolean("is_locked", true) ?: true
                        withContext(Dispatchers.Main) {
                            onLockStateChanged(isLocked)
                        }
                    }
                    "Result" -> {
                        val payload = json.optJSONObject("payload")
                        val success = payload?.optBoolean("success", false) ?: false
                        val message = payload?.optString("message", "") ?: ""
                        withContext(Dispatchers.Main) {
                            if (success) {
                                onLockStateChanged(false)
                            } else {
                                onChallengeCancelled(message)
                            }
                        }
                    }
                    "Challenge" -> {
                        val payload = json.optJSONObject("payload") ?: continue
                        val event = ChallengeEvent(
                            challengeId = payload.optString("challenge_id"),
                            nonceHex = payload.optString("nonce_hex"),
                            timestampEpoch = payload.optLong("timestamp_epoch", System.currentTimeMillis() / 1000),
                            pcName = payload.optString("pc_name", "PC")
                        )
                        withContext(Dispatchers.Main) {
                            onLockStateChanged(true)
                            onChallenge(event)
                        }
                    }
                    "FileOffer" -> {
                        try {
                            pruneIdleIncomingTransfers()
                            val payload = json.optJSONObject("payload") ?: continue
                            val fileId = payload.optString("file_id", "file_${UUID.randomUUID().toString().take(6)}")
                            val rawFileName = payload.optString("file_name", "received_file.bin")
                            val fileSize = payload.optLong("file_size", 0L)
                            val mimeType = payload.optString("mime_type", "application/octet-stream")
                            val senderName = payload.optString("sender_name", "PC")

                            // Validate transfer bounds (5 GB cap, max 3 concurrent)
                            if (fileSize < 0 || fileSize > MAX_FILE_SIZE_BYTES) {
                                Log.w(TAG, "Rejected file offer with invalid size: $fileSize bytes")
                                continue
                            }
                            if (incomingFiles.size >= MAX_CONCURRENT_TRANSFERS) {
                                Log.w(TAG, "Rejected file offer: maximum concurrent transfers reached (${incomingFiles.size}/$MAX_CONCURRENT_TRANSFERS)")
                                continue
                            }

                            val targetDir: File = try {
                                val pub = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "AuraLock"
                                )
                                if (!pub.exists()) pub.mkdirs()
                                if (pub.canWrite()) pub else (fallbackStorageDir ?: pub)
                            } catch (_: Throwable) {
                                fallbackStorageDir ?: File("/sdcard/Download")
                            }
                            if (!targetDir.exists()) {
                                try { targetDir.mkdirs() } catch (_: Throwable) {}
                            }

                            // Storage quota check: verify sufficient usable disk space on device
                            val usable = targetDir.usableSpace
                            if (fileSize > 0 && usable > 0 && usable < (fileSize + 100L * 1024L * 1024L)) {
                                Log.w(TAG, "Rejected file offer: insufficient storage space ($usable bytes free, required $fileSize)")
                                continue
                            }

                            val targetFile = getUniqueTargetFile(targetDir, rawFileName)
                            val bos = try {
                                BufferedOutputStream(FileOutputStream(targetFile), 256 * 1024)
                            } catch (e: Exception) {
                                val safeDir = fallbackStorageDir ?: targetDir
                                if (!safeDir.exists()) safeDir.mkdirs()
                                val safeFile = getUniqueTargetFile(safeDir, rawFileName)
                                BufferedOutputStream(FileOutputStream(safeFile), 256 * 1024)
                            }

                            incomingFiles[fileId] = Pair(targetFile, bos)
                            incomingSizes[fileId] = fileSize
                            incomingBytesReceived[fileId] = 0L
                            incomingLastProgressTime[fileId] = 0L
                            incomingLastActivityTime[fileId] = System.currentTimeMillis()

                            withContext(Dispatchers.Main) {
                                onIncomingFileOffer(IncomingFileOfferEvent(fileId, targetFile.name, fileSize, mimeType, senderName))
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "FileOffer handling error: ${e.message}")
                        }
                    }
                    "FileChunk" -> {
                        try {
                            pruneIdleIncomingTransfers()
                            val payload = json.optJSONObject("payload") ?: continue
                            val fileId = payload.optString("file_id")
                            val chunkIndex = payload.optInt("chunk_index", 0)
                            val totalChunks = payload.optInt("total_chunks", 1)
                            val base64Data = payload.optString("data_base64", "")

                            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                            if (bytes.size > MAX_CHUNK_SIZE_BYTES) {
                                Log.w(TAG, "Rejected oversized chunk: ${bytes.size} bytes")
                                continue
                            }

                            val pair = incomingFiles[fileId]
                            if (pair != null) {
                                pair.second.write(bytes)
                                val received = (incomingBytesReceived[fileId] ?: 0L) + bytes.size
                                incomingBytesReceived[fileId] = received
                                incomingLastActivityTime[fileId] = System.currentTimeMillis()
                                val totalSize = incomingSizes[fileId] ?: 1L

                                // Guard against runaway file transfer size
                                if (totalSize > 0 && received > totalSize + (5 * 1024 * 1024)) {
                                    Log.w(TAG, "Aborting transfer $fileId: received bytes ($received) exceeded expected size ($totalSize)")
                                    try {
                                        pair.second.close()
                                        pair.first.delete()
                                    } catch (_: Throwable) {}
                                    incomingFiles.remove(fileId)
                                    continue
                                }

                                val progress = (received.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)

                                val now = System.currentTimeMillis()
                                val lastProgTime = incomingLastProgressTime[fileId] ?: 0L
                                val isFinalChunk = chunkIndex + 1 >= totalChunks || received >= totalSize
                                if (now - lastProgTime >= 60L || isFinalChunk) {
                                    incomingLastProgressTime[fileId] = now
                                    withContext(Dispatchers.Main) {
                                        onFileProgress(
                                            FileTransferProgressEvent(
                                                fileId = fileId,
                                                fileName = pair.first.name,
                                                isIncoming = true,
                                                progress = progress,
                                                bytesTransferred = received,
                                                totalBytes = totalSize
                                            )
                                        )
                                    }
                                }

                                if (isFinalChunk) {
                                    try {
                                        pair.second.flush()
                                        pair.second.close()
                                    } catch (_: Throwable) {}
                                    incomingFiles.remove(fileId)
                                    incomingLastProgressTime.remove(fileId)
                                    withContext(Dispatchers.Main) {
                                        onFileReceived(fileId, pair.first.name, pair.first)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "FileChunk handling error: ${e.message}")
                        }
                    }
                    "FileComplete" -> {
                        try {
                            val payload = json.optJSONObject("payload") ?: continue
                            val fileId = payload.optString("file_id")
                            val fileName = payload.optString("file_name", "received_file")
                            val isSuccess = payload.optBoolean("success", true)
                            val message = payload.optString("message", if (isSuccess) "Completed" else "Transfer cancelled")
                            val pair = incomingFiles.remove(fileId)
                            incomingLastProgressTime.remove(fileId)
                            if (pair != null) {
                                try {
                                    pair.second.flush()
                                    pair.second.close()
                                } catch (_: Throwable) {}
                                val received = incomingBytesReceived.remove(fileId) ?: 0L
                                val expected = incomingSizes.remove(fileId) ?: 0L
                                if (!isSuccess || (expected > 0L && received < expected)) {
                                    try { pair.first.delete() } catch (_: Throwable) {}
                                }
                                withContext(Dispatchers.Main) {
                                    if (isSuccess && (expected <= 0L || received >= expected)) {
                                        onFileReceived(fileId, fileName, pair.first)
                                    } else {
                                        val errorMsg = if (!isSuccess) message else "Incomplete file ($received / $expected bytes)"
                                        onFileTransferCancelled(fileId, fileName, errorMsg)
                                    }
                                }
                            } else if (!isSuccess) {
                                withContext(Dispatchers.Main) {
                                    onFileTransferCancelled(fileId, fileName, message)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "FileComplete handling error: ${e.message}")
                        }
                    }
                    "Clipboard" -> {
                        val payload = json.optJSONObject("payload")
                        val text = payload?.optString("text") ?: ""
                        val sender = payload?.optString("sender_name", "PC") ?: "PC"
                        withContext(Dispatchers.Main) {
                            onClipboardReceived(text, sender)
                        }
                    }
                    "UnpairResp" -> {
                        val payload = json.optJSONObject("payload")
                        if (payload?.optBoolean("success", false) == true) {
                            withContext(Dispatchers.Main) {
                                onUnpaired()
                            }
                        }
                    }
                    "Pong" -> {
                        // Heartbeat reply from PC
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listenForEvents error: ${e.message}")
        } finally {
            // Close any remaining open file output streams cleanly
            for ((_, pair) in incomingFiles) {
                try {
                    pair.second.flush()
                    pair.second.close()
                } catch (_: Throwable) {}
            }
            incomingFiles.clear()
            incomingLastProgressTime.clear()
            heartbeatJob?.cancel()
            withContext(Dispatchers.Main) {
                onDisconnect()
            }
        }
    }

    suspend fun sendFileStreamToPc(
        fileName: String,
        inputStream: InputStream,
        totalBytes: Long,
        mimeType: String = "application/octet-stream",
        phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var transferSuccessfullyCompleted = false
        val fileId = "phone_file_${UUID.randomUUID().toString().take(8)}"
        val bufferedInput = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream, 256 * 1024)
        try {
            val safeTotal = if (totalBytes > 0L) totalBytes else 1L

            // 1. Send FileOffer
            val offerJson = JSONObject().apply {
                put("type", "FileOffer")
                put("payload", JSONObject().apply {
                    put("file_id", fileId)
                    put("file_name", fileName)
                    put("file_size", totalBytes)
                    put("mime_type", mimeType)
                    put("sender_name", phoneName)
                })
            }
            writer?.println(offerJson.toString())
            writer?.flush()

            // 2. Stream chunks (256 KB each for super-fast throughput)
            val chunkSize = 256 * 1024
            val buffer = ByteArray(chunkSize)
            val totalChunks = if (totalBytes <= 0L) 1 else ((totalBytes + chunkSize - 1) / chunkSize).toInt()
            var bytesSent = 0L
            var chunkIdx = 0
            var lastProgressTime = 0L

            var bytesRead: Int
            while (bufferedInput.read(buffer).also { bytesRead = it } != -1) {
                ensureActive()
                if (bytesRead <= 0) continue
                val chunk = if (bytesRead == chunkSize) buffer else buffer.copyOfRange(0, bytesRead)
                val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)

                val chunkJson = JSONObject().apply {
                    put("type", "FileChunk")
                    put("payload", JSONObject().apply {
                        put("file_id", fileId)
                        put("chunk_index", chunkIdx)
                        put("total_chunks", totalChunks)
                        put("data_base64", base64)
                    })
                }
                writer?.println(chunkJson.toString())
                writer?.flush()
                bytesSent += bytesRead
                chunkIdx++

                val prog = if (totalBytes > 0) {
                    (bytesSent.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 60L || bytesSent >= totalBytes) {
                    lastProgressTime = now
                    withContext(Dispatchers.Main) {
                        onProgress(prog, bytesSent, totalBytes)
                    }
                }

                yield()
            }

            // 3. Send Complete (Success)
            val completeJson = JSONObject().apply {
                put("type", "FileComplete")
                put("payload", JSONObject().apply {
                    put("file_id", fileId)
                    put("file_name", fileName)
                    put("success", true)
                    put("message", "Sent from Android Phone")
                })
            }
            writer?.println(completeJson.toString())
            writer?.flush()
            transferSuccessfullyCompleted = true
            true
        } catch (e: CancellationException) {
            // Cancelled by user/coroutine
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val cancelJson = JSONObject().apply {
                        put("type", "FileComplete")
                        put("payload", JSONObject().apply {
                            put("file_id", fileId)
                            put("file_name", fileName)
                            put("success", false)
                            put("message", "Cancelled by Phone")
                        })
                    }
                    writer?.println(cancelJson.toString())
                    writer?.flush()
                } catch (_: Throwable) {}
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sendFileStreamToPc error: ${e.message}")
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val cancelJson = JSONObject().apply {
                        put("type", "FileComplete")
                        put("payload", JSONObject().apply {
                            put("file_id", fileId)
                            put("file_name", fileName)
                            put("success", false)
                            put("message", "Transfer error: ${e.message ?: "Unknown"}")
                        })
                    }
                    writer?.println(cancelJson.toString())
                    writer?.flush()
                } catch (_: Throwable) {}
            }
            false
        } finally {
            if (!transferSuccessfullyCompleted) {
                try {
                    val cancelJson = JSONObject().apply {
                        put("type", "FileComplete")
                        put("payload", JSONObject().apply {
                            put("file_id", fileId)
                            put("file_name", fileName)
                            put("success", false)
                            put("message", "Transfer aborted")
                        })
                    }
                    writer?.println(cancelJson.toString())
                    writer?.flush()
                } catch (_: Throwable) {}
            }
            try {
                bufferedInput.close()
            } catch (_: Throwable) {}
        }
    }

    fun cancelFileTransfer(fileId: String? = null, fileName: String = "") {
        try {
            val json = JSONObject().apply {
                put("type", "FileComplete")
                put("payload", JSONObject().apply {
                    put("file_id", fileId ?: "")
                    put("file_name", fileName)
                    put("success", false)
                    put("message", "Transfer cancelled by phone user")
                })
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "cancelFileTransfer error: ${e.message}")
        }
    }

    suspend fun sendFileToPc(
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream",
        phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean = sendFileStreamToPc(
        fileName = fileName,
        inputStream = ByteArrayInputStream(fileBytes),
        totalBytes = fileBytes.size.toLong(),
        mimeType = mimeType,
        phoneName = phoneName,
        onProgress = onProgress
    )

    suspend fun sendClipboardToPc(text: String, phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}"): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "Clipboard")
                put("payload", JSONObject().apply {
                    put("text", text)
                    put("sender_name", phoneName)
                    put("timestamp_epoch", System.currentTimeMillis() / 1000)
                })
            }
            writer?.println(json.toString())
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendClipboardToPc error: ${e.message}")
            false
        }
    }

    suspend fun sendUnlockResponse(
        challengeId: String,
        signatureHex: String,
        timestampEpoch: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "Response")
                put("payload", JSONObject().apply {
                    put("challenge_id", challengeId)
                    put("signature_hex", signatureHex)
                    put("biometric_type", "fingerprint")
                    put("timestamp_epoch", timestampEpoch)
                })
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendUnlockResponse error: ${e.message}")
        }
    }

    fun sendPing() {
        try {
            val json = JSONObject().apply {
                put("type", "Ping")
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendPing error: ${e.message}")
        }
    }

    suspend fun measurePing(): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(host, port), 1200)
            sock.soTimeout = 1200
            val w = java.io.PrintWriter(sock.getOutputStream(), true)
            val r = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
            val pingMsg = JSONObject().apply { put("type", "Ping") }
            w.println(pingMsg.toString())
            val line = r.readLine()
            val rtt = System.currentTimeMillis() - start
            sock.close()
            if (line != null) rtt else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun sendRemoteLock(reason: String = "user_manual_lock"): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "RemoteLockReq")
                put("payload", JSONObject().apply {
                    put("reason", reason)
                })
            }
            var ok = sendRaw(json.toString())
            if (!ok) {
                // Socket might have gone stale; reconnect quickly and retry
                disconnect()
                if (connect()) {
                    ok = sendRaw(json.toString())
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "sendRemoteLock error: ${e.message}")
            false
        }
    }

    suspend fun sendUnlockTrigger(phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}"): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "UnlockTriggerReq")
                put("payload", JSONObject().apply {
                    put("phone_name", phoneName)
                })
            }
            var ok = sendRaw(json.toString())
            if (!ok) {
                disconnect()
                if (connect()) {
                    ok = sendRaw(json.toString())
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "sendUnlockTrigger error: ${e.message}")
            false
        }
    }

    fun sendUnpairRequest(phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}"): Boolean {
        return try {
            val json = JSONObject().apply {
                put("type", "UnpairReq")
                put("payload", JSONObject().apply {
                    put("pin", "")
                    put("phone_name", phoneName)
                })
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendUnpairRequest error: ${e.message}")
            false
        }
    }

    fun disconnect() {
        isRunning = false
        heartbeatJob?.cancel()
        // Clean up and delete any partial/incomplete files on disconnect
        for ((_, pair) in incomingFiles) {
            try {
                pair.second.close()
                pair.first.delete()
            } catch (_: Throwable) {}
        }
        incomingFiles.clear()
        incomingSizes.clear()
        incomingBytesReceived.clear()
        incomingLastProgressTime.clear()
        incomingLastActivityTime.clear()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }
}

