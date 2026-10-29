console.log("🚀 Msm Connect GUI initialized");

// Prevent default browser zoom via Ctrl/Meta + wheel & gestures
window.addEventListener('wheel', (e) => {
  if (e.ctrlKey || e.metaKey) e.preventDefault();
}, { passive: false });

window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && ['+', '-', '=', '0'].includes(e.key)) {
    e.preventDefault();
  }
});

// Suppress default WebKit browser context menu (Back, Forward, Stop, Reload)
document.addEventListener('contextmenu', (e) => {
  if (!e.ctrlKey && e.target.tagName !== 'INPUT' && e.target.tagName !== 'TEXTAREA') {
    e.preventDefault();
  }
});

// Universal CSP Interceptor: Prevents browser inline script blocking and executes the handler in safe JS scope
document.addEventListener('click', (e) => {
  const targetDesc = e.target.id ? ('#' + e.target.id) : (e.target.className ? ('.' + e.target.className) : e.target.tagName);
  console.log("🖱️ [UI Click]", targetDesc, e.target);

  const el = e.target.closest('[onclick]');
  if (el) {
    const rawCode = el.getAttribute('onclick');
    if (rawCode) {
      // Prevent browser's default inline handler which gets blocked by CSP
      e.preventDefault();
      try {
        const clean = rawCode.trim().replace(/;$/, '');
        const match = clean.match(/^([a-zA-Z0-9_$.]+)\((.*)\)$/);
        if (match) {
          const fnPath = match[1];
          const rawArgs = match[2].trim();
          let args = [];
          if (rawArgs.length > 0) {
            args = rawArgs.split(',').map(a => {
              const t = a.trim();
              if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"'))) {
                return t.slice(1, -1);
              }
              if (t === 'true') return true;
              if (t === 'false') return false;
              if (!isNaN(t)) return Number(t);
              return t;
            });
          }
          let fn = window[fnPath];
          if (typeof fn === 'function') {
            console.log("⚡ [CSP Intercept Executed]:", fnPath, args);
            fn.apply(el, args);
            return;
          }
        }
        // Fallback for custom code expressions
        new Function(clean).call(el);
      } catch (err) {
        console.error("Error executing handler for:", rawCode, err);
      }
    }
  }
}, true);

// Clean event listener attachment to avoid any inline handlers
window.addEventListener('DOMContentLoaded', () => {
  // Navigation tabs
  document.querySelectorAll('.tab-btn').forEach(btn => {
    const code = btn.getAttribute('onclick') || '';
    const match = code.match(/switchTab\('([^']+)'\)/);
    if (match) {
      const tabId = match[1];
      btn.addEventListener('click', (ev) => {
        ev.preventDefault();
        window.switchTab(tabId);
      });
    }
  });

  // Auto clean all buttons with onclick
  document.querySelectorAll('[onclick]').forEach(el => {
    const code = el.getAttribute('onclick') || '';
    if (!code) return;
    el.addEventListener('click', (ev) => {
      ev.preventDefault();
      try {
        const clean = code.trim().replace(/;$/, '');
        const match = clean.match(/^([a-zA-Z0-9_$.]+)\((.*)\)$/);
        if (match && typeof window[match[1]] === 'function') {
          const rawArgs = match[2].trim();
          let args = [];
          if (rawArgs.length > 0) {
            args = rawArgs.split(',').map(a => {
              const t = a.trim();
              if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"'))) return t.slice(1, -1);
              if (t === 'true') return true;
              if (t === 'false') return false;
              if (!isNaN(t)) return Number(t);
              return t;
            });
          }
          window[match[1]](...args);
        } else {
          new Function(clean).call(el);
        }
      } catch (e) {
        console.error("Handler error:", e);
      }
    });
  });
});

// Global JS error listener
window.addEventListener('error', (e) => {
  console.error("💥 [UI Error]:", e.message, "at", e.filename, ":", e.lineno);
});

let currentPin = "";
let pairingQrObj = null;
let isPairedState = false;
let isPhoneConnectedState = false;
let apiAuthToken = window.__MSMCONNECT_SESSION_TOKEN__ || "";
const originalFetch = window.fetch;

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

