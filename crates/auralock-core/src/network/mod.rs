/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::error::{AuraLockError, Result};
use crate::protocol::{
    ClipboardSync, FileTransferAccept, FileTransferChunk, FileTransferComplete,
    FileTransferOffer, PairingQRData, PairingResponse, ProtocolMessage, UnlockChallenge,
    UnlockResult, BLE_SERVICE_UUID, PROTOCOL_VERSION,
};
use crate::state::StateManager;
use crate::vault::{PairedPhone, VaultManager};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, Mutex};
use tracing::{error, info, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuraDropReceivedFile {
    pub file_id: String,
    pub file_name: String,
    pub file_size: u64,
    pub mime_type: String,
    pub sender_name: String,
    pub local_path: String,
    pub timestamp_epoch: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum WebEvent {
    ClientConnected { phone_name: String },
    ClientDisconnected,
    DeviceUnpaired,
    PairingSuccess { phone_name: String, pairing_id: String },
    UnlockEvent { success: bool, message: String },
    FileTransferProgress {
        file_id: String,
        file_name: String,
        is_incoming: bool,
        progress: f32,
        bytes_transferred: u64,
        total_bytes: u64,
    },
    FileTransferCompleted {
        file_id: String,
        file_name: String,
        is_incoming: bool,
        success: bool,
        message: String,
        local_path: Option<String>,
    },
    ClipboardReceived {
        text: String,
        sender_name: String,
        timestamp_epoch: i64,
    },
    PhoneUnpairRequested {
        phone_name: String,
        pin: String,
    },
    PhoneUnpairCancelled,
}

struct IncomingTransfer {
    file_name: String,
    file_size: u64,
    mime_type: String,
    sender_name: String,
    local_path: PathBuf,
    bytes_received: u64,
    file_handle: tokio::fs::File,
    last_broadcast_time: std::time::Instant,
}

pub struct NetworkService {
    pub port: u16,
    pub pc_name: String,
    pub pc_id: String,
    pub state_mgr: StateManager,
    pub vault_mgr: Arc<VaultManager>,
    pub active_client: Arc<Mutex<Option<tokio::sync::mpsc::Sender<ProtocolMessage>>>>,
    pub active_pairing_pin: Arc<Mutex<Option<String>>>,
    pub recent_pairing_pins: Arc<Mutex<Vec<String>>>,
    pub active_pc_unpair_pin: Arc<Mutex<Option<String>>>,
    pub active_phone_unpair_pin: Arc<Mutex<Option<String>>>,
    pub connected_phone_name: Arc<Mutex<Option<String>>>,
    pub latest_qr_data: Arc<Mutex<Option<PairingQRData>>>,
    pub event_sender: broadcast::Sender<WebEvent>,
    pub received_files: Arc<Mutex<Vec<AuraDropReceivedFile>>>,
    pub latest_clipboard: Arc<Mutex<Option<ClipboardSync>>>,
    pub transfer_enabled: Arc<Mutex<bool>>,
    pub transfer_session_pin: Arc<Mutex<String>>,
    pub transfer_authorized: Arc<Mutex<bool>>,
    pub failed_pairing_attempts: Arc<Mutex<u32>>,
    pub pairing_lockout_until: Arc<Mutex<Option<std::time::Instant>>>,
    pub pairing_pin_created_at: Arc<Mutex<Option<std::time::Instant>>>,
    incoming_transfers: Arc<Mutex<HashMap<String, IncomingTransfer>>>,
}

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

pub fn get_default_downloads_dir() -> PathBuf {
    #[cfg(unix)]
    {
        // 1. Check if an active logged-in desktop user session exists
        let sessions = get_linux_user_sessions();
        for s in &sessions {
            if s.uid >= 1000 && !s.homedir.is_empty() {
                let p = PathBuf::from(&s.homedir).join("Downloads").join("AuraLock");
                let _ = std::fs::create_dir_all(&p);
                return p;
            }
        }
        if let Some(home) = std::env::var_os("HOME") {
            let p = PathBuf::from(home).join("Downloads").join("AuraLock");
            let _ = std::fs::create_dir_all(&p);
            return p;
        }
    }
    #[cfg(windows)]
    {
        if let Some(userprofile) = std::env::var_os("USERPROFILE") {
            let p = PathBuf::from(userprofile).join("Downloads").join("AuraLock");
            let _ = std::fs::create_dir_all(&p);
            return p;
        }
    }
    let p = std::env::temp_dir().join("AuraLock_Downloads");
    let _ = std::fs::create_dir_all(&p);
    p
}

pub fn get_unique_target_path(dir: &std::path::Path, file_name: &str) -> (String, PathBuf) {
    let original = PathBuf::from(file_name);
    let stem = original.file_stem().and_then(|s| s.to_str()).unwrap_or("file");
    let ext = original.extension().and_then(|s| s.to_str());

    let mut candidate_name = file_name.to_string();
    let mut candidate_path = dir.join(&candidate_name);
    let mut counter = 1;

    while candidate_path.exists() {
        candidate_name = match ext {
            Some(e) if !e.is_empty() => format!("{} ({}).{}", stem, counter, e),
            _ => format!("{} ({})", stem, counter),
        };
        candidate_path = dir.join(&candidate_name);
        counter += 1;
    }

    (candidate_name, candidate_path)
}

impl NetworkService {
    pub fn new(
        port: u16,
        pc_name: String,
        pc_id: String,
        state_mgr: StateManager,
        vault_mgr: Arc<VaultManager>,
    ) -> Self {
        let (event_sender, _) = broadcast::channel(64);
        let default_transfer_pin = format!("{:04}", rand::random::<u16>() % 9000 + 1000);
        Self {
            port,
            pc_name,
            pc_id,
            state_mgr,
            vault_mgr,
            active_client: Arc::new(Mutex::new(None)),
            active_pairing_pin: Arc::new(Mutex::new(None)),
            recent_pairing_pins: Arc::new(Mutex::new(Vec::new())),
            active_pc_unpair_pin: Arc::new(Mutex::new(None)),
            active_phone_unpair_pin: Arc::new(Mutex::new(None)),
            connected_phone_name: Arc::new(Mutex::new(None)),
            latest_qr_data: Arc::new(Mutex::new(None)),
            event_sender,
            received_files: Arc::new(Mutex::new(Vec::new())),
            latest_clipboard: Arc::new(Mutex::new(None)),
            transfer_enabled: Arc::new(Mutex::new(false)),
            transfer_session_pin: Arc::new(Mutex::new(default_transfer_pin)),
            transfer_authorized: Arc::new(Mutex::new(false)),
            failed_pairing_attempts: Arc::new(Mutex::new(0)),
            pairing_lockout_until: Arc::new(Mutex::new(None)),
            pairing_pin_created_at: Arc::new(Mutex::new(None)),
            incoming_transfers: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Toggles the AuraDrop transfer channel
    pub async fn toggle_transfer(&self, enabled: bool) -> (bool, String) {
        let mut en = self.transfer_enabled.lock().await;
        *en = enabled;
        *self.transfer_authorized.lock().await = enabled;
        let pin_guard = self.transfer_session_pin.lock().await;
        (enabled, pin_guard.clone())
    }

    /// Authorizes transfer session with 4-digit PIN
    pub async fn authorize_transfer(&self, pin: &str) -> bool {
        let pin_guard = self.transfer_session_pin.lock().await;
        if *pin_guard == pin {
            *self.transfer_authorized.lock().await = true;
            true
        } else {
            false
        }
    }

    pub fn subscribe_events(&self) -> broadcast::Receiver<WebEvent> {
        self.event_sender.subscribe()
    }

    pub fn broadcast_event(&self, event: WebEvent) {
        let _ = self.event_sender.send(event);
    }

    pub async fn is_phone_connected(&self) -> bool {
        self.active_client.lock().await.is_some()
    }

    pub async fn get_connected_phone_name(&self) -> Option<String> {
        self.connected_phone_name.lock().await.clone()
    }

    /// Directly unpairs the device from PC without requiring PIN.
    pub async fn direct_unpair(&self) -> bool {
        let _ = self.vault_mgr.clear();
        self.state_mgr.unpair().await;
        {
            let mut phone_guard = self.connected_phone_name.lock().await;
            *phone_guard = None;
        }
        {
            let mut pin_guard = self.active_pc_unpair_pin.lock().await;
            *pin_guard = None;
        }
        {
            let mut phone_pin_guard = self.active_phone_unpair_pin.lock().await;
            *phone_pin_guard = None;
        }
        {
            let mut qr_guard = self.latest_qr_data.lock().await;
            *qr_guard = None;
        }

        // Notify phone if connected so it unpairs too, and disconnect active client
        let client_opt = {
            let mut client_guard = self.active_client.lock().await;
            client_guard.take()
        };
        if let Some(tx) = client_opt {
            let resp = ProtocolMessage::UnpairResp(crate::protocol::RemoteUnpairResponse {
                success: true,
                message: "Device successfully unpaired".into(),
            });
            let _ = tx.send(resp).await;
        }

        self.broadcast_event(WebEvent::DeviceUnpaired);
        self.broadcast_event(WebEvent::ClientDisconnected);
        true
    }

    /// Checks if PC Bluetooth adapter is available and powered on
    pub fn is_pc_bluetooth_active(&self) -> bool {
        is_pc_bluetooth_active()
    }

    /// Initiates unpair from PC (Direct unpair)
    pub async fn initiate_unpair_from_pc(&self) -> (bool, Option<String>) {
        let connected = self.is_phone_connected().await;
        self.direct_unpair().await;
        (connected, None)
    }

    /// Verifies and confirms PC unpair
    pub async fn verify_and_confirm_pc_unpair(&self, _pin: &str) -> bool {
        self.direct_unpair().await
    }

    /// Force unpairs locally when phone is offline/missing.
    pub async fn force_unpair_offline(&self) -> bool {
        self.direct_unpair().await
    }

    /// Sets an ephemeral 6-digit PIN for an active pairing session
    pub async fn set_pairing_pin(&self, pin: String) {
        let mut guard = self.active_pairing_pin.lock().await;
        *guard = Some(pin.clone());
        let mut created_guard = self.pairing_pin_created_at.lock().await;
        *created_guard = Some(std::time::Instant::now());
        let mut pins_guard = self.recent_pairing_pins.lock().await;
        pins_guard.push(pin);
        if pins_guard.len() > 10 {
            pins_guard.remove(0);
        }
    }

    /// Gets existing QR data if available, or generates a new one
    pub async fn get_or_create_qr_payload(&self, local_ip: &str, ephem_pub_hex: &str) -> PairingQRData {
        let qr_guard = self.latest_qr_data.lock().await;
        if let Some(existing) = &*qr_guard {
            return existing.clone();
        }
        drop(qr_guard);
        self.generate_qr_payload(local_ip, ephem_pub_hex).await
    }

    /// Generates QR Code data payload for pairing
    pub async fn generate_qr_payload(&self, local_ip: &str, ephem_pub_hex: &str) -> PairingQRData {
        let pin = format!("{:06}", rand::random::<u32>() % 1_000_000);
        self.set_pairing_pin(pin.clone()).await;

        let data = PairingQRData {
            v: PROTOCOL_VERSION,
            pc_id: self.pc_id.clone(),
            pc_name: self.pc_name.clone(),
            ephem_pub_hex: ephem_pub_hex.to_string(),
            ble_uuid: BLE_SERVICE_UUID.to_string(),
            ip: local_ip.to_string(),
            port: self.port,
            pin,
        };

        let mut qr_guard = self.latest_qr_data.lock().await;
        *qr_guard = Some(data.clone());

        data
    }

    /// Sends an unlock challenge to the connected phone
    pub async fn push_challenge(&self, challenge: UnlockChallenge) -> Result<()> {
        let guard = self.active_client.lock().await;
        if let Some(tx) = &*guard {
            let msg = ProtocolMessage::Challenge(challenge);
            tx.send(msg)
                .await
                .map_err(|e| AuraLockError::ProtocolError(format!("Failed to send challenge: {e}")))?;
            Ok(())
        } else {
            Err(AuraLockError::ProtocolError("No active phone client connected".into()))
        }
    }

    /// Immediately cancels active biometric challenge and notifies connected phone
    pub async fn cancel_challenge(&self) {
        self.state_mgr.cancel_all_challenges().await;
        let guard = self.active_client.lock().await;
        if let Some(tx) = &*guard {
            let msg = ProtocolMessage::Result(UnlockResult {
                success: false,
                message: "Biometric challenge cancelled by PC (Esc pressed)".into(),
            });
            let _ = tx.send(msg).await;
        }
    }

    /// Send a file from PC to connected Phone (AuraDrop PC -> Mobile)
    pub async fn send_file_to_phone(
        &self,
        file_name: String,
        file_bytes: Vec<u8>,
        mime_type: String,
    ) -> Result<String> {
        let tx = {
            let guard = self.active_client.lock().await;
            guard.clone().ok_or_else(|| {
                AuraLockError::ProtocolError("No phone currently connected to send file".into())
            })?
        };

        let file_id = format!("file_{:08x}", rand::random::<u32>());
        let file_size = file_bytes.len() as u64;

        // 1. Send File Offer
        let offer = ProtocolMessage::FileOffer(FileTransferOffer {
            file_id: file_id.clone(),
            file_name: file_name.clone(),
            file_size,
            mime_type,
            sender_name: self.pc_name.clone(),
        });
        tx.send(offer)
            .await
            .map_err(|e| AuraLockError::ProtocolError(format!("Failed to send file offer: {e}")))?;

        // 2. Stream Chunks (256 KB each for maximum bandwidth saturation)
        let chunk_size = 256 * 1024;
        let total_chunks = if file_size == 0 {
            1
        } else {
            ((file_size + chunk_size as u64 - 1) / chunk_size as u64) as u32
        };

        let engine = base64::engine::general_purpose::STANDARD;
        let mut bytes_sent: u64 = 0;
        let mut last_progress_time = std::time::Instant::now();

        for (idx, chunk) in file_bytes.chunks(chunk_size).enumerate() {
            let chunk_msg = ProtocolMessage::FileChunk(FileTransferChunk {
                file_id: file_id.clone(),
                chunk_index: idx as u32,
                total_chunks,
                data_base64: engine.encode(chunk),
            });

            if let Err(e) = tx.send(chunk_msg).await {
                self.broadcast_event(WebEvent::FileTransferCompleted {
                    file_id: file_id.clone(),
                    file_name: file_name.clone(),
                    is_incoming: false,
                    success: false,
                    message: "Transfer cancelled: connection lost".into(),
                    local_path: None,
                });
                return Err(AuraLockError::ProtocolError(format!("Failed to send chunk: {e}")));
            }

            bytes_sent += chunk.len() as u64;
            let progress = (bytes_sent as f32) / (file_size.max(1) as f32);

            let should_broadcast = last_progress_time.elapsed() >= std::time::Duration::from_millis(60)
                || bytes_sent >= file_size;

            if should_broadcast {
                last_progress_time = std::time::Instant::now();
                self.broadcast_event(WebEvent::FileTransferProgress {
                    file_id: file_id.clone(),
                    file_name: file_name.clone(),
                    is_incoming: false,
                    progress,
                    bytes_transferred: bytes_sent,
                    total_bytes: file_size,
                });
            }

            // Yield cooperatively to runtime without introducing artificial latency
            tokio::task::yield_now().await;
        }

        // 3. Send Complete
        let complete_msg = ProtocolMessage::FileComplete(FileTransferComplete {
            file_id: file_id.clone(),
            file_name: file_name.clone(),
            success: true,
            message: "File transferred successfully to Phone".into(),
        });
        let _ = tx.send(complete_msg).await;

        self.broadcast_event(WebEvent::FileTransferCompleted {
            file_id: file_id.clone(),
            file_name: file_name.clone(),
            is_incoming: false,
            success: true,
            message: "Sent to Phone".into(),
            local_path: None,
        });

        Ok(file_id)
    }

    /// Send Clipboard text to connected Phone
    pub async fn send_clipboard_to_phone(&self, text: String) -> Result<()> {
        let tx = {
            let guard = self.active_client.lock().await;
            guard.clone().ok_or_else(|| {
                AuraLockError::ProtocolError("No phone connected to send text".into())
            })?
        };

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64;

        let msg = ProtocolMessage::Clipboard(ClipboardSync {
            text,
            sender_name: self.pc_name.clone(),
            timestamp_epoch: now,
        });

        tx.send(msg)
            .await
            .map_err(|e| AuraLockError::ProtocolError(format!("Failed to send clipboard: {e}")))?;

        Ok(())
    }

    /// Starts the TCP listener for incoming mobile connections (over local Wi-Fi) & UDP discovery beacon
    pub async fn run_listener(self: Arc<Self>) -> Result<()> {
        let addr = SocketAddr::from(([0, 0, 0, 0], self.port));
        let listener = TcpListener::bind(addr).await?;
        info!("AuraLock Network daemon listening on {}", addr);

        // Spawn UDP Auto-Discovery Responder on port (port + 2 e.g. 47892)
        let udp_port = self.port + 2;
        let pc_name_udp = self.pc_name.clone();
        let pc_id_udp = self.pc_id.clone();
        let tcp_port = self.port;
        let is_paired_check = self.vault_mgr.clone();

        tokio::spawn(async move {
            use tokio::net::UdpSocket;
            let bind_addr = SocketAddr::from(([0, 0, 0, 0], udp_port));
            if let Ok(socket) = UdpSocket::bind(bind_addr).await {
                let _ = socket.set_broadcast(true);
                info!("AuraLock UDP discovery responder active on {}", bind_addr);
                let mut buf = [0u8; 1024];
                loop {
                    if let Ok((len, src)) = socket.recv_from(&mut buf).await {
                        let query = String::from_utf8_lossy(&buf[..len]);
                        if query.contains("Discover") || query.contains("auralock") || query.contains("ping") {
                            let is_paired = is_paired_check.is_paired();
                            let resp = serde_json::json!({
                                "type": "AuraLockBeacon",
                                "pc_name": pc_name_udp,
                                "pc_id": pc_id_udp,
                                "port": tcp_port,
                                "is_paired": is_paired
                            });
                            let resp_bytes = serde_json::to_vec(&resp).unwrap_or_default();
                            let _ = socket.send_to(&resp_bytes, src).await;
                        }
                    }
                }
            }
        });

        loop {
            match listener.accept().await {
                Ok((stream, client_addr)) => {
                    info!("Incoming connection from {}", client_addr);
                    let svc = Arc::clone(&self);
                    tokio::spawn(async move {
                        if let Err(e) = svc.handle_connection(stream, client_addr).await {
                            info!("Connection with {} closed: {:?}", client_addr, e);
                        }
                    });
                }
                Err(e) => {
                    warn!("Failed to accept incoming TCP connection: {:?}", e);
                }
            }
        }
    }

    async fn handle_connection(&self, stream: TcpStream, client_addr: SocketAddr) -> Result<()> {
        let _ = stream.set_nodelay(true);
        let (reader, mut writer) = stream.into_split();
        let mut buf_reader = BufReader::new(reader);
        let mut line = String::new();

        let (tx, mut rx) = tokio::sync::mpsc::channel::<ProtocolMessage>(64);

        // Register active client channel
        {
            let mut guard = self.active_client.lock().await;
            *guard = Some(tx.clone());
        }

        // Send immediate Bluetooth status on connection
        let initial_bt = is_pc_bluetooth_active();
        let _ = tx.send(ProtocolMessage::BluetoothStatus(crate::protocol::BluetoothStatusMessage {
            is_active: initial_bt,
            device_name: Some(self.pc_name.clone()),
        })).await;

        // Send immediate Lock state on connection
        let initial_lock = is_pc_screen_locked();
        let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked: initial_lock }).await;

        // If there is an active pending challenge, deliver ONLY if screen is ACTUALLY locked
        if initial_lock {
            if let Some(pending_ch) = self.state_mgr.get_latest_valid_challenge().await {
                info!("Pushing active pending challenge {} to newly connected phone", pending_ch.challenge_id);
                let _ = tx.send(ProtocolMessage::Challenge(pending_ch)).await;
            }
        }

        // Spawn real-time Lock state & Bluetooth status watcher (checks every 500ms and sends on state change)
        let tx_bt = tx.clone();
        let pc_name_bt = self.pc_name.clone();
        let bt_watcher_task = tokio::spawn(async move {
            let mut last_status = initial_bt;
            loop {
                tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
                let current_status = is_pc_bluetooth_active();
                if current_status != last_status {
                    last_status = current_status;
                    let msg = ProtocolMessage::BluetoothStatus(crate::protocol::BluetoothStatusMessage {
                        is_active: current_status,
                        device_name: Some(pc_name_bt.clone()),
                    });
                    if tx_bt.send(msg).await.is_err() {
                        break;
                    }
                }
            }
        });

        let tx_lock = tx.clone();
        let lock_watcher_task = tokio::spawn(async move {
            let mut last_lock = initial_lock;
            loop {
                tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
                let current_lock = is_pc_screen_locked();
                if current_lock != last_lock {
                    last_lock = current_lock;
                    let msg = ProtocolMessage::LockStateChanged { is_locked: current_lock };
                    if tx_lock.send(msg).await.is_err() {
                        break;
                    }
                }
            }
        });

        // Outbound message loop
        let writer_task = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if let Ok(json) = serde_json::to_string(&msg) {
                    if writer.write_all(format!("{}\n", json).as_bytes()).await.is_err() {
                        break;
                    }
                }
            }
        });

        loop {
            line.clear();
            let bytes_read = match buf_reader.read_line(&mut line).await {
                Ok(n) => n,
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset || e.kind() == std::io::ErrorKind::BrokenPipe => {
                    info!("Client {} disconnected cleanly", client_addr);
                    break;
                }
                Err(e) => return Err(e.into()),
            };
            if bytes_read == 0 {
                info!("Client {} disconnected", client_addr);
                break;
            }

            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }

            let msg: ProtocolMessage = match serde_json::from_str(trimmed) {
                Ok(m) => m,
                Err(e) => {
                    warn!("Malformed JSON from {}: {}", client_addr, e);
                    continue;
                }
            };

            if let Err(e) = self.process_message(msg).await {
                warn!("Error processing message from {}: {:?}", client_addr, e);
            }
        }

        // Cleanup on disconnect
        {
            let mut guard = self.active_client.lock().await;
            *guard = None;
            let mut phone_guard = self.connected_phone_name.lock().await;
            *phone_guard = None;
            let mut transfers_guard = self.incoming_transfers.lock().await;
            for (file_id, transfer) in transfers_guard.drain() {
                drop(transfer.file_handle);
                let _ = tokio::fs::remove_file(&transfer.local_path).await;
                self.broadcast_event(WebEvent::FileTransferCompleted {
                    file_id,
                    file_name: transfer.file_name,
                    is_incoming: true,
                    success: false,
                    message: "Phone disconnected during transfer".into(),
                    local_path: None,
                });
            }
        }
        self.broadcast_event(WebEvent::ClientDisconnected);
        writer_task.abort();
        bt_watcher_task.abort();
        lock_watcher_task.abort();

        info!("Client disconnected (Manual locking mode active; no auto-lock on disconnect)");

        Ok(())
    }

    async fn process_message(&self, msg: ProtocolMessage) -> Result<()> {
        match msg {
            ProtocolMessage::Identify(req) => {
                info!("Phone identified on connection: {}", req.phone_name);
                {
                    let mut phone_guard = self.connected_phone_name.lock().await;
                    *phone_guard = Some(req.phone_name.clone());
                }
                self.broadcast_event(WebEvent::ClientConnected {
                    phone_name: req.phone_name,
                });

                let is_locked = is_pc_screen_locked();
                if let Some(tx) = &*self.active_client.lock().await {
                    let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked }).await;
                }

                // Deliver any pending challenge waiting for phone biometric response (only if locked)
                if is_locked {
                    if let Some(pending_ch) = self.state_mgr.get_latest_valid_challenge().await {
                        info!("Pushing pending challenge {} to phone on identify", pending_ch.challenge_id);
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(ProtocolMessage::Challenge(pending_ch)).await;
                        }
                    }
                } else {
                    self.state_mgr.cancel_all_challenges().await;
                }
            }
            ProtocolMessage::PairingReq(req) => {
                info!("Received pairing request from phone: {}", req.phone_name);

                let active_pin = self.active_pairing_pin.lock().await.clone();
                let recent_pins = self.recent_pairing_pins.lock().await.clone();
                let latest_qr_pin = self.latest_qr_data.lock().await.as_ref().map(|q| q.pin.clone());

                let req_pin: String = req.pin.chars().filter(|c| c.is_ascii_digit()).collect();
                let active_pin_clean: Option<String> = active_pin.as_ref().map(|p| p.chars().filter(|c| c.is_ascii_digit()).collect());
                let recent_pins_clean: Vec<String> = recent_pins.iter().map(|p| p.chars().filter(|c| c.is_ascii_digit()).collect()).collect();
                let qr_pin_clean: Option<String> = latest_qr_pin.as_ref().map(|q| q.chars().filter(|c| c.is_ascii_digit()).collect());

                // Prevent rogue device pairing takeover if PC is already paired and NOT currently in active pairing setup mode
                if self.vault_mgr.is_paired() && active_pin_clean.is_none() && qr_pin_clean.is_none() {
                    warn!("Rejected pairing request: PC is already paired and not in pairing setup mode");
                    let resp = ProtocolMessage::PairingResp(PairingResponse {
                        success: false,
                        pairing_id: String::new(),
                        message: "PC is already paired with another device. Please unpair from PC first.".into(),
                    });
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(resp).await;
                    }
                    return Ok(());
                }

                // 1. Validate Phone Public Key format (must be valid 32 bytes / 64 hex characters)
                let pubkey_bytes = match hex::decode(&req.phone_ed25519_pub_hex) {
                    Ok(b) if b.len() == 32 => b,
                    _ => {
                        warn!("Rejected pairing request with invalid public key format from {}", req.phone_name);
                        let resp = ProtocolMessage::PairingResp(PairingResponse {
                            success: false,
                            pairing_id: String::new(),
                            message: "Invalid cryptographic public key provided. Please regenerate keys.".into(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(resp).await;
                        }
                        return Ok(());
                    }
                };

                // Check pairing rate limit / brute-force lockout
                {
                    let lockout_guard = self.pairing_lockout_until.lock().await;
                    if let Some(until) = *lockout_guard {
                        let now = std::time::Instant::now();
                        if now < until {
                            let remaining = until.duration_since(now).as_secs();
                            warn!("Rejected pairing request: rate limited / locked out for {} more seconds", remaining);
                            let resp = ProtocolMessage::PairingResp(PairingResponse {
                                success: false,
                                pairing_id: String::new(),
                                message: format!("Too many failed pairing attempts. Please wait {} seconds before trying again.", remaining),
                            });
                            if let Some(tx) = &*self.active_client.lock().await {
                                let _ = tx.send(resp).await;
                            }
                            return Ok(());
                        }
                    }
                }

                // Check pairing PIN expiration (TTL 5 minutes / 300 seconds)
                {
                    let created_guard = self.pairing_pin_created_at.lock().await;
                    if let Some(created) = *created_guard {
                        if created.elapsed().as_secs() > 300 {
                            warn!("Rejected pairing request: pairing PIN has expired (TTL 300s)");
                            let resp = ProtocolMessage::PairingResp(PairingResponse {
                                success: false,
                                pairing_id: String::new(),
                                message: "Pairing session has expired. Please refresh the QR code on your PC screen.".into(),
                            });
                            if let Some(tx) = &*self.active_client.lock().await {
                                let _ = tx.send(resp).await;
                            }
                            return Ok(());
                        }
                    }
                }

                let mut pin_matches = false;
                if !req_pin.is_empty() {
                    // Check active PIN on PC screen
                    if let Some(ref p) = active_pin_clean {
                        if constant_time_eq(p.as_bytes(), req_pin.as_bytes()) {
                            pin_matches = true;
                        }
                    }
                    // Check recent PINs in case session was refreshed
                    if !pin_matches && !recent_pins_clean.is_empty() {
                        if recent_pins_clean.iter().any(|p| constant_time_eq(p.as_bytes(), req_pin.as_bytes())) {
                            pin_matches = true;
                        }
                    }
                    // Check latest QR payload PIN
                    if !pin_matches {
                        if let Some(ref qp) = qr_pin_clean {
                            if constant_time_eq(qp.as_bytes(), req_pin.as_bytes()) {
                                pin_matches = true;
                            }
                        }
                    }
                }

                if pin_matches {
                    // Reset failed attempt counter on successful pairing
                    {
                        let mut failed = self.failed_pairing_attempts.lock().await;
                        *failed = 0;
                        let mut lockout = self.pairing_lockout_until.lock().await;
                        *lockout = None;
                    }
                    let now = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap()
                        .as_secs() as i64;
                    let safe_phone_name: String = req.phone_name.chars()
                        .filter(|c| !c.is_control())
                        .take(64)
                        .collect();
                    let safe_phone_name = if safe_phone_name.trim().is_empty() {
                        "Android Phone".to_string()
                    } else {
                        safe_phone_name
                    };

                    let paired_phone = PairedPhone {
                        device_name: safe_phone_name.clone(),
                        public_key_hex: hex::encode(pubkey_bytes),
                        pairing_id: format!("pair_{:08x}", rand::random::<u32>()),
                        paired_at_epoch: now,
                    };

                    info!("Phone {} successfully paired with ID {}", safe_phone_name, paired_phone.pairing_id);

                    // Persist pairing in vault and state manager
                    let _ = self.vault_mgr.save_paired_phone(paired_phone.clone());
                    self.state_mgr.set_paired_phone(Some(paired_phone.clone())).await;

                    {
                        let mut phone_guard = self.connected_phone_name.lock().await;
                        *phone_guard = Some(req.phone_name.clone());
                    }

                    // Clear pairing PINs now that device is paired
                    {
                        let mut guard = self.active_pairing_pin.lock().await;
                        *guard = None;
                        let mut pins_guard = self.recent_pairing_pins.lock().await;
                        pins_guard.clear();
                        let mut qr_guard = self.latest_qr_data.lock().await;
                        *qr_guard = None;
                    }

                    let resp = ProtocolMessage::PairingResp(PairingResponse {
                        success: true,
                        pairing_id: paired_phone.pairing_id.clone(),
                        message: "Device paired successfully".into(),
                    });

                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(resp).await;
                    }

                    self.broadcast_event(WebEvent::PairingSuccess {
                        phone_name: req.phone_name.clone(),
                        pairing_id: paired_phone.pairing_id,
                    });
                    self.broadcast_event(WebEvent::ClientConnected {
                        phone_name: req.phone_name,
                    });
                } else {
                    let mut failed = self.failed_pairing_attempts.lock().await;
                    *failed += 1;
                    let current_failed = *failed;
                    if current_failed >= 5 {
                        let mut lockout = self.pairing_lockout_until.lock().await;
                        *lockout = Some(std::time::Instant::now() + std::time::Duration::from_secs(60));
                        warn!("Max pairing PIN attempts exceeded (5). Locking out pairing for 60s");
                    }
                    warn!("Pairing PIN mismatch from phone (attempt {}/5)", current_failed);
                    let resp = ProtocolMessage::PairingResp(PairingResponse {
                        success: false,
                        pairing_id: String::new(),
                        message: if current_failed >= 5 {
                            "Too many failed pairing attempts. Locked out for 60 seconds.".into()
                        } else {
                            format!("Pairing PIN was rejected by PC ({}/5 attempts). Please check the PIN on your PC screen.", current_failed)
                        },
                    });
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(resp).await;
                    }
                }
            }
            ProtocolMessage::Response(resp) => {
                info!("Received biometric unlock response for challenge {}", resp.challenge_id);
                let result = self.state_mgr.verify_unlock_response(resp).await;
                match result {
                    Ok(res) => {
                        self.state_mgr.set_lock_state(crate::state::SessionLockState::Unlocked).await;
                        unlock_pc_screen();
                        self.broadcast_event(WebEvent::UnlockEvent {
                            success: res.success,
                            message: res.message.clone(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked: !res.success }).await;
                            let _ = tx.send(ProtocolMessage::Result(res)).await;
                        }
                    }
                    Err(e) => {
                        self.broadcast_event(WebEvent::UnlockEvent {
                            success: false,
                            message: e.to_string(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked: true }).await;
                            let _ = tx
                                .send(ProtocolMessage::Result(UnlockResult {
                                    success: false,
                                    message: e.to_string(),
                                }))
                                .await;
                        }
                    }
                }
            }
            ProtocolMessage::Ping => {
                if let Some(tx) = &*self.active_client.lock().await {
                    let _ = tx.send(ProtocolMessage::Pong).await;
                    let _ = tx.send(ProtocolMessage::BluetoothStatus(crate::protocol::BluetoothStatusMessage {
                        is_active: is_pc_bluetooth_active(),
                        device_name: Some(self.pc_name.clone()),
                    })).await;
                    let is_locked = is_pc_screen_locked();
                    let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked }).await;
                    if is_locked {
                        if let Some(pending_ch) = self.state_mgr.get_latest_valid_challenge().await {
                            let _ = tx.send(ProtocolMessage::Challenge(pending_ch)).await;
                        }
                    } else {
                        self.state_mgr.cancel_all_challenges().await;
                    }
                }
            }
            ProtocolMessage::FileOffer(offer) => {
                let is_paired = self.vault_mgr.is_paired();
                let is_authorized = *self.transfer_authorized.lock().await;
                if !is_paired && !is_authorized {
                    warn!("Rejected FileOffer from unauthorized sender: {}", offer.sender_name);
                    let reject = ProtocolMessage::FileAccept(FileTransferAccept {
                        file_id: offer.file_id,
                        accepted: false,
                        message: Some("Device not paired or transfer not authorized with PIN".into()),
                    });
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(reject).await;
                    }
                    return Ok(());
                }

                // Enforce max file size limit (50 GB)
                if offer.file_size > 50 * 1024 * 1024 * 1024 {
                    warn!("Rejected FileOffer exceeding size limit: {} bytes", offer.file_size);
                    let reject = ProtocolMessage::FileAccept(FileTransferAccept {
                        file_id: offer.file_id,
                        accepted: false,
                        message: Some("File size exceeds 50 GB maximum limit".into()),
                    });
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(reject).await;
                    }
                    return Ok(());
                }

                // Enforce max concurrent active transfers limit (10)
                let active_count = self.incoming_transfers.lock().await.len();
                if active_count >= 10 {
                    warn!("Rejected FileOffer: max concurrent transfers reached ({}/10)", active_count);
                    let reject = ProtocolMessage::FileAccept(FileTransferAccept {
                        file_id: offer.file_id,
                        accepted: false,
                        message: Some("Max concurrent transfers limit (10) reached. Please retry later.".into()),
                    });
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(reject).await;
                    }
                    return Ok(());
                }

                info!("Incoming AuraDrop file offer from {}: {} ({} bytes)", offer.sender_name, offer.file_name, offer.file_size);
                let downloads_dir = get_default_downloads_dir();
                
                // Sanitize and ensure conflict-free file name (strips path separators & null characters)
                let cleaned_name = offer.file_name.replace(['/', '\\', '\0', '\r', '\n'], "_");
                let safe_base_name = PathBuf::from(&cleaned_name)
                    .file_name()
                    .map(|f| f.to_string_lossy().to_string())
                    .unwrap_or_else(|| "downloaded_file.bin".to_string());
                let safe_base_name = safe_base_name.trim_start_matches('.').to_string();
                let safe_base_name = if safe_base_name.is_empty() {
                    "downloaded_file.bin".to_string()
                } else {
                    safe_base_name
                };
                
                let (safe_name, target_path) = get_unique_target_path(&downloads_dir, &safe_base_name);
                
                match tokio::fs::File::create(&target_path).await {
                    Ok(file) => {
                        let transfer = IncomingTransfer {
                            file_name: safe_name.clone(),
                            file_size: offer.file_size,
                            mime_type: offer.mime_type.clone(),
                            sender_name: offer.sender_name.clone(),
                            local_path: target_path,
                            bytes_received: 0,
                            file_handle: file,
                            last_broadcast_time: std::time::Instant::now(),
                        };
                        {
                            let mut guard = self.incoming_transfers.lock().await;
                            guard.insert(offer.file_id.clone(), transfer);
                        }

                        // Accept file transfer
                        let accept = ProtocolMessage::FileAccept(FileTransferAccept {
                            file_id: offer.file_id,
                            accepted: true,
                            message: None,
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(accept).await;
                        }
                    }
                    Err(e) => {
                        error!("Failed to create local destination file: {:?}", e);
                        let reject = ProtocolMessage::FileAccept(FileTransferAccept {
                            file_id: offer.file_id,
                            accepted: false,
                            message: Some(format!("Server could not create file: {e}")),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(reject).await;
                        }
                    }
                }
            }
            ProtocolMessage::FileChunk(chunk) => {
                // Enforce chunk payload size cap (15 MB base64 data max)
                if chunk.data_base64.len() > 15 * 1024 * 1024 {
                    warn!("Aborting transfer {}: incoming chunk exceeds 15 MB limit", chunk.file_id);
                    let mut guard = self.incoming_transfers.lock().await;
                    if let Some(transfer) = guard.remove(&chunk.file_id) {
                        drop(transfer.file_handle);
                        let _ = tokio::fs::remove_file(&transfer.local_path).await;
                        self.broadcast_event(WebEvent::FileTransferCompleted {
                            file_id: chunk.file_id.clone(),
                            file_name: transfer.file_name,
                            is_incoming: true,
                            success: false,
                            message: "Transfer aborted: chunk exceeded 15 MB limit".into(),
                            local_path: None,
                        });
                    }
                    return Ok(());
                }

                let engine = base64::engine::general_purpose::STANDARD;
                let decoded_bytes = engine.decode(&chunk.data_base64).unwrap_or_default();
                let mut completed_info: Option<AuraDropReceivedFile> = None;
                let mut runaway_detected = false;
                let mut runaway_file_name = String::new();
                let mut runaway_path = PathBuf::new();

                {
                    let mut guard = self.incoming_transfers.lock().await;
                    if let Some(transfer) = guard.get_mut(&chunk.file_id) {
                        // Runaway transfer protection: incoming bytes exceed declared file_size + margin
                        if transfer.bytes_received + decoded_bytes.len() as u64 > transfer.file_size + 1024 * 1024 {
                            runaway_detected = true;
                            runaway_file_name = transfer.file_name.clone();
                            runaway_path = transfer.local_path.clone();
                        } else {
                            let _ = transfer.file_handle.write_all(&decoded_bytes).await;
                            transfer.bytes_received += decoded_bytes.len() as u64;

                            let progress = (transfer.bytes_received as f32) / (transfer.file_size.max(1) as f32);
                            let is_final = chunk.chunk_index + 1 >= chunk.total_chunks || transfer.bytes_received >= transfer.file_size;

                            if transfer.last_broadcast_time.elapsed() >= std::time::Duration::from_millis(60) || is_final {
                                transfer.last_broadcast_time = std::time::Instant::now();
                                self.broadcast_event(WebEvent::FileTransferProgress {
                                    file_id: chunk.file_id.clone(),
                                    file_name: transfer.file_name.clone(),
                                    is_incoming: true,
                                    progress,
                                    bytes_transferred: transfer.bytes_received,
                                    total_bytes: transfer.file_size,
                                });
                            }

                            if is_final {
                                let _ = transfer.file_handle.flush().await;
                                let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64;
                                completed_info = Some(AuraDropReceivedFile {
                                    file_id: chunk.file_id.clone(),
                                    file_name: transfer.file_name.clone(),
                                    file_size: transfer.bytes_received,
                                    mime_type: transfer.mime_type.clone(),
                                    sender_name: transfer.sender_name.clone(),
                                    local_path: transfer.local_path.to_string_lossy().to_string(),
                                    timestamp_epoch: now,
                                });
                            }
                        }
                    }
                }

                if runaway_detected {
                    warn!("Aborting transfer {}: received bytes exceeded declared size", chunk.file_id);
                    let mut transfers_guard = self.incoming_transfers.lock().await;
                    if let Some(transfer) = transfers_guard.remove(&chunk.file_id) {
                        drop(transfer.file_handle);
                    }
                    let _ = tokio::fs::remove_file(&runaway_path).await;
                    self.broadcast_event(WebEvent::FileTransferCompleted {
                        file_id: chunk.file_id.clone(),
                        file_name: runaway_file_name,
                        is_incoming: true,
                        success: false,
                        message: "Transfer aborted: byte count exceeded declared file size".into(),
                        local_path: None,
                    });
                    return Ok(());
                }

                if let Some(info) = completed_info {
                    {
                        let mut transfers_guard = self.incoming_transfers.lock().await;
                        transfers_guard.remove(&chunk.file_id);
                        let mut files_guard = self.received_files.lock().await;
                        files_guard.insert(0, info.clone());
                    }

                    self.broadcast_event(WebEvent::FileTransferCompleted {
                        file_id: chunk.file_id.clone(),
                        file_name: info.file_name.clone(),
                        is_incoming: true,
                        success: true,
                        message: "Saved to Downloads/AuraLock".into(),
                        local_path: Some(info.local_path),
                    });
                }
            }
            ProtocolMessage::FileAccept(accept) => {
                if !accept.accepted {
                    info!("File transfer rejected by phone for file_id: {}", accept.file_id);
                    self.broadcast_event(WebEvent::FileTransferCompleted {
                        file_id: accept.file_id,
                        file_name: "File".into(),
                        is_incoming: false,
                        success: false,
                        message: accept.message.unwrap_or_else(|| "Transfer rejected by phone".into()),
                        local_path: None,
                    });
                }
            }
            ProtocolMessage::FileComplete(comp) => {
                info!("Received FileComplete for file_id: {}, success: {}", comp.file_id, comp.success);
                let mut transfers_guard = self.incoming_transfers.lock().await;
                if let Some(mut transfer) = transfers_guard.remove(&comp.file_id) {
                    if comp.success {
                        let _ = transfer.file_handle.flush().await;
                        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64;
                        let info = AuraDropReceivedFile {
                            file_id: comp.file_id.clone(),
                            file_name: transfer.file_name.clone(),
                            file_size: transfer.bytes_received,
                            mime_type: transfer.mime_type.clone(),
                            sender_name: transfer.sender_name.clone(),
                            local_path: transfer.local_path.to_string_lossy().to_string(),
                            timestamp_epoch: now,
                        };
                        {
                            let mut files_guard = self.received_files.lock().await;
                            files_guard.insert(0, info.clone());
                        }
                        self.broadcast_event(WebEvent::FileTransferCompleted {
                            file_id: comp.file_id.clone(),
                            file_name: info.file_name.clone(),
                            is_incoming: true,
                            success: true,
                            message: "Saved to Downloads/AuraLock".into(),
                            local_path: Some(info.local_path),
                        });
                    } else {
                        // Transfer cancelled / failed — remove partial file from disk
                        drop(transfer.file_handle);
                        let _ = tokio::fs::remove_file(&transfer.local_path).await;
                        self.broadcast_event(WebEvent::FileTransferCompleted {
                            file_id: comp.file_id.clone(),
                            file_name: transfer.file_name.clone(),
                            is_incoming: true,
                            success: false,
                            message: if comp.message.is_empty() { "Transfer cancelled".into() } else { comp.message.clone() },
                            local_path: None,
                        });
                    }
                } else if !comp.success {
                    self.broadcast_event(WebEvent::FileTransferCompleted {
                        file_id: comp.file_id.clone(),
                        file_name: if comp.file_name.is_empty() { "File".into() } else { comp.file_name.clone() },
                        is_incoming: true,
                        success: false,
                        message: if comp.message.is_empty() { "Transfer cancelled".into() } else { comp.message.clone() },
                        local_path: None,
                    });
                }
            }
            ProtocolMessage::Clipboard(clip) => {
                let is_paired = self.vault_mgr.is_paired();
                let is_authorized = *self.transfer_authorized.lock().await;
                if !is_paired && !is_authorized {
                    warn!("Rejected Clipboard from unauthorized sender: {}", clip.sender_name);
                    return Ok(());
                }

                info!("Received shared clipboard from phone: '{}'", clip.text);
                {
                    let mut guard = self.latest_clipboard.lock().await;
                    *guard = Some(clip.clone());
                }
                self.broadcast_event(WebEvent::ClipboardReceived {
                    text: clip.text,
                    sender_name: clip.sender_name,
                    timestamp_epoch: clip.timestamp_epoch,
                });
            }
            ProtocolMessage::UnpairInitiateFromPhone(req) => {
                info!("Received unpair initiate from phone: {}", req.phone_name);
                let pin = format!("{:06}", rand::random::<u32>() % 900000 + 100000);
                {
                    let mut guard = self.active_phone_unpair_pin.lock().await;
                    *guard = Some(pin.clone());
                }
                self.broadcast_event(WebEvent::PhoneUnpairRequested {
                    phone_name: req.phone_name,
                    pin,
                });
            }
            ProtocolMessage::UnpairReq(req) => {
                info!("Received direct unpair request from phone: {}", req.phone_name);
                if let Ok(vault) = self.vault_mgr.load() {
                    if let Some(paired) = vault.paired_phone {
                        if paired.device_name == req.phone_name || paired.pairing_id == req.phone_name {
                            self.direct_unpair().await;
                        } else {
                            warn!("Rejected UnpairReq from non-paired device: {}", req.phone_name);
                        }
                    } else {
                        self.direct_unpair().await;
                    }
                } else {
                    self.direct_unpair().await;
                }
            }
            ProtocolMessage::TransferAuthReq(req) => {
                info!("Received transfer session auth request from phone");
                let authorized = self.authorize_transfer(&req.pin).await;
                let resp = ProtocolMessage::TransferAuthResp(crate::protocol::TransferAuthResponse {
                    success: authorized,
                    message: if authorized {
                        "Transfer channel authorized".into()
                    } else {
                        "Invalid 4-digit transfer PIN".into()
                    },
                });
                if let Some(tx) = &*self.active_client.lock().await {
                    let _ = tx.send(resp).await;
                }
            }
            ProtocolMessage::RemoteLockReq(_req) => {
                if !self.vault_mgr.is_paired() {
                    warn!("Rejected RemoteLockReq from unpaired client");
                    return Ok(());
                }
                info!("Received manual remote lock request from phone");
                self.state_mgr.set_lock_state(crate::state::SessionLockState::Locked).await;
                lock_pc_screen();
                tokio::time::sleep(tokio::time::Duration::from_millis(400)).await;
                let is_locked = is_pc_screen_locked();
                if let Some(tx) = &*self.active_client.lock().await {
                    let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked }).await;
                }
            }
            ProtocolMessage::UnlockTriggerReq(_req) => {
                if !self.vault_mgr.is_paired() {
                    warn!("Rejected UnlockTriggerReq from unpaired client");
                    return Ok(());
                }
                info!("Received manual unlock trigger request from phone");
                let is_locked = is_pc_screen_locked();
                if !is_locked {
                    info!("PC screen is already UNLOCKED. Notifying phone and skipping challenge creation.");
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked: false }).await;
                    }
                } else {
                    if let Some(tx) = &*self.active_client.lock().await {
                        let _ = tx.send(ProtocolMessage::LockStateChanged { is_locked: true }).await;
                    }
                    // If an active challenge already exists (e.g. from PAM / Windows CP), reuse it so IPC unblocks
                    if let Some(pending_ch) = self.state_mgr.get_latest_valid_challenge().await {
                        info!("Resending active pending challenge {} on phone trigger", pending_ch.challenge_id);
                        let _ = self.push_challenge(pending_ch).await;
                    } else {
                        match self.state_mgr.create_challenge(&self.pc_name).await {
                            Ok((challenge, _rx)) => {
                                let _ = self.push_challenge(challenge).await;
                            }
                            Err(e) => {
                                error!("Failed to create challenge on unlock trigger: {:?}", e);
                            }
                        }
                    }
                }
            }
            _ => {}
        }
        Ok(())
    }
}

