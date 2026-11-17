//! Runs the fixed radio scripts and returns what they printed, unchanged and bounded.
//!
//! The scripts are the app's own (core/src/main/resources/radio), carried here at build time and joined exactly as the
//! app joins them (`RadioScripts`: prelude, a newline, the body), so the guest agent path and the Engine print the same
//! text and the app reads both with one parser. Only a mode from [Mode] and a number of seconds within [SCAN_SECONDS] are
//! ever passed to them, as `$1`; no text from a request reaches the shell.

use std::io::Read;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const PRELUDE: &str = include_str!("../../../core/src/main/resources/radio/prelude.sh");
const PROBE: &str = include_str!("../../../core/src/main/resources/radio/probe.sh");
const WIFI_SCAN: &str = include_str!("../../../core/src/main/resources/radio/wifi-scan.sh");
const BT_SCAN: &str = include_str!("../../../core/src/main/resources/radio/bt-scan.sh");

pub const OUTPUT_MAX: usize = 192 * 1024;
const ERRORS_MAX: usize = 4 * 1024;
pub const SCAN_SECONDS: std::ops::RangeInclusive<u32> = 4..=30;
pub const SCAN_DEFAULT_SECONDS: u32 = 10;
/// Time the script may take beyond the scan itself (starting tools, listing results).
const GRACE: Duration = Duration::from_secs(15);
const PATH: &str = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Mode {
    Probe,
    BluetoothScan,
    WifiScan,
}

impl Mode {
    pub fn parse(text: &str) -> Option<Mode> {
        match text {
            "probe" => Some(Mode::Probe),
            "bt-scan" => Some(Mode::BluetoothScan),
            "wifi-scan" => Some(Mode::WifiScan),
            _ => None,
        }
    }

    pub fn arg(self) -> &'static str {
        match self {
            Mode::Probe => "probe",
            Mode::BluetoothScan => "bt-scan",
            Mode::WifiScan => "wifi-scan",
        }
    }
}

pub struct Run {
    pub exit: Option<i32>,
    pub output: Vec<u8>,
    pub truncated: bool,
    pub timed_out: bool,
    pub millis: u128,
}

impl Mode {
    /// The script as the app builds it: prelude, newline, body. CRLF is read as LF, as the app does.
    pub fn script(self) -> String {
        let body = match self {
            Mode::Probe => PROBE,
            Mode::BluetoothScan => BT_SCAN,
            Mode::WifiScan => WIFI_SCAN,
        };
        format!("{PRELUDE}\n{body}").replace("\r\n", "\n")
    }
}

/// SHA-256 of the probe script as run, so the app can tell this Engine carries the same probe as itself.
pub fn script_sha256() -> String {
    crate::cert::hex(ring::digest::digest(&ring::digest::SHA256, Mode::Probe.script().as_bytes()).as_ref())
}

/// Runs the script in [mode]. A scan's seconds are clamped to [SCAN_SECONDS]; the probe gets the grace time only.
pub fn run(mode: Mode, seconds: u32) -> Run {
    let seconds = if mode == Mode::Probe { 0 } else { seconds.clamp(*SCAN_SECONDS.start(), *SCAN_SECONDS.end()) };
    let limit = Duration::from_secs(seconds as u64) + GRACE;
    let started = Instant::now();
    let child = Command::new("/bin/sh")
        .arg("-c")
        .arg(mode.script())
        .arg("ringmaster-radio")
        .arg(seconds.to_string())
        .env_clear()
        .env("PATH", PATH)
        .env("LC_ALL", "C")
        .env("LANG", "C")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .spawn();
    let mut child = match child {
        Ok(c) => c,
        Err(_) => return Run { exit: None, output: b"ringmaster-engine: the probe could not start\n".to_vec(), truncated: false, timed_out: false, millis: 0 },
    };
    let pid = child.id() as libc::pid_t;
    let out = collect(child.stdout.take(), OUTPUT_MAX);
    let err = collect(child.stderr.take(), ERRORS_MAX);
    let mut timed_out = false;
    loop {
        // WNOWAIT leaves the exited script unreaped, so its pid (the group's id) cannot be reused before the kill below.
        let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
        let r = unsafe { libc::waitid(libc::P_PID, pid as libc::id_t, &mut info, libc::WEXITED | libc::WNOHANG | libc::WNOWAIT) };
        if r != 0 || unsafe { info.si_pid() } != 0 {
            break;
        }
        if started.elapsed() >= limit {
            timed_out = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    // The whole group goes, so a tool the script left behind (a scan still running) cannot outlive the request.
    unsafe { libc::kill(-pid, libc::SIGKILL) };
    let status = child.wait().ok();
    let (output, truncated) = out.finish();
    drop(err.finish());
    Run { exit: status.and_then(|s| s.code()), output, truncated, timed_out, millis: started.elapsed().as_millis() }
}

struct Collector {
    data: Arc<Mutex<(Vec<u8>, bool)>>,
    done: std::sync::mpsc::Receiver<()>,
}

impl Collector {
    /// What was read; waits a moment for the reader, never for ever (a stray process may hold the pipe).
    fn finish(self) -> (Vec<u8>, bool) {
        let _ = self.done.recv_timeout(Duration::from_secs(2));
        let guard = self.data.lock().unwrap_or_else(|e| e.into_inner());
        (guard.0.clone(), guard.1)
    }
}

fn collect(pipe: Option<impl Read + Send + 'static>, max: usize) -> Collector {
    let data = Arc::new(Mutex::new((Vec::new(), false)));
    let (tx, done) = std::sync::mpsc::channel();
    let shared = data.clone();
    std::thread::spawn(move || {
        if let Some(mut pipe) = pipe {
            let mut buf = [0u8; 8192];
            while let Ok(n) = pipe.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let mut d = shared.lock().unwrap_or_else(|e| e.into_inner());
                let room = max.saturating_sub(d.0.len());
                if n > room {
                    d.1 = true;
                }
                let take = n.min(room);
                d.0.extend_from_slice(&buf[..take]);
            }
        }
        let _ = tx.send(());
    });
    Collector { data, done }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_three_modes_exist() {
        assert_eq!(Mode::parse("probe"), Some(Mode::Probe));
        assert_eq!(Mode::parse("wifi-scan").unwrap().arg(), "wifi-scan");
        assert_eq!(Mode::parse("rm -rf /"), None);
    }

    #[test]
    fn the_probe_runs_and_ends_with_its_last_line() {
        let r = run(Mode::Probe, 0);
        let text = String::from_utf8_lossy(&r.output);
        assert!(!r.timed_out);
        assert!(text.starts_with("RINGMASTER-RADIO 1 probe "), "{text}");
        assert!(text.lines().any(|l| l.ends_with("\tdone")), "{text}");
    }
}