async function getAuthToken() {
  if (apiAuthToken) return apiAuthToken;
  if (window.__MSMCONNECT_SESSION_TOKEN__) {
    apiAuthToken = window.__MSMCONNECT_SESSION_TOKEN__;
    return apiAuthToken;
  }
  if (checkIsTauri()) {
    try {
      const tok = await invokeCommand('get_api_auth_token');
      if (tok) {
        apiAuthToken = tok;
        window.__MSMCONNECT_SESSION_TOKEN__ = tok;
        return tok;
      }
    } catch (_) {}
  }
  try {
    const res = await originalFetch('http://127.0.0.1:47891/api/auth/token');
    if (res.ok) {
      const d = await res.json();
      if (d && d.token) {
        apiAuthToken = d.token;
        window.__MSMCONNECT_SESSION_TOKEN__ = d.token;
        return d.token;
      }
    }
  } catch (_) {}
  return apiAuthToken || window.__MSMCONNECT_SESSION_TOKEN__ || "";
}

function getApiAuthToken() {
  return apiAuthToken || window.__MSMCONNECT_SESSION_TOKEN__ || "";
}
window.getApiAuthToken = getApiAuthToken;

// Global fetch interceptor to inject X-MsmConnect-Auth header
(function setupApiAuthInterceptor() {
  window.fetch = async function(resource, init = {}) {
    const url = typeof resource === 'string' ? resource : (resource?.url || '');
    if (url.includes('/api/')) {
      const token = await getAuthToken();
      if (token) {
        init = init || {};
        const headers = new Headers(init.headers || {});
        if (!headers.has('X-MsmConnect-Auth')) {
          headers.set('X-MsmConnect-Auth', token);
        }
        init.headers = headers;
      }
    }
    return originalFetch(resource, init);
  };
})();

window.switchTab = function (tabId) {
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

  const btn = Array.from(document.querySelectorAll('.tab-btn')).find(b => b.getAttribute('onclick')?.includes(tabId));
  if (btn) btn.classList.add('active');

  const content = document.getElementById('tab-' + tabId);
  if (content) content.classList.add('active');
};

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

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
  div.innerHTML = `<span class="log-time">[${escapeHtml(time)}]</span> <span class="log-${escapeHtml(type)}">${escapeHtml(msg)}</span>`;
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

    if (data.qr_data) {
      const pcIp = data.qr_data.ip || '127.0.0.1';
      const pcPort = data.qr_data.port || 47890;
      window.__PC_LOCAL_IP__ = pcIp;
      const ipDisp = document.getElementById('pc-ip-display');
      if (ipDisp) ipDisp.innerText = pcIp;
      const portDisp = document.getElementById('pc-port-display');
      if (portDisp) portDisp.innerText = pcPort;
    }
  } catch (e) {
    console.error("QR fetch error", e);
  }
}

window.refreshPairing = async function () {
  try {
    let refreshed = false;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/pair/refresh', { method: 'POST' });
      if (res.ok) refreshed = true;
    } catch (_) {}

    if (!refreshed && checkIsTauri()) {
      await invokeCommand('refresh_pairing_session');
    }

    pairingQrObj = null;
    await fetchQr();
    showToast("Generated new pairing session & PIN", "🔄");
    log("Generated fresh pairing QR & 6-digit PIN", "info");
  } catch (e) {
    console.error("Refresh pairing error", e);
    showToast("Failed to refresh pairing", "❌");
  }
};

window.copyPin = function () {
  if (!currentPin) return;
  navigator.clipboard.writeText(currentPin);
  showToast("PIN " + currentPin + " copied!", "📋");
};

window.copyPcIp = function () {
  const ip = window.__PC_LOCAL_IP__;
  if (!ip) return;
  navigator.clipboard.writeText(ip);
  showToast("PC Wi-Fi IP " + ip + " copied!", "📋");
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

// RECEIVED CLIPBOARD STATE & ACTIONS
let receivedClipboardHistory = [];

window.copyReceivedClipboard = function (customText) {
  const textEl = document.getElementById('received-clipboard-text');
  const text = customText || (textEl ? textEl.innerText : '');
  if (!text) return;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      const btnText = document.getElementById('copy-btn-text');
      const btnIcon = document.getElementById('copy-btn-icon');
      if (btnText && btnIcon) {
        btnText.innerText = "Copied!";
        btnIcon.innerText = "✅";
        setTimeout(() => {
          btnText.innerText = "Copy to Clipboard";
          btnIcon.innerText = "📋";
        }, 2000);
      }
      showToast("Copied to PC clipboard!", "📋");
    }).catch(err => {
      console.warn("Clipboard copy error:", err);
    });
  }
};