/// Helper function to check if Linux / Host Bluetooth subsystem is powered on and active
pub fn is_pc_bluetooth_active() -> bool {
    #[cfg(target_os = "linux")]
    {
        // 1. Direct zero-cost check via sysfs /sys/class/rfkill
        if let Ok(entries) = std::fs::read_dir("/sys/class/rfkill") {
            let mut found_bt = false;
            let mut bt_active = false;
            for entry in entries.flatten() {
                let p = entry.path();
                if let Ok(typ) = std::fs::read_to_string(p.join("type")) {
                    if typ.trim() == "bluetooth" {
                        found_bt = true;
                        if let Ok(st) = std::fs::read_to_string(p.join("state")) {
                            if st.trim() == "1" {
                                bt_active = true;
                            }
                        }
                    }
                }
            }
            if found_bt {
                return bt_active;
            }
        }

        // 2. Fallback check via rfkill command
        if let Ok(output) = std::process::Command::new("rfkill")
            .args(["list", "bluetooth"])
            .output()
        {
            let text = String::from_utf8_lossy(&output.stdout).to_lowercase();
            if text.contains("soft blocked: yes") || text.contains("hard blocked: yes") {
                return false;
            }
            if text.contains("bluetooth") && (text.contains("soft blocked: no") || text.contains("hard blocked: no")) {
                return true;
            }
        }

        // 3. Fallback check via bluetoothctl show
        if let Ok(output) = std::process::Command::new("bluetoothctl")
            .arg("show")
            .output()
        {
            let text = String::from_utf8_lossy(&output.stdout);
            if text.contains("Powered: yes") {
                return true;
            } else if text.contains("Powered: no") {
                return false;
            }
        }

        false
    }
    #[cfg(not(target_os = "linux"))]
    {
        true
    }
}

