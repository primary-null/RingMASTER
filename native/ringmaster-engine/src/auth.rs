//! The pairing token and the lockout of callers who keep sending a wrong one.
//!
//! The token is 25 characters of Crockford base32 (125 random bits), shown once at install in five groups of five. Only
//! its SHA-256 is stored: the token is random, so a slow hash adds nothing. A presented token is compared by digest in
//! constant time.

use ring::digest::{SHA256, digest};
use ring::rand::{SecureRandom, SystemRandom};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const TOKEN_FILE: &str = "token.sha256";
const LENGTH: usize = 25;
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

pub fn new_token() -> Result<String, String> {
    let mut raw = [0u8; LENGTH];
    SystemRandom::new().fill(&mut raw).map_err(|_| "No random numbers.".to_string())?;
    let chars: Vec<char> = raw.iter().map(|b| ALPHABET[(b & 31) as usize] as char).collect();
    Ok(chars.chunks(5).map(|c| c.iter().collect::<String>()).collect::<Vec<_>>().join("-"))
}

/// The token as stored and compared: upper case, without dashes or spaces, with the letters people mistake for digits
/// read as those digits (O as 0, I and L as 1). None when it cannot be a token.
pub fn canonical(text: &str) -> Option<String> {
    if text.len() > 64 {
        return None;
    }
    let mut out = String::with_capacity(LENGTH);
    for c in text.chars() {
        let c = match c.to_ascii_uppercase() {
            '-' | ' ' => continue,
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        };
        if !c.is_ascii() || !ALPHABET.contains(&(c as u8)) {
            return None;
        }
        out.push(c);
    }
    (out.len() == LENGTH).then_some(out)
}

pub fn digest_hex(canonical: &str) -> String {
    crate::cert::hex(digest(&SHA256, canonical.as_bytes()).as_ref())
}

/// Constant time over the whole length.
pub fn same(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Checks a presented `Authorization` value against the stored digest (hex).
pub fn matches(header: Option<&str>, stored_hex: &str) -> bool {
    let presented = header.and_then(|h| h.strip_prefix("Bearer ")).and_then(canonical);
    let presented_hex = digest_hex(presented.as_deref().unwrap_or("-"));
    same(presented_hex.as_bytes(), stored_hex.as_bytes()) && presented.is_some()
}

pub const FAILURES_ALLOWED: usize = 5;
pub const FAILURE_WINDOW: Duration = Duration::from_secs(10 * 60);
pub const LOCKOUT: Duration = Duration::from_secs(15 * 60);
const MAX_CALLERS: usize = 4096;

#[derive(Default)]
struct Caller {
    failures: Vec<Instant>,
    locked_until: Option<Instant>,
}

/// Wrong-token failures by caller. An IPv6 caller counts by its /64, since one host can hold a whole /64.
#[derive(Default)]
pub struct Lockout {
    callers: Mutex<HashMap<IpAddr, Caller>>,
}

fn caller_key(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V4(_) => ip,
        IpAddr::V6(v6) => {
            let mut s = v6.segments();
            s[4..].fill(0);
            IpAddr::V6(s.into())
        }
    }
}

impl Lockout {
    /// Seconds left of a lockout, or None.
    pub fn locked(&self, ip: IpAddr, now: Instant) -> Option<u64> {
        let callers = self.callers.lock().unwrap_or_else(|e| e.into_inner());
        let until = callers.get(&caller_key(ip))?.locked_until?;
        (until > now).then(|| (until - now).as_secs().max(1))
    }

    /// Counts a failure; true when the caller is now locked out.
    pub fn failed(&self, ip: IpAddr, now: Instant) -> bool {
        let mut callers = self.callers.lock().unwrap_or_else(|e| e.into_inner());
        if callers.len() >= MAX_CALLERS && !callers.contains_key(&caller_key(ip)) {
            callers.retain(|_, c| c.locked_until.is_some_and(|u| u > now));
            if callers.len() >= MAX_CALLERS {
                callers.clear();
            }
        }
        let caller = callers.entry(caller_key(ip)).or_default();
        caller.failures.retain(|t| now.duration_since(*t) < FAILURE_WINDOW);
        caller.failures.push(now);
        if caller.failures.len() >= FAILURES_ALLOWED {
            caller.failures.clear();
            caller.locked_until = Some(now + LOCKOUT);
            return true;
        }
        false
    }

    pub fn succeeded(&self, ip: IpAddr) {
        self.callers.lock().unwrap_or_else(|e| e.into_inner()).remove(&caller_key(ip));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_token_is_five_groups_and_canonical() {
        let t = new_token().unwrap();
        assert_eq!(t.len(), 29);
        assert_eq!(t.matches('-').count(), 4);
        assert_eq!(canonical(&t).unwrap().len(), 25);
    }

    #[test]
    fn look_alike_letters_are_read_as_digits() {
        assert_eq!(canonical("abcde-fghjk-mnpqr-stvwx-yzoil").unwrap(), "ABCDEFGHJKMNPQRSTVWXYZ011");
        assert!(canonical("ABCDE-FGHJK-MNPQR-STVWX-YZ01U").is_none());
        assert!(canonical("ABCD").is_none());
    }

    #[test]
    fn only_the_right_bearer_matches() {
        let stored = digest_hex("ABCDEFGHJKMNPQRSTVWXYZ011");
        assert!(matches(Some("Bearer abcde-fghjk-mnpqr-stvwx-yzoil"), &stored));
        assert!(!matches(Some("Bearer ABCDE-FGHJK-MNPQR-STVWX-YZ012"), &stored));
        assert!(!matches(Some("ABCDEFGHJKMNPQRSTVWXYZ011"), &stored));
        assert!(!matches(None, &stored));
    }

    #[test]
    fn five_failures_lock_the_caller_and_its_neighbours_in_the_same_64() {
        let l = Lockout::default();
        let now = Instant::now();
        let a: IpAddr = "fd00::1".parse().unwrap();
        let b: IpAddr = "fd00::2".parse().unwrap();
        for i in 0..4 {
            assert!(!l.failed(a, now + Duration::from_secs(i)));
        }
        assert!(l.failed(b, now + Duration::from_secs(5)));
        assert!(l.locked(a, now + Duration::from_secs(6)).is_some());
        assert!(l.locked(a, now + LOCKOUT + Duration::from_secs(6)).is_none());
        assert!(l.locked("fd00:0:0:1::1".parse().unwrap(), now).is_none());
    }
}
