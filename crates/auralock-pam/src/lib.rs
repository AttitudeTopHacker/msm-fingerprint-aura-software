/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

#[cfg(unix)]
use auralock_core::ipc::{IpcClient, DEFAULT_UNIX_SOCKET_PATH};
#[cfg(unix)]
use std::ffi::CStr;
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
extern "C" {
    fn pam_get_item(pamh: *mut c_void, item_type: c_int, item: *mut *const c_void) -> c_int;
}

/// Linux PAM Authentication Hook
///
/// When the screen lock or login prompt is triggered on Linux (GNOME, KDE, GDM, SDDM, etc.),
/// this function communicates with the local AuraLock background daemon.
/// If the user validates via phone fingerprint within 10 seconds, this returns PAM_SUCCESS.
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
            PAM_SUCCESS
        }
        Ok(false) => {
            // Phone rejected or timed out; allow fallback to standard password
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
