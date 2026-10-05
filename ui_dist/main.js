let currentPin = "";
let pairingQrObj = null;
let isPairedState = false;
let isPhoneConnectedState = false;

// Helper to check if Tauri API is available
function checkIsTauri() {
  return typeof window !== 'undefined' && (
    !!window.__TAURI__ || 
    !!window.__TAURI_METADATA__ || 
    !!window.__TAURI_INTERNALS__
  );
}

async function invokeCommand(cmd, args = {}) {
  try {
    if (window.__TAURI__?.core?.invoke) {
      return await window.__TAURI__.core.invoke(cmd, args);
    }
    if (window.__TAURI__?.invoke) {
      return await window.__TAURI__.invoke(cmd, args);
    }
    if (window.__TAURI__?.tauri?.invoke) {
      return await window.__TAURI__.tauri.invoke(cmd, args);
    }
    if (window.__TAURI_INTERNALS__?.invoke) {
      return await window.__TAURI_INTERNALS__.invoke(cmd, args);
    }
  } catch (e) {
    console.warn("Tauri invoke error for " + cmd, e);
  }
  return null;
}

window.switchTab = function (tabId) {
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

  const btn = Array.from(document.querySelectorAll('.tab-btn')).find(b => b.getAttribute('onclick')?.includes(tabId));
  if (btn) btn.classList.add('active');

  const content = document.getElementById('tab-' + tabId);
  if (content) content.classList.add('active');
};

function showToast(msg, icon = "✅") {
  const toast = document.getElementById('toast');
  if (!toast) return;
  const iconEl = document.getElementById('toast-icon');
  const msgEl = document.getElementById('toast-message');
  if (iconEl) iconEl.innerText = icon;
  if (msgEl) msgEl.innerText = msg;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 3500);
}

