/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use thiserror::Error;

#[derive(Error, Debug)]
pub enum AuraLockError {
    #[error("Cryptographic error: {0}")]
    CryptoError(String),

    #[error("Signature verification failed: invalid biometric token or key mismatch")]
    InvalidSignature,

    #[error("Replay attack detected: nonce has expired or already used")]
    ExpiredOrReplayedNonce,

    #[error("Timestamp drift exceeded: challenge expired (drift: {0}s)")]
    TimestampDriftExceeded(i64),

    #[error("Vault error: {0}")]
    VaultError(String),

    #[error("Decryption failed: corrupted ciphertext or authentication tag mismatch")]
    DecryptionFailed,

    #[error("Device not paired: no registered mobile phone found")]
    DeviceNotPaired,

    #[error("Protocol error: {0}")]
    ProtocolError(String),

    #[error("IO error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Serialization error: {0}")]
    SerializationError(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, AuraLockError>;