window.clearReceivedClipboard = function () {
  const empty = document.getElementById('received-clipboard-empty');
  const box = document.getElementById('received-clipboard-content-box');
  const sender = document.getElementById('received-clipboard-sender');
  if (empty) empty.style.display = 'block';
  if (box) box.style.display = 'none';
  if (sender) sender.innerText = 'Waiting for text...';
};

window.clearAllReceivedTexts = function () {
  receivedClipboardHistory = [];
  renderReceivedTextHistory();
  window.clearReceivedClipboard();
  showToast("Received text history cleared", "🗑️");
};

function handleReceivedClipboard(text, senderName, timestampEpoch) {
  if (!text) return;
  
  // Try automatic clipboard copy
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).catch(e => console.warn("Auto-copy blocked:", e));
  }
  
  const timeStr = timestampEpoch ? new Date(timestampEpoch * 1000).toLocaleTimeString() : new Date().toLocaleTimeString();
  const sender = senderName || 'Phone';

  // Update active view card
  const empty = document.getElementById('received-clipboard-empty');
  const box = document.getElementById('received-clipboard-content-box');
  const textEl = document.getElementById('received-clipboard-text');
  const senderEl = document.getElementById('received-clipboard-sender');
  
  if (empty) empty.style.display = 'none';
  if (box) box.style.display = 'block';
  if (textEl) textEl.innerText = text;
  if (senderEl) senderEl.innerText = `From: ${sender} • ${timeStr}`;

  // Add to history (avoid duplicates at the top)
  if (receivedClipboardHistory.length === 0 || receivedClipboardHistory[0].text !== text) {
    receivedClipboardHistory.unshift({ text, sender, time: timeStr, timestamp: Date.now() });
    if (receivedClipboardHistory.length > 20) receivedClipboardHistory.pop();
  }
  renderReceivedTextHistory();
}

function renderReceivedTextHistory() {
  const container = document.getElementById('received-texts-history-list');
  if (!container) return;
  
  if (receivedClipboardHistory.length === 0) {
    container.innerHTML = `<p style="text-align: center; color: var(--text-muted); padding: 1.5rem;">No text received yet. Text sent from your phone will show up here automatically.</p>`;
    return;
  }

  container.innerHTML = receivedClipboardHistory.map((item) => `
    <div style="background: rgba(255, 255, 255, 0.03); border: 1px solid var(--border-color); border-radius: 12px; padding: 12px 16px; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; gap: 12px;">
      <div style="flex: 1; min-width: 0;">
        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
          <span style="font-size: 0.75rem; color: var(--accent-cyan); font-weight: 600;">📱 ${escapeHtml(item.sender)}</span>
          <span style="font-size: 0.75rem; color: var(--text-muted);">• ${escapeHtml(item.time)}</span>
        </div>
        <div style="font-family: var(--font-mono); font-size: 0.85rem; color: #E2E8F0; white-space: pre-wrap; word-break: break-word; max-height: 60px; overflow-y: auto;">${escapeHtml(item.text)}</div>
      </div>
      <button class="btn btn-secondary" style="padding: 6px 12px; font-size: 0.8rem; white-space: nowrap;" onclick="window.copyTextItem(${JSON.stringify(item.text).replace(/"/g, '&quot;')})">
        <span>📋</span> Copy
      </button>
    </div>
  `).join('');
}

window.copyTextItem = function (text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      showToast("Copied to PC clipboard!", "📋");
    });
  }
};

async function fetchLatestClipboard() {
  try {
    let clip = null;
    try {
      const res = await fetch('http://127.0.0.1:47891/api/auradrop/clipboard');
      if (res.ok) clip = await res.json();
    } catch (_) {}

    if (!clip && checkIsTauri()) {
      clip = await invokeCommand('get_latest_clipboard');
    }

    if (clip && clip.text) {
      handleReceivedClipboard(clip.text, clip.sender_name, clip.timestamp_epoch);
    }
  } catch (e) {}
}

