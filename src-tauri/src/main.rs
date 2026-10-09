// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use auralock_core::crypto::generate_x25519_keypair;
use auralock_core::network::{get_default_downloads_dir, AuraDropReceivedFile, NetworkService};
use auralock_core::protocol::DEFAULT_AURALOCK_PORT;
use auralock_core::state::StateManager;
use auralock_core::vault::{PairedPhone, SecretBytes, VaultManager};
use auralock_core::web::{start_web_server, DEFAULT_AURALOCK_WEB_PORT};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;
use tauri::State;

struct AppStateWrapper {
    net_service: Arc<NetworkService>,
    vault_mgr: Arc<VaultManager>,
}

fn get_default_vault_path() -> PathBuf {
    auralock_core::vault::get_default_vault_path()
}

#[derive(Serialize)]
pub struct DaemonStatus {
    pub pc_name: String,
    pub port: u16,
    pub is_phone_connected: bool,
    pub is_paired: bool,
    pub connected_phone: Option<String>,
    pub paired_phone: Option<String>,
    pub vault_exists: bool,
    pub transfer_enabled: bool,
    pub transfer_pin: String,
    pub transfer_authorized: bool,
    pub is_bluetooth_active: bool,
}

#[derive(Serialize)]
pub struct QrDataResponse {
    pub pin: String,
    pub qr_data: serde_json::Value,
}

#[derive(Serialize, Deserialize)]
pub struct CommandResult {
    pub success: bool,
    pub message: String,
}

#[derive(Serialize)]
pub struct TransferToggleResult {
    pub enabled: bool,
    pub pin: String,
    pub qr_data: serde_json::Value,
}

#[tauri::command]
async fn get_daemon_status(state: State<'_, AppStateWrapper>) -> Result<DaemonStatus, String> {
    let vault = state.vault_mgr.load().ok();
    let is_paired = state.vault_mgr.is_paired();
    let paired_phone = vault.as_ref().and_then(|v| v.paired_phone.as_ref().map(|p| p.device_name.clone()));
    let is_connected = state.net_service.is_phone_connected().await;
    let connected_phone = state.net_service.get_connected_phone_name().await;
    let transfer_enabled = *state.net_service.transfer_enabled.lock().await;
    let transfer_pin = state.net_service.transfer_session_pin.lock().await.clone();
    let transfer_authorized = *state.net_service.transfer_authorized.lock().await;

    Ok(DaemonStatus {
        pc_name: state.net_service.pc_name.clone(),
        port: state.net_service.port,
        is_phone_connected: is_connected,
        is_paired,
        connected_phone,
        paired_phone,
        vault_exists: state.vault_mgr.exists(),
        transfer_enabled,
        transfer_pin,
        transfer_authorized,
        is_bluetooth_active: state.net_service.is_pc_bluetooth_active(),
    })
}

#[tauri::command]
async fn get_pairing_qr(state: State<'_, AppStateWrapper>) -> Result<QrDataResponse, String> {
    let local_ip = auralock_core::network::get_best_local_ip();

    let (_, ephem_pub) = generate_x25519_keypair();
    let ephem_pub_hex = hex::encode(ephem_pub.as_bytes());

    let qr_data = state
        .net_service
        .get_or_create_qr_payload(&local_ip, &ephem_pub_hex)
        .await;

    Ok(QrDataResponse {
        pin: qr_data.pin.clone(),
        qr_data: serde_json::to_value(qr_data).unwrap_or_default(),
    })
}

#[tauri::command]
async fn trigger_test_unlock(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    match state.net_service.state_mgr.create_challenge(&state.net_service.pc_name).await {
        Ok((challenge, _)) => match state.net_service.push_challenge(challenge).await {
            Ok(_) => Ok(CommandResult {
                success: true,
                message: "Biometric challenge pushed to phone".into(),
            }),
            Err(e) => Ok(CommandResult {
                success: false,
                message: e.to_string(),
            }),
        },
        Err(e) => Ok(CommandResult {
            success: false,
            message: format!("Failed to generate challenge: {e}"),
        }),
    }
}

#[tauri::command]
async fn cancel_challenge(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    state.net_service.cancel_challenge().await;
    Ok(CommandResult {
        success: true,
        message: "Biometric challenge cancelled".into(),
    })
}

