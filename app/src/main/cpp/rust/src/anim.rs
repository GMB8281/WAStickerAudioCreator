use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;

extern "C" {
    fn wa_anim_overlay(
        src: *const u8,
        src_len: usize,
        ov: *const u8,
        ov_w: i32,
        ov_h: i32,
        a: f32,
        b: f32,
        c: f32,
        d: f32,
        e: f32,
        f: f32,
        out_size: i32,
        quality: f32,
        out: *mut *mut u8,
        out_len: *mut usize,
    ) -> i32;

    fn wa_anim_free(p: *mut u8);
}

fn d_size() -> i32 {
    512
}

fn d_max() -> usize {
    500_000
}

#[derive(Deserialize)]
struct Req {
    src_webp: String,

    /// Raw RGBA bytes, size*size*4 (overlay rendered by Kotlin). Optional.
    #[serde(default)]
    overlay_rgba: Option<String>,

    out_webp: String,

    #[serde(default = "d_size")]
    size: i32,

    /// Affine transform from source media coordinates to 512 sticker coordinates:
    /// [a, b, c, d, e, f]
    ///
    /// dst_x = a*src_x + b*src_y + c
    /// dst_y = d*src_x + e*src_y + f
    matrix: Vec<f32>,

    #[serde(default = "d_max")]
    max_bytes: usize,
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
            let b = fs::read(p).map_err(|e| format!("read overlay: {e}"))?;
            if b.len() != expect {
                return Err(format!(
                    "overlay must be {} bytes of RGBA, got {}",
                    expect,
                    b.len()
                ));
            }
            b
        }
        None => Vec::new(),
    };

    let (ov_ptr, ov_w, ov_h) = if ov.is_empty() {
        (std::ptr::null(), 0, 0)
    } else {
        (ov.as_ptr(), r.size, r.size)
    };

    let mut best: Vec<u8> = Vec::new();
    let mut best_q = 0f32;

    for q in [75f32, 60.0, 45.0, 30.0, 20.0] {
        let mut out: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;

        let ok = unsafe {
            wa_anim_overlay(
                src.as_ptr(),
                src.len(),
                ov_ptr,
                ov_w,
                ov_h,
                a,
                b,
                c,
                d,
                e,
                f,
                r.size,
                q,
                &mut out,
                &mut out_len,
            )
        };

        if ok != 1 || out.is_null() || out_len == 0 {
            if !out.is_null() {
                unsafe { wa_anim_free(out); }
            }
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
        "quality": best_q
    }))
}