/// Reliably resolves the best non-loopback local network IP (Wi-Fi / LAN),
/// avoiding internal Docker, bridge, or virtual adapter interfaces.
pub fn get_best_local_ip() -> String {
    // 1. Ask OS routing table via UDP connect (no traffic sent, 100% accurate for active route)
    if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("8.8.8.8:80").is_ok() {
            if let Ok(addr) = socket.local_addr() {
                let ip = addr.ip();
                if !ip.is_loopback() && !ip.is_unspecified() {
                    return ip.to_string();
                }
            }
        }
    }

    // 2. Iterate network interfaces prioritizing Wi-Fi and Ethernet, excluding virtual/docker bridges
    if let Ok(mut interfaces) = local_ip_address::list_afinet_netifas() {
        // Sort prioritizing Wi-Fi (wl) and Ethernet (en, eth)
        interfaces.sort_by(|(name_a, _), (name_b, _)| {
            let prio = |name: &str| {
                let l = name.to_lowercase();
                if l.starts_with("wl") || l.starts_with("wlan") || l.starts_with("wifi") {
                    1
                } else if l.starts_with("en") || l.starts_with("eth") {
                    2
                } else {
                    3
                }
            };
            prio(name_a).cmp(&prio(name_b))
        });

        for (name, ip) in interfaces {
            let lower = name.to_lowercase();
            if lower.starts_with("lo")
                || lower.starts_with("docker")
                || lower.starts_with("br-")
                || lower.starts_with("veth")
                || lower.starts_with("virbr")
                || lower.starts_with("vmnet")
                || lower.starts_with("tun")
                || lower.starts_with("tap")
            {
                continue;
            }
            if let std::net::IpAddr::V4(ipv4) = ip {
                if !ipv4.is_loopback() && !ipv4.is_link_local() {
                    return ipv4.to_string();
                }
            }
        }
    }

    // 3. Fallback
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}

