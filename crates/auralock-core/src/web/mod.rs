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
        Multipart, Path, Request, State,
    },
    http::StatusCode,
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use tower_http::cors::CorsLayer;
use tracing::{error, info, warn};

pub const DEFAULT_AURALOCK_WEB_PORT: u16 = 47891;

static WEB_AUTH_TOKEN: OnceLock<String> = OnceLock::new();

/// Retrieve or initialize a cryptographically random 256-bit API session token
pub fn get_or_create_web_auth_token() -> &'static str {
    WEB_AUTH_TOKEN.get_or_init(|| {
        let mut bytes = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut bytes);
        let token = hex::encode(bytes);
        save_auth_token_to_disk(&token);
        token
    })
}

fn get_auth_token_file_path() -> Option<PathBuf> {
    if let Some(runtime_dir) = std::env::var_os("XDG_RUNTIME_DIR") {
        return Some(PathBuf::from(runtime_dir).join("msmconnect_auth_token"));
    }
    #[cfg(unix)]
    {
        if let Some(home) = std::env::var_os("HOME") {
            let dir = PathBuf::from(home).join(".config").join("auralock");
            let _ = std::fs::create_dir_all(&dir);
            return Some(dir.join(".auth_token"));
        }
    }
    #[cfg(windows)]
    {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            let dir = PathBuf::from(appdata).join("auralock");
            let _ = std::fs::create_dir_all(&dir);
            return Some(dir.join(".auth_token"));
        }
    }
    None
}

fn save_auth_token_to_disk(token: &str) {
    if let Some(path) = get_auth_token_file_path() {
        #[cfg(unix)]
        {
            use std::fs::OpenOptions;
            use std::io::Write;
            use std::os::unix::fs::OpenOptionsExt;
            if let Ok(mut file) = OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&path)
            {
                let _ = file.write_all(token.as_bytes());
            }
        }
        #[cfg(not(unix))]
        {
            let _ = std::fs::write(&path, token.as_bytes());
        }
    }
}

pub fn read_auth_token_from_disk() -> Option<String> {
    if let Some(path) = get_auth_token_file_path() {
        if let Ok(content) = std::fs::read_to_string(&path) {
            let tok = content.trim().to_string();
            if !tok.is_empty() {
                return Some(tok);
            }
        }
    }
    None
}

/// Constant-time slice comparison to prevent timing attacks on token verification
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

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TicketScope {
    Download(String),
    WebSocketStream,
}

pub struct TicketEntry {
    pub scope: TicketScope,
    pub expires_at: std::time::Instant,
}

static TICKET_STORE: OnceLock<tokio::sync::Mutex<std::collections::HashMap<String, TicketEntry>>> = OnceLock::new();

fn get_ticket_store() -> &'static tokio::sync::Mutex<std::collections::HashMap<String, TicketEntry>> {
    TICKET_STORE.get_or_init(|| tokio::sync::Mutex::new(std::collections::HashMap::new()))
}

/// Issues a scoped, single-use ticket valid for 60 seconds
pub async fn issue_ticket(scope: TicketScope) -> String {
    let mut bytes = [0u8; 16];
    rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut bytes);
    let ticket = hex::encode(bytes);

    let mut store = get_ticket_store().lock().await;
    let now = std::time::Instant::now();
    store.retain(|_, entry| entry.expires_at > now);

    store.insert(
        ticket.clone(),
        TicketEntry {
            scope,
            expires_at: now + std::time::Duration::from_secs(60),
        },
    );
    ticket
}

/// Consumes a scoped single-use ticket
pub async fn consume_ticket(ticket: &str, expected_scope: &TicketScope) -> bool {
    let mut store = get_ticket_store().lock().await;
    let now = std::time::Instant::now();
    if let Some(entry) = store.remove(ticket) {
        if entry.expires_at >= now && &entry.scope == expected_scope {
            return true;
        }
    }
    false
}

fn check_request_headers_auth(req: &Request, expected_token: &str) -> bool {
    let headers = req.headers();
    if let Some(auth_hdr) = headers.get("x-msmconnect-auth").or_else(|| headers.get("x-auralock-auth")) {
        if let Ok(token_str) = auth_hdr.to_str() {
            if constant_time_eq(token_str.trim().as_bytes(), expected_token.as_bytes()) {
                return true;
            }
        }
    }
    if let Some(auth_hdr) = headers.get("authorization") {
        if let Ok(token_str) = auth_hdr.to_str() {
            if let Some(bearer_token) = token_str.strip_prefix("Bearer ") {
                if constant_time_eq(bearer_token.trim().as_bytes(), expected_token.as_bytes()) {
                    return true;
                }
            }
        }
    }
    false
}

