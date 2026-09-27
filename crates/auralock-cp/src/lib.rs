/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

// Windows Credential Provider DLL Entry Points

#[no_mangle]
pub extern "system" fn DllGetClassObject(
    _rclsid: *const std::ffi::c_void,
    _riid: *const std::ffi::c_void,
    _ppv: *mut *mut std::ffi::c_void,
) -> i32 {
    0 // S_OK
}

#[no_mangle]
pub extern "system" fn DllCanUnloadNow() -> i32 {
    0 // S_OK
}

#[no_mangle]
pub extern "system" fn DllRegisterServer() -> i32 {
    0 // S_OK
}

#[no_mangle]
pub extern "system" fn DllUnregisterServer() -> i32 {
    0 // S_OK
}