#[cfg(target_os = "linux")]
extern "C" {
    fn getuid() -> u32;
    fn getgid() -> u32;
}

#[cfg(target_os = "linux")]
#[derive(Debug, Clone)]
struct LinuxUserSession {
    pub uid: u32,
    pub gid: u32,
    pub username: String,
    pub homedir: String,
    pub runtime_dir: String,
    pub bus_socket: Option<String>,
    pub display: String,
    pub xauthority: Option<String>,
}

#[cfg(target_os = "linux")]
fn get_linux_user_sessions() -> Vec<LinuxUserSession> {
    let mut sessions = Vec::new();

    // 1. Read /etc/passwd to map UID -> (username, gid, homedir)
    let mut passwd_map: HashMap<u32, (String, u32, String)> = HashMap::new();
    if let Ok(content) = std::fs::read_to_string("/etc/passwd") {
        for line in content.lines() {
            let parts: Vec<&str> = line.split(':').collect();
            if parts.len() >= 6 {
                let user = parts[0].to_string();
                if let (Ok(uid), Ok(gid)) = (parts[2].parse::<u32>(), parts[3].parse::<u32>()) {
                    let home = parts[5].to_string();
                    passwd_map.insert(uid, (user, gid, home));
                }
            }
        }
    }

    // 2. Discover active users in /run/user/* (handles systemd root daemons)
    if let Ok(entries) = std::fs::read_dir("/run/user") {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if let Ok(uid) = name.parse::<u32>() {
                        if uid >= 1000 {
                            let (username, gid, homedir) = passwd_map
                                .get(&uid)
                                .cloned()
                                .unwrap_or_else(|| (format!("user{}", uid), uid, format!("/home/user{}", uid)));

                            let runtime_dir = format!("/run/user/{}", uid);
                            let bus_path = format!("/run/user/{}/bus", uid);
                            let bus_socket = if std::path::Path::new(&bus_path).exists() {
                                Some(bus_path)
                            } else {
                                None
                            };

                            let display = std::env::var("DISPLAY").unwrap_or_else(|_| ":0".to_string());

                            // Find .Xauthority
                            let user_xauth = format!("{}/.Xauthority", homedir);
                            let run_xauth = format!("/run/user/{}/.Xauthority", uid);
                            let gdm_xauth = format!("/run/user/{}/gdm/Xauthority", uid);

                            let xauthority = if std::path::Path::new(&user_xauth).exists() {
                                Some(user_xauth)
                            } else if std::path::Path::new(&run_xauth).exists() {
                                Some(run_xauth)
                            } else if std::path::Path::new(&gdm_xauth).exists() {
                                Some(gdm_xauth)
                            } else {
                                std::env::var("XAUTHORITY").ok()
                            };

                            sessions.push(LinuxUserSession {
                                uid,
                                gid,
                                username,
                                homedir,
                                runtime_dir,
                                bus_socket,
                                display,
                                xauthority,
                            });
                        }
                    }
                }
            }
        }
    }

    // Fallback if no /run/user sessions found
    if sessions.is_empty() {
        let current_uid = unsafe { getuid() };
        let current_gid = unsafe { getgid() };
        let username = std::env::var("USER").unwrap_or_else(|_| "current".to_string());
        let homedir = std::env::var("HOME").unwrap_or_else(|_| "/root".to_string());
        let runtime_dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| format!("/run/user/{}", current_uid));
        let bus_socket = std::env::var("DBUS_SESSION_BUS_ADDRESS")
            .ok()
            .or_else(|| {
                let p = format!("{}/bus", runtime_dir);
                if std::path::Path::new(&p).exists() { Some(p) } else { None }
            });
        let display = std::env::var("DISPLAY").unwrap_or_else(|_| ":0".to_string());
        let xauthority = std::env::var("XAUTHORITY").ok();

        sessions.push(LinuxUserSession {
            uid: current_uid,
            gid: current_gid,
            username,
            homedir,
            runtime_dir,
            bus_socket,
            display,
            xauthority,
        });
    }

    sessions
}

