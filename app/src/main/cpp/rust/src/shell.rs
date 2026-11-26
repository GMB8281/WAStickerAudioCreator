use std::io::Write;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// Single-quote for POSIX shell.
pub fn q(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Whether the root solution supports `su --mount-master` (Magisk does).
static MM_ENABLED: AtomicBool = AtomicBool::new(true);

fn spawn_su(mount_master: bool) -> Result<std::process::Child, String> {
    let candidates = ["su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"];
    let mut last = String::from("su not found");
    for bin in candidates {
        let mut c = Command::new(bin);
        if mount_master {
            c.arg("--mount-master");
        }
        c.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
        match c.spawn() {
            Ok(child) => return Ok(child),
            Err(e) => last = format!("{bin}: {e}"),
        }
    }
    Err(last)
}

fn run_su(mount_master: bool, cmd: &str, timeout_s: u64) -> Result<String, String> {
    let mut child = spawn_su(mount_master)?;
    if let Some(mut stdin) = child.stdin.take() {
        // Run the command, then exit the shell with the command's status.
        let script = format!("{cmd}\nexit $?\n");
        let _ = stdin.write_all(script.as_bytes());
        let _ = stdin.flush();
        // dropping stdin closes the pipe -> the shell sees EOF after `exit`.
    }
    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => {
                let out = child.wait_with_output().map_err(|e| e.to_string())?;
                if out.status.success() {
                    return Ok(String::from_utf8_lossy(&out.stdout).trim().to_string());
                }
                let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
                let so = String::from_utf8_lossy(&out.stdout).trim().to_string();
                return Err(if err.is_empty() { so } else { err });
            }
            Ok(None) => {
                if start.elapsed() > Duration::from_secs(timeout_s) {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err("su timed out (root prompt not granted?)".into());
                }
                std::thread::sleep(Duration::from_millis(40));
            }
            Err(e) => return Err(e.to_string()),
        }
    }
}

/// True if the error looks like the root solution rejecting the --mount-master flag.
fn is_flag_error(e: &str) -> bool {
    let l = e.to_lowercase();
    l.contains("mount-master")
        || l.contains("unknown option")
        || l.contains("invalid option")
        || l.contains("unrecognized option")
        || l.contains("usage: su")
}

/// Run a command as root.
///
/// Two Android/Magisk quirks are handled here:
///  1. Feed the command to the root shell's STDIN (not `su -c`), so the real `sh`
///     parses quoting.
///  2. Pass `--mount-master` so the shell runs in the GLOBAL mount namespace.
///     Android isolates each app's mount namespace so other apps' /data/data is
///     invisible (ENOENT) even to a root shell that inherits the app's namespace.
///     `su --mount-master` (== libsu FLAG_MOUNT_MASTER) fixes that.
pub fn su(cmd: &str, timeout_s: u64) -> Result<String, String> {
    if MM_ENABLED.load(Ordering::Relaxed) {
        match run_su(true, cmd, timeout_s) {
            Err(e) if is_flag_error(&e) => {
                // Root solution doesn't know --mount-master; fall back to plain su.
                MM_ENABLED.store(false, Ordering::Relaxed);
            }
            other => return other,
        }
    }
    run_su(false, cmd, timeout_s)
}

pub fn root_ok() -> Result<(), String> {
    let out = su("id", 120)?;
    if out.contains("uid=0") {
        Ok(())
    } else {
        Err(format!("not root: {out}"))
    }
}