/// Axum Authentication Middleware
/// Rejects unauthenticated requests to sensitive endpoints (e.g. /api/unlock, /api/lock, /api/vault/*, /api/events)
async fn auth_middleware(
    req: Request,
    next: Next,
) -> Response {
    let path = req.uri().path().to_string();

    // Whitelist public endpoints: root dashboard UI
    if path == "/" {
        return next.run(req).await;
    }

    let expected_token = get_or_create_web_auth_token();

    // 1. Check custom auth header 'X-MsmConnect-Auth' or 'X-AuraLock-Auth' or Bearer token
    if check_request_headers_auth(&req, expected_token) {
        return next.run(req).await;
    }

    // 2. Status API (Medium M-03): If unauthenticated, return minimal safe status without disclosing sensitive device metadata
    if path == "/api/status" {
        return (
            StatusCode::OK,
            Json(serde_json::json!({
                "status": "online",
                "app": "Msm Connect Control Center",
                "version": "1.6.3",
                "authenticated": false
            })),
        )
            .into_response();
    }

    // 3. Query parameter ticket/token for WebSocket handshake and file downloads (Medium M-01)
    if path == "/api/events" {
        if let Some(query) = req.uri().query() {
            for pair in query.split('&') {
                if let Some((k, v)) = pair.split_once('=') {
                    if k == "ticket" {
                        if consume_ticket(v.trim(), &TicketScope::WebSocketStream).await {
                            return next.run(req).await;
                        }
                    } else if (k == "token" || k == "auth_token") && constant_time_eq(v.trim().as_bytes(), expected_token.as_bytes()) {
                        return next.run(req).await;
                    }
                }
            }
        }
    } else if path.starts_with("/api/auradrop/download/") {
        let requested_filename = path.strip_prefix("/api/auradrop/download/").unwrap_or_default().to_string();
        let safe_name = std::path::Path::new(&requested_filename)
            .file_name()
            .map(|f| f.to_string_lossy().to_string())
            .unwrap_or_default();
        if let Some(query) = req.uri().query() {
            for pair in query.split('&') {
                if let Some((k, v)) = pair.split_once('=') {
                    if k == "ticket" {
                        if consume_ticket(v.trim(), &TicketScope::Download(safe_name.clone())).await {
                            return next.run(req).await;
                        }
                    } else if (k == "token" || k == "auth_token") && constant_time_eq(v.trim().as_bytes(), expected_token.as_bytes()) {
                        return next.run(req).await;
                    }
                }
            }
        }
    }

    warn!("Blocked unauthorized access attempt to endpoint: {}", path);
    (
        StatusCode::UNAUTHORIZED,
        Json(serde_json::json!({
            "success": false,
            "message": "Unauthorized: Missing or invalid MsmConnect API session authentication token"
        })),
    )
        .into_response()
}

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
    pub transfer_authorized: bool,
    pub is_bluetooth_active: bool,
}

#[derive(Serialize)]
pub struct QrResponse {
    pub pin: String,
    pub qr_data: serde_json::Value,
}

#[derive(Deserialize)]
pub struct CreateDownloadTicketRequest {
    pub filename: String,
}

#[derive(Serialize)]
pub struct TicketResponse {
    pub success: bool,
    pub ticket: String,
    pub expires_in_seconds: u64,
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

#[derive(Deserialize)]
pub struct VerifyUnlockRequest {
    pub challenge_id: String,
    pub signature_hex: String,
    pub biometric_type: Option<String>,
    pub timestamp_epoch: Option<i64>,
}

#[derive(Serialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
}

async fn create_download_ticket(
    Json(payload): Json<CreateDownloadTicketRequest>,
) -> Json<TicketResponse> {
    let safe_name = std::path::Path::new(&payload.filename)
        .file_name()
        .map(|f| f.to_string_lossy().to_string())
        .unwrap_or_default();
    let ticket = issue_ticket(TicketScope::Download(safe_name)).await;
    Json(TicketResponse {
        success: true,
        ticket,
        expires_in_seconds: 60,
    })
}

async fn create_ws_ticket() -> Json<TicketResponse> {
    let ticket = issue_ticket(TicketScope::WebSocketStream).await;
    Json(TicketResponse {
        success: true,
        ticket,
        expires_in_seconds: 60,
    })
}

