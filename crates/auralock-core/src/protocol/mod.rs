/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const DEFAULT_AURALOCK_PORT: u16 = 47890;
pub const BLE_SERVICE_UUID: &str = "0000a110-0000-1000-8000-00805f9b34fb";
pub const BLE_CHALLENGE_CHAR_UUID: &str = "0000a111-0000-1000-8000-00805f9b34fb";
pub const BLE_RESPONSE_CHAR_UUID: &str = "0000a112-0000-1000-8000-00805f9b34fb";

/// Data encoded in the QR code displayed on the PC during pairing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingQRData {
    pub v: u32,
    pub pc_id: String,
    pub pc_name: String,
    pub ephem_pub_hex: String,
    pub ble_uuid: String,
    pub ip: String,
    pub port: u16,
    pub pin: String,
}

/// Pairing request sent from Phone to PC
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingRequest {
    pub phone_name: String,
    pub phone_ed25519_pub_hex: String,
    pub phone_ephem_pub_hex: String,
    pub pin: String,
}

/// Pairing response returned from PC to Phone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingResponse {
    pub success: bool,
    pub pairing_id: String,
    pub message: String,
}

/// Challenge issued by PC to Phone when login/unlock is requested
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnlockChallenge {
    pub challenge_id: String,
    pub nonce_hex: String,
    pub timestamp_epoch: i64,
    pub pc_name: String,
}

impl UnlockChallenge {
    /// Formats the exact byte payload that the Phone's hardware keystore must sign
    pub fn get_signable_payload(&self) -> Vec<u8> {
        format!(
            "auralock:v{}:{}:{}:{}",
            PROTOCOL_VERSION, self.challenge_id, self.nonce_hex, self.timestamp_epoch
        )
        .into_bytes()
    }
}

/// Biometric authentication response sent from Phone back to PC
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnlockResponse {
    pub challenge_id: String,
    pub signature_hex: String,
    pub biometric_type: String, // e.g. "fingerprint"
    pub timestamp_epoch: i64,
}

/// Result of unlock verification
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnlockResult {
    pub success: bool,
    pub message: String,
}

/// Notification sent when PC lock state changes
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ProtocolMessage {
    PairingReq(PairingRequest),
    PairingResp(PairingResponse),
    Challenge(UnlockChallenge),
    Response(UnlockResponse),
    Result(UnlockResult),
    LockStateChanged { is_locked: bool },
    Ping,
    Pong,
}
