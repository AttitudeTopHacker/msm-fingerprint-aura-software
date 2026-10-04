/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

pub mod ui;

use crate::crypto::generate_x25519_keypair;
use crate::error::Result;
use crate::network::{get_default_downloads_dir, AuraDropReceivedFile, NetworkService};
use crate::vault::{PairedPhone, SecretBytes};
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Multipart, Path, State,
    },
    http::StatusCode,
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tower_http::cors::{Any, CorsLayer};
use tracing::{error, info};

pub const DEFAULT_AURALOCK_WEB_PORT: u16 = 47891;

#[derive(Clone)]
pub struct AppState {
    pub net_service: Arc<NetworkService>,
}

#[derive(Serialize)]
pub struct StatusResponse {
    pub pc_name: String,
    pub pc_id: String,
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
pub struct QrResponse {
    pub pin: String,
    pub qr_data: serde_json::Value,
}

#[derive(Deserialize)]
pub struct SavePasswordRequest {
    pub password: String,
}

#[derive(Deserialize)]
pub struct UnpairRequest {
    pub pin: String,
}

#[derive(Deserialize)]
pub struct ToggleTransferRequest {
    pub enabled: bool,
}

#[derive(Deserialize)]
pub struct AuthTransferRequest {
    pub pin: String,
}

#[derive(Deserialize)]
pub struct SendTextRequest {
    pub text: String,
}

#[derive(Serialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
}

pub async fn start_web_server(net_service: Arc<NetworkService>, port: u16) -> Result<()> {
    let state = AppState { net_service };

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        .route("/", get(serve_dashboard))
        .route("/api/status", get(get_status))
        .route("/api/qr", get(get_qr))
        .route("/api/pair/refresh", post(refresh_pairing))
        .route("/api/unlock/test", post(test_unlock))
        .route("/api/lock", post(lock_endpoint))
        .route("/api/unlock", post(unlock_endpoint))
        .route("/api/vault/password", post(save_password))
        .route("/api/vault/unpair", post(force_unpair_endpoint))
        .route("/api/unpair/initiate", post(initiate_unpair_endpoint))
        .route("/api/unpair/confirm", post(confirm_unpair_endpoint))
        .route("/api/unpair/force", post(force_unpair_endpoint))
        .route("/api/auradrop/toggle", post(toggle_transfer_endpoint))
        .route("/api/auradrop/auth", post(auth_transfer_endpoint))
        .route("/api/auradrop/send-file", post(send_file))
        .route("/api/auradrop/send-text", post(send_text))
        .route("/api/auradrop/files", get(get_received_files))
        .route("/api/auradrop/open-folder", post(open_folder))
        .route("/api/auradrop/download/:filename", get(download_file))
        .route("/api/events", get(ws_handler))
        .layer(cors)
        .with_state(state);

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], port));
    info!("AuraLock Desktop Control Center UI running at http://127.0.0.1:{}", port);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tokio::spawn(async move {
        if let Err(e) = axum::serve(listener, app).await {
            error!("Web server error: {:?}", e);
        }
    });

    Ok(())
}

async fn serve_dashboard() -> Html<&'static str> {
    Html(ui::DASHBOARD_HTML)
}

async fn get_status(State(state): State<AppState>) -> Json<StatusResponse> {
    let vault = state.net_service.vault_mgr.load().ok();
    let is_paired = state.net_service.vault_mgr.is_paired();
    let paired_phone_name = vault
        .as_ref()
        .and_then(|v| v.paired_phone.as_ref().map(|p| p.device_name.clone()));

    let is_connected = state.net_service.is_phone_connected().await;
    let connected_phone = state.net_service.get_connected_phone_name().await;
    let transfer_enabled = *state.net_service.transfer_enabled.lock().await;
    let transfer_pin = state.net_service.transfer_session_pin.lock().await.clone();
    let transfer_authorized = *state.net_service.transfer_authorized.lock().await;

    Json(StatusResponse {
        pc_name: state.net_service.pc_name.clone(),
        pc_id: state.net_service.pc_id.clone(),
        port: state.net_service.port,
        is_phone_connected: is_connected,
        is_paired,
        connected_phone,
        paired_phone: paired_phone_name,
        vault_exists: state.net_service.vault_mgr.exists(),
        transfer_enabled,
        transfer_pin,
        transfer_authorized,
        is_bluetooth_active: state.net_service.is_pc_bluetooth_active(),
    })
}