pub async fn start_web_server(net_service: Arc<NetworkService>, port: u16) -> Result<()> {
    // Initialize session authentication token
    let _ = get_or_create_web_auth_token();
    let state = AppState { net_service };

    let cors = CorsLayer::new()
        .allow_origin(tower_http::cors::AllowOrigin::predicate(|origin, _| {
            if let Ok(s) = origin.to_str() {
                s.starts_with("http://127.0.0.1")
                    || s.starts_with("http://localhost")
                    || s.starts_with("tauri://")
                    || s.starts_with("https://tauri.localhost")
                    || s.starts_with("http://tauri.localhost")
            } else {
                false
            }
        }))
        .allow_methods(tower_http::cors::Any)
        .allow_headers(tower_http::cors::Any);

    let app = Router::new()
        .route("/", get(serve_dashboard))
        .route("/api/status", get(get_status))
        .route("/api/qr", get(get_qr))
        .route("/api/ticket/download", post(create_download_ticket))
        .route("/api/ticket/ws", post(create_ws_ticket))
        .route("/api/pair/refresh", post(refresh_pairing))
        .route("/api/unlock/test", post(test_unlock))
        .route("/api/unlock/cancel", post(cancel_unlock_endpoint))
        .route("/api/unlock/verify", post(verify_unlock_endpoint))
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
        .route("/api/auradrop/clipboard", get(get_latest_clipboard))
        .route("/api/auradrop/files", get(get_received_files))
        .route("/api/auradrop/open-folder", post(open_folder))
        .route("/api/auradrop/download/:filename", get(download_file))
        .route("/api/events", get(ws_handler))
        .layer(middleware::from_fn(auth_middleware))
        .layer(cors)
        .with_state(state);

    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    info!("Msm Connect Desktop Control Center UI running at http://127.0.0.1:{}", port);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tokio::spawn(async move {
        if let Err(e) = axum::serve(listener, app).await {
            error!("Web server error: {:?}", e);
        }
    });

    Ok(())
}

async fn serve_dashboard() -> Response {
    let token = get_or_create_web_auth_token();
    let mut nonce_bytes = [0u8; 16];
    rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut nonce_bytes);
    let nonce = hex::encode(nonce_bytes);

    let injection = format!("<script nonce=\"{}\">window.__MSMCONNECT_SESSION_TOKEN__ = \"{}\";</script>\n</head>", nonce, token);
    let html = ui::DASHBOARD_HTML.replace("</head>", &injection);

    let csp = format!(
        "default-src 'self'; script-src 'self' 'nonce-{}' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: blob:; connect-src 'self' http://127.0.0.1:* ws://127.0.0.1:* http://localhost:* ws://localhost:* tauri://localhost https://tauri.localhost ipc:; frame-ancestors 'none'; base-uri 'self'; form-action 'self';",
        nonce
    );

    (
        [
            ("Content-Type", "text/html; charset=utf-8"),
            ("Content-Security-Policy", &csp),
            ("X-Content-Type-Options", "nosniff"),
            ("X-Frame-Options", "DENY"),
            ("Referrer-Policy", "no-referrer"),
        ],
        html,
    )
        .into_response()
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
            Err(e) => {
                error!("Test unlock failed to push challenge: {e}");
                Json(ApiResponse {
                    success: false,
                    message: "Failed to communicate with paired device".into(),
                })
            }
        },
        Err(e) => {
            error!("Test unlock failed to create challenge: {e}");
            Json(ApiResponse {
                success: false,
                message: "Failed to create biometric challenge".into(),
            })
        }
    }
}