// SEND FILE (Supports multi-file queue)
window.handleFileSelected = async function (files) {
  if (!files || files.length === 0) return;
  const fileList = Array.from(files);
  const totalFiles = fileList.length;

  for (let i = 0; i < totalFiles; i++) {
    const file = fileList[i];
    const prefix = totalFiles > 1 ? `(${i + 1}/${totalFiles}) ` : '';

    const progressBox = document.getElementById('upload-progress-box');
    const progressFill = document.getElementById('upload-progress-fill');
    const statusText = document.getElementById('upload-status-text');

    if (progressBox) progressBox.style.display = 'block';
    if (progressFill) progressFill.style.width = '10%';
    if (statusText) statusText.innerText = `${prefix}Streaming ${file.name} to Phone...`;
    log(`Starting AuraDrop file transfer: ${prefix}${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MB)`, "info");

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
        if (statusText) statusText.innerText = `✅ Sent ${prefix}${file.name} successfully!`;
        showToast(`Sent ${file.name} to Phone!`, "🚀");
        log(`File ${file.name} successfully transferred to phone`, "success");
      } else {
        if (statusText) statusText.innerText = `❌ ${prefix}${data.message || 'Transfer failed'}`;
        showToast(data.message || "Transfer failed", "❌");
        log(`File transfer failed: ${data.message}`, "error");
        break;
      }
    } catch (e) {
      if (statusText) statusText.innerText = `❌ Failed to transfer ${file.name}`;
      showToast("Transfer error", "❌");
      break;
    }
  }

  setTimeout(() => {
    const progressBox = document.getElementById('upload-progress-box');
    const statusText = document.getElementById('upload-status-text');
    if (progressBox) progressBox.style.display = 'none';
    if (statusText) statusText.innerText = '';
  }, 3500);
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
      list.innerHTML = '<p style="text-align: center; color: var(--text-muted); padding: 2rem;">No files received yet. Share files from your phone\'s Msm Connect app!</p>';
      return;
    }

    list.innerHTML = files.map(f => {
      const cleanName = escapeHtml(f.file_name).replace(/'/g, "\\'");
      return `
        <div class="file-item">
          <div class="file-info">
            <div class="file-icon">📄</div>
            <div class="file-details">
              <h4>${escapeHtml(f.file_name)}</h4>
              <p>${(f.file_size / 1024 / 1024).toFixed(2)} MB • From ${escapeHtml(f.sender_name || 'Phone')}</p>
            </div>
          </div>
          <button onclick="window.downloadFileWithTicket('${cleanName}')" class="btn btn-secondary" style="padding: 6px 12px; cursor: pointer;">
            ⬇️ Save
          </button>
        </div>
      `;
    }).join('');
  } catch (e) {
    console.error("Fetch files error", e);
  }
}

window.downloadFileWithTicket = async function (fileName) {
  try {
    const token = await getAuthToken();
    const res = await fetch('http://127.0.0.1:47891/api/ticket/download', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-MsmConnect-Auth': token
      },
      body: JSON.stringify({ filename: fileName })
    });
    const data = await res.json();
    if (data && data.ticket) {
      const a = document.createElement('a');
      a.href = `http://127.0.0.1:47891/api/auradrop/download/${encodeURIComponent(fileName)}?ticket=${encodeURIComponent(data.ticket)}`;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    } else {
      showToast("Could not generate download ticket", "⚠️");
    }
  } catch (e) {
    showToast("Download failed", "❌");
  }
};

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

