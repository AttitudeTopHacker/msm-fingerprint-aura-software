/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::crypto::{
    decrypt_chacha20, derive_hkdf_key, encrypt_chacha20, generate_nonce_12, generate_nonce_32,
};
use crate::error::{AuraLockError, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use zeroize::{Zeroize, ZeroizeOnDrop};

static FAILED_VAULT_PIN_ATTEMPTS: AtomicU32 = AtomicU32::new(0);
static VAULT_LOCKOUT_UNTIL: Mutex<Option<Instant>> = Mutex::new(None);
const MAX_FAILED_PIN_ATTEMPTS: u32 = 5;
const PIN_LOCKOUT_DURATION: Duration = Duration::from_secs(60);

/// Constant-time slice comparison to prevent timing attacks on PIN verification
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// A memory-safe zeroizing container for plaintext credentials
#[derive(Debug, Clone, Zeroize, ZeroizeOnDrop)]
pub struct SecretBytes(pub Vec<u8>);

impl SecretBytes {
    pub fn new(data: Vec<u8>) -> Self {
        Self(data)
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.0
    }

    pub fn as_str(&self) -> std::result::Result<&str, std::str::Utf8Error> {
        std::str::from_utf8(&self.0)
    }
}

/// Metadata for a registered paired phone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedPhone {
    /// Friendly name of phone (e.g. "Salman's Pixel")
    pub device_name: String,
    /// Ed25519 Public Key of Phone (hex encoded, 32 bytes)
    pub public_key_hex: String,
    /// Unique pairing ID
    pub pairing_id: String,
    /// Timestamp when pairing occurred (UTC seconds)
    pub paired_at_epoch: i64,
}

impl PairedPhone {
    pub fn get_public_key_bytes(&self) -> Result<Vec<u8>> {
        let bytes = hex::decode(&self.public_key_hex)
            .map_err(|e| AuraLockError::CryptoError(format!("Invalid public key hex: {e}")))?;
        if bytes.is_empty() {
            return Err(AuraLockError::CryptoError("Empty public key".into()));
        }
        Ok(bytes)
    }
}

/// On-disk vault storing encrypted PC credentials using Split-Key protection
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VaultData {
    pub version: u32,
    pub paired_phone: Option<PairedPhone>,
    /// Salt used for HKDF key derivation
    pub salt_hex: String,
    /// 12-byte Nonce used for ChaCha20 encryption
    pub nonce_hex: String,
    /// Encrypted password ciphertext (base64)
    pub encrypted_password_b64: String,
    /// Local machine secret part (for split-key derivation)
    pub local_part_hex: String,
    /// 4-digit Security PIN for unpairing authorization
    #[serde(default = "default_security_pin")]
    pub security_pin: String,
}

fn default_security_pin() -> String {
    let pin: u32 = rand::random::<u32>() % 900000 + 100000;
    format!("{:06}", pin)
}

pub fn get_default_vault_path() -> PathBuf {
    if let Ok(val) = std::env::var("AURALOCK_VAULT_PATH") {
        return PathBuf::from(val);
    }
    #[cfg(unix)]
    {
        let sys_path = PathBuf::from("/etc/auralock/vault.json");
        if sys_path.exists() {
            return sys_path;
        }
        if let Some(home) = std::env::var_os("HOME") {
            let p = PathBuf::from(home).join(".config").join("auralock").join("vault.json");
            if p.exists() {
                return p;
            }
        }
        if std::env::var("USER").map(|u| u == "root").unwrap_or(false) || std::path::Path::new("/etc/auralock").exists() {
            return sys_path;
        }
        if let Some(home) = std::env::var_os("HOME") {
            return PathBuf::from(home).join(".config").join("auralock").join("vault.json");
        }
        sys_path
    }
    #[cfg(windows)]
    {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            return PathBuf::from(appdata).join("auralock").join("vault.json");
        }
        PathBuf::from("vault.json")
    }
}

pub struct VaultManager {
    vault_file_path: PathBuf,
}

impl VaultManager {
    pub fn new<P: AsRef<Path>>(vault_path: P) -> Self {
        Self {
            vault_file_path: vault_path.as_ref().to_path_buf(),
        }
    }

    /// Checks if a vault file already exists
    pub fn exists(&self) -> bool {
        self.vault_file_path.exists()
    }

    /// Checks if a phone is currently paired
    pub fn is_paired(&self) -> bool {
        self.load().ok().and_then(|v| v.paired_phone).is_some()
    }

    /// Loads vault data from disk
    pub fn load(&self) -> Result<VaultData> {
        if !self.exists() {
            return Err(AuraLockError::VaultError("Vault file not found".into()));
        }
        let content = std::fs::read_to_string(&self.vault_file_path)?;
        let vault: VaultData = serde_json::from_str(&content)?;
        Ok(vault)
    }