async fn cancel_unlock_endpoint(State(state): State<AppState>) -> Json<ApiResponse> {
    state.net_service.cancel_challenge().await;
    Json(ApiResponse {
        success: true,
        message: "Biometric challenge cancelled".into(),
    })
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

async fn unlock_endpoint(State(state): State<AppState>) -> (StatusCode, Json<ApiResponse>) {
    // Direct arbitrary unlock without cryptographic biometric verification is strictly prevented.
    // Initiates a real cryptographic biometric challenge and pushes to the enrolled phone.
    match state
        .net_service
        .state_mgr
        .create_challenge(&state.net_service.pc_name)
        .await
    {
        Ok((challenge, _)) => match state.net_service.push_challenge(challenge).await {
            Ok(_) => (
                StatusCode::OK,
                Json(ApiResponse {
                    success: true,
                    message: "Biometric challenge initiated. Please authenticate on your phone.".into(),
                }),
            ),
            Err(e) => {
                error!("Unlock request failed to push challenge: {e}");
                (
                    StatusCode::BAD_REQUEST,
                    Json(ApiResponse {
                        success: false,
                        message: "Failed to communicate with paired device".into(),
                    }),
                )
            }
        },
        Err(e) => {
            error!("Unlock request failed to create challenge: {e}");
            (
                StatusCode::BAD_REQUEST,
                Json(ApiResponse {
                    success: false,
                    message: "Failed to create biometric challenge".into(),
                }),
            )
        }
    }
}

async fn verify_unlock_endpoint(
    State(state): State<AppState>,
    Json(payload): Json<VerifyUnlockRequest>,
) -> (StatusCode, Json<ApiResponse>) {
    let now = chrono::Utc::now().timestamp();
    let unlock_resp = crate::protocol::UnlockResponse {
        challenge_id: payload.challenge_id,
        signature_hex: payload.signature_hex,
        biometric_type: payload.biometric_type.unwrap_or_else(|| "fingerprint".to_string()),
        timestamp_epoch: payload.timestamp_epoch.unwrap_or(now),
    };

    match state.net_service.state_mgr.verify_unlock_response(unlock_resp).await {
        Ok(res) => {
            if res.success {
                state.net_service.state_mgr.set_lock_state(crate::state::SessionLockState::Unlocked).await;
                crate::network::unlock_pc_screen();
                state.net_service.broadcast_event(crate::network::WebEvent::UnlockEvent {
                    success: true,
                    message: "Biometric signature verified. PC Screen Unlocked.".into(),
                });
                if let Some(tx) = &*state.net_service.active_client.lock().await {
                    let _ = tx.send(crate::protocol::ProtocolMessage::LockStateChanged { is_locked: false }).await;
                }
                (
                    StatusCode::OK,
                    Json(ApiResponse {
                        success: true,
                        message: "Biometric signature verified. PC Screen Unlocked.".into(),
                    }),
                )
            } else {
                warn!("Biometric verification rejected: {}", res.message);
                (
                    StatusCode::UNAUTHORIZED,
                    Json(ApiResponse {
                        success: false,
                        message: "Biometric verification failed".into(),
                    }),
                )
            }
        }
        Err(e) => {
            error!("Biometric verification error: {e}");
            (
                StatusCode::UNAUTHORIZED,
                Json(ApiResponse {
                    success: false,
                    message: "Biometric verification failed".into(),
                }),
            )
        }
    }
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
        Err(e) => {
            error!("Vault save error: {e}");
            Json(ApiResponse {
                success: false,
                message: "Failed to securely save password to vault".into(),
            })
        }
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
                        error!("Send file error: {e}");
                        return (
                            StatusCode::BAD_REQUEST,
                            Json(ApiResponse {
                                success: false,
                                message: "Failed to send file to phone".into(),
                            }),
                        );
                    }
                }
            }
            Err(e) => {
                error!("Multipart read file bytes error: {e}");
                return (
                    StatusCode::BAD_REQUEST,
                    Json(ApiResponse {
                        success: false,
                        message: "Failed to read file content".into(),
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
        Err(e) => {
            error!("Send clipboard text error: {e}");
            Json(ApiResponse {
                success: false,
                message: "Failed to send text to phone".into(),
            })
        }
    }
}

async fn get_latest_clipboard(State(state): State<AppState>) -> Json<Option<crate::protocol::ClipboardSync>> {
    let clip = state.net_service.latest_clipboard.lock().await.clone();
    Json(clip)
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
        message: "Opened downloads folder".into(),
    })
}

async fn download_file(Path(filename): Path<String>) -> Response {
    let safe_name = std::path::Path::new(&filename)
        .file_name()
        .map(|f| f.to_string_lossy().to_string())
        .unwrap_or_default();

    if safe_name.is_empty() || safe_name.starts_with('.') {
        return (StatusCode::BAD_REQUEST, "Invalid file name").into_response();
    }

    let downloads_dir = get_default_downloads_dir();
    let p = downloads_dir.join(&safe_name);

    if let (Ok(canonical_dir), Ok(canonical_path)) = (downloads_dir.canonicalize(), p.canonicalize()) {
        if !canonical_path.starts_with(&canonical_dir) {
            return (StatusCode::FORBIDDEN, "Access denied").into_response();
        }
    }

    if let Ok(bytes) = tokio::fs::read(&p).await {
        let mime = "application/octet-stream";
        (
            [
                ("Content-Type", mime),
                (
                    "Content-Disposition",
                    &format!("attachment; filename=\"{safe_name}\""),
                ),
            ],
            bytes,
        )
            .into_response()
    } else {
        (StatusCode::NOT_FOUND, "File not found").into_response()
    }
}

async fn ws_handler(
    headers: axum::http::HeaderMap,
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    if let Some(origin_val) = headers.get("origin").and_then(|o| o.to_str().ok()) {
        let is_allowed = origin_val.starts_with("http://127.0.0.1")
            || origin_val.starts_with("http://localhost")
            || origin_val.starts_with("tauri://")
            || origin_val.starts_with("https://tauri.localhost")
            || origin_val.starts_with("http://tauri.localhost");
        if !is_allowed {
            warn!("Rejected WebSocket connection from unauthorized origin: {}", origin_val);
            return (StatusCode::FORBIDDEN, "Forbidden Origin").into_response();
        }
    }
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