async fn get_qr(State(state): State<AppState>) -> Json<QrResponse> {
    let local_ip = crate::network::get_best_local_ip();

    let (_, ephem_pub) = generate_x25519_keypair();
    let ephem_pub_hex = hex::encode(ephem_pub.as_bytes());

    let qr_data = state
        .net_service
        .get_or_create_qr_payload(&local_ip, &ephem_pub_hex)
        .await;

    Json(QrResponse {
        pin: qr_data.pin.clone(),
        qr_data: serde_json::to_value(qr_data).unwrap_or_default(),
    })
}

async fn refresh_pairing(State(state): State<AppState>) -> Json<ApiResponse> {
    let local_ip = crate::network::get_best_local_ip();

    let (_, ephem_pub) = generate_x25519_keypair();
    let ephem_pub_hex = hex::encode(ephem_pub.as_bytes());

    state
        .net_service
        .generate_qr_payload(&local_ip, &ephem_pub_hex)
        .await;

    Json(ApiResponse {
        success: true,
        message: "Pairing session regenerated".into(),
    })
}

async fn test_unlock(State(state): State<AppState>) -> Json<ApiResponse> {
    match state
        .net_service
        .state_mgr
        .create_challenge(&state.net_service.pc_name)
        .await
    {
        Ok((challenge, _)) => match state.net_service.push_challenge(challenge).await {
            Ok(_) => Json(ApiResponse {
                success: true,
                message: "Biometric challenge pushed to phone".into(),
            }),
            Err(e) => Json(ApiResponse {
                success: false,
                message: e.to_string(),
            }),
        },
        Err(e) => Json(ApiResponse {
            success: false,
            message: format!("Failed to create challenge: {e}"),
        }),
    }
}

async fn lock_endpoint(State(state): State<AppState>) -> Json<ApiResponse> {
    state.net_service.state_mgr.set_lock_state(crate::state::SessionLockState::Locked).await;
    crate::network::lock_pc_screen();
    if let Some(tx) = &*state.net_service.active_client.lock().await {
        let _ = tx.send(crate::protocol::ProtocolMessage::LockStateChanged { is_locked: true }).await;
    }
    Json(ApiResponse {
        success: true,
        message: "PC Screen Locked successfully".into(),
    })
}

async fn unlock_endpoint(State(state): State<AppState>) -> Json<ApiResponse> {
    state.net_service.state_mgr.set_lock_state(crate::state::SessionLockState::Unlocked).await;
    crate::network::unlock_pc_screen();
    if let Some(tx) = &*state.net_service.active_client.lock().await {
        let _ = tx.send(crate::protocol::ProtocolMessage::LockStateChanged { is_locked: false }).await;
    }
    Json(ApiResponse {
        success: true,
        message: "PC Screen Unlocked successfully".into(),
    })
}

async fn save_password(
    State(state): State<AppState>,
    Json(payload): Json<SavePasswordRequest>,
) -> Json<ApiResponse> {
    let dummy_phone = PairedPhone {
        device_name: "Android Phone".to_string(),
        public_key_hex: hex::encode([0u8; 32]),
        pairing_id: format!("pair_{:08x}", rand::random::<u32>()),
        paired_at_epoch: chrono::Utc::now().timestamp(),
    };

    let dummy_secret = [1u8; 32];
    let secret_bytes = SecretBytes::new(payload.password.into_bytes());

    match state
        .net_service
        .vault_mgr
        .save_new_vault(dummy_phone, &dummy_secret, &secret_bytes)
    {
        Ok(_) => Json(ApiResponse {
            success: true,
            message: "Password encrypted and saved into split-key vault".into(),
        }),
        Err(e) => Json(ApiResponse {
            success: false,
            message: format!("Vault error: {e}"),
        }),
    }
}

async fn initiate_unpair_endpoint(
    State(state): State<AppState>,
) -> Json<serde_json::Value> {
    let (connected, _pin) = state.net_service.initiate_unpair_from_pc().await;
    Json(serde_json::json!({
        "success": true,
        "is_connected": connected,
        "pin_sent": connected,
        "message": if connected {
            "A 6-digit verification PIN has been sent to your phone. Enter it here to unpair."
        } else {
            "Paired phone is currently offline. You can unpair directly on this PC."
        }
    }))
}

async fn confirm_unpair_endpoint(
    State(state): State<AppState>,
    Json(payload): Json<UnpairRequest>,
) -> Json<ApiResponse> {
    let ok = state.net_service.verify_and_confirm_pc_unpair(&payload.pin).await;
    Json(ApiResponse {
        success: ok,
        message: if ok {
            "Device successfully unpaired".into()
        } else {
            "Invalid PIN entered. Please enter the 6-digit code shown on your phone.".into()
        },
    })
}

