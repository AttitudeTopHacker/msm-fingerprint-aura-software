/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use auralock_core::crypto::generate_x25519_keypair;
use auralock_core::network::NetworkService;
use auralock_core::protocol::DEFAULT_AURALOCK_PORT;
use auralock_core::state::{SessionLockState, StateManager};
use auralock_core::vault::{PairedPhone, SecretBytes, VaultManager};
use clap::{Parser, Subcommand};
use colored::*;
use std::path::PathBuf;
use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Parser)]
#[command(name = "auralock")]
#[command(author = "msm.movin")]
#[command(version = "0.1.0")]
#[command(about = "Instant Remote Biometric Unlock for Windows & Linux", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Start the AuraLock background daemon
    Daemon {
        #[arg(short, long, default_value_t = DEFAULT_AURALOCK_PORT)]
        port: u16,
    },
    /// Pair a new Android phone using terminal QR code
    Pair {
        #[arg(short, long, default_value_t = DEFAULT_AURALOCK_PORT)]
        port: u16,
    },
    /// Test biometric unlock handshake
    TestUnlock,
    /// Display device pairing & vault status
    Status,
    /// Simulate a virtual phone client for end-to-end testing
    MockPhone {
        #[arg(long, default_value = "127.0.0.1")]
        host: String,
        #[arg(short, long, default_value_t = DEFAULT_AURALOCK_PORT)]
        port: u16,
        #[arg(short, long)]
        pin: Option<String>,
    },
}

fn get_default_vault_path() -> PathBuf {
    let mut path = dirs_next::config_dir().unwrap_or_else(|| PathBuf::from("."));
    path.push("auralock");
    path.push("vault.json");
    path
}

