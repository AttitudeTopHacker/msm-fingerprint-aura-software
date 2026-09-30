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
  document.getElementById('toast-icon').innerText = icon;
  document.getElementById('toast-message').innerText = msg;
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
  document.getElementById('log-container').innerHTML = '';
};

// UNPAIR MODAL
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
    let res = null;
    if (checkIsTauri()) {
      res = await invokeCommand('unpair_device');
    }
    if (!res) {
      const resp = await fetch('http://127.0.0.1:47891/api/vault/unpair', { method: 'POST' });
      if (resp.ok) res = await resp.json();
    }

    window.closeUnpairModal();
    if (res && res.success) {
      showToast("Device successfully unpaired!", "🗑️");
      log("Device successfully unpaired from PC", "warn");
      fetchStatus();
      fetchQr();
    } else {
      showToast(res?.message || "Unpair failed", "❌");
      log("Unpair failed: " + (res?.message || "Error"), "error");
    }
  } catch (e) {
    window.closeUnpairModal();
    showToast("Error unpairing device", "❌");
  }
};

// FETCH STATUS
async function fetchStatus() {
  try {
    let data = null;
    if (checkIsTauri()) {
      data = await invokeCommand('get_daemon_status');
    }
    if (!data) {
      const res = await fetch('http://127.0.0.1:47891/api/status');
      if (res.ok) data = await res.json();
    }

    if (!data) return;

    document.getElementById('pc-name-val').innerText = data.pc_name || 'AuraLock-PC';
    document.getElementById('pc-port-val').innerText = data.port || 47890;
    const setupPort = document.getElementById('setup-port');
    if (setupPort) setupPort.innerText = data.port || 47890;

    // Pairing State Management (Single Device Rule)
    isPairedState = !!data.is_paired;
    isPhoneConnectedState = !!data.is_phone_connected;
    const unpairedView = document.getElementById('unpaired-view');
    const pairedView = document.getElementById('paired-view');

    if (isPairedState) {
      unpairedView.style.display = 'none';
      pairedView.style.display = 'grid';
      document.getElementById('active-phone-name').innerText = data.paired_phone || 'Android Smartphone';
      document.getElementById('active-phone-status').innerText = data.is_phone_connected
        ? `🟢 Hardware Connected (${data.connected_phone || 'Active'})`
        : '🟡 Hardware Paired • Listening for Connection';
    } else {
      unpairedView.style.display = 'grid';
      pairedView.style.display = 'none';
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

    if (data.vault_exists) {
      document.getElementById('vault-status-val').innerText = 'ChaCha20-Poly1305 (Armed)';
      document.getElementById('vault-status-val').style.color = 'var(--accent-green)';
    } else {
      document.getElementById('vault-status-val').innerText = 'Unset (Password Required)';
      document.getElementById('vault-status-val').style.color = 'var(--accent-yellow)';
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
    let data;
    if (checkIsTauri()) {
      data = await invokeCommand('get_pairing_qr');
    } else {
      const res = await fetch('http://127.0.0.1:47891/api/qr');
      data = await res.json();
    }

    if (!data) return;
    currentPin = data.pin;
    document.getElementById('pin-display').innerText = data.pin;

    const qrContainer = document.getElementById('qrcode');
    qrContainer.innerHTML = '';
    pairingQrObj = new QRCode(qrContainer, {
      text: JSON.stringify(data.qr_data),
      width: 200,
      height: 200,
      colorDark: "#000000",
      colorLight: "#ffffff",
      correctLevel: QRCode.CorrectLevel.M
    });
  } catch (e) {
    console.error("QR fetch error", e);
  }
}

window.refreshPairing = async function () {
  try {
    if (checkIsTauri()) {
      await fetchQr();
    } else {
      await fetch('http://127.0.0.1:47891/api/pair/refresh', { method: 'POST' });
      await fetchQr();
    }
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
    if (checkIsTauri()) {
      await invokeCommand('toggle_transfer_channel', { enabled });
      log(enabled ? "AuraDrop transfer channel opened" : "AuraDrop transfer channel closed", "info");
    } else {
      await fetch('http://127.0.0.1:47891/api/auradrop/toggle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled })
      });
    }
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
    let data;
    if (checkIsTauri()) {
      data = await invokeCommand('trigger_test_unlock');
    } else {
      const res = await fetch('http://127.0.0.1:47891/api/unlock/test', { method: 'POST' });
      data = await res.json();
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
    let data;
    if (checkIsTauri()) {
      data = await invokeCommand('save_vault_password', { password: pwd });
    } else {
      const res = await fetch('http://127.0.0.1:47891/api/vault/password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pwd })
      });
      data = await res.json();
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
    let data;
    if (checkIsTauri()) {
      data = await invokeCommand('send_auradrop_text', { text });
    } else {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/send-text', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text })
      });
      data = await res.json();
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

  progressBox.style.display = 'block';
  progressFill.style.width = '15%';
  statusText.innerText = `Streaming ${file.name} to Phone...`;
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
      progressFill.style.width = '100%';
      statusText.innerText = `✅ Sent ${file.name} successfully!`;
      showToast(`Sent ${file.name} to Phone!`, "🚀");
      log(`File ${file.name} successfully transferred to phone`, "success");
      setTimeout(() => { progressBox.style.display = 'none'; statusText.innerText = ''; }, 3000);
    } else {
      statusText.innerText = `❌ ${data.message || 'Transfer failed'}`;
      showToast(data.message || "Transfer failed", "❌");
      log(`File transfer failed: ${data.message}`, "error");
    }
  } catch (e) {
    statusText.innerText = '❌ Failed to transfer file';
    showToast("Transfer error", "❌");
  }
};

// RECEIVED FILES
async function fetchReceivedFiles() {
  try {
    let files;
    if (checkIsTauri()) {
      files = await invokeCommand('get_received_files');
    } else {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/files');
      files = await res.json();
    }

    if (!files) return;
    const list = document.getElementById('files-list');
    document.getElementById('received-badge').innerText = files.length;

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
    if (checkIsTauri()) {
      await invokeCommand('open_downloads_folder');
    } else {
      await fetch('http://127.0.0.1:47891/api/auradrop/open-folder', { method: 'POST' });
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
        fetchStatus();
        fetchQr();
      } else if (ev.type === 'PairingSuccess') {
        log(`Pairing completed with ${ev.data.phone_name}`, "success");
        showToast(`Paired with ${ev.data.phone_name}!`, "🎉");
        fetchStatus();
      } else if (ev.type === 'UnlockEvent') {
        if (ev.data.success) {
          log("Biometric signature verified! PC Authorized 🔓", "success");
          showToast("Biometric Verified! PC Unlocked 🚀", "🔓");
        } else {
          log(`Unlock rejected: ${ev.data.message}`, "error");
          showToast(`Unlock denied: ${ev.data.message}`, "❌");
        }
      } else if (ev.type === 'FileTransferCompleted') {
        if (ev.data.is_incoming) {
          log(`Received file: ${ev.data.file_name}`, "success");
          showToast(`Received ${ev.data.file_name} from phone!`, "📥");
          fetchReceivedFiles();
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

// INITIALIZATION
fetchStatus();
fetchReceivedFiles();
setupWebSocket();
setInterval(fetchStatus, 4000);
