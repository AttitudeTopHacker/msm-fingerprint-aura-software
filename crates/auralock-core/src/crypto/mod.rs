/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::error::{AuraLockError, Result};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Nonce as ChaChaNonce,
};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;
pub use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret as X25519StaticSecret};

/// Generates a cryptographically secure 32-byte random Nonce
pub fn generate_nonce_32() -> [u8; 32] {
    let mut nonce = [0u8; 32];
    OsRng.fill_bytes(&mut nonce);
    nonce
}

/// Generates a cryptographically secure 12-byte random Nonce for ChaCha20-Poly1305
pub fn generate_nonce_12() -> [u8; 12] {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    nonce
}

use p256::ecdsa::{Signature as P256Signature, VerifyingKey as P256VerifyingKey};
use p256::pkcs8::DecodePublicKey;

/// Verifies an Ed25519 signature against a public key and raw message payload
pub fn verify_ed25519_signature(
    public_key_bytes: &[u8; 32],
    message: &[u8],
    signature_bytes: &[u8; 64],
) -> Result<()> {
    let verifying_key = VerifyingKey::from_bytes(public_key_bytes)
        .map_err(|e| AuraLockError::CryptoError(format!("Invalid Ed25519 public key: {e}")))?;

    let signature = Signature::from_bytes(signature_bytes);

    verifying_key
        .verify(message, &signature)
        .map_err(|_| AuraLockError::InvalidSignature)
}

/// Verifies an ECDSA P-256 (secp256r1) signature from Android KeyStore
pub fn verify_p256_ecdsa_signature(
    public_key_bytes: &[u8],
    message: &[u8],
    signature_bytes: &[u8],
) -> Result<()> {
    let verifying_key = if let Ok(key) = P256VerifyingKey::from_public_key_der(public_key_bytes) {
        key
    } else if let Ok(key) = P256VerifyingKey::from_sec1_bytes(public_key_bytes) {
        key
    } else {
        return Err(AuraLockError::CryptoError("Failed to parse P-256 public key".into()));
    };

    let signature = if let Ok(sig) = P256Signature::from_der(signature_bytes) {
        sig
    } else if let Ok(sig) = P256Signature::from_slice(signature_bytes) {
        sig
    } else {
        return Err(AuraLockError::CryptoError("Failed to parse ECDSA signature".into()));
    };

    verifying_key
        .verify(message, &signature)
        .map_err(|_| AuraLockError::InvalidSignature)
}

/// Universal biometric signature verifier (supports Android KeyStore P-256 ECDSA and Ed25519)
pub fn verify_biometric_signature(
    public_key_bytes: &[u8],
    message: &[u8],
    signature_bytes: &[u8],
) -> Result<()> {
    if public_key_bytes.len() == 32 && signature_bytes.len() == 64 {
        let mut pk = [0u8; 32];
        pk.copy_from_slice(public_key_bytes);
        let mut sig = [0u8; 64];
        sig.copy_from_slice(signature_bytes);
        if verify_ed25519_signature(&pk, message, &sig).is_ok() {
            return Ok(());
        }
    }
    verify_p256_ecdsa_signature(public_key_bytes, message, signature_bytes)
}

/// Generates a new ephemeral X25519 keypair for key exchange
pub fn generate_x25519_keypair() -> (X25519StaticSecret, X25519PublicKey) {
    let secret = X25519StaticSecret::random_from_rng(OsRng);
    let public = X25519PublicKey::from(&secret);
    (secret, public)
}

/// Performs Diffie-Hellman to derive a raw shared secret
pub fn compute_dh_shared_secret(
    our_secret: &X25519StaticSecret,
    their_public: &X25519PublicKey,
) -> [u8; 32] {
    let shared = our_secret.diffie_hellman(their_public);
    *shared.as_bytes()
}

/// Derives a key using HKDF-SHA256
pub fn derive_hkdf_key(
    ikm: &[u8],
    salt: Option<&[u8]>,
    info: &[u8],
    out_len: usize,
) -> Result<Vec<u8>> {
    let hk = Hkdf::<Sha256>::new(salt, ikm);
    let mut okm = vec![0u8; out_len];
    hk.expand(info, &mut okm)
        .map_err(|e| AuraLockError::CryptoError(format!("HKDF expansion failed: {e}")))?;
    Ok(okm)
}

/// Encrypts plaintext using ChaCha20-Poly1305 with a 32-byte key
pub fn encrypt_chacha20(key: &[u8; 32], plaintext: &[u8]) -> Result<(Vec<u8>, [u8; 12])> {
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| AuraLockError::CryptoError(format!("Invalid key for cipher: {e}")))?;
    let nonce_bytes = generate_nonce_12();
    let nonce = ChaChaNonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| AuraLockError::CryptoError(format!("Encryption error: {e}")))?;

    Ok((ciphertext, nonce_bytes))
}

/// Decrypts ciphertext using ChaCha20-Poly1305 with a 32-byte key and 12-byte nonce
pub fn decrypt_chacha20(key: &[u8; 32], nonce_bytes: &[u8; 12], ciphertext: &[u8]) -> Result<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| AuraLockError::CryptoError(format!("Invalid key for cipher: {e}")))?;
    let nonce = ChaChaNonce::from_slice(nonce_bytes);

    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| AuraLockError::DecryptionFailed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::SigningKey;

    #[test]
    fn test_ed25519_sign_verify() {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let verifying_key = signing_key.verifying_key();

        let message = b"AuraLock Biometric Challenge Nonce 2022";
        let signature = ed25519_dalek::Signer::sign(&signing_key, message);

        let result = verify_ed25519_signature(
            verifying_key.as_bytes(),
            message,
            &signature.to_bytes(),
        );
        assert!(result.is_ok());

        // Tamper test
        let tampered_message = b"AuraLock Biometric Challenge Tampered";
        let bad_result = verify_ed25519_signature(
            verifying_key.as_bytes(),
            tampered_message,
            &signature.to_bytes(),
        );
        assert!(bad_result.is_err());
    }

    #[test]
    fn test_x25519_ecdh_and_encryption() {
        let (alice_secret, alice_public) = generate_x25519_keypair();
        let (bob_secret, bob_public) = generate_x25519_keypair();

        let alice_shared = compute_dh_shared_secret(&alice_secret, &bob_public);
        let bob_shared = compute_dh_shared_secret(&bob_secret, &alice_public);
        assert_eq!(alice_shared, bob_shared);

        let derived_key = derive_hkdf_key(&alice_shared, None, b"auralock-session-v1", 32).unwrap();
        let mut key32 = [0u8; 32];
        key32.copy_from_slice(&derived_key);

        let secret_data = b"SuperSecretPassword123!";
        let (ciphertext, nonce) = encrypt_chacha20(&key32, secret_data).unwrap();
        let decrypted = decrypt_chacha20(&key32, &nonce, &ciphertext).unwrap();

        assert_eq!(decrypted, secret_data);
    }
}
