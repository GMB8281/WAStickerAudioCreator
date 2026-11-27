//! Ogg Opus helpers: duration and interval trimming.

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
    nsegs: usize,
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
            nsegs,
            header_type_offset: pos + 5,
            crc_offset: pos + 22,
        });

        pos = end;
    }

    pages
}

fn find_pre_skip(data: &[u8]) -> u32 {
    if let Some(idx) = data.windows(8).position(|w| w == b"OpusHead") {
        if idx + 12 <= data.len() {
            return u16::from_le_bytes([data[idx + 10], data[idx + 11]]) as u32;
        }
    }
    0
}

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

pub fn trim_interval(data: &[u8], start_ms: u64, end_ms: u64) -> Result<Vec<u8>, String> {
    if data.len() < 27 || &data[0..4] != b"OggS" {
        return Err("not Ogg".into());
    }

    let pre_skip = find_pre_skip(data) as i64;
    let start_samples = pre_skip + (start_ms as i64 * 48000) / 1000;
    let end_samples = pre_skip + (end_ms as i64 * 48000) / 1000;

    let pages = parse_pages(data);
    if pages.len() < 3 {
        return Err("too few Ogg pages".into());
    }

    let head_page = &pages[0];
    let tags_page = &pages[1];

    let mut base_granule: i64 = pre_skip;
    let mut first_kept_idx: Option<usize> = None;

    for (i, page) in pages.iter().enumerate().skip(2) {
        if page.granule > 0 {
            if page.granule < start_samples {
                base_granule = page.granule;
            } else if first_kept_idx.is_none() {
                first_kept_idx = Some(i);
            }
            if page.granule > end_samples {
                break;
            }
        }
    }

    let first_idx = first_kept_idx.ok_or("no audio pages in interval")?;
    let kept_pages: Vec<&Page> = pages.iter()
        .skip(first_idx)
        .take_while(|p| p.granule <= end_samples || p.granule < 0)
        .collect();

    if kept_pages.is_empty() {
        return Err("no audio pages in interval".into());
    }

    let mut out: Vec<u8> = Vec::new();

    let head_data = &data[head_page.start..head_page.end];
    out.extend_from_slice(head_data);
    let crc_offset = head_page.crc_offset - head_page.start;
    for i in 0..4 { out[crc_offset + i] = 0; }
    let crc = ogg_crc(&out[0..head_page.end - head_page.start]);
    out[crc_offset..crc_offset + 4].copy_from_slice(&crc.to_le_bytes());

    let tags_data = &data[tags_page.start..tags_page.end];
    let tags_offset = out.len();
    out.extend_from_slice(tags_data);
    let crc_offset = tags_page.crc_offset - tags_page.start + tags_offset;
    for i in 0..4 { out[crc_offset + i] = 0; }
    let crc = ogg_crc(&out[tags_offset..tags_offset + tags_page.end - tags_page.start]);
    out[crc_offset..crc_offset + 4].copy_from_slice(&crc.to_le_bytes());

    for (i, page) in kept_pages.iter().enumerate() {
        let page_data = &data[page.start..page.end];
        let page_offset = out.len();
        out.extend_from_slice(page_data);

        let granule_offset = page_offset + 6;
        let old_granule = i64::from_le_bytes(
            out[granule_offset..granule_offset + 8].try_into().unwrap_or([0u8; 8]),
        );
        let new_granule = old_granule - base_granule;
        out[granule_offset..granule_offset + 8].copy_from_slice(&new_granule.to_le_bytes());

        if i == kept_pages.len() - 1 {
            let header_type_offset = page.header_type_offset - page.start + page_offset;
            out[header_type_offset] |= 0x04;
        }

        let crc_offset = page.crc_offset - page.start + page_offset;
        for j in 0..4 { out[crc_offset + j] = 0; }
        let crc = ogg_crc(&out[page_offset..page_offset + page.end - page.start]);
        out[crc_offset..crc_offset + 4].copy_from_slice(&crc.to_le_bytes());
    }

    Ok(out)
}