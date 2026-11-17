//! The HTTPS service: one request per connection, a thread per connection up to [MAX_CONNECTIONS].
//!
//! Endpoints (api 1):
//!   GET  /v1/ping                       no token; says this is an Engine and its api, nothing else
//!   GET  /v1/whoami                     version, host, kind of system, what it can see
//!   GET  /v1/radios                     the probe
//!   POST /v1/scan/bluetooth?seconds=N   a Bluetooth scan, one at a time
//!   POST /v1/scan/wifi?seconds=N        a Wi-Fi scan, one at a time
//! The token comes in `Authorization: Bearer ...`. A wrong one is answered after [WRONG_TOKEN_DELAY] and counted; a
//! caller with too many is answered 429 with `Retry-After` until its lockout ends, without the token being looked at.

use crate::auth::{self, Lockout};
use crate::config::{self, Allow};
use crate::http::{self, Refusal, Request};
use crate::json::{object, string};
use crate::probe::{self, Mode};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use std::io::{self, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_CONNECTIONS: usize = 16;
/// The whole exchange before the answer: handshake and request head.
const REQUEST_DEADLINE: Duration = Duration::from_secs(10);
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);
const WRONG_TOKEN_DELAY: Duration = Duration::from_secs(1);

struct Shared {
    tls: Arc<ServerConfig>,
    token_hex: String,
    lockout: Lockout,
    probe_lock: Mutex<()>,
    scan_lock: Mutex<()>,
    active: AtomicUsize,
    started_unix: u64,
    refused: AtomicUsize,
}

pub fn serve(dir: &Path, as_root: bool) -> i32 {
    if unsafe { libc::geteuid() } == 0 && !as_root {
        eprintln!("ringmaster-engine: refusing to run as root. The installer runs it as its own account; add --as-root only if you mean it.");
        return 1;
    }
    let shared = match load(dir) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("ringmaster-engine: {e}");
            return 1;
        }
    };
    let config = match config::read(dir) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("ringmaster-engine: {e}");
            return 1;
        }
    };
    let listeners = bind(config.port);
    if listeners.is_empty() {
        eprintln!("ringmaster-engine: cannot listen on port {}.", config.port);
        return 1;
    }
    eprintln!("ringmaster-engine {} (api {}) listening on port {}, allowing {}", crate::VERSION, crate::API, config.port, describe(&config.allow));
    let allow = Arc::new(config.allow);
    let mut threads = Vec::new();
    for listener in listeners {
        let shared = shared.clone();
        let allow = allow.clone();
        threads.push(std::thread::spawn(move || accept(listener, shared, allow)));
    }
    for t in threads {
        let _ = t.join();
    }
    1
}

fn describe(allow: &Allow) -> String {
    match allow {
        Allow::Lan => "local networks and tailnets".into(),
        Allow::Any => "any address".into(),
        Allow::Networks(n) => n.iter().map(|(a, b)| format!("{a}/{b}")).collect::<Vec<_>>().join(", "),
    }
}

fn load(dir: &Path) -> Result<Shared, String> {
    let cert = std::fs::read(dir.join(crate::cert::CERT_FILE)).map_err(|_| "Cannot read the certificate. Run init first.")?;
    let key = std::fs::read(dir.join(crate::cert::KEY_FILE)).map_err(|_| "Cannot read the key.")?;
    let token_hex = std::fs::read_to_string(dir.join(auth::TOKEN_FILE)).map_err(|_| "Cannot read the token.")?.trim().to_string();
    if token_hex.len() != 64 || !token_hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("The stored token is damaged. Run new-token.".into());
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|_| "TLS setup failed.")?
        .with_no_client_auth()
        .with_single_cert(vec![CertificateDer::from(cert)], PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)))
        .map_err(|_| "The key and certificate do not fit together.")?;
    let started_unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    Ok(Shared {
        tls: Arc::new(tls),
        token_hex: token_hex.to_ascii_lowercase(),
        lockout: Lockout::default(),
        probe_lock: Mutex::new(()),
        scan_lock: Mutex::new(()),
        active: AtomicUsize::new(0),
        started_unix,
        refused: AtomicUsize::new(0),
    })
}

