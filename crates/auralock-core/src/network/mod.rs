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
    incoming_transfers: Arc<Mutex<HashMap<String, IncomingTransfer>>>,
}

pub fn get_default_downloads_dir() -> PathBuf {
    #[cfg(unix)]
    {
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
        self.broadcast_event(WebEvent::DeviceUnpaired);
        self.broadcast_event(WebEvent::ClientDisconnected);

        // Notify phone if connected so it unpairs too
        if let Some(tx) = &*self.active_client.lock().await {
            let resp = ProtocolMessage::UnpairResp(crate::protocol::RemoteUnpairResponse {
                success: true,
                message: "Device successfully unpaired".into(),
            });
            let _ = tx.send(resp).await;
        }
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

        // 2. Stream Chunks (64 KB each)
        let chunk_size = 64 * 1024;
        let total_chunks = if file_size == 0 {
            1
        } else {
            ((file_size + chunk_size as u64 - 1) / chunk_size as u64) as u32
        };

        let engine = base64::engine::general_purpose::STANDARD;
        let mut bytes_sent: u64 = 0;

        for (idx, chunk) in file_bytes.chunks(chunk_size).enumerate() {
            let chunk_msg = ProtocolMessage::FileChunk(FileTransferChunk {
                file_id: file_id.clone(),
                chunk_index: idx as u32,
                total_chunks,
                data_base64: engine.encode(chunk),
            });

            tx.send(chunk_msg)
                .await
                .map_err(|e| AuraLockError::ProtocolError(format!("Failed to send chunk: {e}")))?;

            bytes_sent += chunk.len() as u64;
            let progress = (bytes_sent as f32) / (file_size.max(1) as f32);

            self.broadcast_event(WebEvent::FileTransferProgress {
                file_id: file_id.clone(),
                file_name: file_name.clone(),
                is_incoming: false,
                progress,
                bytes_transferred: bytes_sent,
                total_bytes: file_size,
            });

            tokio::time::sleep(tokio::time::Duration::from_millis(5)).await;
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

        // Spawn real-time Bluetooth status watcher (checks every 500ms and sends on state change)
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
        }
        self.broadcast_event(WebEvent::ClientDisconnected);
        writer_task.abort();
        bt_watcher_task.abort();

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
            }
            ProtocolMessage::PairingReq(req) => {
                info!("Received pairing request from phone: {}", req.phone_name);

                let active_pin = self.active_pairing_pin.lock().await.clone();
                let recent_pins = self.recent_pairing_pins.lock().await.clone();
                let latest_qr_pin = self.latest_qr_data.lock().await.as_ref().map(|q| q.pin.clone());

                let pin_matches = if let Some(ref p) = active_pin {
                    p == &req.pin || recent_pins.contains(&req.pin) || latest_qr_pin.as_ref() == Some(&req.pin)
                } else if !recent_pins.is_empty() {
                    recent_pins.contains(&req.pin)
                } else if let Some(ref qp) = latest_qr_pin {
                    qp == &req.pin
                } else {
                    // No PIN was generated yet on PC
                    true
                };

                if pin_matches {
                    let now = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap()
                        .as_secs() as i64;
                    let paired_phone = PairedPhone {
                        device_name: req.phone_name.clone(),
                        public_key_hex: req.phone_ed25519_pub_hex,
                        pairing_id: format!("pair_{:08x}", rand::random::<u32>()),
                        paired_at_epoch: now,
                    };

                    info!("Phone {} successfully paired with ID {}", req.phone_name, paired_phone.pairing_id);

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
                    warn!("Pairing PIN mismatch from phone (received: '{}')", req.pin);
                    let resp = ProtocolMessage::PairingResp(PairingResponse {
                        success: false,
                        pairing_id: String::new(),
                        message: format!("Pairing PIN '{}' was rejected by PC. Please check the PIN on your PC screen.", req.pin),
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
                        unlock_pc_screen();
                        self.broadcast_event(WebEvent::UnlockEvent {
                            success: res.success,
                            message: res.message.clone(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(ProtocolMessage::Result(res)).await;
                        }
                    }
                    Err(e) => {
                        self.broadcast_event(WebEvent::UnlockEvent {
                            success: false,
                            message: e.to_string(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
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
                }
            }
            ProtocolMessage::FileOffer(offer) => {
                info!("Incoming AuraDrop file offer from {}: {} ({} bytes)", offer.sender_name, offer.file_name, offer.file_size);
                let downloads_dir = get_default_downloads_dir();
                
                // Sanitize file name
                let safe_name = PathBuf::from(&offer.file_name)
                    .file_name()
                    .map(|f| f.to_string_lossy().to_string())
                    .unwrap_or_else(|| "downloaded_file.bin".to_string());
                
                let target_path = downloads_dir.join(&safe_name);
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
                let engine = base64::engine::general_purpose::STANDARD;
                let decoded_bytes = engine.decode(&chunk.data_base64).unwrap_or_default();
                let mut completed_info: Option<AuraDropReceivedFile> = None;

                {
                    let mut guard = self.incoming_transfers.lock().await;
                    if let Some(transfer) = guard.get_mut(&chunk.file_id) {
                        let _ = transfer.file_handle.write_all(&decoded_bytes).await;
                        transfer.bytes_received += decoded_bytes.len() as u64;

                        let progress = (transfer.bytes_received as f32) / (transfer.file_size.max(1) as f32);
                        self.broadcast_event(WebEvent::FileTransferProgress {
                            file_id: chunk.file_id.clone(),
                            file_name: transfer.file_name.clone(),
                            is_incoming: true,
                            progress,
                            bytes_transferred: transfer.bytes_received,
                            total_bytes: transfer.file_size,
                        });

                        if chunk.chunk_index + 1 >= chunk.total_chunks || transfer.bytes_received >= transfer.file_size {
                            let _ = transfer.file_handle.flush().await;
                            let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64;
                            completed_info = Some(AuraDropReceivedFile {
                                file_id: chunk.file_id.clone(),
                                file_name: transfer.file_name.clone(),
                                file_size: transfer.file_size,
                                mime_type: transfer.mime_type.clone(),
                                sender_name: transfer.sender_name.clone(),
                                local_path: transfer.local_path.to_string_lossy().to_string(),
                                timestamp_epoch: now,
                            });
                        }
                    }
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
            ProtocolMessage::Clipboard(clip) => {
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
                self.direct_unpair().await;
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
                info!("Received manual remote lock request from phone");
                lock_pc_screen();
            }
            ProtocolMessage::UnlockTriggerReq(_req) => {
                info!("Received manual unlock trigger request from phone");
                match self.state_mgr.create_challenge(&self.pc_name).await {
                    Ok((challenge, _rx)) => {
                        let _ = self.push_challenge(challenge).await;
                    }
                    Err(e) => {
                        error!("Failed to create challenge on unlock trigger: {:?}", e);
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

/// Helper function to trigger Linux desktop lockscreen
pub fn lock_pc_screen() {
    #[cfg(target_os = "linux")]
    {
        // 1. Standard systemd loginctl lock
        let _ = std::process::Command::new("loginctl")
            .arg("lock-session")
            .spawn();

        // 2. GNOME screensaver lock fallback
        let _ = std::process::Command::new("gnome-screensaver-command")
            .arg("-l")
            .spawn();

        // 3. XDG screensaver lock fallback
        let _ = std::process::Command::new("xdg-screensaver")
            .arg("lock")
            .spawn();
    }
}

/// Helper function to trigger Linux desktop unlock / dismiss screensaver
pub fn unlock_pc_screen() {
    #[cfg(target_os = "linux")]
    {
        // 1. Standard systemd loginctl unlock
        let _ = std::process::Command::new("loginctl")
            .arg("unlock-session")
            .spawn();

        // 2. GNOME screensaver deactivate fallback
        let _ = std::process::Command::new("gnome-screensaver-command")
            .arg("-d")
            .spawn();

        // 3. XDG screensaver reset fallback
        let _ = std::process::Command::new("xdg-screensaver")
            .arg("reset")
            .spawn();
    }
}


