//! The few JSON objects the Engine writes. Strings are escaped per RFC 8259; nothing is ever parsed.

pub fn string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c == '\u{7f}' || c == '\u{2028}' || c == '\u{2029}' => {
                out.push_str(&format!("\\u{:04x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// An object from ready-made values: `("name", string("x"))`, `("n", 5.to_string())`.
pub fn object(fields: &[(&str, String)]) -> String {
    let body: Vec<String> = fields.iter().map(|(k, v)| format!("{}:{}", string(k), v)).collect();
    format!("{{{}}}", body.join(","))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn control_characters_and_quotes_are_escaped() {
        assert_eq!(string("a\"b\\c\nd\u{1}"), "\"a\\\"b\\\\c\\nd\\u0001\"");
        assert_eq!(object(&[("a", "1".into()), ("b", string("x"))]), "{\"a\":1,\"b\":\"x\"}");
    }
}
