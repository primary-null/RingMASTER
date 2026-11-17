//! Just enough HTTP/1.1: one request per connection, no body, a bounded head, `Connection: close` on every answer.

use std::io::{self, Read, Write};
use std::time::Instant;

pub const MAX_HEAD: usize = 8 * 1024;
const MAX_HEADERS: usize = 64;

#[derive(Debug, PartialEq)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    pub authorization: Option<String>,
}

/// Why a request was not read: an HTTP status to answer with, or nothing to answer (the connection failed).
#[derive(Debug, PartialEq)]
pub enum Refusal {
    Status(u16),
    Gone,
}

/// Reads one request head. [deadline] bounds the whole read, so a caller who sends a byte at a time is cut off.
pub fn read_request(r: &mut impl Read, deadline: Instant) -> Result<Request, Refusal> {
    let mut head = Vec::with_capacity(1024);
    let mut buf = [0u8; 1024];
    loop {
        if Instant::now() >= deadline {
            return Err(Refusal::Gone);
        }
        let n = match r.read(&mut buf) {
            Ok(0) => return Err(Refusal::Gone),
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => return Err(Refusal::Gone),
        };
        head.extend_from_slice(&buf[..n]);
        if let Some(end) = find(&head, b"\r\n\r\n") {
            if end + 4 != head.len() {
                return Err(Refusal::Status(400)); // bytes after the head: a body or a second request
            }
            return parse(&head[..end]);
        }
        if head.len() > MAX_HEAD {
            return Err(Refusal::Status(431));
        }
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

pub fn parse(head: &[u8]) -> Result<Request, Refusal> {
    let text = std::str::from_utf8(head).map_err(|_| Refusal::Status(400))?;
    let mut lines = text.split("\r\n");
    let line = lines.next().ok_or(Refusal::Status(400))?;
    let mut parts = line.split(' ');
    let (method, target, version) = match (parts.next(), parts.next(), parts.next(), parts.next()) {
        (Some(m), Some(t), Some(v), None) => (m, t, v),
        _ => return Err(Refusal::Status(400)),
    };
    if version != "HTTP/1.1" && version != "HTTP/1.0" {
        return Err(Refusal::Status(505));
    }
    if !target.starts_with('/') || target.len() > 256 || !target.bytes().all(|b| b.is_ascii_graphic()) {
        return Err(Refusal::Status(400));
    }
    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p, Some(q.to_string())),
        None => (target, None),
    };
    let mut authorization = None;
    let mut count = 0;
    for header in lines {
        count += 1;
        if count > MAX_HEADERS {
            return Err(Refusal::Status(431));
        }
        let (name, value) = header.split_once(':').ok_or(Refusal::Status(400))?;
        if name.is_empty() || name.bytes().any(|b| !b.is_ascii_graphic()) {
            return Err(Refusal::Status(400));
        }
        let value = value.trim();
        match name.to_ascii_lowercase().as_str() {
            "authorization" => {
                if authorization.replace(value.to_string()).is_some() {
                    return Err(Refusal::Status(400));
                }
            }
            "content-length" if value != "0" => return Err(Refusal::Status(413)),
            "transfer-encoding" => return Err(Refusal::Status(501)),
            _ => {}
        }
    }
    Ok(Request { method: method.to_string(), path: path.to_string(), query, authorization })
}

pub fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        413 => "Content Too Large",
        429 => "Too Many Requests",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        505 => "HTTP Version Not Supported",
        _ => "Error",
    }
}

pub fn write_response(w: &mut impl Write, status: u16, extra: &[(&str, String)], body: &str) -> io::Result<()> {
    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n",
        reason(status),
        body.len()
    );
    for (name, value) in extra {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str("\r\n");
    w.write_all(head.as_bytes())?;
    w.write_all(body.as_bytes())?;
    w.flush()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Result<Request, Refusal> {
        parse(s.as_bytes())
    }

    #[test]
    fn a_plain_get_is_read_with_its_token_and_query() {
        let r = p("GET /v1/whoami?x=1 HTTP/1.1\r\nHost: a\r\nAuthorization: Bearer T").unwrap();
        assert_eq!(r.method, "GET");
        assert_eq!(r.path, "/v1/whoami");
        assert_eq!(r.query.as_deref(), Some("x=1"));
        assert_eq!(r.authorization.as_deref(), Some("Bearer T"));
    }

    #[test]
    fn bodies_doubled_tokens_and_odd_targets_are_refused() {
        assert_eq!(p("POST /v1/scan/wifi HTTP/1.1\r\nContent-Length: 5"), Err(Refusal::Status(413)));
        assert_eq!(p("POST /v1/scan/wifi HTTP/1.1\r\nTransfer-Encoding: chunked"), Err(Refusal::Status(501)));
        assert_eq!(p("GET /v1 HTTP/1.1\r\nAuthorization: a\r\nauthorization: b"), Err(Refusal::Status(400)));
        assert_eq!(p("GET http://x/v1 HTTP/1.1"), Err(Refusal::Status(400)));
        assert_eq!(p("GET /v1 HTTP/2"), Err(Refusal::Status(505)));
        assert!(p("POST /v1/scan/wifi HTTP/1.1\r\nContent-Length: 0").is_ok());
    }

    #[test]
    fn a_head_with_trailing_bytes_or_over_the_limit_is_refused() {
        let far = Instant::now() + std::time::Duration::from_secs(5);
        let mut extra: &[u8] = b"GET /v1/ping HTTP/1.1\r\n\r\nGET";
        assert_eq!(read_request(&mut extra, far), Err(Refusal::Status(400)));
        let big = format!("GET /v1/ping HTTP/1.1\r\nX: {}\r\n", "a".repeat(MAX_HEAD));
        let mut big: &[u8] = big.as_bytes();
        assert_eq!(read_request(&mut big, far), Err(Refusal::Status(431)));
    }
}
