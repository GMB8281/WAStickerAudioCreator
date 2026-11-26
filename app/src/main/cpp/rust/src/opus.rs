//! Ogg Opus helpers: duration and trimming on page boundaries.

fn ogg_crc(data: &[u8]) -> u32 {
    let mut crc: u32 = 0;
    for &byte in data {
        crc ^= (byte as u32) << 24;
        for _ in 0..8 {
            crc = if crc & 0x80000000 != 0 {
                (crc << 1) ^ 0x04c11db7
            } else {
                crc << 1
            };
        }
    }
    crc
}

struct Page {
    start: usize,
    end: usize,
    granule: i64,
    header_type_offset: usize,
    crc_offset: usize,
}

fn parse_pages(data: &[u8]) -> Vec<Page> {
    let mut pages = Vec::new();
    let mut pos = 0usize;

    while pos + 27 <= data.len() {
        if &data[pos..pos + 4] != b"OggS" {
            pos += 1;
            continue;
        }

        let granule = i64::from_le_bytes(
            data[pos + 6..pos + 14].try_into().unwrap_or([0u8; 8]),
        );
        let nsegs = data[pos + 26] as usize;
        let seg_table = pos + 27;
        if seg_table + nsegs > data.len() {
            break;
        }

        let mut body = 0usize;
        for i in 0..nsegs {
            body += data[seg_table + i] as usize;
        }
        let end = seg_table + nsegs + body;
        if end > data.len() {
            break;
        }

        pages.push(Page {
            start: pos,
            end,
            granule,
            header_type_offset: pos + 5,
            crc_offset: pos + 22,
        });

        pos = end;
    }

    pages
}

fn find_pre_skip(data: &[u8]) -> u32 {
    // OpusHead: "OpusHead" + version(1) + channels(1) + pre_skip(2, LE)
    if let Some(idx) = data.windows(8).position(|w| w == b"OpusHead") {
        if idx + 12 <= data.len() {
            return u16::from_le_bytes([data[idx + 10], data[idx + 11]]) as u32;
        }
    }
    0
}

/// Duration of the Ogg Opus stream in milliseconds.
pub fn duration_ms(data: &[u8]) -> u64 {
    if data.len() < 27 || &data[0..4] != b"OggS" {
        return 0;
    }

    let pre_skip = find_pre_skip(data) as i64;
    let pages = parse_pages(data);
    let mut last_granule: i64 = 0;

    for p in &pages {
        if p.granule > 0 {
            last_granule = p.granule;
        }
    }

    if last_granule <= pre_skip {
        return 0;
    }

    let samples = last_granule - pre_skip;
    (samples as u64 * 1000) / 48000
}

/// Trim the Ogg Opus stream so its duration does not exceed max_ms.
/// Cuts on a page boundary, rewrites EOS and recomputes CRC of the last page.
pub fn trim_to_ms(data: &[u8], max_ms: u64) -> Result<Vec<u8>, String> {
    if data.len() < 27 || &data[0..4] != b"OggS" {
        return Err("not Ogg".into());
    }

    let full = duration_ms(data);
    if full <= max_ms {
        return Ok(data.to_vec());
    }

    let pre_skip = find_pre_skip(data) as i64;
    let max_samples = pre_skip + (max_ms as i64 * 48000) / 1000;

    let pages = parse_pages(data);
    if pages.is_empty() {
        return Err("no Ogg pages".into());
    }

    // Find the last page whose granule is within the allowed sample budget.
    let mut cut_index: Option<usize> = None;
    for (i, p) in pages.iter().enumerate() {
        if p.granule > 0 && p.granule <= max_samples {
            cut_index = Some(i);
        }
    }

    let keep = match cut_index {
        Some(i) => i,
        None => return Err("cannot trim: first page already exceeds limit".into()),
    };

    let end = pages[keep].end;
    let mut out = data[..end].to_vec();

    // Set EOS flag on the last kept page and recompute its CRC.
    let page_start = pages[keep].start;
    let header_type_offset = pages[keep].header_type_offset;
    let crc_offset = pages[keep].crc_offset;

    out[header_type_offset] |= 0x04; // EOS

    // Zero the CRC field before computing.
    for i in 0..4 {
        out[crc_offset + i] = 0;
    }

    let crc = ogg_crc(&out[page_start..end]);
    out[crc_offset..crc_offset + 4].copy_from_slice(&crc.to_le_bytes());

    Ok(out)
}