mod dirs_next {
    use std::path::PathBuf;
    pub fn config_dir() -> Option<PathBuf> {
        #[cfg(unix)]
        {
            std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config"))
        }
        #[cfg(windows)]
        {
            std::env::var_os("APPDATA").map(PathBuf::from)
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let cli = Cli::parse();
    let vault_path = get_default_vault_path();
    let vault_mgr = Arc::new(VaultManager::new(&vault_path));

    match cli.command {
        Commands::Daemon { port } => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  🌟 AuraLock Core Daemon (msm.movin)".bold().green());
            println!("{}", "  Instant Remote Biometric Unlock for Windows & Linux".white());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            let paired_phone = vault_mgr.load().ok().and_then(|v| v.paired_phone);
            if let Some(ref phone) = paired_phone {
                println!("  📱 Paired Device: {}", phone.device_name.bold().yellow());
            } else {
                println!("  ⚠️  {}", "No phone paired yet. Run `auralock pair` to link your device.".yellow());
            }

            let state_mgr = StateManager::new(paired_phone);
            let hostname = hostname::get()
                .map(|h| h.to_string_lossy().to_string())
                .unwrap_or_else(|_| "AuraLock-PC".to_string());

            let net_service = Arc::new(NetworkService::new(
                port,
                hostname.clone(),
                format!("pc_{:08x}", rand::random::<u32>()),
                state_mgr.clone(),
                vault_mgr,
            ));

            #[cfg(unix)]
            {
                let ipc_server = Arc::new(auralock_core::ipc::IpcServer::new(
                    state_mgr.clone(),
                    Arc::clone(&net_service),
                    hostname.clone(),
                ));
                tokio::spawn(async move {
                    if let Err(e) = ipc_server.run_unix_socket(auralock_core::ipc::DEFAULT_UNIX_SOCKET_PATH).await {
                        tracing::error!("IPC Server error: {:?}", e);
                    }
                });
                println!("  🔌 Linux PAM IPC socket active at {}", auralock_core::ipc::DEFAULT_UNIX_SOCKET_PATH.green());
            }

            println!("  🚀 Network daemon running on port {}. Press Ctrl+C to stop.\n", port);
            net_service.run_listener().await?;
        }
        Commands::Pair { port } => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  📱 AuraLock Device Pairing Setup".bold().green());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            let (_, ephem_pub) = generate_x25519_keypair();
            let ephem_pub_hex = hex::encode(ephem_pub.as_bytes());

            let hostname = hostname::get()
                .map(|h| h.to_string_lossy().to_string())
                .unwrap_or_else(|_| "AuraLock-PC".to_string());

            let state_mgr = StateManager::new(None);
            let net_service = Arc::new(NetworkService::new(
                port,
                hostname.clone(),
                format!("pc_{:08x}", rand::random::<u32>()),
                state_mgr.clone(),
                vault_mgr.clone(),
            ));

            let local_ip = local_ip_address::local_ip()
                .map(|ip| ip.to_string())
                .unwrap_or_else(|_| "127.0.0.1".to_string());

            let qr_data = net_service.generate_qr_payload(&local_ip, &ephem_pub_hex).await;
            let qr_json = serde_json::to_string(&qr_data)?;

            println!("Scan this QR Code in the {} on your Android Phone:\n", "AuraLock App".bold().yellow());
            qr2term::print_qr(&qr_json).unwrap_or_else(|_| {
                println!("QR Data: {}", qr_json);
            });

            println!("\n  🔑 Pairing PIN: {}", qr_data.pin.bold().green());
            println!("  📡 Listening on: {}:{}", local_ip, port);
            println!("\nWaiting for phone connection...");

            // Run network listener in background while waiting for pairing
            let svc_clone = Arc::clone(&net_service);
            tokio::spawn(async move {
                let _ = svc_clone.run_listener().await;
            });

            // Wait for user interaction or save password
            println!("\nEnter your PC Login Password (saved securely into Split-Key Vault):");
            let password = rpassword::read_password()?;

            // Save test vault entry
            let dummy_phone = PairedPhone {
                device_name: "Android Device".to_string(),
                public_key_hex: hex::encode([0u8; 32]),
                pairing_id: format!("pair_{:08x}", rand::random::<u32>()),
                paired_at_epoch: chrono::Utc::now().timestamp(),
            };

            let dummy_secret = [1u8; 32];
            vault_mgr.save_new_vault(dummy_phone, &dummy_secret, &SecretBytes::new(password.into_bytes()))?;

            println!("\n{}", "✅ Vault encrypted and saved successfully!".bold().green());
        }
        Commands::Status => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  🔍 AuraLock System Status".bold().green());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            if vault_mgr.exists() {
                match vault_mgr.load() {
                    Ok(vault) => {
                        println!("  Status:       {}", "Paired & Ready".green().bold());
                        if let Some(phone) = vault.paired_phone {
                            println!("  Phone:        {}", phone.device_name.yellow());
                            println!("  Pairing ID:   {}", phone.pairing_id);
                            println!("  Public Key:   {}...", &phone.public_key_hex[..16]);
                        }
                    }
                    Err(e) => println!("  Vault Error:  {}", e.to_string().red()),
                }
            } else {
                println!("  Status:       {}", "Unpaired (No vault found)".yellow());
            }
        }
        Commands::TestUnlock => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  🧪 AuraLock Biometric Test Trigger".bold().green());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            let state_mgr = StateManager::new(None);
            state_mgr.set_lock_state(SessionLockState::Locked).await;
            let (challenge, _) = state_mgr.create_challenge("Salman-PC").await?;

            println!("  Created Challenge ID: {}", challenge.challenge_id.bold().yellow());
            println!("  Nonce:                {}", challenge.nonce_hex);
            println!("  Timestamp:            {}", challenge.timestamp_epoch);
            println!("\n  Waiting for biometric signature from phone...");
        }
        Commands::MockPhone { host, port, pin } => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  📱 AuraLock Virtual Phone Simulator (msm.movin)".bold().green());
            println!("{}", "  Simulating Android Biometric KeyStore Client".white());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            use auralock_core::protocol::{PairingRequest, ProtocolMessage, UnlockResponse};
            use ed25519_dalek::{Signer, SigningKey};
            use rand::rngs::OsRng;
            use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
            use tokio::net::TcpStream;

            let mut csprng = OsRng;
            let signing_key = SigningKey::generate(&mut csprng);
            let verifying_key = signing_key.verifying_key();
            let pub_hex = hex::encode(verifying_key.as_bytes());

            println!("  🔑 Generated Virtual KeyStore PubKey: {}", pub_hex.bold().yellow());
            println!("  🌐 Connecting to AuraLock PC at {}:{}...", host, port);

            let stream = match TcpStream::connect((host.as_str(), port)).await {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("  ❌ Failed to connect: {}. Is 'auralock daemon' running?", e);
                    return Ok(());
                }
            };
            println!("  ✅ Connected to PC over Wi-Fi socket!\n");

            let (reader, mut writer) = stream.into_split();
            let mut buf_reader = BufReader::new(reader);

            if let Some(pairing_pin) = pin {
                println!("  📤 Sending Pairing Request with PIN: {}...", pairing_pin.bold().green());
                let req = ProtocolMessage::PairingReq(PairingRequest {
                    phone_name: "Pixel 8 Pro (Simulated)".to_string(),
                    phone_ed25519_pub_hex: pub_hex.clone(),
                    phone_ephem_pub_hex: pub_hex.clone(),
                    pin: pairing_pin,
                });
                let json = serde_json::to_string(&req)?;
                writer.write_all(format!("{}\n", json).as_bytes()).await?;
            }

            println!("  🎧 Listening for PC Lockscreen Challenge (touch fingerprint to unlock)...");

            let mut line = String::new();
            while buf_reader.read_line(&mut line).await? > 0 {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }

                if let Ok(msg) = serde_json::from_str::<ProtocolMessage>(trimmed) {
                    match msg {
                        ProtocolMessage::PairingResp(resp) => {
                            if resp.success {
                                println!("  🎉 {}", "Pairing Approved by PC!".bold().green());
                            } else {
                                println!("  ❌ Pairing Failed: {}", resp.message.red());
                            }
                        }
                        ProtocolMessage::Challenge(challenge) => {
                            println!("\n  🚨 {}", "PC LOCKSCREEN CHALLENGE RECEIVED!".bold().yellow());
                            println!("     Challenge ID: {}", challenge.challenge_id.cyan());
                            println!("     PC Name:      {}", challenge.pc_name);
                            println!("     Timestamp:    {}", challenge.timestamp_epoch);
                            println!("  👉 [Simulating Live Android Fingerprint Sensor Tap]...");

                            let signable_payload = challenge.get_signable_payload();
                            let signature = signing_key.sign(&signable_payload);
                            let sig_hex = hex::encode(signature.to_bytes());

                            let response_msg = ProtocolMessage::Response(UnlockResponse {
                                challenge_id: challenge.challenge_id,
                                signature_hex: sig_hex,
                                biometric_type: "fingerprint".into(),
                                timestamp_epoch: chrono::Utc::now().timestamp(),
                            });

                            let json = serde_json::to_string(&response_msg)?;
                            writer.write_all(format!("{}\n", json).as_bytes()).await?;
                            println!("  📤 Sent Verified Biometric Signature to PC!");
                        }
                        ProtocolMessage::Result(result) => {
                            if result.success {
                                println!("\n  🔓 {}", "PC UNLOCK APPROVED & CONFIRMED! 🚀".bold().green());
                            } else {
                                println!("\n  ❌ Unlock Denied: {}", result.message.red());
                            }
                        }
                        _ => {}
                    }
                }
                line.clear();
            }
        }
    }

    Ok(())
}
