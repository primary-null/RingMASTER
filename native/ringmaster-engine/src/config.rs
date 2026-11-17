//! `engine.conf` (key=value lines) and the file writes with fixed modes.

use std::fs::OpenOptions;
use std::io::Write;
use std::net::IpAddr;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

pub const CONFIG_FILE: &str = "engine.conf";
pub const DEFAULT_PORT: u16 = 8696;

/// Who may connect. `lan` (the default): loopback, link-local, private (RFC 1918, RFC 4193) and tailnet (100.64/10,
/// fd7a:115c:a1e0::/48) addresses, so a guest with a public address does not answer the world. `any`, or a list of
/// networks (`192.0.2.0/24,2001:db8::/32`).
#[derive(Debug, Clone, PartialEq)]
pub enum Allow {
    Lan,
    Any,
    Networks(Vec<(IpAddr, u8)>),
}

#[derive(Debug, Clone)]
pub struct Config {
    pub port: u16,
    pub allow: Allow,
}

impl Default for Config {
    fn default() -> Self {
        Config { port: DEFAULT_PORT, allow: Allow::Lan }
    }
}

pub fn read(dir: &Path) -> Result<Config, String> {
    let path = dir.join(CONFIG_FILE);
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Config::default()),
        Err(_) => return Err(format!("Cannot read {}.", path.display())),
    };
    parse(&text)
}

pub fn parse(text: &str) -> Result<Config, String> {
    let mut config = Config::default();
    for (n, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (key, value) = line.split_once('=').ok_or(format!("Line {} of {CONFIG_FILE} is not key=value.", n + 1))?;
        match key.trim() {
            "port" => config.port = value.trim().parse().ok().filter(|p| *p > 0).ok_or("port must be 1 to 65535.")?,
            "allow" => config.allow = parse_allow(value.trim())?,
            other => return Err(format!("{CONFIG_FILE} has an unknown setting: {other}.")),
        }
    }
    Ok(config)
}

fn parse_allow(value: &str) -> Result<Allow, String> {
    match value {
        "lan" => return Ok(Allow::Lan),
        "any" => return Ok(Allow::Any),
        _ => {}
    }
    let mut networks = Vec::new();
    for part in value.split(',').map(str::trim).filter(|p| !p.is_empty()) {
        let (addr, bits) = part.split_once('/').unwrap_or((part, ""));
        let ip: IpAddr = addr.parse().map_err(|_| format!("allow: {part} is not a network."))?;
        let max = if ip.is_ipv4() { 32 } else { 128 };
        let bits = if bits.is_empty() { max } else { bits.parse().ok().filter(|b| *b <= max).ok_or(format!("allow: {part} has a bad prefix."))? };
        networks.push((ip, bits));
    }
    if networks.is_empty() {
        return Err("allow is empty.".into());
    }
    Ok(Allow::Networks(networks))
}

/// The caller's address with an IPv4-mapped IPv6 address read as IPv4.
pub fn plain(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(ip),
        v4 => v4,
    }
}

fn in_network(ip: IpAddr, net: IpAddr, bits: u8) -> bool {
    match (ip, net) {
        (IpAddr::V4(a), IpAddr::V4(n)) => {
            let mask = if bits == 0 { 0 } else { u32::MAX << (32 - bits as u32) };
            u32::from(a) & mask == u32::from(n) & mask
        }
        (IpAddr::V6(a), IpAddr::V6(n)) => {
            let mask = if bits == 0 { 0 } else { u128::MAX << (128 - bits as u32) };
            u128::from(a) & mask == u128::from(n) & mask
        }
        _ => false,
    }
}

pub fn allowed(allow: &Allow, ip: IpAddr) -> bool {
    let ip = plain(ip);
    match allow {
        Allow::Any => true,
        Allow::Networks(list) => list.iter().any(|(n, b)| in_network(ip, *n, *b)),
        Allow::Lan => {
            const LAN: &[(&str, u8)] = &[
                ("127.0.0.0", 8), ("10.0.0.0", 8), ("172.16.0.0", 12), ("192.168.0.0", 16), ("169.254.0.0", 16),
                ("100.64.0.0", 10), ("::1", 128), ("fc00::", 7), ("fe80::", 10),
            ];
            LAN.iter().any(|(n, b)| in_network(ip, n.parse().unwrap(), *b))
        }
    }
}

/// Writes a file only its owner can read (0600), replacing any old one.
pub fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    write_mode(path, bytes, 0o600)
}

pub fn write_public(path: &Path, bytes: &[u8]) -> Result<(), String> {
    write_mode(path, bytes, 0o644)
}

fn write_mode(path: &Path, bytes: &[u8], mode: u32) -> Result<(), String> {
    let temp = path.with_extension("new");
    let _ = std::fs::remove_file(&temp);
    let result = (|| {
        let mut f = OpenOptions::new().write(true).create_new(true).mode(mode).open(&temp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        std::fs::rename(&temp, path)
    })();
    result.map_err(|_| {
        let _ = std::fs::remove_file(&temp);
        format!("Cannot write {}.", path.display())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_default_answers_the_lan_and_the_tailnet_only() {
        let lan = Allow::Lan;
        for ok in ["10.1.2.3", "192.168.1.9", "172.31.0.1", "100.100.1.1", "fd7a:115c:a1e0::1", "fe80::1", "::ffff:192.168.0.5", "127.0.0.1"] {
            assert!(allowed(&lan, ok.parse().unwrap()), "{ok}");
        }
        for no in ["8.8.8.8", "172.32.0.1", "2001:db8::1", "::ffff:8.8.8.8"] {
            assert!(!allowed(&lan, no.parse().unwrap()), "{no}");
        }
    }

    #[test]
    fn a_network_list_narrows() {
        let c = parse("port=9000\nallow=192.0.2.0/24, 2001:db8::/32\n").unwrap();
        assert_eq!(c.port, 9000);
        assert!(allowed(&c.allow, "192.0.2.77".parse().unwrap()));
        assert!(!allowed(&c.allow, "192.0.3.1".parse().unwrap()));
        assert!(allowed(&c.allow, "2001:db8:1::5".parse().unwrap()));
        assert!(parse("allow=nonsense").is_err());
        assert!(parse("shell=/bin/sh").is_err());
    }
}
