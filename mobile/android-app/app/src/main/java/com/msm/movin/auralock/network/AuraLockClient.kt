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

    private val incomingFiles = mutableMapOf<String, Pair<File, FileOutputStream>>()
    private val incomingSizes = mutableMapOf<String, Long>()
    private val incomingBytesReceived = mutableMapOf<String, Long>()

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
            writer = PrintWriter(OutputStreamWriter(sock.getOutputStream()), true)
            reader = BufferedReader(InputStreamReader(sock.getInputStream()))
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
        onUnpaired: () -> Unit = {},
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

                val json = JSONObject(line)
                val type = json.optString("type")
                when (type) {
                    "BluetoothStatus" -> {
                        val payload = json.optJSONObject("payload")
                        val isActive = payload?.optBoolean("is_active", false) ?: false
                        withContext(Dispatchers.Main) {
                            onPcBluetoothStatus(isActive)
                        }
                    }
                    "Challenge" -> {
                        val payload = json.getJSONObject("payload")
                        val event = ChallengeEvent(
                            challengeId = payload.getString("challenge_id"),
                            nonceHex = payload.getString("nonce_hex"),
                            timestampEpoch = payload.getLong("timestamp_epoch"),
                            pcName = payload.getString("pc_name")
                        )
                        withContext(Dispatchers.Main) {
                            onChallenge(event)
                        }
                    }
                    "FileOffer" -> {
                        val payload = json.getJSONObject("payload")
                        val fileId = payload.getString("file_id")
                        val fileName = payload.getString("file_name")
                        val fileSize = payload.getLong("file_size")
                        val mimeType = payload.optString("mime_type", "application/octet-stream")
                        val senderName = payload.optString("sender_name", "PC")

                        val downloadsDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "AuraLock"
                        )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()

                        val targetFile = File(downloadsDir, fileName)
                        val fos = FileOutputStream(targetFile)
                        incomingFiles[fileId] = Pair(targetFile, fos)
                        incomingSizes[fileId] = fileSize
                        incomingBytesReceived[fileId] = 0L

                        withContext(Dispatchers.Main) {
                            onIncomingFileOffer(IncomingFileOfferEvent(fileId, fileName, fileSize, mimeType, senderName))
                        }
                    }
                    "FileChunk" -> {
                        val payload = json.getJSONObject("payload")
                        val fileId = payload.getString("file_id")
                        val chunkIndex = payload.getInt("chunk_index")
                        val totalChunks = payload.getInt("total_chunks")
                        val base64Data = payload.getString("data_base64")

                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        val pair = incomingFiles[fileId]
                        if (pair != null) {
                            pair.second.write(bytes)
                            val received = (incomingBytesReceived[fileId] ?: 0L) + bytes.size
                            incomingBytesReceived[fileId] = received
                            val totalSize = incomingSizes[fileId] ?: 1L
                            val progress = (received.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)

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

                            if (chunkIndex + 1 >= totalChunks || received >= totalSize) {
                                pair.second.flush()
                                pair.second.close()
                                incomingFiles.remove(fileId)
                                withContext(Dispatchers.Main) {
                                    onFileReceived(fileId, pair.first.name, pair.first)
                                }
                            }
                        }
                    }
                    "FileComplete" -> {
                        val payload = json.getJSONObject("payload")
                        val fileId = payload.getString("file_id")
                        val fileName = payload.getString("file_name")
                        val pair = incomingFiles.remove(fileId)
                        if (pair != null) {
                            pair.second.flush()
                            pair.second.close()
                            withContext(Dispatchers.Main) {
                                onFileReceived(fileId, fileName, pair.first)
                            }
                        }
                    }
                    "Clipboard" -> {
                        val payload = json.getJSONObject("payload")
                        val text = payload.getString("text")
                        val sender = payload.optString("sender_name", "PC")
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
            heartbeatJob?.cancel()
            withContext(Dispatchers.Main) {
                onDisconnect()
            }
        }
    }

    suspend fun sendFileToPc(
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream",
        phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileId = "phone_file_${UUID.randomUUID().toString().take(8)}"
            val totalBytes = fileBytes.size.toLong()

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

            // 2. Stream chunks (64 KB each)
            val chunkSize = 64 * 1024
            val totalChunks = if (totalBytes == 0L) 1 else ((totalBytes + chunkSize - 1) / chunkSize).toInt()
            var bytesSent = 0L

            var offset = 0
            var chunkIdx = 0
            while (offset < fileBytes.size) {
                val end = (offset + chunkSize).coerceAtMost(fileBytes.size)
                val chunk = fileBytes.copyOfRange(offset, end)
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
                bytesSent += chunk.size
                offset = end
                chunkIdx++

                val prog = (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                withContext(Dispatchers.Main) {
                    onProgress(prog, bytesSent, totalBytes)
                }

                delay(5)
            }

            // 3. Send Complete
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

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

