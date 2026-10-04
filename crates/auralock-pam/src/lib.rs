/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

#[cfg(unix)]
use auralock_core::ipc::{IpcClient, DEFAULT_UNIX_SOCKET_PATH};
#[cfg(unix)]
use std::ffi::{CStr, CString};
#[cfg(unix)]
use std::os::raw::{c_char, c_int, c_void};

#[cfg(unix)]
pub const PAM_SUCCESS: c_int = 0;
#[cfg(unix)]
pub const PAM_AUTH_ERR: c_int = 7;
#[cfg(unix)]
pub const PAM_IGNORE: c_int = 25;
#[cfg(unix)]
pub const PAM_USER: c_int = 2;
#[cfg(unix)]
pub const PAM_CONV: c_int = 5;
#[cfg(unix)]
pub const PAM_TEXT_INFO: c_int = 4;
#[cfg(unix)]
pub const PAM_ERROR_MSG: c_int = 3;

#[cfg(unix)]
#[repr(C)]
struct PamMessage {
    msg_style: c_int,
    msg: *const c_char,
}

#[cfg(unix)]
#[repr(C)]
struct PamResponse {
    resp: *mut c_char,
    resp_retcode: c_int,
}

#[cfg(unix)]
#[repr(C)]
struct PamConv {
    conv: Option<
        unsafe extern "C" fn(
            num_msg: c_int,
            msg: *mut *const PamMessage,
            resp: *mut *mut PamResponse,
            appdata_ptr: *mut c_void,
        ) -> c_int,
    >,
    appdata_ptr: *mut c_void,
}

#[cfg(unix)]
extern "C" {
    fn pam_get_item(pamh: *mut c_void, item_type: c_int, item: *mut *const c_void) -> c_int;
    fn free(ptr: *mut c_void);
}

#[cfg(unix)]
fn send_pam_info(pamh: *mut c_void, message: &str, is_error: bool) {
    unsafe {
        let mut conv_ptr: *const c_void = std::ptr::null();
        if pam_get_item(pamh, PAM_CONV, &mut conv_ptr) != PAM_SUCCESS || conv_ptr.is_null() {
            return;
        }
        let conv_struct = &*(conv_ptr as *const PamConv);
        if let Some(conv_fn) = conv_struct.conv {
            if let Ok(c_msg) = CString::new(message) {
                let msg = PamMessage {
                    msg_style: if is_error { PAM_ERROR_MSG } else { PAM_TEXT_INFO },
                    msg: c_msg.as_ptr(),
                };
                let mut msg_ptr: *const PamMessage = &msg;
                let mut resp_ptr: *mut PamResponse = std::ptr::null_mut();
                let _ = conv_fn(1, &mut msg_ptr, &mut resp_ptr, conv_struct.appdata_ptr);
                if !resp_ptr.is_null() {
                    if !(*resp_ptr).resp.is_null() {
                        free((*resp_ptr).resp as *mut c_void);
                    }
                    free(resp_ptr as *mut c_void);
                }
            }
        }
    }
}

/// Linux PAM Authentication Hook
///
/// When the screen lock or login prompt is triggered on Linux (GNOME, KDE, GDM, SDDM, etc.),
/// this function communicates with the local AuraLock background daemon and displays native lock screen prompts.
#[cfg(unix)]
#[no_mangle]
pub extern "C" fn pam_sm_authenticate(
    pamh: *mut c_void,
    _flags: c_int,
    _argc: c_int,
    _argv: *const *const c_char,
) -> c_int {
    let mut user_ptr: *const c_void = std::ptr::null();
    let ret = unsafe { pam_get_item(pamh, PAM_USER, &mut user_ptr) };

    let username = if ret == PAM_SUCCESS && !user_ptr.is_null() {
        let c_str = unsafe { CStr::from_ptr(user_ptr as *const c_char) };
        c_str.to_string_lossy().to_string()
    } else {
        std::env::var("USER").unwrap_or_else(|_| "user".to_string())
    };

    // Check if the AuraLock Unix socket is active
    if !std::path::Path::new(DEFAULT_UNIX_SOCKET_PATH).exists() {
        // Daemon is not running, fall back gracefully to password prompt
        return PAM_IGNORE;
    }

    // Display native UI prompt on lockscreen
    send_pam_info(pamh, "📱 [AuraLock] Touch fingerprint on paired phone to unlock (1 min)...", false);

    // Run async IPC request synchronously inside the PAM thread
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(_) => return PAM_AUTH_ERR,
    };

    match runtime.block_on(IpcClient::request_unlock(DEFAULT_UNIX_SOCKET_PATH, &username)) {
        Ok(true) => {
            // Biometric verified by phone!
            send_pam_info(pamh, "✨ [AuraLock] Biometric verified! Unlocking...", false);
            PAM_SUCCESS
        }
        Ok(false) => {
            // Phone rejected or timed out; allow fallback to standard password
            send_pam_info(pamh, "⚠️ [AuraLock] Biometric timed out (1 min) / rejected. Please enter password:", true);
            PAM_AUTH_ERR
        }
        Err(_) => {
            // Socket or IPC error; ignore so standard password auth takes over
            PAM_IGNORE
        }
    }
}

#[cfg(unix)]
#[no_mangle]
pub extern "C" fn pam_sm_setcred(
    _pamh: *mut c_void,
    _flags: c_int,
    _argc: c_int,
    _argv: *const *const c_char,
) -> c_int {
    PAM_SUCCESS
}
