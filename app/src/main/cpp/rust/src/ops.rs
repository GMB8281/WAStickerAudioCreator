use rusqlite::{params, Connection};
use serde::Deserialize;
use serde_json::{json, Value};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::shell::{q, root_ok, su};
use crate::webp::{b64, b64sha, hex, is_ogg, is_webp, sha256, uniquify};

#[derive(Deserialize)]
pub struct Common {
    pub app_uid: u32,
    pub package: String,
    pub stage_dir: String,
    #[serde(default)]
    pub relaunch: bool,
}

fn default_dur() -> u32 { 5 }

#[derive(Deserialize)]
pub struct StickerIn {
    pub webp_path: String,
    #[serde(default)]
    pub opus_path: Option<String>,
    #[serde(default)]
    pub emoji: String,
    #[serde(default = "default_dur")]
    pub duration: u32,
}

#[derive(Deserialize)]
pub struct PackReq {
    #[serde(flatten)]
    pub c: Common,
    pub pack_id: String,
    pub name: String,
    #[serde(default)]
    pub publisher: String,
    pub tray_path: String,
    #[serde(default)]
    pub replace: bool,
    pub stickers: Vec<StickerIn>,
}

#[derive(Deserialize)]
pub struct FavReq {
    #[serde(flatten)]
    pub c: Common,
    pub webp_path: String,
    #[serde(default)]
    pub opus_path: Option<String>,
    #[serde(default)]
    pub emoji: String,
    #[serde(default = "default_dur")]
    pub duration: u32,
}

#[derive(Deserialize)]
pub struct RemoveReq {
    #[serde(flatten)]
    pub c: Common,
    pub pack_id: String,
}

struct Paths { data: String, stk: String, aud: String, db: String }