async fn force_unpair_endpoint(
    State(state): State<AppState>,
) -> Json<ApiResponse> {
    state.net_service.force_unpair_offline().await;
    Json(ApiResponse {
        success: true,
        message: "Device pairing removed locally from PC".into(),
    })
}

async fn toggle_transfer_endpoint(
    State(state): State<AppState>,
    Json(payload): Json<ToggleTransferRequest>,
) -> Json<serde_json::Value> {
    let (enabled, pin) = state.net_service.toggle_transfer(payload.enabled).await;
    Json(serde_json::json!({
        "success": true,
        "enabled": enabled,
        "pin": pin,
        "message": if enabled { "Transfer channel opened" } else { "Transfer channel closed" }
    }))
}

async fn auth_transfer_endpoint(
    State(state): State<AppState>,
    Json(payload): Json<AuthTransferRequest>,
) -> Json<ApiResponse> {
    let ok = state.net_service.authorize_transfer(&payload.pin).await;
    Json(ApiResponse {
        success: ok,
        message: if ok {
            "Transfer channel authorized".into()
        } else {
            "Invalid 4-digit transfer PIN".into()
        },
    })
}

async fn send_file(
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> (StatusCode, Json<ApiResponse>) {
    while let Ok(Some(field)) = multipart.next_field().await {
        let file_name = field
            .file_name()
            .unwrap_or("unnamed_file.bin")
            .to_string();
        let mime_type = field
            .content_type()
            .unwrap_or("application/octet-stream")
            .to_string();

        match field.bytes().await {
            Ok(bytes) => {
                match state
                    .net_service
                    .send_file_to_phone(file_name, bytes.to_vec(), mime_type)
                    .await
                {
                    Ok(id) => {
                        return (
                            StatusCode::OK,
                            Json(ApiResponse {
                                success: true,
                                message: format!("File sent with ID {id}"),
                            }),
                        );
                    }
                    Err(e) => {
                        return (
                            StatusCode::BAD_REQUEST,
                            Json(ApiResponse {
                                success: false,
                                message: e.to_string(),
                            }),
                        );
                    }
                }
            }
            Err(e) => {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(ApiResponse {
                        success: false,
                        message: format!("Failed to read file bytes: {e}"),
                    }),
                );
            }
        }
    }

    (
        StatusCode::BAD_REQUEST,
        Json(ApiResponse {
            success: false,
            message: "No file provided in multipart body".into(),
        }),
    )
}

async fn send_text(
    State(state): State<AppState>,
    Json(payload): Json<SendTextRequest>,
) -> Json<ApiResponse> {
    match state.net_service.send_clipboard_to_phone(payload.text).await {
        Ok(_) => Json(ApiResponse {
            success: true,
            message: "Text sent to phone".into(),
        }),
        Err(e) => Json(ApiResponse {
            success: false,
            message: e.to_string(),
        }),
    }
}

async fn get_received_files(State(state): State<AppState>) -> Json<Vec<AuraDropReceivedFile>> {
    let files = state.net_service.received_files.lock().await.clone();
    Json(files)
}

async fn open_folder() -> Json<ApiResponse> {
    let p = get_default_downloads_dir();
    let _ = open::that(&p);
    Json(ApiResponse {
        success: true,
        message: format!("Opened folder: {}", p.display()),
    })
}

async fn download_file(Path(filename): Path<String>) -> Response {
    let p = get_default_downloads_dir().join(&filename);
    if let Ok(bytes) = tokio::fs::read(&p).await {
        let mime = "application/octet-stream";
        (
            [
                ("Content-Type", mime),
                (
                    "Content-Disposition",
                    &format!("attachment; filename=\"{filename}\""),
                ),
            ],
            bytes,
        )
            .into_response()
    } else {
        (StatusCode::NOT_FOUND, "File not found").into_response()
    }
}

async fn ws_handler(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(|socket| handle_websocket(socket, state))
}

async fn handle_websocket(mut socket: WebSocket, state: AppState) {
    let mut rx = state.net_service.subscribe_events();
    while let Ok(event) = rx.recv().await {
        if let Ok(json) = serde_json::to_string(&event) {
            if socket.send(Message::Text(json)).await.is_err() {
                break;
            }
        }
    }
}
