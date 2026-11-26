mod anim;
mod ops;
mod opus;
mod shell;
mod webp;

use serde_json::json;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

fn to_str(p: *const c_char) -> String {
    if p.is_null() { return String::new(); }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn out(v: serde_json::Value) -> *mut c_char {
    CString::new(v.to_string())
        .unwrap_or_else(|_| CString::new("{\"ok\":false,\"error\":\"encoding\"}").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "C" fn wa_free_string(p: *mut c_char) {
    if !p.is_null() { unsafe { drop(CString::from_raw(p)); } }
}

#[no_mangle]
pub extern "C" fn wa_root_status() -> *mut c_char {
    out(match shell::root_ok() {
        Ok(()) => json!({"ok": true}),
        Err(e) => json!({"ok": false, "error": e}),
    })
}

#[no_mangle]
pub extern "C" fn wa_env_check(req: *const c_char) -> *mut c_char {
    out(ops::env(&to_str(req)))
}

#[no_mangle]
pub extern "C" fn wa_inject_pack(req: *const c_char) -> *mut c_char {
    out(ops::inject_pack(&to_str(req)))
}

#[no_mangle]
pub extern "C" fn wa_inject_favorite(req: *const c_char) -> *mut c_char {
    out(ops::inject_favorite(&to_str(req)))
}

#[no_mangle]
pub extern "C" fn wa_remove_pack(req: *const c_char) -> *mut c_char {
    out(ops::remove_pack(&to_str(req)))
}

/// Animated WebP re-encode with baked overlay (no root needed).
#[no_mangle]
pub extern "C" fn wa_overlay_animated(req: *const c_char) -> *mut c_char {
    out(anim::overlay_animated(&to_str(req)))
}