// WEBSOCKET (Scoped Ticket Authenticated)
async function setupWebSocket() {
  let wsUrl = 'ws://127.0.0.1:47891/api/events';
  try {
    const token = await getAuthToken();
    const res = await fetch('http://127.0.0.1:47891/api/ticket/ws', {
      method: 'POST',
      headers: { 'X-MsmConnect-Auth': token }
    });
    const data = await res.json();
    if (data && data.ticket) {
      wsUrl += `?ticket=${encodeURIComponent(data.ticket)}`;
    }
  } catch (_) {}
  const ws = new WebSocket(wsUrl);

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
        const text = ev.data.text || '';
        const sender = ev.data.sender_name || 'Phone';
        log(`Received clipboard from ${sender}: "${text}"`, "info");
        handleReceivedClipboard(text, sender, ev.data.timestamp_epoch);
        showToast(`Received text from ${sender}! (Copied to Clipboard)`, "📋");
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
let hudSpeedTracker = {
  file_id: null,
  bytes: 0,
  time: 0,
  speed: 0
};

function calculateSpeedAndEta(fileId, bytesTransferred, totalBytes) {
  const now = performance.now();
  if (hudSpeedTracker.file_id !== fileId || !hudSpeedTracker.time) {
    hudSpeedTracker = {
      file_id: fileId,
      bytes: bytesTransferred,
      time: now,
      speed: 0
    };
    return '';
  }

  const dt = (now - hudSpeedTracker.time) / 1000;
  if (dt >= 0.25) {
    const dBytes = bytesTransferred - hudSpeedTracker.bytes;
    if (dBytes > 0) {
      const instantSpeed = dBytes / dt;
      hudSpeedTracker.speed = hudSpeedTracker.speed > 0
        ? (hudSpeedTracker.speed * 0.65 + instantSpeed * 0.35)
        : instantSpeed;
    }
    hudSpeedTracker.bytes = bytesTransferred;
    hudSpeedTracker.time = now;
  }

  const speedBps = hudSpeedTracker.speed;
  const speedStr = speedBps > 1024 ? `${formatBytes(speedBps)}/s` : '';
  const remainingBytes = Math.max(0, totalBytes - bytesTransferred);
  let etaStr = '';
  if (speedBps > 1024 && remainingBytes > 0) {
    const etaSec = Math.round(remainingBytes / speedBps);
    etaStr = etaSec < 60 ? `ETA ${etaSec}s` : `ETA ${Math.floor(etaSec / 60)}m ${etaSec % 60}s`;
  }

  if (speedStr && etaStr) return `${speedStr} • ${etaStr}`;
  if (speedStr) return speedStr;
  return etaStr;
}

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

  expanded?.classList.remove('hud-failed', 'hud-success');
  minimized?.classList.remove('hud-failed', 'hud-success');

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
  const speedEl = document.getElementById('transfer-hud-speed');
  const pctEl = document.getElementById('transfer-hud-pct');
  const fillEl = document.getElementById('transfer-hud-progress-fill');
  const completeActions = document.getElementById('transfer-hud-complete-actions');
  const closeBtn = document.getElementById('transfer-hud-close-btn');

  const speedEtaText = calculateSpeedAndEta(data.file_id, data.bytes_transferred, data.total_bytes);

  if (dirIconEl) dirIconEl.innerText = directionIcon;
  if (titleEl) titleEl.innerText = directionTitle;
  if (fileIconEl) fileIconEl.innerText = getFileIcon(data.file_name);
  if (fileNameEl) fileNameEl.innerText = data.file_name || 'file';
  if (bytesEl) bytesEl.innerText = `${formatBytes(data.bytes_transferred)} / ${formatBytes(data.total_bytes)}`;
  if (speedEl) speedEl.innerText = speedEtaText;
  if (pctEl) {
    pctEl.innerText = `${pctVal}%`;
    pctEl.style.color = 'var(--accent-green)';
  }
  if (fillEl) {
    fillEl.style.width = `${pctVal}%`;
    fillEl.classList.remove('fill-failed');
  }
  if (completeActions) completeActions.style.display = 'none';
  if (closeBtn) closeBtn.style.display = 'flex';

  // Update minimized pill
  const pillIcon = document.getElementById('transfer-pill-icon');
  const pillName = document.getElementById('transfer-pill-name');
  const pillPct = document.getElementById('transfer-pill-pct');
  const pillFill = document.getElementById('transfer-pill-circle-fill');

  if (pillIcon) pillIcon.innerText = directionIcon;
  if (pillName) pillName.innerText = speedEtaText ? `${data.file_name || 'File'} • ${speedEtaText}` : (data.file_name || 'Transferring...');
  if (pillPct) {
    pctVal >= 100 ? (pillPct.innerText = '100%') : (pillPct.innerText = `${pctVal}%`);
    pillPct.style.color = 'var(--accent-green)';
  }
  if (pillFill) {
    pillFill.setAttribute('stroke-dasharray', `${pctVal}, 100`);
    pillFill.style.stroke = 'var(--accent-green)';
  }
}