function log(msg, type = "info") {
  const container = document.getElementById('log-container');
  if (!container) return;
  const time = new Date().toLocaleTimeString();
  const div = document.createElement('div');
  div.className = 'log-entry';
  div.innerHTML = `<span class="log-time">[${time}]</span> <span class="log-${type}">${msg}</span>`;
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

window.clearLogs = function () {
  const c = document.getElementById('log-container');
  if (c) c.innerHTML = '';
};

// UNPAIR MODAL & ACTIONS
window.openUnpairModal = function () {
  const modal = document.getElementById('unpair-modal');
  if (modal) modal.classList.add('show');
};

window.closeUnpairModal = function () {
  const modal = document.getElementById('unpair-modal');
  if (modal) modal.classList.remove('show');
};

window.executeDirectUnpair = async function () {
  try {
    window.closeUnpairModal();
    let res = null;

    // Send unpair command to background daemon REST API first
    try {
      const resp = await fetch('http://127.0.0.1:47891/api/vault/unpair', { method: 'POST' });
      if (resp.ok) res = await resp.json();
    } catch (_) {}

    // Also notify Tauri local state if running in Tauri desktop wrapper
    if (checkIsTauri()) {
      try {
        const tauriRes = await invokeCommand('unpair_device');
        if (!res) res = tauriRes;
      } catch (_) {}
    }

    // Instantly transition UI to unpaired state
    isPairedState = false;
    isPhoneConnectedState = false;
    const unpairedView = document.getElementById('unpaired-view');
    const pairedView = document.getElementById('paired-view');
    if (unpairedView) unpairedView.style.display = 'grid';
    if (pairedView) pairedView.style.display = 'none';
    pairingQrObj = null;

    showToast("Device successfully unpaired!", "🗑️");
    log("Device successfully unpaired from PC", "warn");

    // Immediately fetch updated status and generate fresh QR
    await fetchStatus();
    await fetchQr();
  } catch (e) {
    window.closeUnpairModal();
    showToast("Error unpairing device", "❌");
  }
};

// FETCH STATUS
async function fetchStatus() {
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/status');
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('get_daemon_status');
    }

    if (!data) return;

    const pcNameEl = document.getElementById('pc-name-val');
    if (pcNameEl) pcNameEl.innerText = data.pc_name || 'AuraLock-PC';
    const pcPortEl = document.getElementById('pc-port-val');
    if (pcPortEl) pcPortEl.innerText = data.port || 47890;
    const setupPort = document.getElementById('setup-port');
    if (setupPort) setupPort.innerText = data.port || 47890;

    // Pairing State Management (Single Device Rule)
    isPairedState = !!data.is_paired;
    isPhoneConnectedState = !!data.is_phone_connected;
    const unpairedView = document.getElementById('unpaired-view');
    const pairedView = document.getElementById('paired-view');

    if (isPairedState) {
      if (unpairedView) unpairedView.style.display = 'none';
      if (pairedView) pairedView.style.display = 'grid';
      const phoneNameEl = document.getElementById('active-phone-name');
      if (phoneNameEl) phoneNameEl.innerText = data.paired_phone || 'Android Smartphone';
      const phoneStatusEl = document.getElementById('active-phone-status');
      if (phoneStatusEl) {
        phoneStatusEl.innerText = data.is_phone_connected
          ? `🟢 Hardware Connected (${data.connected_phone || 'Active'})`
          : '🟡 Hardware Paired • Listening for Connection';
      }
    } else {
      if (unpairedView) unpairedView.style.display = 'grid';
      if (pairedView) pairedView.style.display = 'none';
      if (!pairingQrObj) {
        fetchQr();
      }
    }

    // Dual Status Pills (Wi-Fi & Bluetooth)
    const wifiDot = document.getElementById('wifi-dot');
    const wifiText = document.getElementById('wifi-text');
    const bleDot = document.getElementById('ble-dot');
    const bleText = document.getElementById('ble-text');

    if (data.is_bluetooth_active === false) {
      if (bleDot) {
        bleDot.className = 'status-dot disconnected';
        bleDot.style.background = 'var(--accent-red)';
        bleDot.style.boxShadow = '0 0 10px var(--accent-red)';
      }
      if (bleText) bleText.innerText = '🔴 BLE: Adapter Off (Disabled)';
    } else if (data.is_phone_connected) {
      if (bleDot) {
        bleDot.className = 'status-dot connected';
        bleDot.style.background = 'var(--accent-green)';
        bleDot.style.boxShadow = '0 0 10px var(--accent-green)';
      }
      if (bleText) bleText.innerText = '🟢 BLE: Linked & Active';
    } else if (data.is_paired) {
      if (bleDot) {
        bleDot.className = 'status-dot disconnected';
        bleDot.style.background = 'var(--accent-purple)';
        bleDot.style.boxShadow = '0 0 10px var(--accent-purple)';
      }
      if (bleText) bleText.innerText = '🔵 BLE: Standby (Phone Disconnected)';
    } else {
      if (bleDot) {
        bleDot.className = 'status-dot disconnected';
        bleDot.style.background = 'var(--accent-yellow)';
        bleDot.style.boxShadow = '0 0 10px var(--accent-yellow)';
      }
      if (bleText) bleText.innerText = '⚪ BLE: Standby (Unpaired)';
    }

    if (data.is_phone_connected) {
      if (wifiDot) wifiDot.className = 'status-dot connected';
      if (wifiText) wifiText.innerText = '📶 Wi-Fi: Connected (' + (data.connected_phone || 'Phone') + ')';
    } else if (data.is_paired) {
      if (wifiDot) wifiDot.className = 'status-dot';
      if (wifiText) wifiText.innerText = '📶 Wi-Fi: Standby (Auto-Reconnecting)';
    } else {
      if (wifiDot) wifiDot.className = 'status-dot disconnected';
      if (wifiText) wifiText.innerText = '📶 Wi-Fi: Unpaired';
    }

    const vaultStatusEl = document.getElementById('vault-status-val');
    if (vaultStatusEl) {
      if (data.vault_exists) {
        vaultStatusEl.innerText = 'ChaCha20-Poly1305 (Armed)';
        vaultStatusEl.style.color = 'var(--accent-green)';
      } else {
        vaultStatusEl.innerText = 'Unset (Password Required)';
        vaultStatusEl.style.color = 'var(--accent-yellow)';
      }
    }

    // Transfer State Management (Requires Paired AND Connected Phone)
    const transferToggle = document.getElementById('transfer-toggle');
    const transferStatusLabel = document.getElementById('transfer-toggle-status-label');
    const transferOffView = document.getElementById('transfer-off-view');
    const transferUnlinkedView = document.getElementById('transfer-unlinked-view');
    const transferActiveView = document.getElementById('transfer-active-view');
    const transferSwitchWrapper = document.getElementById('transfer-switch-wrapper');

    if (!data.is_paired) {
      if (transferToggle) {
        transferToggle.checked = false;
        transferToggle.disabled = true;
      }
      if (transferStatusLabel) {
        transferStatusLabel.innerText = "LOCKED";
        transferStatusLabel.className = "transfer-status-tag";
      }
      if (transferSwitchWrapper) transferSwitchWrapper.style.opacity = "0.5";
      if (transferUnlinkedView) {
        transferUnlinkedView.style.display = 'block';
        const title = document.getElementById('transfer-unlinked-title');
        const desc = document.getElementById('transfer-unlinked-desc');
        if (title) title.innerText = "Device Not Paired";
        if (desc) desc.innerText = "Please pair your smartphone in the Security & Pairing tab first.";
      }
      if (transferOffView) transferOffView.style.display = 'none';
      if (transferActiveView) transferActiveView.style.display = 'none';
    } else if (!data.is_phone_connected) {
      if (transferToggle) {
        transferToggle.checked = false;
        transferToggle.disabled = true;
      }
      if (transferStatusLabel) {
        transferStatusLabel.innerText = "STANDBY";
        transferStatusLabel.className = "transfer-status-tag";
      }
      if (transferSwitchWrapper) transferSwitchWrapper.style.opacity = "0.5";
      if (transferUnlinkedView) {
        transferUnlinkedView.style.display = 'block';
        const title = document.getElementById('transfer-unlinked-title');
        const desc = document.getElementById('transfer-unlinked-desc');
        if (title) title.innerText = "Phone Standby (Not Linked)";
        if (desc) desc.innerText = `Paired phone (${data.paired_phone || 'Device'}) is in standby. Tap 'Tap to Link' on your phone to activate AuraDrop.`;
      }
      if (transferOffView) transferOffView.style.display = 'none';
      if (transferActiveView) transferActiveView.style.display = 'none';
    } else {
      // Paired AND Connected (Live Linked)
      if (transferToggle) {
        transferToggle.disabled = false;
        transferToggle.checked = !!data.transfer_enabled;
      }
      if (transferSwitchWrapper) transferSwitchWrapper.style.opacity = "1";
      if (transferUnlinkedView) transferUnlinkedView.style.display = 'none';

      if (data.transfer_enabled) {
        if (transferStatusLabel) {
          transferStatusLabel.innerText = "ON";
          transferStatusLabel.className = "transfer-status-tag active";
        }
        if (transferOffView) transferOffView.style.display = 'none';
        if (transferActiveView) transferActiveView.style.display = 'grid';
      } else {
        if (transferStatusLabel) {
          transferStatusLabel.innerText = "OFF";
          transferStatusLabel.className = "transfer-status-tag";
        }
        if (transferOffView) transferOffView.style.display = 'block';
        if (transferActiveView) transferActiveView.style.display = 'none';
      }
    }

  } catch (e) {
    console.error("Status fetch error", e);
  }
}

