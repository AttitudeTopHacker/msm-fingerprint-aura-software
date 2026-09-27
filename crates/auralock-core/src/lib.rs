/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

pub mod crypto;
pub mod error;
pub mod ipc;
pub mod network;
pub mod protocol;
pub mod state;
pub mod vault;

pub use error::{AuraLockError, Result};
pub use protocol::{
    PairingQRData, PairingRequest, PairingResponse, ProtocolMessage, UnlockChallenge,
    UnlockResponse, UnlockResult, DEFAULT_AURALOCK_PORT, PROTOCOL_VERSION,
};
pub use state::{SessionLockState, StateManager};
pub use vault::{PairedPhone, SecretBytes, VaultData, VaultManager};
