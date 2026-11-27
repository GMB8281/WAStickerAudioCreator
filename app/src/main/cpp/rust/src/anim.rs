use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;

use crate::opus;

extern "C" {
    fn wa_anim_overlay(
        src: *const u8, src_len: usize,
        ov: *const u8, ov_w: i32, ov_h: i32,
        m0: f32, m1: f32, m2: f32,
        m3: f32, m4: f32, m5: f32,
        out_size: i32, quality: f32,
        target_duration_ms: i32, max_duration_ms: i32, target_fps: i32,
        out: *mut *mut u8, out_len: *mut usize,
    ) -> i32;

    fn wa_anim_free(p: *mut u8);
}

fn d_size() -> i32 { 512 }
fn d_max() -> usize { 500_000 }
fn d_fps() -> i32 { 10 }
fn d_ceiling() -> i32 { 10_000 }

#[derive(Deserialize)]
struct Req {
    src_webp: String,

    #[serde(default)]
    overlay_rgba: Option<String>,

    out_webp: String,

    #[serde(default)]
    opus_path: Option<String>,

    #[serde(default)]
    out_opus: Option<String>,

    #[serde(default = "d_size")]
    size: i32,

    matrix: Vec<f32>,

    #[serde(default = "d_max")]
    max_bytes: usize,

    #[serde(default = "d_fps")]
    target_fps: i32,

    #[serde(default = "d_ceiling")]
    max_duration_ms: i32,

    #[serde(default)]
    audio_start_ms: Option<i32>,

    #[serde(default)]
    audio_end_ms: Option<i32>,
}

pub fn overlay_animated(req: &str) -> Value {
    match inner(req) {
        Ok(v) => v,
        Err(e) => json!({ "ok": false, "error": e }),
    }
}

fn inner(req: &str) -> Result<Value, String> {
    let r: Req = serde_json::from_str(req).map_err(|e| e.to_string())?;

    if r.size <= 0 || r.size > 1024 {
        return Err("bad output size".into());
    }
    if r.matrix.len() != 6 {
        return Err("matrix must have 6 values".into());
    }
    if !r.matrix.iter().all(|v| v.is_finite()) {
        return Err("matrix contains non-finite values".into());
    }

    let a = r.matrix[0];
    let b = r.matrix[1];
    let c = r.matrix[2];
    let d = r.matrix[3];
    let e = r.matrix[4];
    let f = r.matrix[5];

    let det = a * e - b * d;
    if det.abs() < 1e-8 {
        return Err("matrix is not invertible".into());
    }

    let src = fs::read(&r.src_webp).map_err(|e| format!("read src: {e}"))?;
    if src.len() < 12 || &src[0..4] != b"RIFF" || &src[8..12] != b"WEBP" {
        return Err("source is not WebP".into());
    }

    let expect = (r.size as usize) * (r.size as usize) * 4;
    let ov: Vec<u8> = match &r.overlay_rgba {
        Some(p) => {
            let bv = fs::read(p).map_err(|e| format!("read overlay: {e}"))?;
            if bv.len() != expect {
                return Err(format!(
                    "overlay must be {} bytes of RGBA, got {}",
                    expect, bv.len()
                ));
            }
            bv
        }
        None => Vec::new(),
    };
    let (ov_ptr, ov_w, ov_h) = if ov.is_empty() {
        (std::ptr::null(), 0, 0)
    } else {
        (ov.as_ptr(), r.size, r.size)
    };

    let mut target_duration_ms: i32 = -1;
    let mut audio_trimmed = false;

    if let Some(opus_path) = &r.opus_path {
        let opus_bytes = fs::read(opus_path).map_err(|e| format!("read opus: {e}"))?;
        let audio_ms = opus::duration_ms(&opus_bytes) as i32;

        if audio_ms > 0 {
            let effective_start_ms = r.audio_start_ms.unwrap_or(0).max(0);
            let effective_end_ms = match r.audio_end_ms {
                Some(end) => end.min(audio_ms).min(r.max_duration_ms),
                None => audio_ms.min(r.max_duration_ms),
            };
            let effective_end_ms = effective_end_ms.max(effective_start_ms);
            let effective_duration_ms = effective_end_ms - effective_start_ms;

            target_duration_ms = effective_duration_ms;

            if effective_start_ms > 0 || effective_end_ms < audio_ms {
                if let Some(out_opus) = &r.out_opus {
                    let trimmed = opus::trim_interval(
                        &opus_bytes,
                        effective_start_ms as u64,
                        effective_end_ms as u64
                    )?;
                    fs::write(out_opus, &trimmed).map_err(|e| format!("write opus: {e}"))?;
                    audio_trimmed = true;
                }
            } else if let Some(out_opus) = &r.out_opus {
                fs::write(out_opus, &opus_bytes).map_err(|e| format!("write opus: {e}"))?;
            }
        }
    }

    let mut best: Vec<u8> = Vec::new();
    let mut best_q = 0f32;

    for q in [75f32, 60.0, 45.0, 30.0, 20.0] {
        let mut out: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;

        let ok = unsafe {
            wa_anim_overlay(
                src.as_ptr(), src.len(),
                ov_ptr, ov_w, ov_h,
                a, b, c, d, e, f,
                r.size, q,
                target_duration_ms, r.max_duration_ms, r.target_fps,
                &mut out, &mut out_len,
            )
        };

        if ok != 1 || out.is_null() || out_len == 0 {
            if !out.is_null() { unsafe { wa_anim_free(out); } }
            return Err("animated re-encode failed".into());
        }

        let bytes = unsafe { std::slice::from_raw_parts(out, out_len) }.to_vec();
        unsafe { wa_anim_free(out); }

        best = bytes;
        best_q = q;

        if best.len() <= r.max_bytes {
            break;
        }
    }

    fs::write(&r.out_webp, &best).map_err(|e| format!("write out: {e}"))?;

    Ok(json!({
        "ok": true,
        "bytes": best.len(),
        "quality": best_q,
        "audio_trimmed": audio_trimmed,
        "target_duration_ms": target_duration_ms
    }))
}