#[tauri::command]
async fn lock_pc(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    state.net_service.state_mgr.set_lock_state(auralock_core::state::SessionLockState::Locked).await;
    auralock_core::network::lock_pc_screen();
    if let Some(tx) = &*state.net_service.active_client.lock().await {
        let _ = tx.send(auralock_core::protocol::ProtocolMessage::LockStateChanged { is_locked: true }).await;
    }
    Ok(CommandResult {
        success: true,
        message: "PC Screen Locked".into(),
    })
}

#[tauri::command]
async fn unlock_pc(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    state.net_service.state_mgr.set_lock_state(auralock_core::state::SessionLockState::Unlocked).await;
    auralock_core::network::unlock_pc_screen();
    if let Some(tx) = &*state.net_service.active_client.lock().await {
        let _ = tx.send(auralock_core::protocol::ProtocolMessage::LockStateChanged { is_locked: false }).await;
    }
    Ok(CommandResult {
        success: true,
        message: "PC Screen Unlocked".into(),
    })
}

#[tauri::command]
async fn save_vault_password(password: String, state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    let paired_phone = state.vault_mgr.load().ok().and_then(|v| v.paired_phone);
    let paired_phone = match paired_phone {
        Some(p) => p,
        None => {
            state.net_service.state_mgr.get_paired_phone().await.unwrap_or_else(|| PairedPhone {
                device_name: "Android Phone".to_string(),
                public_key_hex: hex::encode([0u8; 32]),
                pairing_id: format!("pair_{:08x}", rand::random::<u32>()),
                paired_at_epoch: chrono::Utc::now().timestamp(),
            })
        }
    };

    let dummy_secret = [1u8; 32];
    let secret_bytes = SecretBytes::new(password.into_bytes());

    match state.vault_mgr.save_new_vault(paired_phone, &dummy_secret, &secret_bytes) {
        Ok(_) => Ok(CommandResult {
            success: true,
            message: "PC Password encrypted into local Split-Key Vault".into(),
        }),
        Err(e) => Ok(CommandResult {
            success: false,
            message: format!("Vault error: {e}"),
        }),
    }
}

#[derive(Serialize)]
pub struct UnpairInitiateResponse {
    pub is_connected: bool,
    pub pin_sent: bool,
    pub message: String,
}

#[tauri::command]
async fn initiate_pc_unpair(state: State<'_, AppStateWrapper>) -> Result<UnpairInitiateResponse, String> {
    let (connected, _pin_opt) = state.net_service.initiate_unpair_from_pc().await;
    if connected {
        Ok(UnpairInitiateResponse {
            is_connected: true,
            pin_sent: true,
            message: "A 6-digit verification PIN has been sent to your phone. Enter it here to unpair.".into(),
        })
    } else {
        Ok(UnpairInitiateResponse {
            is_connected: false,
            pin_sent: false,
            message: "Paired phone is currently offline. You can unpair directly on this PC.".into(),
        })
    }
}

#[tauri::command]
async fn confirm_pc_unpair(pin: String, state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    let ok = state.net_service.verify_and_confirm_pc_unpair(&pin).await;
    if ok {
        Ok(CommandResult {
            success: true,
            message: "Device successfully unpaired".into(),
        })
    } else {
        Ok(CommandResult {
            success: false,
            message: "Invalid PIN entered. Please enter the 6-digit code shown on your phone.".into(),
        })
    }
}

#[tauri::command]
async fn force_unpair_offline(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    state.net_service.force_unpair_offline().await;
    Ok(CommandResult {
        success: true,
        message: "Device pairing removed locally from PC".into(),
    })
}

#[tauri::command]
async fn unpair_device(state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    state.net_service.direct_unpair().await;
    Ok(CommandResult {
        success: true,
        message: "Device successfully unpaired".into(),
    })
}

#[tauri::command]
async fn toggle_transfer_channel(enabled: bool, state: State<'_, AppStateWrapper>) -> Result<TransferToggleResult, String> {
    let (en, pin) = state.net_service.toggle_transfer(enabled).await;
    let local_ip = auralock_core::network::get_best_local_ip();

    let qr_payload = serde_json::json!({
        "v": 1,
        "type": "auradrop",
        "pc_name": state.net_service.pc_name,
        "ip": local_ip,
        "port": state.net_service.port,
        "pin": pin
    });

    Ok(TransferToggleResult {
        enabled: en,
        pin,
        qr_data: qr_payload,
    })
}

