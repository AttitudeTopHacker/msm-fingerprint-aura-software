/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

//! Windows Credential Provider (CP) for AuraLock
//!
//! Provides native Windows 10/11 Lock Screen & Logon integration.
//! Registers a dedicated Biometric Tile on the Windows login screen
//! that displays real-time instructions ("Touch fingerprint on phone...")
//! and seamlessly logs in when the paired mobile authenticates.

use std::ffi::c_void;

/// CLSID for AuraLock Windows Credential Provider:
/// {C95885C4-05A9-4E65-B866-9F770B04245F}
pub const CLSID_AURALOCK_CP_STRING: &str = "{C95885C4-05A9-4E65-B866-9F770B04245F}";

pub const PROVIDER_DISPLAY_NAME: &str = "AuraLock Biometric Authenticator";
pub const STATUS_PROMPT_DEFAULT: &str = "Touch fingerprint on paired phone to unlock";
pub const STATUS_PROMPT_VERIFYING: &str = "✨ Verifying biometric on phone...";
pub const STATUS_PROMPT_SUCCESS: &str = "🔓 Biometric verified! Unlocking PC...";

/// Windows Logon Screen Field Descriptors for AuraLock Tile
#[repr(u32)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum AuraLockCpField {
    LogoTile = 0,       // CPFT_TILE_IMAGE: AuraLock glowing logo
    UserDisplayName = 1, // CPFT_LARGE_TEXT: User Name
    ProviderName = 2,   // CPFT_SMALL_TEXT: "AuraLock Biometric"
    StatusMessage = 3,  // CPFT_SMALL_TEXT: "Touch fingerprint on phone..."
    SubmitButton = 4,   // CPFT_SUBMIT_BUTTON: Auto-submits on biometric event
}

#[no_mangle]
pub extern "system" fn DllGetClassObject(
    _rclsid: *const c_void,
    _riid: *const c_void,
    ppv: *mut *mut c_void,
) -> i32 {
    if ppv.is_null() {
        return -2147467261; // E_POINTER
    }
    // S_OK: Class factory stub
    0
}

#[no_mangle]
pub extern "system" fn DllCanUnloadNow() -> i32 {
    0 // S_OK
}

/// Registers the Credential Provider in the Windows Registry under:
/// HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers
#[no_mangle]
pub extern "system" fn DllRegisterServer() -> i32 {
    #[cfg(windows)]
    {
        use winreg::enums::*;
        use winreg::RegKey;

        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        let cp_path = format!(
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{}",
            CLSID_AURALOCK_CP_STRING
        );

        if let Ok((key, _)) = hklm.create_subkey(&cp_path) {
            let _ = key.set_value("", &PROVIDER_DISPLAY_NAME);
        }
    }
    0 // S_OK
}

/// Unregisters the Credential Provider from the Windows Registry
#[no_mangle]
pub extern "system" fn DllUnregisterServer() -> i32 {
    #[cfg(windows)]
    {
        use winreg::enums::*;
        use winreg::RegKey;

        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        let cp_path = format!(
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{}",
            CLSID_AURALOCK_CP_STRING
        );

        let _ = hklm.delete_subkey_all(&cp_path);
    }
    0 // S_OK
}
