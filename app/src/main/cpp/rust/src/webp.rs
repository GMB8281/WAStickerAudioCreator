use sha2::{Digest, Sha256};

pub fn sha256(b: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(b);
    h.finalize().into()
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn b64(data: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::with_capacity((data.len() + 2) / 3 * 4);
    for c in data.chunks(3) {
        let n = ((c[0] as u32) << 16)
            | ((*c.get(1).unwrap_or(&0) as u32) << 8)
            | (*c.get(2).unwrap_or(&0) as u32);
        s.push(T[((n >> 18) & 63) as usize] as char);
        s.push(T[((n >> 12) & 63) as usize] as char);
        s.push(if c.len() > 1 { T[((n >> 6) & 63) as usize] as char } else { '=' });
        s.push(if c.len() > 2 { T[(n & 63) as usize] as char } else { '=' });
    }
    s
}

pub fn b64sha(b: &[u8]) -> String {
    b64(&sha256(b))
}

pub fn is_webp(b: &[u8]) -> bool {
    b.len() > 12 && &b[0..4] == b"RIFF" && &b[8..12] == b"WEBP"
}

pub fn is_ogg(b: &[u8]) -> bool {
    b.len() > 4 && &b[0..4] == b"OggS"
}

fn chunk(fourcc: &[u8], payload: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(8 + payload.len() + 1);
    v.extend(fourcc);
    v.extend((payload.len() as u32).to_le_bytes());
    v.extend(payload);
    if payload.len() & 1 == 1 {
        v.push(0);
    }
    v
}

/// Minimal little-endian TIFF with a single ImageDescription (0x010E) ASCII tag,
/// exactly like the Python `build_exif` from the reverse engineering.
fn build_exif(json: &str) -> Vec<u8> {
    let mut s = json.as_bytes().to_vec();
    s.push(0);
    let n = s.len() as u32;
    let mut b: Vec<u8> = Vec::new();
    b.extend(b"II");
    b.extend(42u16.to_le_bytes());
    b.extend(8u32.to_le_bytes());
    b.extend(1u16.to_le_bytes()); // 1 IFD entry
    b.extend(0x010Eu16.to_le_bytes()); // ImageDescription
    b.extend(2u16.to_le_bytes()); // ASCII
    b.extend(n.to_le_bytes());
    if n <= 4 {
        b.extend(&s);
        b.extend(vec![0u8; (4 - n) as usize]);
    } else {
        b.extend(26u32.to_le_bytes());
    }
    b.extend(0u32.to_le_bytes()); // next IFD = 0
    if n > 4 {
        b.extend(&s);
    }
    b
}

/// Rotate the file hash WITHOUT touching the image: rewrite/append the EXIF chunk
/// with a fresh nonce. plain_file_hash = sha256(whole file) changes; pixels don't.
pub fn uniquify(data: &[u8], name: &str, publisher: &str) -> Result<Vec<u8>, String> {
    if !is_webp(data) {
        return Err("not a RIFF/WEBP file".into());
    }
    let meta = serde_json::json!({
        "sticker-pack-id": uuid::Uuid::new_v4().to_string(),
        "sticker-pack-name": name,
        "sticker-pack-publisher": publisher,
        "nonce": format!("{:016x}", rand::random::<u64>()),
    });
    let payload = build_exif(&meta.to_string());
    let mut body: Vec<u8> = b"WEBP".to_vec();
    let mut pos = 12usize;
    let mut done = false;
    while pos + 8 <= data.len() {
        let fourcc = &data[pos..pos + 4];
        let size = u32::from_le_bytes(data[pos + 4..pos + 8].try_into().unwrap()) as usize;
        let total = 8 + size + (size & 1);
        if pos + total > data.len() {
            return Err("truncated WEBP".into());
        }
        if fourcc == b"EXIF" {
            body.extend(chunk(b"EXIF", &payload));
            done = true;
        } else {
            body.extend(&data[pos..pos + total]);
        }
        pos += total;
    }
    if !done {
        body.extend(chunk(b"EXIF", &payload));
    }
    let mut out = Vec::with_capacity(body.len() + 8);
    out.extend(b"RIFF");
    out.extend((body.len() as u32).to_le_bytes());
    out.extend(body);
    Ok(out)
}