// PAIRING QR CODE
async function fetchQr() {
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/qr');
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('get_pairing_qr');
    }

    if (!data) return;
    currentPin = data.pin;
    const pinDisplay = document.getElementById('pin-display');
    if (pinDisplay) pinDisplay.innerText = data.pin;

    const qrContainer = document.getElementById('qrcode');
    if (qrContainer) {
      qrContainer.innerHTML = '';
      pairingQrObj = new QRCode(qrContainer, {
        text: JSON.stringify(data.qr_data),
        width: 200,
        height: 200,
        colorDark: "#000000",
        colorLight: "#ffffff",
        correctLevel: QRCode.CorrectLevel.M
      });
    }
  } catch (e) {
    console.error("QR fetch error", e);
  }
}

window.refreshPairing = async function () {
  try {
    try {
      await fetch('http://127.0.0.1:47891/api/pair/refresh', { method: 'POST' });
    } catch (_) {}
    pairingQrObj = null;
    await fetchQr();
    showToast("Generated new pairing session & PIN", "🔄");
    log("Generated fresh pairing QR & 6-digit PIN", "info");
  } catch (e) {
    showToast("Failed to refresh pairing", "❌");
  }
};

window.copyPin = function () {
  if (!currentPin) return;
  navigator.clipboard.writeText(currentPin);
  showToast("PIN " + currentPin + " copied!", "📋");
};