impl Paths {
    fn new(pkg: &str) -> Self {
        let data = format!("/data/data/{pkg}");
        Self {
            stk: format!("{data}/files/Stickers"),
            aud: format!("{data}/files/SavedStickerAudio"),
            db: format!("{data}/databases/stickers.db"),
            data,
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn valid_pkg(p: &str) -> bool {
    !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_')
}

fn e<E: std::fmt::Display>(x: E) -> String { x.to_string() }

fn wa_owner(p: &Paths) -> Result<(u32, u32), String> {
    let out = su(&format!("stat -c %u:%g {}", q(&format!("{}/files", p.data))), 15)?;
    let (a, b) = out.split_once(':').ok_or_else(|| format!("bad stat output: {out}"))?;
    Ok((a.trim().parse().map_err(e)?, b.trim().parse().map_err(e)?))
}

fn ensure_dirs(p: &Paths, owner: (u32, u32)) -> Result<(), String> {
    su(&format!("mkdir -p {} {} && chown {}:{} {} {}",
        q(&p.stk), q(&p.aud), owner.0, owner.1, q(&p.stk), q(&p.aud)), 15)?;
    Ok(())
}

fn stage_db(c: &Common, p: &Paths, stop: bool) -> Result<(), String> {
    if stop { su(&format!("am force-stop {}", c.package), 20)?; }
    fs::create_dir_all(&c.stage_dir).map_err(e)?;
    for n in ["stickers.db", "stickers.db-wal", "stickers.db-shm"] {
        let _ = fs::remove_file(Path::new(&c.stage_dir).join(n));
    }
    let dst = format!("{}/stickers.db", c.stage_dir);
    let cmd = format!(
        "cp {} {} && (cp {} {} 2>/dev/null; cp {} {} 2>/dev/null; true) && chown {}:{} {}/stickers.db*",
        q(&p.db), q(&dst),
        q(&format!("{}-wal", p.db)), q(&format!("{dst}-wal")),
        q(&format!("{}-shm", p.db)), q(&format!("{dst}-shm")),
        c.app_uid, c.app_uid, q(&c.stage_dir));
    su(&cmd, 30).map_err(|err| format!("staging stickers.db failed: {err}"))?;
    Ok(())
}

fn open_staged(c: &Common) -> Result<Connection, String> {
    let conn = Connection::open(Path::new(&c.stage_dir).join("stickers.db")).map_err(e)?;
    let _: i32 = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| r.get(0)).unwrap_or(0);
    Ok(conn)
}

fn has_table(conn: &Connection, name: &str) -> bool {
    conn.query_row("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?1",
        params![name], |r| r.get::<_, i64>(0)).unwrap_or(0) > 0
}

fn check_schema(conn: &Connection, need_audio: bool) -> Result<(), String> {
    for t in ["stickers", "installed_sticker_packs", "starred_stickers", "recent_stickers"] {
        if !has_table(conn, t) {
            return Err(format!("unsupported WhatsApp schema (missing table {t})"));
        }
    }
    if need_audio && !has_table(conn, "sticker_audio") {
        return Err("this WhatsApp build has no sound stickers (sticker_audio missing)".into());
    }
    Ok(())
}

fn write_back(c: &Common, p: &Paths, owner: (u32, u32), backup: bool) -> Result<(), String> {
    let staged = format!("{}/stickers.db", c.stage_dir);
    let mut cmd = String::new();
    if backup {
        cmd += &format!("cp {} {}.bak-{} && ", q(&p.db), q(&p.db), now_ms());
    }
    cmd += &format!(
        "cp {} {} && rm -f {} {} && chown {}:{} {} && chmod 660 {} && (restorecon {} 2>/dev/null; true)",
        q(&staged), q(&p.db),
        q(&format!("{}-wal", p.db)), q(&format!("{}-shm", p.db)),
        owner.0, owner.1, q(&p.db), q(&p.db), q(&p.db));
    su(&cmd, 30)?;
    Ok(())
}

fn plant(src: &Path, dst: &str, owner: (u32, u32)) -> Result<(), String> {
    su(&format!(
        "cp {} {} && chown {}:{} {} && chmod 600 {} && (restorecon {} 2>/dev/null; true)",
        q(src.to_str().unwrap_or("")), q(dst), owner.0, owner.1, q(dst), q(dst), q(dst)), 20)?;
    Ok(())
}

fn relaunch(c: &Common) {
    if c.relaunch {
        let _ = su(&format!("monkey -p {} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", c.package), 20);
    }
}

fn cleanup_stage(c: &Common) {
    if let Ok(rd) = fs::read_dir(&c.stage_dir) {
        for f in rd.flatten() { let _ = fs::remove_file(f.path()); }
    }
}

struct Prepared {
    hash: String,
    webp_name: String,
    webp_stage: PathBuf,
    webp_len: u64,
    opus: Option<(String, PathBuf, [u8; 32], u64)>, // name, stage path, sha256, len
}

fn prepare(c: &Common, s: &StickerIn, name: &str, publisher: &str) -> Result<Prepared, String> {
    let raw = fs::read(&s.webp_path).map_err(|err| format!("read {}: {err}", s.webp_path))?;
    if !is_webp(&raw) { return Err(format!("{} is not WebP", s.webp_path)); }
    let wb = uniquify(&raw, name, publisher)?;
    let hash = b64sha(&wb);
    let webp_name = format!("{}.webp", hash.replace('/', "-"));
    let webp_stage = Path::new(&c.stage_dir).join(format!("out_{webp_name}"));
    fs::write(&webp_stage, &wb).map_err(e)?;
    let opus = if let Some(op) = &s.opus_path {
        let ob = fs::read(op).map_err(|err| format!("read {op}: {err}"))?;
        if !is_ogg(&ob) { return Err(format!("{op} is not Ogg/Opus")); }
        let sha = sha256(&ob);
        let name = format!("{}.opus", b64(&sha).replace('/', "-"));
        let st = Path::new(&c.stage_dir).join(format!("out_{name}"));
        fs::write(&st, &ob).map_err(e)?;
        Some((name, st, sha, ob.len() as u64))
    } else { None };
    Ok(Prepared { hash, webp_name, webp_stage, webp_len: wb.len() as u64, opus })
}

/// Reference count across every table that can point at a sticker hash.
fn hash_refs(tx: &Connection, h: &str) -> i64 {
    tx.query_row(
        "SELECT (SELECT COUNT(*) FROM stickers WHERE plain_file_hash=?1)+\
                (SELECT COUNT(*) FROM starred_stickers WHERE plaintext_hash=?1)+\
                (SELECT COUNT(*) FROM recent_stickers WHERE plaintext_hash=?1)",
        params![h], |r| r.get(0)).unwrap_or(1)
}

// ---------------------------------------------------------------- pack inject

pub fn inject_pack(req: &str) -> Value {
    match inject_pack_inner(req) {
        Ok(v) => v,
        Err(err) => json!({"ok": false, "error": err}),
    }
}

fn inject_pack_inner(req: &str) -> Result<Value, String> {
    let r: PackReq = serde_json::from_str(req).map_err(e)?;
    if !valid_pkg(&r.c.package) { return Err("bad package".into()); }
    if r.stickers.is_empty() { return Err("pack has no stickers".into()); }
    root_ok()?;
    let p = Paths::new(&r.c.package);
    let owner = wa_owner(&p)?;
    ensure_dirs(&p, owner)?;
    fs::create_dir_all(&r.c.stage_dir).map_err(e)?;
    let tray = fs::read(&r.tray_path).map_err(e)?;
    let prepared: Vec<Prepared> = r.stickers.iter()
        .map(|s| prepare(&r.c, s, &r.name, &r.publisher))
        .collect::<Result<_, _>>()?;

    stage_db(&r.c, &p, true)?;
    let mut conn = open_staged(&r.c)?;
    check_schema(&conn, prepared.iter().any(|x| x.opus.is_some()))?;

    let mut concat: Vec<u8> = Vec::new();
    let mut total = tray.len() as u64;
    let mut animated = false;
    for pr in &prepared {
        let wb = fs::read(&pr.webp_stage).map_err(e)?;
        if wb.windows(4).any(|w| w == b"ANIM") { animated = true; }
        concat.extend(&wb);
        total += pr.webp_len + pr.opus.as_ref().map(|o| o.3).unwrap_or(0);
    }
    let imgdata = b64(hex(&sha256(&concat)).as_bytes());

    let tx = conn.transaction().map_err(e)?;
    let mut removed_webp: Vec<String> = Vec::new();
    let mut removed_audio: Vec<String> = Vec::new();
    if r.replace {
        {
            let mut st = tx.prepare("SELECT plain_file_hash FROM stickers WHERE sticker_pack_id=?1").map_err(e)?;
            let rows = st.query_map(params![r.pack_id], |row| row.get::<_, String>(0)).map_err(e)?;
            for row in rows { removed_webp.push(row.map_err(e)?); }
        }
        for h in &removed_webp {
            if let Ok(fp) = tx.query_row("SELECT file_path FROM sticker_audio WHERE plaintext_hash=?1",
                params![h], |row| row.get::<_, String>(0)) {
                removed_audio.push(fp);
            }
            tx.execute("DELETE FROM sticker_audio WHERE plaintext_hash=?1", params![h]).map_err(e)?;
        }
        tx.execute("DELETE FROM stickers WHERE sticker_pack_id=?1", params![r.pack_id]).map_err(e)?;
    }
    let base: i64 = if r.replace { 0 } else {
        tx.query_row("SELECT COALESCE(MAX(order_in_pack),-1)+1 FROM stickers WHERE sticker_pack_id=?1",
            params![r.pack_id], |row| row.get(0)).unwrap_or(0)
    };
    tx.execute(
        "INSERT OR REPLACE INTO installed_sticker_packs \
         (installed_id,installed_name,installed_publisher,installed_description,installed_size,\
          installed_image_data_hash,installed_tray_image_id,installed_tray_image_preview_id,\
          installed_animated_pack,installed_is_avatar_pack,installed_lottie_pack,installed_pack_type,\
          is_created_by_me,installed_premium_pack) \
         VALUES (?1,?2,?3,'',?4,?5,?6,'',?7,0,0,'user_created',1,0)",
        params![r.pack_id, r.name, r.publisher, total as i64, imgdata,
                format!("{}/{}.png", p.stk, r.pack_id), animated as i32]).map_err(e)?;
    for (i, pr) in prepared.iter().enumerate() {
        tx.execute(
            "INSERT OR REPLACE INTO stickers \
             (plain_file_hash,mime_type,height,width,sticker_pack_id,file_path,file_size,\
              hash_of_image_part,is_avatar,is_lottie,order_in_pack,premium,emojis) \
             VALUES (?1,'image/webp',512,512,?2,?3,?4,?1,0,0,?5,0,?6)",
            params![pr.hash, r.pack_id, format!("{}/{}", p.stk, pr.webp_name),
                    pr.webp_len as i64, base + i as i64, r.stickers[i].emoji]).map_err(e)?;
        if let Some((name, _, sha, len)) = &pr.opus {
            tx.execute(
                "INSERT OR REPLACE INTO sticker_audio \
                 (plaintext_hash,file_sha256,file_path,mimetype,duration_seconds,file_length,waveform) \
                 VALUES (?1,?2,?3,'audio/ogg; codecs=opus',?4,?5,NULL)",
                params![pr.hash, &sha[..], name, r.stickers[i].duration.max(1) as i64, *len as i64]).map_err(e)?;
        }
    }
    let mut files_to_rm: Vec<String> = Vec::new();
    for h in &removed_webp {
        if hash_refs(&tx, h) == 0 {
            files_to_rm.push(format!("{}/{}.webp", p.stk, h.replace('/', "-")));
        }
    }
    for fp in &removed_audio {
        let refs: i64 = tx.query_row("SELECT COUNT(*) FROM sticker_audio WHERE file_path=?1",
            params![fp], |row| row.get(0)).unwrap_or(1);
        if refs == 0 { files_to_rm.push(format!("{}/{}", p.aud, fp)); }
    }
    tx.commit().map_err(e)?;
    let _: i32 = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |row| row.get(0)).unwrap_or(0);
    drop(conn);

    let tray_stage = Path::new(&r.c.stage_dir).join("tray.png");
    fs::write(&tray_stage, &tray).map_err(e)?;
    plant(&tray_stage, &format!("{}/{}.png", p.stk, r.pack_id), owner)?;
    for pr in &prepared {
        plant(&pr.webp_stage, &format!("{}/{}", p.stk, pr.webp_name), owner)?;
        if let Some((name, st, _, _)) = &pr.opus {
            plant(st, &format!("{}/{}", p.aud, name), owner)?;
        }
    }
    write_back(&r.c, &p, owner, true)?;
    if !files_to_rm.is_empty() {
        let joined = files_to_rm.iter().map(|f| q(f)).collect::<Vec<_>>().join(" ");
        let _ = su(&format!("rm -f {joined}"), 15);
    }
    cleanup_stage(&r.c);
    relaunch(&r.c);
    Ok(json!({"ok": true, "pack_id": r.pack_id, "count": prepared.len()}))
}

// ------------------------------------------------------------ favorite inject

pub fn inject_favorite(req: &str) -> Value {
    match inject_favorite_inner(req) {
        Ok(v) => v,
        Err(err) => json!({"ok": false, "error": err}),
    }
}

fn inject_favorite_inner(req: &str) -> Result<Value, String> {
    let r: FavReq = serde_json::from_str(req).map_err(e)?;
    if !valid_pkg(&r.c.package) { return Err("bad package".into()); }
    root_ok()?;
    let p = Paths::new(&r.c.package);
    let owner = wa_owner(&p)?;
    ensure_dirs(&p, owner)?;
    fs::create_dir_all(&r.c.stage_dir).map_err(e)?;
    // Always uniquify: a favorite must never steal the pack member (PK plain_file_hash).
    let s = StickerIn {
        webp_path: r.webp_path.clone(), opus_path: r.opus_path.clone(),
        emoji: r.emoji.clone(), duration: r.duration,
    };
    let pr = prepare(&r.c, &s, "", "")?;

    stage_db(&r.c, &p, true)?;
    let mut conn = open_staged(&r.c)?;
    check_schema(&conn, pr.opus.is_some())?;
    let tx = conn.transaction().map_err(e)?;
    tx.execute(
        "INSERT OR REPLACE INTO stickers \
         (plain_file_hash,mime_type,height,width,file_path,file_size,hash_of_image_part,\
          is_avatar,is_lottie,order_in_pack,premium,emojis) \
         VALUES (?1,'image/webp',512,512,?2,?3,?1,0,0,-1,0,?4)",
        params![pr.hash, format!("{}/{}", p.stk, pr.webp_name), pr.webp_len as i64, r.emoji]).map_err(e)?;
    tx.execute(
        "INSERT OR REPLACE INTO starred_stickers \
         (plaintext_hash,timestamp,hash_of_image_part,mimetype,file_size,width,height,\
          is_first_party,is_avatar,is_lottie,premium) \
         VALUES (?1,?2,?1,'image/webp',?3,512,512,0,0,0,0)",
        params![pr.hash, now_ms() as i64, pr.webp_len as i64]).map_err(e)?;
    if let Some((name, _, sha, len)) = &pr.opus {
        tx.execute(
            "INSERT OR REPLACE INTO sticker_audio \
             (plaintext_hash,file_sha256,file_path,mimetype,duration_seconds,file_length,waveform) \
             VALUES (?1,?2,?3,'audio/ogg; codecs=opus',?4,?5,NULL)",
            params![pr.hash, &sha[..], name, r.duration.max(1) as i64, *len as i64]).map_err(e)?;
    }
    tx.commit().map_err(e)?;
    let _: i32 = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |row| row.get(0)).unwrap_or(0);
    drop(conn);

