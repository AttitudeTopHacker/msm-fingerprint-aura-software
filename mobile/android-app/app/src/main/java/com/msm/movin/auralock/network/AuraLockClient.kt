/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.network

import android.os.Build
import android.os.Environment
import android.util.Base64
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

    private fun getUniqueTargetFile(directory: File, originalName: String): File {
        var candidate = File(directory, originalName)
        if (!candidate.exists()) return candidate
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex > 0) originalName.substring(dotIndex) else ""
        var count = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($count)$extension")
            count++
        }
        return candidate
    }

    companion object {
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }

    suspend fun sendPairingRequest(
        phoneName: String,
        pubKeyHex: String,
        pin: String
    ): PairingResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "PairingReq")
                put("payload", JSONObject().apply {
                    put("phone_name", phoneName)
                    put("phone_ed25519_pub_hex", pubKeyHex)
                    put("phone_ephem_pub_hex", pubKeyHex)
                    put("pin", pin)
                })
            }
            sendRaw(json.toString())
            
            var attempts = 0
            while (attempts < 25) {
                attempts++
                val respLine = reader?.readLine() ?: return@withContext PairingResult(false, "No response from PC socket")
                if (respLine.isBlank()) continue
                
                val respJson = JSONObject(respLine)
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
        } catch (e: Exception) {
            e.printStackTrace()
            PairingResult(false, "Network error: ${e.message}")
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
                            val payload = json.optJSONObject("payload") ?: continue
                            val fileId = payload.optString("file_id", "file_${UUID.randomUUID().toString().take(6)}")
                            val rawFileName = payload.optString("file_name", "received_file.bin")
                            val fileSize = payload.optLong("file_size", 0L)
                            val mimeType = payload.optString("mime_type", "application/octet-stream")
                            val senderName = payload.optString("sender_name", "PC")

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

                            withContext(Dispatchers.Main) {
                                onIncomingFileOffer(IncomingFileOfferEvent(fileId, targetFile.name, fileSize, mimeType, senderName))
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                    "FileChunk" -> {
                        try {
                            val payload = json.optJSONObject("payload") ?: continue
                            val fileId = payload.optString("file_id")
                            val chunkIndex = payload.optInt("chunk_index", 0)
                            val totalChunks = payload.optInt("total_chunks", 1)
                            val base64Data = payload.optString("data_base64", "")

                            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                            val pair = incomingFiles[fileId]
                            if (pair != null) {
                                pair.second.write(bytes)
                                val received = (incomingBytesReceived[fileId] ?: 0L) + bytes.size
                                incomingBytesReceived[fileId] = received
                                val totalSize = incomingSizes[fileId] ?: 1L
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
                            e.printStackTrace()
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
                            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }

    fun sendPing() {
        try {
            val json = JSONObject().apply {
                put("type", "Ping")
            }
            sendRaw(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        isRunning = false
        heartbeatJob?.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }
}

