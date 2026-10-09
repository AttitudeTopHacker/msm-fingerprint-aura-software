/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.model

import org.json.JSONObject

data class ParsedQrData(
    val version: Int = 1,
    val pcId: String = "",
    val pcName: String = "AuraLock-PC",
    val ip: String = "127.0.0.1",
    val port: Int = 47890,
    val pin: String = "",
    val ephemPubHex: String = "",
    val bleUuid: String = ""
) {
    companion object {
        fun parse(raw: String): ParsedQrData? {
            val trimmed = raw.trim()
            return try {
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    val json = JSONObject(trimmed)
                    ParsedQrData(
                        version = json.optInt("v", 1),
                        pcId = json.optString("pc_id", ""),
                        pcName = json.optString("pc_name", if (json.has("hostname")) json.getString("hostname") else "AuraLock-PC"),
                        ip = json.optString("ip", if (json.has("host")) json.getString("host") else "127.0.0.1"),
                        port = json.optInt("port", 47890),
                        pin = json.optString("pin", ""),
                        ephemPubHex = json.optString("ephem_pub_hex", ""),
                        bleUuid = json.optString("ble_uuid", "")
                    )
                } else if (trimmed.contains(":")) {
                    // Fallback format: IP:PORT:PIN or IP:PIN
                    val parts = trimmed.split(":")
                    when (parts.size) {
                        2 -> ParsedQrData(ip = parts[0], pin = parts[1])
                        3 -> ParsedQrData(ip = parts[0], port = parts[1].toIntOrNull() ?: 47890, pin = parts[2])
                        else -> null
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

enum class ConnectionStep {
    DISCOVERING_PC,
    INITIALIZING_KEYSTORE,
    EXCHANGING_KEYS,
    VERIFYING_PIN,
    SECURE_TUNNEL_ACTIVE,
    FAILED
}

data class ConnectionProgressState(
    val isConnecting: Boolean = false,
    val step: ConnectionStep = ConnectionStep.DISCOVERING_PC,
    val progressMessage: String = "",
    val errorMessage: String? = null,
    val pcName: String = "",
    val targetIp: String = ""
)
