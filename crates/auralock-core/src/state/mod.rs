/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::crypto::{generate_nonce_32, verify_ed25519_signature};
use crate::error::{AuraLockError, Result};
use crate::protocol::{UnlockChallenge, UnlockResponse, UnlockResult};
use crate::vault::PairedPhone;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::{broadcast, oneshot, Mutex};
use tracing::{error, info, warn};

const MAX_CHALLENGE_AGE_SECS: i64 = 5;
const MAX_FAILED_ATTEMPTS: u32 = 5;
const LOCKOUT_DURATION_SECS: u64 = 60;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionLockState {
    Unlocked,
    Locked,
    Authenticating,
}

struct ActiveChallenge {
    challenge: UnlockChallenge,
    created_at: Instant,
    completion_sender: Option<oneshot::Sender<bool>>,
}

pub struct CoreState {
    pub lock_state: SessionLockState,
    pub paired_phone: Option<PairedPhone>,
    active_challenges: HashMap<String, ActiveChallenge>,
    failed_attempts: u32,
    lockout_until: Option<Instant>,
}

#[derive(Clone)]
pub struct StateManager {
    inner: Arc<Mutex<CoreState>>,
    event_bus: broadcast::Sender<SessionLockState>,
}

impl StateManager {
    pub fn new(paired_phone: Option<PairedPhone>) -> Self {
        let (tx, _) = broadcast::channel(16);
        Self {
            inner: Arc::new(Mutex::new(CoreState {
                lock_state: SessionLockState::Unlocked,
                paired_phone,
                active_challenges: HashMap::new(),
                failed_attempts: 0,
                lockout_until: None,
            })),
            event_bus: tx,
        }
    }

    /// Sets the PC lock state (called by system hook / logind / Winlogon)
    pub async fn set_lock_state(&self, new_state: SessionLockState) {
        let mut state = self.inner.lock().await;
        if state.lock_state != new_state {
            info!("State transition: {:?} -> {:?}", state.lock_state, new_state);
            state.lock_state = new_state.clone();
            let _ = self.event_bus.send(new_state);
        }
    }

    /// Creates a new cryptographic challenge for the lockscreen
    pub async fn create_challenge(
        &self,
        pc_name: &str,
    ) -> Result<(UnlockChallenge, oneshot::Receiver<bool>)> {
        let mut state = self.inner.lock().await;

        // Check for brute force lockout
        if let Some(until) = state.lockout_until {
            if Instant::now() < until {
                return Err(AuraLockError::VaultError(
                    "Temporarily locked out due to too many failed biometric attempts".into(),
                ));
            } else {
                state.lockout_until = None;
                state.failed_attempts = 0;
            }
        }

        let nonce_bytes = generate_nonce_32();
        let nonce_hex = hex::encode(nonce_bytes);
        let challenge_id = format!("ch_{}", &nonce_hex[..8]);
        let now_epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|e| AuraLockError::CryptoError(e.to_string()))?
            .as_secs() as i64;

        let challenge = UnlockChallenge {
            challenge_id: challenge_id.clone(),
            nonce_hex,
            timestamp_epoch: now_epoch,
            pc_name: pc_name.to_string(),
        };

        let (tx, rx) = oneshot::channel();
        state.active_challenges.insert(
            challenge_id,
            ActiveChallenge {
                challenge: challenge.clone(),
                created_at: Instant::now(),
                completion_sender: Some(tx),
            },
        );

        Ok((challenge, rx))
    }

    /// Validates an incoming biometric response from the Phone
    pub async fn verify_unlock_response(&self, resp: UnlockResponse) -> Result<UnlockResult> {
        let mut state = self.inner.lock().await;

        let phone = match &state.paired_phone {
            Some(p) => p.clone(),
            None => return Err(AuraLockError::DeviceNotPaired),
        };

        // 1. Find active challenge
        let active = match state.active_challenges.remove(&resp.challenge_id) {
            Some(c) => c,
            None => return Err(AuraLockError::ExpiredOrReplayedNonce),
        };

        // 2. Enforce 5-second TTL
        if active.created_at.elapsed() > Duration::from_secs(MAX_CHALLENGE_AGE_SECS as u64) {
            warn!("Challenge {} expired (> 5s)", resp.challenge_id);
            return Err(AuraLockError::ExpiredOrReplayedNonce);
        }

        // 3. Verify Ed25519 signature
        let pubkey_bytes = phone.get_public_key_bytes()?;
        let signable = active.challenge.get_signable_payload();

        let sig_bytes = hex::decode(&resp.signature_hex)
            .map_err(|e| AuraLockError::CryptoError(format!("Invalid signature hex: {e}")))?;
        if sig_bytes.len() != 64 {
            return Err(AuraLockError::CryptoError("Invalid signature length".into()));
        }
        let mut sig_arr = [0u8; 64];
        sig_arr.copy_from_slice(&sig_bytes);

        match verify_ed25519_signature(&pubkey_bytes, &signable, &sig_arr) {
            Ok(()) => {
                info!("Biometric signature verified successfully for challenge {}", resp.challenge_id);
                state.failed_attempts = 0;
                if let Some(sender) = active.completion_sender {
                    let _ = sender.send(true);
                }
                Ok(UnlockResult {
                    success: true,
                    message: "Biometric authentication approved".into(),
                })
            }
            Err(e) => {
                error!("Invalid biometric signature: {:?}", e);
                state.failed_attempts += 1;
                if state.failed_attempts >= MAX_FAILED_ATTEMPTS {
                    warn!("Max failed attempts exceeded. Locking out for {}s", LOCKOUT_DURATION_SECS);
                    state.lockout_until = Some(Instant::now() + Duration::from_secs(LOCKOUT_DURATION_SECS));
                }
                if let Some(sender) = active.completion_sender {
                    let _ = sender.send(false);
                }
                Err(e)
            }
        }
    }

    /// Sets the paired phone metadata
    pub async fn set_paired_phone(&self, phone: Option<PairedPhone>) {
        let mut state = self.inner.lock().await;
        state.paired_phone = phone;
    }

    /// Unpairs the active phone and resets challenges
    pub async fn unpair(&self) {
        let mut state = self.inner.lock().await;
        state.paired_phone = None;
        state.active_challenges.clear();
        state.failed_attempts = 0;
        state.lockout_until = None;
    }
}