    /// Verifies security PIN with constant-time comparison and brute-force lockout
    pub fn verify_security_pin(&self, pin: &str) -> bool {
        let input_pin = pin.trim();
        if input_pin.is_empty() {
            return false;
        }

        // 1. Check if locked out due to prior brute force attempts
        if let Ok(mut lockout_guard) = VAULT_LOCKOUT_UNTIL.lock() {
            if let Some(until) = *lockout_guard {
                if Instant::now() < until {
                    tracing::warn!("Vault PIN verification rejected: Locked out due to too many failed attempts");
                    return false;
                } else {
                    *lockout_guard = None;
                    FAILED_VAULT_PIN_ATTEMPTS.store(0, Ordering::SeqCst);
                }
            }
        }

        match self.load() {
            Ok(vault) => {
                let stored = vault.security_pin.trim();
                if stored.is_empty() {
                    return false;
                }

                if constant_time_eq(stored.as_bytes(), input_pin.as_bytes()) {
                    // Reset failure counter on success
                    FAILED_VAULT_PIN_ATTEMPTS.store(0, Ordering::SeqCst);
                    if let Ok(mut lockout_guard) = VAULT_LOCKOUT_UNTIL.lock() {
                        *lockout_guard = None;
                    }
                    true
                } else {
                    // Record failure and check for lockout
                    let failed = FAILED_VAULT_PIN_ATTEMPTS.fetch_add(1, Ordering::SeqCst) + 1;
                    tracing::warn!("Failed Vault PIN attempt ({}/{})", failed, MAX_FAILED_PIN_ATTEMPTS);
                    if failed >= MAX_FAILED_PIN_ATTEMPTS {
                        if let Ok(mut lockout_guard) = VAULT_LOCKOUT_UNTIL.lock() {
                            *lockout_guard = Some(Instant::now() + PIN_LOCKOUT_DURATION);
                            tracing::warn!("Max Vault PIN attempts exceeded. Locking out for {}s", PIN_LOCKOUT_DURATION.as_secs());
                        }
                    }
                    // Add micro throttle against tight-loop brute force
                    std::thread::sleep(Duration::from_millis(50));
                    false
                }
            }
            Err(_) => false,
        }
    }

    /// Unpairs device only if security PIN matches
    pub fn unpair_with_pin(&self, pin: &str) -> Result<()> {
        if !self.verify_security_pin(pin) {
            return Err(AuraLockError::VaultError("Invalid Security PIN".into()));
        }
        self.clear()
    }

    /// Clears and removes the vault file from disk, as well as any other known vault paths
    pub fn clear(&self) -> Result<()> {
        Self::wipe_all_vault_locations();
        Ok(())
    }

    /// Completely wipes vault files across all potential system and user paths
    pub fn wipe_all_vault_locations() {
        let mut candidates = Vec::new();

        if let Ok(val) = std::env::var("AURALOCK_VAULT_PATH") {
            candidates.push(PathBuf::from(val));
        }

        #[cfg(unix)]
        {
            candidates.push(PathBuf::from("/etc/auralock/vault.json"));
            candidates.push(PathBuf::from("/etc/auralock"));
            if let Some(home) = std::env::var_os("HOME") {
                candidates.push(PathBuf::from(home.clone()).join(".config").join("auralock").join("vault.json"));
                candidates.push(PathBuf::from(home).join(".config").join("auralock"));
            }
        }

        #[cfg(windows)]
        {
            if let Some(appdata) = std::env::var_os("APPDATA") {
                candidates.push(PathBuf::from(appdata.clone()).join("auralock").join("vault.json"));
                candidates.push(PathBuf::from(appdata).join("auralock"));
            }
        }

        for path in candidates {
            if path.is_file() {
                let _ = std::fs::write(&path, "{}");
                let _ = std::fs::remove_file(&path);
            } else if path.is_dir() {
                let _ = std::fs::remove_dir_all(&path);
            }
        }
    }

    /// Saves or updates a paired phone in the vault (clearing any old stale passwords)
    pub fn save_paired_phone(&self, phone: PairedPhone) -> Result<()> {
        let existing_pin = self.load().map(|v| v.security_pin).unwrap_or_else(|_| default_security_pin());

        let vault_data = VaultData {
            version: 1,
            paired_phone: Some(phone),
            salt_hex: hex::encode(generate_nonce_32()),
            nonce_hex: hex::encode(generate_nonce_12()),
            encrypted_password_b64: String::new(),
            local_part_hex: hex::encode(generate_nonce_32()),
            security_pin: existing_pin,
        };

        if let Some(parent) = self.vault_file_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let json = serde_json::to_string_pretty(&vault_data)?;
        std::fs::write(&self.vault_file_path, json)?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(meta) = std::fs::metadata(&self.vault_file_path) {
                let mut perms = meta.permissions();
                perms.set_mode(0o644);
                let _ = std::fs::set_permissions(&self.vault_file_path, perms);
            }
        }

