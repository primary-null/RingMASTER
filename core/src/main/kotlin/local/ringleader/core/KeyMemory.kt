package local.ringleader.core

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.security.auth.x500.X500Principal

/**
 * What a certificate check read from a server: the leaf's SHA-256 fingerprint (what a pin is, and what Proxmox shows), the
 * SHA-256 of its SubjectPublicKeyInfo (the server's key, which Proxmox keeps when it renews its own certificate), and the
 * facts a person compares before trusting it. Reading it is never trust.
 */
data class ProbedCertificate(
    /** Canonical: 64 lower-case hex digits. */
    val fingerprint: String,
    /** SHA-256 of the public key (SubjectPublicKeyInfo), 64 lower-case hex digits. */
    val spki: String,
    val subject: String,
    val issuer: String,
    val notBefore: Instant,
    val notAfter: Instant,
    /** Subject alternative names: DNS names and IP addresses, as the certificate lists them. */
    val names: List<String> = emptyList(),
) {
    fun validAt(now: Instant): Boolean = !now.isBefore(notBefore) && !now.isAfter(notAfter)

    companion object {
        fun of(certificate: X509Certificate): ProbedCertificate = ProbedCertificate(
            fingerprint = sha256Hex(certificate.encoded),
            spki = spkiSha256(certificate),
            subject = certificate.subjectX500Principal.getName(X500Principal.RFC1779),
            issuer = certificate.issuerX500Principal.getName(X500Principal.RFC1779),
            notBefore = certificate.notBefore.toInstant(),
            notAfter = certificate.notAfter.toInstant(),
            names = runCatching {
                certificate.subjectAlternativeNames.orEmpty().filter { (it[0] as? Int) == 2 || (it[0] as? Int) == 7 }.mapNotNull { it[1] as? String }
            }.getOrDefault(emptyList()).take(32),
        )
    }
}

/** The SHA-256 of [certificate]'s SubjectPublicKeyInfo, lower-case hex. */
fun spkiSha256(certificate: X509Certificate): String = sha256Hex(certificate.publicKey.encoded)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Keys of leaves that passed a pinned handshake in this run, by pin. The pinned trust manager records one each time the
 * leaf matched its pin, so a pin approved before keys were remembered (certificates.json version 1, or a scan approval,
 * which reads no key) gets its key filled in after the next successful connection. Recording a key is not trust: the leaf
 * already matched the pin. Memory only, bounded.
 */
private val pinnedKeys = ConcurrentHashMap<String, String>()

internal fun recordPinnedKey(pin: String, leaf: X509Certificate) {
    if (pinnedKeys.size >= 64 && !pinnedKeys.containsKey(pin)) pinnedKeys.clear()
    pinnedKeys[pin] = spkiSha256(leaf)
}

/** The key of the leaf last seen matching [pin] in this run, or null. */
fun pinnedKeyFor(pin: String): String? = pinnedKeys[pin]
