/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use auralock_core::crypto::generate_x25519_keypair;
use auralock_core::network::NetworkService;
use auralock_core::protocol::DEFAULT_AURALOCK_PORT;
use auralock_core::state::{SessionLockState, StateManager};
use auralock_core::vault::VaultManager;
use auralock_core::web::{start_web_server, DEFAULT_AURALOCK_WEB_PORT};
use clap::{Parser, Subcommand};
use colored::*;
use std::path::PathBuf;
use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Parser)]
#[command(name = "auralock")]
#[command(author = "msm.movin")]
#[command(version)]
#[command(about = "Instant Remote Biometric Unlock & AuraDrop for Windows & Linux", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand)]
enum Commands {
    /// Start the AuraLock background daemon & Desktop Control Center UI
    Daemon {
        #[arg(short, long, default_value_t = DEFAULT_AURALOCK_PORT)]
        port: u16,
        #[arg(long, default_value_t = DEFAULT_AURALOCK_WEB_PORT)]
        web_port: u16,
        #[arg(long)]
        headless: bool,
    },
    /// Open the AuraLock Desktop Control Center UI in browser
    Gui {
        #[arg(short, long, default_value_t = DEFAULT_AURALOCK_PORT)]
        port: u16,
        #[arg(long, default_value_t = DEFAULT_AURALOCK_WEB_PORT)]
        web_port: u16,
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
    /// Completely wipe saved PC passwords, paired phone keys, and reset vault
    Reset {
        #[arg(short, long)]
        force: bool,
    },
}

fn get_default_vault_path() -> PathBuf {
    auralock_core::vault::get_default_vault_path()
}

fn launch_desktop_window(url: &str) {
    // 1. Try launching the true native standalone desktop application first
    #[cfg(unix)]
    {
        let native_candidates = [
            "auralock-desktop",
            "/usr/bin/auralock-desktop",
            "/usr/local/bin/auralock-desktop",
        ];
        for cmd in native_candidates {
            if std::process::Command::new(cmd).spawn().is_ok() {
                return;
            }
        }
    }

    #[cfg(windows)]
    {
        if std::process::Command::new("auralock-desktop.exe").spawn().is_ok() {
            return;
        }
    }

    let _ = open::that(url);
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

    let cmd = cli.command.unwrap_or(Commands::Gui {
        port: DEFAULT_AURALOCK_PORT,
        web_port: DEFAULT_AURALOCK_WEB_PORT,
    });

    let (port, web_port, is_gui) = match cmd {
        Commands::Daemon { port, web_port, headless } => (port, web_port, !headless),
        Commands::Gui { port, web_port } => (port, web_port, true),
        _ => (DEFAULT_AURALOCK_PORT, DEFAULT_AURALOCK_WEB_PORT, false),
    };

    match cmd {
        Commands::Daemon { .. } | Commands::Gui { .. } => {
            let ui_url = format!("http://127.0.0.1:{}", web_port);
            let daemon_already_running = std::net::TcpStream::connect(("127.0.0.1", web_port)).is_ok();

            if daemon_already_running {
                println!("{}", "══════════════════════════════════════════════════".cyan());
                println!("{}", "  🌟 AuraLock Desktop Control Center".bold().green());
                println!("{}", "  Remote Biometric Security & AuraDrop (msm.movin)".white());
                println!("{}", "══════════════════════════════════════════════════\n".cyan());
                println!("  ℹ️  Background service is already active.");
                if is_gui {
                    println!("  🖥️  Launching Desktop App Window: {}\n", ui_url.bold().green());
                    launch_desktop_window(&ui_url);
                }
                return Ok(());
            }

            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  🌟 AuraLock Desktop Control Center & Daemon".bold().green());
            println!("{}", "  Remote Biometric Security & AuraDrop (msm.movin)".white());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            let paired_phone = vault_mgr.load().ok().and_then(|v| v.paired_phone);
            if let Some(ref phone) = paired_phone {
                println!("  📱 Paired Device: {}", phone.device_name.bold().yellow());
            } else {
                println!("  ⚠️  {}", "No phone paired yet. Open Desktop UI to link your phone.".yellow());
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

            // Start Desktop Control Center Native App Window
            if let Err(e) = start_web_server(Arc::clone(&net_service), web_port).await {
                eprintln!("  ⚠️ Failed to start Web server: {:?}", e);
                if is_gui {
                    launch_desktop_window(&ui_url);
                }
            } else {
                println!("  🖥️  Desktop App Window: {}", ui_url.bold().green());
                if is_gui {
                    launch_desktop_window(&ui_url);
                }
            }

            println!("  🚀 Mobile network listener on port {}. Press Ctrl+C to stop.\n", port);
            net_service.run_listener().await?;
        }
        Commands::Pair { port } => {
            println!("{}", "══════════════════════════════════════════════════".cyan());
            println!("{}", "  📱 MsmConnect Device Pairing Setup".bold().green());
            println!("{}", "══════════════════════════════════════════════════\n".cyan());

            let (_, ephem_pub) = generate_x25519_keypair();
            let ephem_pub_hex = hex::encode(ephem_pub.as_bytes());

            let hostname = hostname::get()
                .map(|h| h.to_string_lossy().to_string())
                .unwrap_or_else(|_| "MsmConnect-PC".to_string());

            let state_mgr = StateManager::new(None);
            let net_service = Arc::new(NetworkService::new(
                port,
                hostname.clone(),
                format!("pc_{:08x}", rand::random::<u32>()),
                state_mgr.clone(),
                vault_mgr.clone(),
            ));

            let local_ip = auralock_core::network::get_best_local_ip();

            let qr_data = net_service.generate_qr_payload(&local_ip, &ephem_pub_hex).await;
            let qr_json = serde_json::to_string(&qr_data)?;

            println!("Scan this QR Code in the {} on your Android Phone:\n", "MsmConnect App".bold().yellow());
            qr2term::print_qr(&qr_json).unwrap_or_else(|_| {
                println!("QR Data: {}", qr_json);
            });

            println!("\n  🔑 Pairing PIN: {}", qr_data.pin.bold().green());
            println!("  📡 Listening on: {}:{}", local_ip, port);
            println!("\nWaiting for phone connection & biometric handshake...");

            let mut rx = net_service.subscribe_events();

            // Run network listener in background while waiting for pairing
            let svc_clone = Arc::clone(&net_service);
            tokio::spawn(async move {
                let _ = svc_clone.run_listener().await;
            });

            while let Ok(event) = rx.recv().await {
                if let auralock_core::network::WebEvent::PairingSuccess { phone_name, pairing_id } = event {
                    println!("\n{}", "══════════════════════════════════════════════════".green());
                    println!("  ✨ Phone {} paired successfully!", phone_name.bold().yellow());
                    println!("  🆔 Pairing ID: {}", pairing_id.green());
                    println!("{}", "══════════════════════════════════════════════════\n".green());
                    println!("  You can now start the background daemon with:");
                    println!("  {}", "auralock daemon".bold().cyan());
                    break;
                }
            }
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
        Commands::Reset { force } => {
            println!("{}", "══════════════════════════════════════════════════".red());
            println!("{}", "  🗑️  AuraLock Vault & Security Reset".bold().red());
            println!("{}", "══════════════════════════════════════════════════\n".red());

            if !force {
                print!("  ⚠️  Are you sure you want to completely erase saved passwords and paired devices? (y/N): ");
                use std::io::Write;
                let _ = std::io::stdout().flush();
                let mut input = String::new();
                let _ = std::io::stdin().read_line(&mut input);
                if !input.trim().eq_ignore_ascii_case("y") {
                    println!("  ❌ Reset aborted by user.");
                    return Ok(());
                }
            }

            // Wipe via IPC if daemon is running
            #[cfg(unix)]
            {
                let _ = auralock_core::ipc::IpcClient::reset_vault(auralock_core::ipc::DEFAULT_UNIX_SOCKET_PATH).await;
            }

            // Wipe disk vault files across all locations
            auralock_core::vault::VaultManager::wipe_all_vault_locations();

            println!("  ✅ {}", "All saved PC passwords, encryption keys, and paired phones have been completely erased!".green().bold());
            println!("  ℹ️  The next time you open AuraLock, it will start completely fresh.");
        }
    }

    Ok(())
}
