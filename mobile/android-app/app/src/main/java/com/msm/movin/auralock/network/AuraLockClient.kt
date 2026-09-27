/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket

data class ChallengeEvent(
    val challengeId: String,
    val nonceHex: String,
    val timestampEpoch: Long,
    val pcName: String
)

class AuraLockClient(
    private val host: String,
    private val port: Int
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port)
            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            isRunning = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun sendPairingRequest(
        phoneName: String,
        pubKeyHex: String,
        pin: String
    ): Boolean = withContext(Dispatchers.IO) {
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
            writer?.println(json.toString())
            val respLine = reader?.readLine() ?: return@withContext false
            val respJson = JSONObject(respLine)
            val payload = respJson.optJSONObject("payload")
            payload?.optBoolean("success", false) == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listenForChallenges(
        onChallenge: (ChallengeEvent) -> Unit,
        onDisconnect: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            while (isRunning && socket?.isConnected == true) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue

                val json = JSONObject(line)
                val type = json.optString("type")
                if (type == "Challenge") {
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(Dispatchers.Main) {
                onDisconnect()
            }
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
            writer?.println(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendPing(): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "Ping")
            }
            writer?.println(json.toString())
            val respLine = reader?.readLine() ?: return@withContext false
            val respJson = JSONObject(respLine)
            respJson.optString("type") == "Pong"
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
    }
}