/// Every address of this system: IPv6 (dual-stack where the system allows it) and IPv4. A changed DHCP address or a
/// tailnet that comes up later is answered without a restart; who may connect is decided per caller by `allow`.
fn bind(port: u16) -> Vec<TcpListener> {
    let mut out = Vec::new();
    if let Ok(l) = TcpListener::bind(SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port)) {
        out.push(l);
    }
    // Fails with "in use" when the IPv6 socket already takes IPv4 too, which is the usual case.
    if let Ok(l) = TcpListener::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port)) {
        out.push(l);
    }
    out
}

fn accept(listener: TcpListener, shared: Arc<Shared>, allow: Arc<Allow>) {
    let mut last_note = Instant::now();
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let Ok(peer) = stream.peer_addr() else { continue };
        let ip = config::plain(peer.ip());
        if !config::allowed(&allow, ip) {
            // Not logged one by one, so a scan of the network cannot fill the log.
            let n = shared.refused.fetch_add(1, Ordering::Relaxed) + 1;
            if last_note.elapsed() > Duration::from_secs(600) {
                eprintln!("ringmaster-engine: {n} connections from addresses outside the allowed networks were closed so far");
                last_note = Instant::now();
            }
            continue;
        }
        if shared.active.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
            shared.active.fetch_sub(1, Ordering::SeqCst);
            continue;
        }
        let shared = shared.clone();
        let spawned = std::thread::Builder::new().stack_size(256 * 1024).spawn({
            let shared = shared.clone();
            move || {
                handle(stream, ip, &shared);
                shared.active.fetch_sub(1, Ordering::SeqCst);
            }
        });
        if spawned.is_err() {
            shared.active.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

/// Reads through TLS with the socket's timeout set to what is left of [deadline] before each read.
struct Timed<'a> {
    conn: &'a mut ServerConnection,
    sock: &'a mut TcpStream,
    deadline: Instant,
}

impl io::Read for Timed<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let left = self.deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        self.sock.set_read_timeout(Some(left))?;
        rustls::Stream::new(self.conn, self.sock).read(buf)
    }
}

fn handle(stream: TcpStream, ip: IpAddr, shared: &Shared) {
    let started = Instant::now();
    let _ = stream.set_write_timeout(Some(WRITE_TIMEOUT));
    let _ = stream.set_nodelay(true);
    let Ok(conn) = ServerConnection::new(shared.tls.clone()) else { return };
    let mut tls = StreamOwned::new(conn, stream);
    let read = {
        let mut timed = Timed { conn: &mut tls.conn, sock: &mut tls.sock, deadline: started + REQUEST_DEADLINE };
        http::read_request(&mut timed, started + REQUEST_DEADLINE)
    };
    // A failed handshake (plain http on this port, a client that does not trust the certificate) ends here unanswered.
    let request = match read {
        Ok(r) => r,
        Err(Refusal::Gone) => return,
        Err(Refusal::Status(code)) => {
            answer(&mut tls, code, &[], &error_body("request"));
            log(ip, "-", "-", code, started);
            return;
        }
    };
    let (status, extra, body) = route(&request, ip, shared);
    let _ = tls.sock.set_write_timeout(Some(WRITE_TIMEOUT));
    answer(&mut tls, status, &extra, &body);
    tls.conn.send_close_notify();
    let _ = tls.flush();
    log(ip, &request.method, &request.path, status, started);
}

/// One line per request: caller, method, route and status. Never a header, a query or the token.
fn log(ip: IpAddr, method: &str, path: &str, status: u16, started: Instant) {
    let route = if KNOWN.iter().any(|(_, p)| *p == path) { path } else { "(other)" };
    let method = if method == "GET" || method == "POST" || method == "-" { method } else { "(other)" };
    eprintln!("ringmaster-engine: {ip} {method} {route} {status} {}ms", started.elapsed().as_millis());
}

const KNOWN: &[(&str, &str)] = &[
    ("GET", "/v1/ping"),
    ("GET", "/v1/whoami"),
    ("GET", "/v1/radios"),
    ("POST", "/v1/scan/bluetooth"),
    ("POST", "/v1/scan/wifi"),
];