    plant(&pr.webp_stage, &format!("{}/{}", p.stk, pr.webp_name), owner)?;
    if let Some((name, st, _, _)) = &pr.opus {
        plant(st, &format!("{}/{}", p.aud, name), owner)?;
    }
    write_back(&r.c, &p, owner, true)?;
    cleanup_stage(&r.c);
    relaunch(&r.c);
    Ok(json!({"ok": true, "hash": pr.hash}))
}

// ---------------------------------------------------------------- pack remove

pub fn remove_pack(req: &str) -> Value {
    match remove_pack_inner(req) {
        Ok(v) => v,
        Err(err) => json!({"ok": false, "error": err}),
    }
}

fn remove_pack_inner(req: &str) -> Result<Value, String> {
    let r: RemoveReq = serde_json::from_str(req).map_err(e)?;
    if !valid_pkg(&r.c.package) { return Err("bad package".into()); }
    root_ok()?;
    let p = Paths::new(&r.c.package);
    let owner = wa_owner(&p)?;
    stage_db(&r.c, &p, true)?;
    let mut conn = open_staged(&r.c)?;
    check_schema(&conn, false)?;
    let tx = conn.transaction().map_err(e)?;
    let mut hashes: Vec<String> = Vec::new();
    {
        let mut st = tx.prepare("SELECT plain_file_hash FROM stickers WHERE sticker_pack_id=?1").map_err(e)?;
        let rows = st.query_map(params![r.pack_id], |row| row.get::<_, String>(0)).map_err(e)?;
        for row in rows { hashes.push(row.map_err(e)?); }
    }
    let mut audio_files: Vec<String> = Vec::new();
    let mut files_to_rm: Vec<String> = Vec::new();
    let audio_table = has_table(&tx, "sticker_audio");
    for h in &hashes {
        if audio_table {
            if let Ok(fp) = tx.query_row("SELECT file_path FROM sticker_audio WHERE plaintext_hash=?1",
                params![h], |row| row.get::<_, String>(0)) {
                audio_files.push(fp);
            }
            tx.execute("DELETE FROM sticker_audio WHERE plaintext_hash=?1", params![h]).map_err(e)?;
        }
    }
    tx.execute("DELETE FROM stickers WHERE sticker_pack_id=?1", params![r.pack_id]).map_err(e)?;
    tx.execute("DELETE FROM installed_sticker_packs WHERE installed_id=?1", params![r.pack_id]).map_err(e)?;
    if has_table(&tx, "sticker_pack_order") {
        let mut cols: Vec<String> = Vec::new();
        if let Ok(mut st) = tx.prepare("PRAGMA table_info(sticker_pack_order)") {
            if let Ok(rows) = st.query_map([], |row| row.get::<_, String>(1)) {
                for c in rows.flatten() { cols.push(c); }
            }
        }
        if let Some(col) = cols.iter().find(|c| c.contains("pack") || c.contains("id")) {
            let _ = tx.execute(&format!("DELETE FROM sticker_pack_order WHERE \"{col}\"=?1"), params![r.pack_id]);
        }
    }
    for h in &hashes {
        if hash_refs(&tx, h) == 0 {
            files_to_rm.push(format!("{}/{}.webp", p.stk, h.replace('/', "-")));
        }
    }
    for fp in &audio_files {
        let refs: i64 = tx.query_row("SELECT COUNT(*) FROM sticker_audio WHERE file_path=?1",
            params![fp], |row| row.get(0)).unwrap_or(1);
        if refs == 0 { files_to_rm.push(format!("{}/{}", p.aud, fp)); }
    }
    files_to_rm.push(format!("{}/{}.png", p.stk, r.pack_id));
    tx.commit().map_err(e)?;
    let _: i32 = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |row| row.get(0)).unwrap_or(0);
    drop(conn);
    write_back(&r.c, &p, owner, true)?;
    let joined = files_to_rm.iter().map(|f| q(f)).collect::<Vec<_>>().join(" ");
    let _ = su(&format!("rm -f {joined}"), 15);
    cleanup_stage(&r.c);
    relaunch(&r.c);
    Ok(json!({"ok": true}))
}

// ------------------------------------------------------------------ env check

pub fn env(req: &str) -> Value {
    match env_inner(req) {
        Ok(v) => v,
        Err(err) => json!({"ok": false, "error": err}),
    }
}

fn env_inner(req: &str) -> Result<Value, String> {
    let c: Common = serde_json::from_str(req).map_err(e)?;
    if !valid_pkg(&c.package) { return Err("bad package".into()); }
    root_ok()?;
    let p = Paths::new(&c.package);
    stage_db(&c, &p, false)?; // read-only: do not force-stop
    let conn = open_staged(&c)?;
    check_schema(&conn, false)?;
    let sound = has_table(&conn, "sticker_audio");
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM stickers", [], |r| r.get(0)).unwrap_or(0);
    drop(conn);
    cleanup_stage(&c);
    Ok(json!({"ok": true, "sound_stickers": sound, "sticker_count": count}))
}
