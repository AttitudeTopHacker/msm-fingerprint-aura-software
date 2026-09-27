/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::error::{AuraLockError, Result};
use crate::protocol::{
    PairingQRData, PairingResponse, ProtocolMessage, UnlockChallenge,
    UnlockResult, BLE_SERVICE_UUID, PROTOCOL_VERSION,
};
use crate::state::StateManager;
use crate::vault::{PairedPhone, VaultManager};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tracing::{error, info, warn};

pub struct NetworkService {
    port: u16,
    pc_name: String,
    pc_id: String,
    state_mgr: StateManager,
    #[allow(dead_code)]
    vault_mgr: Arc<VaultManager>,
    active_client: Arc<Mutex<Option<tokio::sync::mpsc::Sender<ProtocolMessage>>>>,
    active_pairing_pin: Arc<Mutex<Option<String>>>,
}

impl NetworkService {
    pub fn new(
        port: u16,
        pc_name: String,
        pc_id: String,
        state_mgr: StateManager,
        vault_mgr: Arc<VaultManager>,
    ) -> Self {
        Self {
            port,
            pc_name,
            pc_id,
            state_mgr,
            vault_mgr,
            active_client: Arc::new(Mutex::new(None)),
            active_pairing_pin: Arc::new(Mutex::new(None)),
        }
    }

    /// Sets an ephemeral 6-digit PIN for an active pairing session
    pub async fn set_pairing_pin(&self, pin: String) {
        let mut guard = self.active_pairing_pin.lock().await;
        *guard = Some(pin);
    }

    /// Generates QR Code data payload for pairing
    pub async fn generate_qr_payload(&self, local_ip: &str, ephem_pub_hex: &str) -> PairingQRData {
        let pin = format!("{:06}", rand::random::<u32>() % 1_000_000);
        self.set_pairing_pin(pin.clone()).await;

        PairingQRData {
            v: PROTOCOL_VERSION,
            pc_id: self.pc_id.clone(),
            pc_name: self.pc_name.clone(),
            ephem_pub_hex: ephem_pub_hex.to_string(),
            ble_uuid: BLE_SERVICE_UUID.to_string(),
            ip: local_ip.to_string(),
            port: self.port,
            pin,
        }
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

    /// Starts the TCP listener for incoming mobile connections (over local Wi-Fi)
    pub async fn run_listener(self: Arc<Self>) -> Result<()> {
        let addr = SocketAddr::from(([0, 0, 0, 0], self.port));
        let listener = TcpListener::bind(addr).await?;
        info!("AuraLock Network daemon listening on {}", addr);

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

        let (tx, mut rx) = tokio::sync::mpsc::channel::<ProtocolMessage>(32);

        // Register active client channel
        {
            let mut guard = self.active_client.lock().await;
            *guard = Some(tx);
        }

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

            self.process_message(msg).await?;
        }

        // Cleanup on disconnect
        {
            let mut guard = self.active_client.lock().await;
            *guard = None;
        }
        writer_task.abort();

        Ok(())
    }

    async fn process_message(&self, msg: ProtocolMessage) -> Result<()> {
        match msg {
            ProtocolMessage::PairingReq(req) => {
                info!("Received pairing request from phone: {}", req.phone_name);
                let guard = self.active_pairing_pin.lock().await;
                let expected_pin = guard.clone();

                if let Some(pin) = expected_pin {
                    if pin == req.pin {
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

                        let resp = ProtocolMessage::PairingResp(PairingResponse {
                            success: true,
                            pairing_id: paired_phone.pairing_id.clone(),
                            message: "Device paired successfully".into(),
                        });

                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(resp).await;
                        }
                    } else {
                        warn!("Pairing PIN mismatch from phone");
                        let resp = ProtocolMessage::PairingResp(PairingResponse {
                            success: false,
                            pairing_id: String::new(),
                            message: "Invalid PIN".into(),
                        });
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(resp).await;
                        }
                    }
                }
            }
            ProtocolMessage::Response(resp) => {
                info!("Received biometric unlock response for challenge {}", resp.challenge_id);
                let result = self.state_mgr.verify_unlock_response(resp).await;
                match result {
                    Ok(res) => {
                        if let Some(tx) = &*self.active_client.lock().await {
                            let _ = tx.send(ProtocolMessage::Result(res)).await;
                        }
                    }
                    Err(e) => {
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
                }
            }
            _ => {}
        }
        Ok(())
    }
}