function updateTransferHudCompleted(data) {
  const hud = document.getElementById('floating-transfer-hud');
  const expanded = document.getElementById('transfer-hud-expanded');
  const minimized = document.getElementById('transfer-hud-minimized');
  if (!hud) return;

  if (transferHudDismissTimeout) {
    clearTimeout(transferHudDismissTimeout);
    transferHudDismissTimeout = null;
  }

  hudSpeedTracker = { file_id: null, bytes: 0, time: 0, speed: 0 };

  const isSuccess = data.success !== false;
  const titleEl = document.getElementById('transfer-hud-title');
  const dirIconEl = document.getElementById('transfer-hud-direction-icon');
  const fillEl = document.getElementById('transfer-hud-progress-fill');
  const speedEl = document.getElementById('transfer-hud-speed');
  const pctEl = document.getElementById('transfer-hud-pct');
  const completeActions = document.getElementById('transfer-hud-complete-actions');
  const closeBtn = document.getElementById('transfer-hud-close-btn');

  expanded?.classList.remove('hud-failed', 'hud-success');
  minimized?.classList.remove('hud-failed', 'hud-success');

  if (speedEl) speedEl.innerText = '';

  if (isSuccess) {
    expanded?.classList.add('hud-success');
    minimized?.classList.add('hud-success');
    if (titleEl) titleEl.innerText = 'Transfer Complete! 🎉';
    if (dirIconEl) dirIconEl.innerText = '✅';
    if (fillEl) {
      fillEl.style.width = '100%';
      fillEl.classList.remove('fill-failed');
    }
    if (pctEl) {
      pctEl.innerText = '100%';
      pctEl.style.color = 'var(--accent-green)';
    }
    if (completeActions && data.is_incoming) completeActions.style.display = 'block';
  } else {
    expanded?.classList.add('hud-failed');
    minimized?.classList.add('hud-failed');
    const msg = data.message || 'Transfer Cancelled';
    if (titleEl) titleEl.innerText = `${msg} 🛑`;
    if (dirIconEl) dirIconEl.innerText = '🛑';
    if (fillEl) {
      fillEl.style.width = '100%';
      fillEl.classList.add('fill-failed');
    }
    if (pctEl) {
      pctEl.innerText = 'Cancelled';
      pctEl.style.color = '#EF4444';
    }
    if (completeActions) completeActions.style.display = 'none';
  }

  if (closeBtn) closeBtn.style.display = 'flex';

  const pillName = document.getElementById('transfer-pill-name');
  const pillPct = document.getElementById('transfer-pill-pct');
  const pillIcon = document.getElementById('transfer-pill-icon');
  const pillFill = document.getElementById('transfer-pill-circle-fill');

  if (pillName) pillName.innerText = isSuccess ? 'Transfer Complete!' : (data.message || 'Cancelled');
  if (pillPct) {
    pillPct.innerText = isSuccess ? '100%' : '🛑';
    pillPct.style.color = isSuccess ? 'var(--accent-green)' : '#EF4444';
  }
  if (pillIcon) pillIcon.innerText = isSuccess ? '✅' : '🛑';
  if (pillFill) {
    pillFill.setAttribute('stroke-dasharray', '100, 100');
    pillFill.style.stroke = isSuccess ? 'var(--accent-green)' : '#EF4444';
  }

  // Ensure HUD is shown
  hud.style.display = 'block';

  // Auto-dismiss after 4 seconds (failure/cancel) or 5 seconds (success)
  transferHudDismissTimeout = setTimeout(() => {
    window.closeTransferHud();
  }, isSuccess ? 5000 : 4000);
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
  if (hud) {
    hud.classList.add('hud-closing');
    setTimeout(() => {
      hud.style.display = 'none';
      hud.classList.remove('hud-closing');
      isTransferHudMinimized = false;
      const expanded = document.getElementById('transfer-hud-expanded');
      const minimized = document.getElementById('transfer-hud-minimized');
      if (expanded) expanded.style.display = 'block';
      if (minimized) minimized.style.display = 'none';
    }, 250);
  }
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
fetchLatestClipboard();
setupWebSocket();
setInterval(fetchStatus, 2000);
