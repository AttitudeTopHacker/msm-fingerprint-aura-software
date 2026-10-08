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
pub const PAM_PROMPT_ECHO_OFF: c_int = 1;
#[cfg(unix)]
pub const PAM_PROMPT_ECHO_ON: c_int = 2;
#[cfg(unix)]
pub const PAM_AUTHTOK: c_int = 6;

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
    fn pam_set_item(pamh: *mut c_void, item_type: c_int, item: *const c_void) -> c_int;
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

#[cfg(unix)]
fn pam_prompt_password(pamh: *mut c_void, prompt_text: &str) -> Option<String> {
    unsafe {
        let mut conv_ptr: *const c_void = std::ptr::null();
        if pam_get_item(pamh, PAM_CONV, &mut conv_ptr) != PAM_SUCCESS || conv_ptr.is_null() {
            return None;
        }
        let conv_struct = &*(conv_ptr as *const PamConv);
        if let Some(conv_fn) = conv_struct.conv {
            if let Ok(c_msg) = CString::new(prompt_text) {
                let msg = PamMessage {
                    msg_style: PAM_PROMPT_ECHO_OFF,
                    msg: c_msg.as_ptr(),
                };
                let mut msg_ptr: *const PamMessage = &msg;
                let mut resp_ptr: *mut PamResponse = std::ptr::null_mut();
                let ret = conv_fn(1, &mut msg_ptr, &mut resp_ptr, conv_struct.appdata_ptr);
                if ret == PAM_SUCCESS && !resp_ptr.is_null() {
                    let mut result = None;
                    if !(*resp_ptr).resp.is_null() {
                        let c_str = CStr::from_ptr((*resp_ptr).resp);
                        result = Some(c_str.to_string_lossy().to_string());
                        free((*resp_ptr).resp as *mut c_void);
                    }
                    free(resp_ptr as *mut c_void);
                    return result;
                }
            }
        }
    }
    None
}

/// Linux PAM Authentication Hook
///
/// When the screen lock, sudo, or polkit login prompt is triggered on Linux,
/// this function concurrently listens for Phone Biometric and Keyboard Password entry.
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

    let (pw_tx, mut pw_rx) = tokio::sync::mpsc::channel(1);
    let pamh_usize = pamh as usize;

    // Concurrently prompt user for password on lockscreen/terminal
    std::thread::spawn(move || {
        let pamh_ptr = pamh_usize as *mut c_void;
        let entered = pam_prompt_password(pamh_ptr, "📱 Touch phone fingerprint or enter password: ");
        let _ = pw_tx.blocking_send(entered);
    });

    let auth_result = runtime.block_on(async {
        tokio::select! {
            bio_res = IpcClient::request_unlock(DEFAULT_UNIX_SOCKET_PATH, &username) => {
                match bio_res {
                    Ok(true) => {
                        // Biometric verified by phone!
                        send_pam_info(pamh, "✨ Biometric verified! Unlocking...", false);
                        PAM_SUCCESS
                    }
                    Ok(false) => {
                        // Phone timed out or rejected. Await password if user is currently typing
                        if let Some(Some(pw)) = pw_rx.recv().await {
                            if let Ok(c_pw) = CString::new(pw) {
                                unsafe {
                                    pam_set_item(pamh, PAM_AUTHTOK, c_pw.as_ptr() as *const c_void);
                                }
                            }
                        }
                        PAM_IGNORE
                    }
                    Err(_) => {
                        // Socket or IPC error; ignore so standard password auth takes over
                        PAM_IGNORE
                    }
                }
            }
            user_input = pw_rx.recv() => {
                // User entered password and submitted via keyboard! Immediately cancel challenge on phone
                let _ = IpcClient::cancel_unlock(DEFAULT_UNIX_SOCKET_PATH).await;
                if let Some(Some(pw)) = user_input {
                    if let Ok(c_pw) = CString::new(pw) {
                        unsafe {
                            pam_set_item(pamh, PAM_AUTHTOK, c_pw.as_ptr() as *const c_void);
                        }
                    }
                }
                PAM_IGNORE
            }
        }
    });

    auth_result
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