#[cfg(target_os = "linux")]
fn spawn_in_user_session(session: &LinuxUserSession, program: &str, args: &[&str]) {
    use std::os::unix::process::CommandExt;
    let mut cmd = std::process::Command::new(program);
    cmd.args(args);
    cmd.env("XDG_RUNTIME_DIR", &session.runtime_dir);
    cmd.env("DISPLAY", &session.display);
    if let Some(ref bus) = session.bus_socket {
        cmd.env("DBUS_SESSION_BUS_ADDRESS", format!("unix:path={}", bus));
    }
    if let Some(ref xauth) = session.xauthority {
        cmd.env("XAUTHORITY", xauth);
    }
    cmd.env("USER", &session.username);
    cmd.env("HOME", &session.homedir);

    // If daemon is root (uid 0), drop to target user's uid and gid!
    if unsafe { getuid() } == 0 && session.uid != 0 {
        cmd.uid(session.uid);
        cmd.gid(session.gid);
    }

    let _ = cmd.spawn();
}

#[cfg(target_os = "linux")]
fn run_in_user_session(session: &LinuxUserSession, program: &str, args: &[&str]) -> Option<String> {
    use std::os::unix::process::CommandExt;
    let mut cmd = std::process::Command::new(program);
    cmd.args(args);
    cmd.env("XDG_RUNTIME_DIR", &session.runtime_dir);
    cmd.env("DISPLAY", &session.display);
    if let Some(ref bus) = session.bus_socket {
        cmd.env("DBUS_SESSION_BUS_ADDRESS", format!("unix:path={}", bus));
    }
    if let Some(ref xauth) = session.xauthority {
        cmd.env("XAUTHORITY", xauth);
    }
    cmd.env("USER", &session.username);
    cmd.env("HOME", &session.homedir);

    if unsafe { getuid() } == 0 && session.uid != 0 {
        cmd.uid(session.uid);
        cmd.gid(session.gid);
    }

    if let Ok(output) = cmd.output() {
        Some(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        None
    }
}

#[cfg(target_os = "linux")]
fn get_active_logind_sessions() -> Vec<String> {
    let mut sessions = Vec::new();
    if let Ok(output) = std::process::Command::new("loginctl")
        .args(["list-sessions", "--no-legend"])
        .output()
    {
        if let Ok(text) = String::from_utf8(output.stdout) {
            for line in text.lines() {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if let Some(session_id) = parts.first() {
                    if !session_id.is_empty() {
                        sessions.push(session_id.to_string());
                    }
                }
            }
        }
    }
    sessions
}

/// Helper function to check if the PC desktop screen is currently locked
pub fn is_pc_screen_locked() -> bool {
    #[cfg(target_os = "linux")]
    {
        // 1. Check systemd logind sessions for LockedHint=yes
        for session_id in get_active_logind_sessions() {
            if let Ok(output) = std::process::Command::new("loginctl")
                .args(["show-session", &session_id, "-p", "LockedHint"])
                .output()
            {
                let text = String::from_utf8_lossy(&output.stdout);
                if text.contains("LockedHint=yes") {
                    return true;
                }
            }
        }

        // 2. Check active user session screensavers (Cinnamon, GNOME, MATE, FreeDesktop, KDE)
        let sessions = get_linux_user_sessions();
        for session in &sessions {
            // Cinnamon screensaver status query
            if let Some(out) = run_in_user_session(session, "cinnamon-screensaver-command", &["-q"]) {
                let lower = out.to_lowercase();
                if lower.contains("inactive") {
                    // explicitly inactive, do not match active
                } else if lower.contains("is active") || (lower.contains("active") && !lower.contains("inactive")) || lower.contains("locked") {
                    return true;
                }
            }

            // GNOME screensaver status query
            if let Some(out) = run_in_user_session(session, "gnome-screensaver-command", &["-q"]) {
                let lower = out.to_lowercase();
                if lower.contains("inactive") {
                    // explicitly inactive
                } else if lower.contains("is active") || (lower.contains("active") && !lower.contains("inactive")) || lower.contains("locked") {
                    return true;
                }
            }

            // MATE screensaver status query
            if let Some(out) = run_in_user_session(session, "mate-screensaver-command", &["-q"]) {
                let lower = out.to_lowercase();
                if lower.contains("inactive") {
                    // explicitly inactive
                } else if lower.contains("is active") || (lower.contains("active") && !lower.contains("inactive")) || lower.contains("locked") {
                    return true;
                }
            }

            // Cinnamon DBus query
            if let Some(out) = run_in_user_session(session, "dbus-send", &[
                "--session",
                "--dest=org.cinnamon.ScreenSaver",
                "--type=method_call",
                "--print-reply",
                "/org/cinnamon/ScreenSaver",
                "org.cinnamon.ScreenSaver.GetActive",
            ]) {
                if out.contains("boolean true") {
                    return true;
                }
            }

            // GNOME DBus query
            if let Some(out) = run_in_user_session(session, "dbus-send", &[
                "--session",
                "--dest=org.gnome.ScreenSaver",
                "--type=method_call",
                "--print-reply",
                "/org/gnome/ScreenSaver",
                "org.gnome.ScreenSaver.GetActive",
            ]) {
                if out.contains("boolean true") {
                    return true;
                }
            }

            // FreeDesktop DBus query
            if let Some(out) = run_in_user_session(session, "dbus-send", &[
                "--session",
                "--dest=org.freedesktop.ScreenSaver",
                "--type=method_call",
                "--print-reply",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.GetActive",
            ]) {
                if out.contains("boolean true") {
                    return true;
                }
            }

            // KDE Plasma ScreenSaver DBus query
            if let Some(out) = run_in_user_session(session, "dbus-send", &[
                "--session",
                "--dest=org.kde.screensaver",
                "--type=method_call",
                "--print-reply",
                "/ScreenSaver",
                "org.freedesktop.ScreenSaver.GetActive",
            ]) {
                if out.contains("boolean true") {
                    return true;
                }
            }
        }

        false
    }
    #[cfg(target_os = "windows")]
    {
        // 1. Direct Win32 API check: OpenInputDesktop
        // When desktop is locked / switched to Winlogon secure desktop, OpenInputDesktop fails with ERROR_ACCESS_DENIED (5)
        #[link(name = "user32")]
        extern "system" {
            fn OpenInputDesktop(dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
            fn CloseDesktop(hDesktop: isize) -> i32;
        }
        #[link(name = "kernel32")]
        extern "system" {
            fn GetLastError() -> u32;
        }

        unsafe {
            let desk = OpenInputDesktop(0, 0, 0x0100 /* DESKTOP_SWITCHDESKTOP */);
            if desk == 0 {
                let err = GetLastError();
                if err == 5 {
                    return true;
                }
            } else {
                CloseDesktop(desk);
            }
        }

        // 2. Check if Windows Lock Screen / LogonUI is running
        let mut cmd = std::process::Command::new("tasklist");
        cmd.args(["/FI", "IMAGENAME eq LogonUI.exe", "/NH"]);
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        if let Ok(output) = cmd.output() {
            let text = String::from_utf8_lossy(&output.stdout);
            if text.contains("LogonUI.exe") {
                return true;
            }
        }

        false
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        false
    }
}

/// Helper function to trigger Linux / Windows / macOS desktop lockscreen
pub fn lock_pc_screen() {
    info!("🔒 Triggering PC desktop screen lock");

    #[cfg(target_os = "linux")]
    {
        // 1. Systemd loginctl lock-session for active graphical sessions
        for session_id in get_active_logind_sessions() {
            let _ = std::process::Command::new("loginctl")
                .args(["lock-session", &session_id, "--no-ask-password"])
                .spawn();
        }

        let _ = std::process::Command::new("loginctl")
            .args(["lock-sessions", "--no-ask-password"])
            .spawn();

        // 2. Trigger desktop-specific screensaver cleanly per user session
        let sessions = get_linux_user_sessions();
        for session in &sessions {
            let de = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().to_lowercase();
            if de.contains("cinnamon") {
                spawn_in_user_session(session, "cinnamon-screensaver-command", &["--lock"]);
            } else if de.contains("gnome") {
                spawn_in_user_session(session, "gnome-screensaver-command", &["-l"]);
            } else if de.contains("mate") {
                spawn_in_user_session(session, "mate-screensaver-command", &["-l"]);
            } else if de.contains("kde") {
                spawn_in_user_session(session, "qdbus", &["org.freedesktop.ScreenSaver", "/ScreenSaver", "Lock"]);
            } else if de.contains("xfce") {
                spawn_in_user_session(session, "xflock4", &[]);
            } else {
                // Fallback: try cinnamon screensaver then xdg-screensaver
                spawn_in_user_session(session, "cinnamon-screensaver-command", &["--lock"]);
                spawn_in_user_session(session, "xdg-screensaver", &["lock"]);
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        // 1. Direct Win32 API Call (Instant & Reliable)
        #[link(name = "user32")]
        extern "system" {
            fn LockWorkStation() -> i32;
        }
        unsafe {
            LockWorkStation();
        }

        // 2. Fallback via rundll32
        let _ = std::process::Command::new("rundll32.exe")
            .args(["user32.dll,LockWorkStation"])
            .spawn();
    }

    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession")
            .arg("-suspend")
            .spawn();
        let _ = std::process::Command::new("pmset")
            .arg("displaysleepnow")
            .spawn();
    }
}

/// Helper function to trigger Linux / Windows / macOS desktop unlock / dismiss screensaver
pub fn unlock_pc_screen() {
    info!("🔓 Triggering PC desktop screen unlock / dismiss screensaver");

    #[cfg(target_os = "linux")]
    {
        // 1. System-wide D-Bus signal to systemd-logind
        let _ = std::process::Command::new("dbus-send")
            .args([
                "--system",
                "--type=method_call",
                "--dest=org.freedesktop.login1",
                "/org/freedesktop/login1",
                "org.freedesktop.login1.Manager.UnlockSessions",
            ])
            .spawn();

        let _ = std::process::Command::new("gdbus")
            .args([
                "call",
                "--system",
                "--dest", "org.freedesktop.login1",
                "--object-path", "/org/freedesktop/login1",
                "--method", "org.freedesktop.login1.Manager.UnlockSessions",
            ])
            .spawn();

        // 2. Standard systemd loginctl unlock-sessions
        let _ = std::process::Command::new("loginctl")
            .args(["unlock-sessions", "--no-ask-password"])
            .spawn();

        for session_id in get_active_logind_sessions() {
            let _ = std::process::Command::new("loginctl")
                .args(["unlock-session", &session_id, "--no-ask-password"])
                .spawn();
        }

        let _ = std::process::Command::new("loginctl")
            .args(["unlock-session", "--no-ask-password"])
            .spawn();

        // 3. Deactivate screensavers in user session context
        let sessions = get_linux_user_sessions();
        for session in &sessions {
            // Cinnamon ScreenSaver Deactivate
            spawn_in_user_session(session, "dbus-send", &["--session", "--type=method_call", "--dest=org.cinnamon.ScreenSaver", "/org/cinnamon/ScreenSaver", "org.cinnamon.ScreenSaver.SetActive", "boolean:false"]);

            // GNOME ScreenSaver Deactivate
            spawn_in_user_session(session, "dbus-send", &["--session", "--type=method_call", "--dest=org.gnome.ScreenSaver", "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver.SetActive", "boolean:false"]);

            // FreeDesktop ScreenSaver Deactivate
            spawn_in_user_session(session, "dbus-send", &["--session", "--type=method_call", "--dest=org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver.SetActive", "boolean:false"]);

            // MATE ScreenSaver Deactivate
            spawn_in_user_session(session, "dbus-send", &["--session", "--type=method_call", "--dest=org.mate.ScreenSaver", "/org/mate/ScreenSaver", "org.mate.ScreenSaver.SetActive", "boolean:false"]);

            // Cinnamon screensaver deactivate command
            spawn_in_user_session(session, "cinnamon-screensaver-command", &["-d"]);
            spawn_in_user_session(session, "cinnamon-screensaver-command", &["--deactivate"]);

            // GNOME screensaver deactivate fallback
            spawn_in_user_session(session, "gnome-screensaver-command", &["-d"]);

            // MATE screensaver deactivate fallback
            spawn_in_user_session(session, "mate-screensaver-command", &["-d"]);

            // XDG screensaver reset fallback
            spawn_in_user_session(session, "xdg-screensaver", &["reset"]);

            // KDE qdbus deactivate
            spawn_in_user_session(session, "qdbus", &["org.freedesktop.ScreenSaver", "/ScreenSaver", "SetActive", "false"]);
        }
    }
}