// TRANSFER TOGGLE & MANAGEMENT
window.handleTransferToggle = async function (enabled) {
  if (!isPairedState) {
    showToast("Please pair your smartphone first", "⚠️");
    const toggle = document.getElementById('transfer-toggle');
    if (toggle) toggle.checked = false;
    return;
  }
  if (!isPhoneConnectedState) {
    showToast("Smartphone must be live linked to open transfer channel", "⚠️");
    const toggle = document.getElementById('transfer-toggle');
    if (toggle) toggle.checked = false;
    return;
  }

  try {
    let sent = false;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/toggle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled })
      });
      if (res.ok) sent = true;
    } catch (_) {}

    if (!sent && checkIsTauri()) {
      await invokeCommand('toggle_transfer_channel', { enabled });
    }

    log(enabled ? "AuraDrop transfer channel opened" : "AuraDrop transfer channel closed", "info");
    showToast(enabled ? "Transfer Channel Opened ⚡" : "Transfer Channel Closed 🔒", enabled ? "⚡" : "🔒");
    fetchStatus();
  } catch (e) {
    showToast("Failed to toggle transfer", "❌");
  }
};

window.enableTransferNow = function () {
  if (!isPairedState || !isPhoneConnectedState) {
    showToast("Smartphone must be live linked to open transfer channel", "⚠️");
    return;
  }
  const toggle = document.getElementById('transfer-toggle');
  if (toggle) toggle.checked = true;
  window.handleTransferToggle(true);
};

// BIOMETRIC TEST UNLOCK
window.testUnlock = async function () {
  showToast("Sending biometric challenge to phone...", "🧪");
  log("Triggering manual test biometric challenge...", "info");
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/unlock/test', { method: 'POST' });
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('trigger_test_unlock');
    }

    if (data && data.success) {
      showToast("Challenge sent! Touch fingerprint on phone.", "📱");
    } else {
      showToast(data?.message || "No phone connected", "⚠️");
      log("Challenge failed: " + (data?.message || "Device unreachable"), "warn");
    }
  } catch (e) {
    showToast("Error sending challenge", "❌");
  }
};

// SAVE VAULT PASSWORD
window.savePassword = async function () {
  const pwd = document.getElementById('new-password').value;
  if (!pwd) {
    showToast("Please enter a password", "⚠️");
    return;
  }
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/vault/password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pwd })
      });
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('save_vault_password', { password: pwd });
    }

    if (data && data.success) {
      showToast("PC Password saved to Split-Key Vault!", "🔐");
      document.getElementById('new-password').value = '';
      fetchStatus();
      log("Updated split-key vault with PC login password", "success");
    }
  } catch (e) {
    showToast("Failed to save password", "❌");
  }
};

