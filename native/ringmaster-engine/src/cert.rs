//! The Engine's own self-signed certificate: ECDSA P-256 with SHA-256, written as DER by hand.
//!
//! The app pins the exact certificate by its SHA-256 at pairing and checks nothing else but the dates, so the
//! certificate carries no host name or address (anyone who connects can read it) and its dates are fixed and wide:
//! from 2020-01-01 to 9999-12-31, the RFC 5280 value for "no end". A guest whose clock is wrong still pairs.

use ring::digest::{SHA256, digest};
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
use std::path::Path;

pub const CERT_FILE: &str = "cert.der";
pub const KEY_FILE: &str = "key.pk8";

const OID_ECDSA_SHA256: &[u8] = &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02];
const OID_EC_PUBLIC_KEY: &[u8] = &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01];
const OID_P256: &[u8] = &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07];
const OID_COMMON_NAME: &[u8] = &[0x55, 0x04, 0x03];
const OID_BASIC_CONSTRAINTS: &[u8] = &[0x55, 0x1D, 0x13];

/// One DER element: tag, definite length, content.
fn der(tag: u8, content: &[u8]) -> Vec<u8> {
    let mut out = vec![tag];
    let n = content.len();
    if n < 0x80 {
        out.push(n as u8);
    } else {
        let bytes: Vec<u8> = n.to_be_bytes().iter().copied().skip_while(|b| *b == 0).collect();
        out.push(0x80 | bytes.len() as u8);
        out.extend(bytes);
    }
    out.extend_from_slice(content);
    out
}

fn seq(parts: &[&[u8]]) -> Vec<u8> {
    der(0x30, &parts.concat())
}

/// A new key pair and its certificate: (certificate DER, PKCS#8 key DER).
pub fn generate() -> Result<(Vec<u8>, Vec<u8>), String> {
    let rng = SystemRandom::new();
    let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).map_err(|_| "Could not create a key.".to_string())?;
    let key = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng)
        .map_err(|_| "Could not read the new key.".to_string())?;

    let mut serial = [0u8; 16];
    rng.fill(&mut serial).map_err(|_| "No random numbers.".to_string())?;
    serial[0] = (serial[0] & 0x3f) | 0x40; // positive and of full length

    let algorithm = seq(&[&der(0x06, OID_ECDSA_SHA256)]);
    let name = seq(&[&der(0x31, &seq(&[&der(0x06, OID_COMMON_NAME), &der(0x0C, b"RingMASTER Engine")]))]);
    let validity = seq(&[&der(0x17, b"200101000000Z"), &der(0x18, b"99991231235959Z")]);
    let point = [&[0u8][..], key.public_key().as_ref()].concat();
    let spki = seq(&[&seq(&[&der(0x06, OID_EC_PUBLIC_KEY), &der(0x06, OID_P256)]), &der(0x03, &point)]);
    // basicConstraints, critical, CA false: this certificate can never sign another.
    let basic = seq(&[&der(0x06, OID_BASIC_CONSTRAINTS), &der(0x01, &[0xFF]), &der(0x04, &seq(&[]))]);
    let extensions = der(0xA3, &seq(&[&basic]));
    let tbs = seq(&[&der(0xA0, &der(0x02, &[2])), &der(0x02, &serial), &algorithm, &name, &validity, &name, &spki, &extensions]);
    let signature = key.sign(&rng, &tbs).map_err(|_| "Could not sign the certificate.".to_string())?;
    let bits = [&[0u8][..], signature.as_ref()].concat();
    let certificate = seq(&[&tbs, &algorithm, &der(0x03, &bits)]);
    Ok((certificate, pkcs8.as_ref().to_vec()))
}

/// The certificate's SHA-256 as the app shows and pins it: 64 lower-case hex digits.
pub fn fingerprint(certificate: &[u8]) -> String {
    hex(digest(&SHA256, certificate).as_ref())
}

pub fn fingerprint_of(path: &Path) -> Result<String, String> {
    std::fs::read(path).map(|c| fingerprint(&c)).map_err(|_| format!("Cannot read {}.", path.display()))
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_lengths_use_the_long_form() {
        assert_eq!(der(0x04, &[0u8; 5])[..2], [0x04, 5]);
        assert_eq!(der(0x04, &[0u8; 200])[..3], [0x04, 0x81, 200]);
        assert_eq!(der(0x04, &[0u8; 300])[..4], [0x04, 0x82, 0x01, 0x2C]);
    }

    #[test]
    fn a_certificate_is_one_sequence_with_a_stable_fingerprint() {
        let (c, k) = generate().unwrap();
        assert_eq!(c[0], 0x30);
        assert!(!k.is_empty());
        assert_eq!(fingerprint(&c).len(), 64);
        assert_eq!(fingerprint(&c), fingerprint(&c));
    }
}