type Answer = (u16, Vec<(&'static str, String)>, String);

fn route(r: &Request, ip: IpAddr, shared: &Shared) -> Answer {
    let Some((method, path)) = KNOWN.iter().find(|(_, p)| *p == r.path) else {
        return (404, vec![], error_body("not-found"));
    };
    if *method != r.method {
        return (405, vec![("Allow", method.to_string())], error_body("method"));
    }
    let scan = path.starts_with("/v1/scan/");
    let seconds = match (scan, r.query.as_deref()) {
        (_, None) => probe::SCAN_DEFAULT_SECONDS,
        (true, Some(q)) => match q.strip_prefix("seconds=").and_then(|n| n.parse::<u32>().ok()) {
            Some(n) if probe::SCAN_SECONDS.contains(&n) => n,
            _ => return (400, vec![], error_body("seconds")),
        },
        (false, Some(_)) => return (400, vec![], error_body("query")),
    };
    if *path == "/v1/ping" {
        return (200, vec![], object(&[("product", string("ringmaster-engine")), ("api", crate::API.to_string())]));
    }
    if let Some(left) = shared.lockout.locked(ip, Instant::now()) {
        return (429, vec![("Retry-After", left.to_string())], error_body("locked"));
    }
    if !auth::matches(r.authorization.as_deref(), &shared.token_hex) {
        std::thread::sleep(WRONG_TOKEN_DELAY);
        if shared.lockout.failed(ip, Instant::now()) {
            eprintln!("ringmaster-engine: {ip} sent a wrong token {} times and is locked out for {} minutes", auth::FAILURES_ALLOWED, auth::LOCKOUT.as_secs() / 60);
            return (429, vec![("Retry-After", auth::LOCKOUT.as_secs().to_string())], error_body("locked"));
        }
        return (401, vec![], error_body("token"));
    }
    shared.lockout.succeeded(ip);
    match *path {
        "/v1/whoami" => (200, vec![], crate::facts::whoami(shared.started_unix)),
        "/v1/radios" => run(&shared.probe_lock, Mode::Probe, 0),
        "/v1/scan/bluetooth" => run(&shared.scan_lock, Mode::BluetoothScan, seconds),
        "/v1/scan/wifi" => run(&shared.scan_lock, Mode::WifiScan, seconds),
        _ => (404, vec![], error_body("not-found")),
    }
}

/// One run at a time per lock: a second caller is told it is busy, never queued.
fn run(lock: &Mutex<()>, mode: Mode, seconds: u32) -> Answer {
    let Ok(_held) = lock.try_lock() else {
        return (409, vec![("Retry-After", "5".to_string())], error_body("busy"));
    };
    let run = probe::run(mode, seconds);
    let body = object(&[
        ("api", crate::API.to_string()),
        ("mode", string(mode.arg())),
        ("seconds", seconds.to_string()),
        ("exit", run.exit.map(|c| c.to_string()).unwrap_or_else(|| "null".into())),
        ("timed_out", run.timed_out.to_string()),
        ("truncated", run.truncated.to_string()),
        ("ms", run.millis.to_string()),
        ("probe_sha256", string(&probe::script_sha256())),
        ("output", string(&String::from_utf8_lossy(&run.output))),
    ]);
    (200, vec![], body)
}

fn answer(tls: &mut impl Write, status: u16, extra: &[(&str, String)], body: &str) {
    let _ = http::write_response(tls, status, extra, body);
}

fn error_body(code: &str) -> String {
    object(&[("error", string(code))])
}

/// Exit 0 when something accepts a connection on the configured port of this system.
pub fn status(dir: &Path) -> i32 {
    let port = config::read(dir).map(|c| c.port).unwrap_or(config::DEFAULT_PORT);
    for ip in [IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)] {
        if TcpStream::connect_timeout(&SocketAddr::new(ip, port), Duration::from_secs(2)).is_ok() {
            println!("listening on port {port}");
            return 0;
        }
    }
    println!("not listening on port {port}");
    1
}