// SEND CLIPBOARD
window.sendClipboard = async function () {
  const text = document.getElementById('clipboard-text').value.trim();
  if (!text) {
    showToast("Please enter text to send", "⚠️");
    return;
  }
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/send-text', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text })
      });
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('send_auradrop_text', { text });
    }

    if (data && data.success) {
      showToast("Sent to Phone via AuraDrop!", "🚀");
      document.getElementById('clipboard-text').value = '';
      log("Sent clipboard text to phone (" + text.length + " chars)", "success");
    } else {
      showToast(data?.message || "Failed to send", "❌");
    }
  } catch (e) {
    showToast("Failed to send clipboard", "❌");
  }
};

// SEND FILE
window.handleFileSelected = async function (files) {
  if (!files || files.length === 0) return;
  const file = files[0];

  const progressBox = document.getElementById('upload-progress-box');
  const progressFill = document.getElementById('upload-progress-fill');
  const statusText = document.getElementById('upload-status-text');

  if (progressBox) progressBox.style.display = 'block';
  if (progressFill) progressFill.style.width = '15%';
  if (statusText) statusText.innerText = `Streaming ${file.name} to Phone...`;
  log(`Starting AuraDrop file transfer: ${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MB)`, "info");

  const formData = new FormData();
  formData.append('file', file);

  try {
    const res = await fetch('http://127.0.0.1:47891/api/auradrop/send-file', {
      method: 'POST',
      body: formData
    });
    const data = await res.json();
    if (data.success) {
      if (progressFill) progressFill.style.width = '100%';
      if (statusText) statusText.innerText = `✅ Sent ${file.name} successfully!`;
      showToast(`Sent ${file.name} to Phone!`, "🚀");
      log(`File ${file.name} successfully transferred to phone`, "success");
      setTimeout(() => { 
        if (progressBox) progressBox.style.display = 'none'; 
        if (statusText) statusText.innerText = ''; 
      }, 3000);
    } else {
      if (statusText) statusText.innerText = `❌ ${data.message || 'Transfer failed'}`;
      showToast(data.message || "Transfer failed", "❌");
      log(`File transfer failed: ${data.message}`, "error");
    }
  } catch (e) {
    if (statusText) statusText.innerText = '❌ Failed to transfer file';
    showToast("Transfer error", "❌");
  }
};

// RECEIVED FILES
async function fetchReceivedFiles() {
  try {
    let files = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/files');
      if (res.ok) files = await res.json();
    } catch (_) {}

    if (!files && checkIsTauri()) {
      files = await invokeCommand('get_received_files');
    }

    if (!files) return;
    const list = document.getElementById('files-list');
    const badge = document.getElementById('received-badge');
    if (badge) badge.innerText = files.length;

    if (!list) return;
    if (files.length === 0) {
      list.innerHTML = '<p style="text-align: center; color: var(--text-muted); padding: 2rem;">No files received yet. Share files from your phone\'s AuraLock app!</p>';
      return;
    }

    list.innerHTML = files.map(f => `
      <div class="file-item">
        <div class="file-info">
          <div class="file-icon">📄</div>
          <div class="file-details">
            <h4>${f.file_name}</h4>
            <p>${(f.file_size / 1024 / 1024).toFixed(2)} MB • From ${f.sender_name || 'Phone'}</p>
          </div>
        </div>
        <a href="http://127.0.0.1:47891/api/auradrop/download/${encodeURIComponent(f.file_name)}" class="btn btn-secondary" style="padding: 6px 12px; text-decoration: none;" download>
          ⬇️ Save
        </a>
      </div>
    `).join('');
  } catch (e) {
    console.error("Fetch files error", e);
  }
}

window.openDownloadsFolder = async function () {
  try {
    let ok = false;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/open-folder', { method: 'POST' });
      if (res.ok) ok = true;
    } catch (_) {}

    if (!ok && checkIsTauri()) {
      await invokeCommand('open_downloads_folder');
    }
    showToast("Opened Downloads/AuraLock in file manager", "📂");
  } catch (e) {
    showToast("Could not open folder", "⚠️");
  }
};