        Ok(())
    }

    /// Initializes and saves a new vault with password protected by phone pairing
    pub fn save_new_vault(
        &self,
        phone: PairedPhone,
        shared_master_secret: &[u8; 32],
        password: &SecretBytes,
    ) -> Result<()> {
        let salt = generate_nonce_32();
        let local_part = generate_nonce_32();

        // Split-key derivation: Combine shared_master_secret + local_part + phone_pubkey
        let phone_pub_bytes = phone.get_public_key_bytes()?;
        let mut combined_ikm = Vec::new();
        combined_ikm.extend_from_slice(shared_master_secret);
        combined_ikm.extend_from_slice(&local_part);
        combined_ikm.extend_from_slice(&phone_pub_bytes);

        let derived_encryption_key = derive_hkdf_key(
            &combined_ikm,
            Some(&salt),
            b"auralock-vault-splitkey-v1",
            32,
        )?;

        let mut key32 = [0u8; 32];
        key32.copy_from_slice(&derived_encryption_key);

        let (ciphertext, nonce) = encrypt_chacha20(&key32, password.as_slice())?;

        // Zeroize key from memory
        key32.zeroize();

        let existing_pin = self.load().map(|v| v.security_pin).unwrap_or_else(|_| default_security_pin());

        let vault_data = VaultData {
            version: 1,
            paired_phone: Some(phone),
            salt_hex: hex::encode(salt),
            nonce_hex: hex::encode(nonce),
            encrypted_password_b64: base64::Engine::encode(
                &base64::engine::general_purpose::STANDARD,
                ciphertext,
            ),
            local_part_hex: hex::encode(local_part),
            security_pin: existing_pin,
        };

        if let Some(parent) = self.vault_file_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let json = serde_json::to_string_pretty(&vault_data)?;
        std::fs::write(&self.vault_file_path, json)?;

        // Set readable permissions for PAM and user app
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(meta) = std::fs::metadata(&self.vault_file_path) {
                let mut perms = meta.permissions();
                perms.set_mode(0o644);
                let _ = std::fs::set_permissions(&self.vault_file_path, perms);
            }
        }

        Ok(())
    }

    /// Decrypts the PC password using the phone's shared secret
    pub fn decrypt_password(
        &self,
        vault: &VaultData,
        shared_master_secret: &[u8; 32],
    ) -> Result<SecretBytes> {
        let phone = vault
            .paired_phone
            .as_ref()
            .ok_or(AuraLockError::DeviceNotPaired)?;

        let phone_pub_bytes = phone.get_public_key_bytes()?;
        let salt = hex::decode(&vault.salt_hex)
            .map_err(|e| AuraLockError::VaultError(format!("Invalid salt hex: {e}")))?;
        let local_part = hex::decode(&vault.local_part_hex)
            .map_err(|e| AuraLockError::VaultError(format!("Invalid local_part hex: {e}")))?;

        let mut combined_ikm = Vec::new();
        combined_ikm.extend_from_slice(shared_master_secret);
        combined_ikm.extend_from_slice(&local_part);
        combined_ikm.extend_from_slice(&phone_pub_bytes);

        let derived_key = derive_hkdf_key(
            &combined_ikm,
            Some(&salt),
            b"auralock-vault-splitkey-v1",
            32,
        )?;

        let mut key32 = [0u8; 32];
        key32.copy_from_slice(&derived_key);

        let nonce_bytes = hex::decode(&vault.nonce_hex)
            .map_err(|e| AuraLockError::VaultError(format!("Invalid nonce hex: {e}")))?;
        if nonce_bytes.len() != 12 {
            return Err(AuraLockError::VaultError("Invalid nonce length".into()));
        }
        let mut nonce12 = [0u8; 12];
        nonce12.copy_from_slice(&nonce_bytes);

        let ciphertext = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &vault.encrypted_password_b64,
        )
        .map_err(|e| AuraLockError::VaultError(format!("Invalid base64 ciphertext: {e}")))?;

        let plaintext_bytes = decrypt_chacha20(&key32, &nonce12, &ciphertext)?;

        // Zeroize key from memory
        key32.zeroize();

        Ok(SecretBytes::new(plaintext_bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vault_save_and_decrypt() {
        let temp_dir = std::env::temp_dir().join(format!("auralock_test_{}", rand::random::<u32>()));
        let vault_file = temp_dir.join("vault.json");
        let manager = VaultManager::new(&vault_file);

        let phone = PairedPhone {
            device_name: "Test Phone".to_string(),
            public_key_hex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_string(),
            pairing_id: "pair_123".to_string(),
            paired_at_epoch: 1700000000,
        };

        let master_secret = [42u8; 32];
        let original_pw = SecretBytes::new(b"SuperSecurePassword123!".to_vec());

        manager
            .save_new_vault(phone.clone(), &master_secret, &original_pw)
            .unwrap();

        assert!(manager.exists());

        let vault_data = manager.load().unwrap();
        let decrypted = manager.decrypt_password(&vault_data, &master_secret).unwrap();

        assert_eq!(decrypted.as_slice(), original_pw.as_slice());

        // Cleanup
        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}
