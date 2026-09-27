/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

use crate::error::{AuraLockError, Result};
use crate::network::NetworkService;
use crate::state::StateManager;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tracing::{error, info, warn};

#[cfg(unix)]
pub const DEFAULT_UNIX_SOCKET_PATH: &str = "/tmp/auralock_daemon.sock";

#[cfg(windows)]
pub const DEFAULT_NAMED_PIPE_PATH: &str = r"\\.\pipe\AuraLockService";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "command", content = "payload")]
pub enum IpcRequest {
    TriggerUnlockRequest { username: String },
    SetLockState { is_locked: bool },
    GetDaemonStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status", content = "payload")]
pub enum IpcResponse {
    Success { message: String },
    UnlockApproved,
    UnlockDenied { reason: String },
    DaemonStatus { is_locked: bool, paired_device: Option<String> },
    Error { error: String },
}

pub struct IpcServer {
    state_mgr: StateManager,
    net_service: Arc<NetworkService>,
    pc_name: String,
}

impl IpcServer {
    pub fn new(state_mgr: StateManager, net_service: Arc<NetworkService>, pc_name: String) -> Self {
        Self {
            state_mgr,
            net_service,
            pc_name,
        }
    }

    #[cfg(unix)]
    pub async fn run_unix_socket(self: Arc<Self>, socket_path: &str) -> Result<()> {
        use tokio::net::UnixListener;
        let _ = std::fs::remove_file(socket_path);

        let listener = UnixListener::bind(socket_path)?;
        info!("AuraLock IPC server listening on Unix socket: {}", socket_path);

        // Set strict permissions (read/write for current user only)
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(socket_path)?.permissions();
            perms.set_mode(0o600);
            std::fs::set_permissions(socket_path, perms)?;
        }

        loop {
            match listener.accept().await {
                Ok((stream, _)) => {
                    let server = Arc::clone(&self);
                    tokio::spawn(async move {
                        if let Err(e) = server.handle_unix_client(stream).await {
                            error!("IPC client error: {:?}", e);
                        }
                    });
                }
                Err(e) => {
                    warn!("Failed to accept IPC connection: {:?}", e);
                }
            }
        }
    }

    #[cfg(unix)]
    async fn handle_unix_client(&self, mut stream: tokio::net::UnixStream) -> Result<()> {
        let (reader, mut writer) = stream.split();
        let mut buf_reader = BufReader::new(reader);
        let mut line = String::new();

        while buf_reader.read_line(&mut line).await? > 0 {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }

            let req: IpcRequest = match serde_json::from_str(trimmed) {
                Ok(r) => r,
                Err(e) => {
                    let err_resp = IpcResponse::Error { error: format!("Malformed request: {e}") };
                    let json = serde_json::to_string(&err_resp)?;
                    writer.write_all(format!("{}\n", json).as_bytes()).await?;
                    continue;
                }
            };

            let resp = self.process_ipc_request(req).await;
            let json = serde_json::to_string(&resp)?;
            writer.write_all(format!("{}\n", json).as_bytes()).await?;
            line.clear();
        }

        Ok(())
    }

    async fn process_ipc_request(&self, req: IpcRequest) -> IpcResponse {
        match req {
            IpcRequest::TriggerUnlockRequest { username } => {
                info!("IPC received unlock trigger for user '{}'", username);
                match self.state_mgr.create_challenge(&self.pc_name).await {
                    Ok((challenge, rx)) => {
                        if let Err(e) = self.net_service.push_challenge(challenge).await {
                            return IpcResponse::UnlockDenied {
                                reason: format!("Could not reach mobile phone: {e}"),
                            };
                        }

                        // Await biometric approval with a 10-second timeout
                        match tokio::time::timeout(std::time::Duration::from_secs(10), rx).await {
                            Ok(Ok(true)) => {
                                info!("Biometric approval received via IPC!");
                                IpcResponse::UnlockApproved
                            }
                            Ok(Ok(false)) => {
                                warn!("Biometric signature failed validation");
                                IpcResponse::UnlockDenied { reason: "Biometric signature invalid".into() }
                            }
                            Ok(Err(_)) => {
                                IpcResponse::UnlockDenied { reason: "Challenge cancelled".into() }
                            }
                            Err(_) => {
                                warn!("Biometric prompt timed out (10s)");
                                IpcResponse::UnlockDenied { reason: "Biometric prompt timed out on phone".into() }
                            }
                        }
                    }
                    Err(e) => IpcResponse::UnlockDenied { reason: e.to_string() },
                }
            }
            IpcRequest::SetLockState { is_locked } => {
                let state = if is_locked {
                    crate::state::SessionLockState::Locked
                } else {
                    crate::state::SessionLockState::Unlocked
                };
                self.state_mgr.set_lock_state(state).await;
                IpcResponse::Success { message: "Lock state updated".into() }
            }
            IpcRequest::GetDaemonStatus => {
                IpcResponse::DaemonStatus {
                    is_locked: false,
                    paired_device: None,
                }
            }
        }
    }
}

/// Client helper used by PAM and Credential Provider to talk to local AuraLock daemon
pub struct IpcClient;

impl IpcClient {
    #[cfg(unix)]
    pub async fn request_unlock(socket_path: &str, username: &str) -> Result<bool> {
        use tokio::net::UnixStream;

        let mut stream = UnixStream::connect(socket_path).await?;
        let (reader, mut writer) = stream.split();
        let mut buf_reader = BufReader::new(reader);

        let req = IpcRequest::TriggerUnlockRequest { username: username.to_string() };
        let json_req = serde_json::to_string(&req)?;
        writer.write_all(format!("{}\n", json_req).as_bytes()).await?;

        let mut response_line = String::new();
        buf_reader.read_line(&mut response_line).await?;

        let resp: IpcResponse = serde_json::from_str(response_line.trim())
            .map_err(|e| AuraLockError::ProtocolError(format!("Failed to parse daemon response: {e}")))?;

        match resp {
            IpcResponse::UnlockApproved => Ok(true),
            IpcResponse::UnlockDenied { reason } => {
                warn!("Unlock denied by daemon: {}", reason);
                Ok(false)
            }
            IpcResponse::Error { error } => Err(AuraLockError::ProtocolError(error)),
            _ => Ok(false),
        }
    }
}