// DRAG & DROP
const dropzone = document.getElementById('dropzone');
if (dropzone) {
  ['dragenter', 'dragover'].forEach(name => {
    dropzone.addEventListener(name, (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
  });
  ['dragleave', 'drop'].forEach(name => {
    dropzone.addEventListener(name, (e) => { e.preventDefault(); dropzone.classList.remove('dragover'); });
  });
  dropzone.addEventListener('drop', (e) => {
    const dt = e.dataTransfer;
    if (dt.files && dt.files.length > 0) {
      window.handleFileSelected(dt.files);
    }
  });
}

// WEBSOCKET
function setupWebSocket() {
  const ws = new WebSocket('ws://127.0.0.1:47891/api/events');

  ws.onmessage = (event) => {
    try {
      const ev = JSON.parse(event.data);
      if (ev.type === 'ClientConnected') {
        log(`Phone connected: ${ev.data.phone_name}`, "success");
        showToast(`Connected to ${ev.data.phone_name}`, "📱");
        fetchStatus();
      } else if (ev.type === 'ClientDisconnected') {
        log("Phone disconnected", "warn");
        fetchStatus();
      } else if (ev.type === 'DeviceUnpaired') {
        log("Device unpaired in real-time", "warn");
        showToast("Device unpaired", "🗑️");
        isPairedState = false;
        isPhoneConnectedState = false;
        const unpairedView = document.getElementById('unpaired-view');
        const pairedView = document.getElementById('paired-view');
        if (unpairedView) unpairedView.style.display = 'grid';
        if (pairedView) pairedView.style.display = 'none';
        pairingQrObj = null;
        fetchStatus();
        fetchQr();
      } else if (ev.type === 'PairingSuccess') {
        log(`Pairing completed with ${ev.data.phone_name}`, "success");
        showToast(`Paired with ${ev.data.phone_name}!`, "🎉");
        isPairedState = true;
        const unpairedView = document.getElementById('unpaired-view');
        const pairedView = document.getElementById('paired-view');
        if (unpairedView) unpairedView.style.display = 'none';
        if (pairedView) pairedView.style.display = 'grid';
        fetchStatus();
      } else if (ev.type === 'UnlockEvent') {
        if (ev.data.success) {
          log("Biometric signature verified! PC Authorized 🔓", "success");
          showToast("Biometric Verified! PC Unlocked 🚀", "🔓");
        } else {
          log(`Unlock rejected: ${ev.data.message}`, "error");
          showToast(`Unlock denied: ${ev.data.message}`, "❌");
        }
      } else if (ev.type === 'FileTransferProgress') {
        updateTransferHudProgress(ev.data);
      } else if (ev.type === 'FileTransferCompleted') {
        updateTransferHudCompleted(ev.data);
        if (ev.data.is_incoming && ev.data.success !== false) {
          log(`Received file: ${ev.data.file_name}`, "success");
          showToast(`Received ${ev.data.file_name} from phone!`, "📥");
          fetchReceivedFiles();
        } else if (!ev.data.is_incoming && ev.data.success !== false) {
          log(`File ${ev.data.file_name} successfully sent to phone`, "success");
          showToast(`Sent ${ev.data.file_name} to phone!`, "🚀");
        } else {
          log(`File transfer failed: ${ev.data.message || 'Error'}`, "error");
          showToast(`Transfer failed: ${ev.data.message || 'Error'}`, "❌");
        }
      } else if (ev.type === 'ClipboardReceived') {
        log(`Received clipboard from phone: "${ev.data.text}"`, "info");
        showToast("Clipboard text received from phone!", "📋");
      } else if (ev.type === 'PhoneUnpairRequested') {
        log(`Incoming unpair request from phone: ${ev.data.phone_name} (PIN: ${ev.data.pin})`, "warn");
        const pinDisp = document.getElementById('phone-unpair-pin-display');
        const sub = document.getElementById('phone-unpair-subtitle');
        if (pinDisp) pinDisp.innerText = ev.data.pin;
        if (sub) sub.innerText = `${ev.data.phone_name} is requesting to unpair. Please enter this PIN in the mobile app:`;
        const modal = document.getElementById('phone-unpair-modal');
        if (modal) modal.classList.add('show');
      } else if (ev.type === 'PhoneUnpairCancelled') {
        const modal = document.getElementById('phone-unpair-modal');
        if (modal) modal.classList.remove('show');
      }
    } catch (e) {
      console.error(e);
    }
  };

  ws.onclose = () => {
    setTimeout(setupWebSocket, 2000);
  };
}

// FLOATING LIVE TRANSFER HUD
let isTransferHudMinimized = false;
let transferHudDismissTimeout = null;

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function getFileIcon(filename) {
  const ext = (filename || '').split('.').pop().toLowerCase();
  if (['jpg', 'jpeg', 'png', 'webp', 'gif', 'svg'].includes(ext)) return '🖼️';
  if (['mp4', 'mkv', 'mov', 'avi', 'webm'].includes(ext)) return '🎬';
  if (['mp3', 'wav', 'flac', 'ogg', 'm4a'].includes(ext)) return '🎵';
  if (['pdf', 'doc', 'docx', 'txt', 'md'].includes(ext)) return '📄';
  if (['zip', 'tar', 'gz', '7z', 'rar', 'deb', 'rpm'].includes(ext)) return '📦';
  return '📁';
}

function updateTransferHudProgress(data) {
  const hud = document.getElementById('floating-transfer-hud');
  const expanded = document.getElementById('transfer-hud-expanded');
  const minimized = document.getElementById('transfer-hud-minimized');
  if (!hud) return;

  if (transferHudDismissTimeout) {
    clearTimeout(transferHudDismissTimeout);
    transferHudDismissTimeout = null;
  }

  hud.style.display = 'block';
  if (isTransferHudMinimized) {
    if (expanded) expanded.style.display = 'none';
    if (minimized) minimized.style.display = 'flex';
  } else {
    if (expanded) expanded.style.display = 'block';
    if (minimized) minimized.style.display = 'none';
  }

  const pctVal = Math.min(100, Math.max(0, Math.round((data.progress || 0) * 100)));
  const directionIcon = data.is_incoming ? '📥' : '📤';
  const directionTitle = data.is_incoming ? 'Receiving File from Phone...' : 'Sending to Phone...';

  const dirIconEl = document.getElementById('transfer-hud-direction-icon');
  const titleEl = document.getElementById('transfer-hud-title');
  const fileIconEl = document.getElementById('transfer-hud-file-icon');
  const fileNameEl = document.getElementById('transfer-hud-file-name');
  const bytesEl = document.getElementById('transfer-hud-bytes');
  const pctEl = document.getElementById('transfer-hud-pct');
  const fillEl = document.getElementById('transfer-hud-progress-fill');
  const completeActions = document.getElementById('transfer-hud-complete-actions');
  const closeBtn = document.getElementById('transfer-hud-close-btn');

  if (dirIconEl) dirIconEl.innerText = directionIcon;
  if (titleEl) titleEl.innerText = directionTitle;
  if (fileIconEl) fileIconEl.innerText = getFileIcon(data.file_name);
  if (fileNameEl) fileNameEl.innerText = data.file_name || 'file';
  if (bytesEl) bytesEl.innerText = `${formatBytes(data.bytes_transferred)} / ${formatBytes(data.total_bytes)}`;
  if (pctEl) pctEl.innerText = `${pctVal}%`;
  if (fillEl) fillEl.style.width = `${pctVal}%`;
  if (completeActions) completeActions.style.display = 'none';
  if (closeBtn) closeBtn.style.display = 'none';

  // Update minimized pill
  const pillIcon = document.getElementById('transfer-pill-icon');
  const pillName = document.getElementById('transfer-pill-name');
  const pillPct = document.getElementById('transfer-pill-pct');
  const pillFill = document.getElementById('transfer-pill-circle-fill');

  if (pillIcon) pillIcon.innerText = directionIcon;
  if (pillName) pillName.innerText = data.file_name || 'Transferring...';
  if (pillPct) pillPct.innerText = `${pctVal}%`;
  if (pillFill) pillFill.setAttribute('stroke-dasharray', `${pctVal}, 100`);
}

function updateTransferHudCompleted(data) {
  const hud = document.getElementById('floating-transfer-hud');
  if (!hud) return;

  const isSuccess = data.success !== false;
  const titleEl = document.getElementById('transfer-hud-title');
  const dirIconEl = document.getElementById('transfer-hud-direction-icon');
  const fillEl = document.getElementById('transfer-hud-progress-fill');
  const pctEl = document.getElementById('transfer-hud-pct');
  const completeActions = document.getElementById('transfer-hud-complete-actions');
  const closeBtn = document.getElementById('transfer-hud-close-btn');

  if (titleEl) titleEl.innerText = isSuccess ? 'Transfer Complete! 🎉' : 'Transfer Failed ❌';
  if (dirIconEl) dirIconEl.innerText = isSuccess ? '✅' : '❌';
  if (fillEl) fillEl.style.width = isSuccess ? '100%' : '0%';
  if (pctEl) pctEl.innerText = isSuccess ? '100%' : 'Failed';
  if (completeActions && data.is_incoming && isSuccess) completeActions.style.display = 'block';
  if (closeBtn) closeBtn.style.display = 'flex';

  const pillName = document.getElementById('transfer-pill-name');
  const pillPct = document.getElementById('transfer-pill-pct');
  const pillIcon = document.getElementById('transfer-pill-icon');
  const pillFill = document.getElementById('transfer-pill-circle-fill');

  if (pillName) pillName.innerText = isSuccess ? 'Transfer Complete!' : 'Transfer Failed';
  if (pillPct) pillPct.innerText = isSuccess ? '100%' : 'Err';
  if (pillIcon) pillIcon.innerText = isSuccess ? '✅' : '❌';
  if (pillFill) pillFill.setAttribute('stroke-dasharray', isSuccess ? '100, 100' : '0, 100');

  // Auto-dismiss after 6 seconds if not interacted
  transferHudDismissTimeout = setTimeout(() => {
    window.closeTransferHud();
  }, 6000);
}

window.toggleTransferHudMinimize = function () {
  isTransferHudMinimized = !isTransferHudMinimized;
  const expanded = document.getElementById('transfer-hud-expanded');
  const minimized = document.getElementById('transfer-hud-minimized');
  if (isTransferHudMinimized) {
    if (expanded) expanded.style.display = 'none';
    if (minimized) minimized.style.display = 'flex';
  } else {
    if (expanded) expanded.style.display = 'block';
    if (minimized) minimized.style.display = 'none';
  }
};

window.closeTransferHud = function () {
  const hud = document.getElementById('floating-transfer-hud');
  if (hud) hud.style.display = 'none';
  if (transferHudDismissTimeout) {
    clearTimeout(transferHudDismissTimeout);
    transferHudDismissTimeout = null;
  }
};

// CANCEL BIOMETRIC CHALLENGE (ESC / Manual Stop)
window.cancelChallenge = async function () {
  try {
    let data = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/unlock/cancel', { method: 'POST' });
      if (res.ok) data = await res.json();
    } catch (_) {}

    if (!data && checkIsTauri()) {
      data = await invokeCommand('cancel_challenge');
    }

    if (data && data.success) {
      showToast("Biometric Challenge Cancelled (Swapped to PIN/Password)", "⌨️");
      log("Biometric challenge force-stopped via Esc / PC override", "info");
    }
  } catch (e) {}
};

// Global Escape Key listener to immediately dismiss/cancel biometric prompts on PC
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    window.cancelChallenge();
  }
});

// INITIALIZATION
fetchStatus();
fetchReceivedFiles();
setupWebSocket();
setInterval(fetchStatus, 2000);
