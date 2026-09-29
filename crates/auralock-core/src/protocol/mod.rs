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

/// Offer sent to initiate a file transfer (Bidirectional: PC <-> Phone)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTransferOffer {
    pub file_id: String,
    pub file_name: String,
    pub file_size: u64,
    pub mime_type: String,
    pub sender_name: String,
}

/// Acceptance response for a file transfer
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTransferAccept {
    pub file_id: String,
    pub accepted: bool,
    pub message: Option<String>,
}

/// Chunk of file payload (Base64 encoded)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTransferChunk {
    pub file_id: String,
    pub chunk_index: u32,
    pub total_chunks: u32,
    pub data_base64: String,
}

/// Notification sent when a file transfer finishes
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTransferComplete {
    pub file_id: String,
    pub file_name: String,
    pub success: bool,
    pub message: String,
}

/// Bidirectional shared clipboard / text payload
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardSync {
    pub text: String,
    pub sender_name: String,
    pub timestamp_epoch: i64,
}

/// Transfer session authorization request
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferAuthRequest {
    pub pin: String,
    pub phone_name: String,
}

/// Transfer session authorization response
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferAuthResponse {
    pub success: bool,
    pub message: String,
}

/// Remote unpair request from phone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteUnpairRequest {
    pub pin: String,
    pub phone_name: String,
}

/// Remote unpair response
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteUnpairResponse {
    pub success: bool,
    pub message: String,
}

/// Unpair prompt sent by PC to Phone with PIN to be entered on PC
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnpairPromptPhone {
    pub pin: String,
    pub pc_name: String,
}

/// Unpair initiate request sent by Phone to PC to display PIN on PC
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnpairInitiateFromPhone {
    pub phone_name: String,
}

/// Device identification sent on connection
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IdentifyMessage {
    pub phone_name: String,
}

/// Remote lock request sent from Phone to PC
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RemoteLockRequest {
    #[serde(default)]
    pub reason: Option<String>,
}

/// Unlock trigger request sent from Phone to PC
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UnlockTriggerRequest {
    #[serde(default)]
    pub phone_name: Option<String>,
}

/// Bluetooth status message sent to sync PC bluetooth state with Phone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BluetoothStatusMessage {
    pub is_active: bool,
    #[serde(default)]
    pub device_name: Option<String>,
}

/// Notification sent when PC lock state changes
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ProtocolMessage {
    Identify(IdentifyMessage),
    PairingReq(PairingRequest),
    PairingResp(PairingResponse),
    Challenge(UnlockChallenge),
    Response(UnlockResponse),
    Result(UnlockResult),
    LockStateChanged { is_locked: bool },
    BluetoothStatus(BluetoothStatusMessage),
    Ping,
    Pong,
    FileOffer(FileTransferOffer),
    FileAccept(FileTransferAccept),
    FileChunk(FileTransferChunk),
    FileComplete(FileTransferComplete),
    Clipboard(ClipboardSync),
    TransferAuthReq(TransferAuthRequest),
    TransferAuthResp(TransferAuthResponse),
    UnpairReq(RemoteUnpairRequest),
    UnpairResp(RemoteUnpairResponse),
    UnpairPromptPhone(UnpairPromptPhone),
    UnpairInitiateFromPhone(UnpairInitiateFromPhone),
    RemoteLockReq(RemoteLockRequest),
    UnlockTriggerReq(UnlockTriggerRequest),
}




