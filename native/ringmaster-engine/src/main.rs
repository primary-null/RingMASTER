//! ringmaster-engine: the RingMASTER Engine, a read-only radio reporter for a Linux guest or a node.
//!
//! It answers the RingMASTER app over HTTPS with a self-signed certificate the app pins at pairing, and a pairing token
//! the app sends in a header. It has no shell and runs no text it is sent: each endpoint runs one fixed probe script,
//! carried in the binary at build time, and returns its output unchanged. A scan runs only when asked, one at a time,
//! with a time limit. It changes nothing on the system and never reads a saved Wi-Fi password.
//!
//! Usage:
//!   ringmaster-engine init --dir DIR [--force]   create the key, certificate and token (prints the token once)
//!   ringmaster-engine new-token --dir DIR        replace the token (prints the new one once)
//!   ringmaster-engine fingerprint --dir DIR      print the certificate's SHA-256
//!   ringmaster-engine serve --dir DIR [--as-root]
//!   ringmaster-engine status --dir DIR           exit 0 when the service answers on its port
//!   ringmaster-engine probe [MODE [SECONDS]]     run the probe here and print its output
//!   ringmaster-engine version

mod auth;
mod cert;
mod config;
mod facts;
mod http;
mod json;
mod probe;
mod server;

use std::path::{Path, PathBuf};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
/// The protocol version. A change the app cannot read raises it; the app refuses an Engine whose api it does not know.
pub const API: u32 = 1;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("init") => with_dir(&args, |dir| init(dir, args.iter().any(|a| a == "--force"))),
        Some("new-token") => with_dir(&args, new_token),
        Some("fingerprint") => with_dir(&args, |dir| match cert::fingerprint_of(&dir.join(cert::CERT_FILE)) {
            Ok(fp) => { println!("{fp}"); 0 }
            Err(e) => fail(&e),
        }),
        Some("serve") => with_dir(&args, |dir| server::serve(dir, args.iter().any(|a| a == "--as-root"))),
        Some("status") => with_dir(&args, server::status),
        Some("probe") => {
            let mode = args.get(1).map(String::as_str).unwrap_or("probe");
            let seconds = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(probe::SCAN_DEFAULT_SECONDS);
            match probe::Mode::parse(mode) {
                Some(m) => {
                    let run = probe::run(m, seconds);
                    print!("{}", String::from_utf8_lossy(&run.output));
                    if run.timed_out { 124 } else { run.exit.unwrap_or(1) }
                }
                None => fail("Modes: probe, bt-scan, wifi-scan."),
            }
        }
        Some("version") | Some("--version") => {
            println!("ringmaster-engine {VERSION} api {API} probe {}", probe::script_sha256());
            0
        }
        _ => {
            eprintln!("Usage: ringmaster-engine init|new-token|fingerprint|serve|status --dir DIR, probe [MODE [SECONDS]], version");
            2
        }
    };
    std::process::exit(code);
}

fn with_dir(args: &[String], f: impl FnOnce(&Path) -> i32) -> i32 {
    match args.iter().position(|a| a == "--dir").and_then(|i| args.get(i + 1)) {
        Some(d) => f(&PathBuf::from(d)),
        None => fail("--dir is required."),
    }
}

fn fail(message: &str) -> i32 {
    eprintln!("ringmaster-engine: {message}");
    1
}

/// Creates the key, the certificate and the token in [dir]. Refuses to replace an existing key without --force, so a
/// second install never breaks a pairing by accident.
fn init(dir: &Path, force: bool) -> i32 {
    let key = dir.join(cert::KEY_FILE);
    if key.exists() && !force {
        return fail("A key already exists here. Use --force to replace it; the app must then be paired again.");
    }
    let (certificate, private_key) = match cert::generate() {
        Ok(pair) => pair,
        Err(e) => return fail(&e),
    };
    if let Err(e) = config::write_private(&key, &private_key)
        .and_then(|_| config::write_public(&dir.join(cert::CERT_FILE), &certificate))
    {
        return fail(&e);
    }
    println!("FINGERPRINT={}", cert::fingerprint(&certificate));
    new_token(dir)
}

fn new_token(dir: &Path) -> i32 {
    let token = match auth::new_token() {
        Ok(t) => t,
        Err(e) => return fail(&e),
    };
    let canonical = auth::canonical(&token).expect("a new token is canonical");
    let digest = auth::digest_hex(&canonical);
    if let Err(e) = config::write_private(&dir.join(auth::TOKEN_FILE), format!("{digest}\n").as_bytes()) {
        return fail(&e);
    }
    println!("TOKEN={token}");
    0
}