#[tauri::command]
async fn authorize_transfer_pin(pin: String, state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    let ok = state.net_service.authorize_transfer(&pin).await;
    Ok(CommandResult {
        success: ok,
        message: if ok { "Transfer channel authorized".into() } else { "Invalid 4-digit transfer PIN".into() },
    })
}

#[tauri::command]
async fn send_auradrop_text(text: String, state: State<'_, AppStateWrapper>) -> Result<CommandResult, String> {
    match state.net_service.send_clipboard_to_phone(text).await {
        Ok(_) => Ok(CommandResult {
            success: true,
            message: "Text sent to phone".into(),
        }),
        Err(e) => Ok(CommandResult {
            success: false,
            message: e.to_string(),
        }),
    }
}

#[tauri::command]
async fn get_latest_clipboard(state: State<'_, AppStateWrapper>) -> Result<Option<auralock_core::protocol::ClipboardSync>, String> {
    let clip = state.net_service.latest_clipboard.lock().await.clone();
    Ok(clip)
}

#[tauri::command]
async fn get_received_files(state: State<'_, AppStateWrapper>) -> Result<Vec<AuraDropReceivedFile>, String> {
    let files = state.net_service.received_files.lock().await.clone();
    Ok(files)
}

#[tauri::command]
async fn open_downloads_folder() -> Result<CommandResult, String> {
    let p = get_default_downloads_dir();
    let _ = open::that(&p);
    Ok(CommandResult {
        success: true,
        message: format!("Opened {}", p.display()),
    })
}

fn main() {
    let vault_path = get_default_vault_path();
    let vault_mgr = Arc::new(VaultManager::new(&vault_path));

    let paired_phone = vault_mgr.load().ok().and_then(|v| v.paired_phone);
    let state_mgr = StateManager::new(paired_phone);
    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "AuraLock-PC".to_string());

    let net_service = Arc::new(NetworkService::new(
        DEFAULT_AURALOCK_PORT,
        hostname.clone(),
        format!("pc_{:08x}", rand::random::<u32>()),
        state_mgr.clone(),
        vault_mgr.clone(),
    ));

    let is_daemon_already_running = std::net::TcpStream::connect(("127.0.0.1", DEFAULT_AURALOCK_WEB_PORT)).is_ok();

    if !is_daemon_already_running {
        // Start PAM IPC Daemon on Linux
        #[cfg(unix)]
        {
            let ipc_server = Arc::new(auralock_core::ipc::IpcServer::new(
                state_mgr.clone(),
                Arc::clone(&net_service),
                hostname.clone(),
            ));
            tauri::async_runtime::spawn(async move {
                if let Err(e) = ipc_server.run_unix_socket(auralock_core::ipc::DEFAULT_UNIX_SOCKET_PATH).await {
                    tracing::error!("PAM IPC socket error: {:?}", e);
                }
            });
        }

        // Start background Wi-Fi network listener
        let svc_clone = Arc::clone(&net_service);
        tauri::async_runtime::spawn(async move {
            let _ = svc_clone.run_listener().await;
        });

        // Start local web API service (for streaming file uploads & websockets)
        let web_svc_clone = Arc::clone(&net_service);
        tauri::async_runtime::spawn(async move {
            let _ = start_web_server(web_svc_clone, DEFAULT_AURALOCK_WEB_PORT).await;
        });
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppStateWrapper {
            net_service: Arc::clone(&net_service),
            vault_mgr: Arc::clone(&vault_mgr),
        })
        .invoke_handler(tauri::generate_handler![
            get_daemon_status,
            get_pairing_qr,
            trigger_test_unlock,
            cancel_challenge,
            lock_pc,
            unlock_pc,
            save_vault_password,
            unpair_device,
            initiate_pc_unpair,
            confirm_pc_unpair,
            force_unpair_offline,
            toggle_transfer_channel,
            authorize_transfer_pin,
            send_auradrop_text,
            get_latest_clipboard,
            get_received_files,
            open_downloads_folder
        ])
        .run(tauri::generate_context!())
        .expect("error while running AuraLock Tauri application");
}
