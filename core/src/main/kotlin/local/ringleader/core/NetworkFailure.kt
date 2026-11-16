package local.ringleader.core

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * The pinned trust manager's refusal of a leaf that is not the pinned one. [observed] is that leaf's fingerprint and
 * [observedSpki] its key (null when the server sent no certificate). The message stays the one it always was.
 */
class PinMismatchException(val observed: String?, val observedSpki: String?) : CertificateException("Certificate fingerprint changed")

/**
 * The pinned leaf matched but is outside its validity on this computer's clock: expired ([expired]) or not yet valid.
 * The cause is the JDK's own [CertificateExpiredException] or [CertificateNotYetValidException].
 */
class CertificateDateException(val notBefore: Instant, val notAfter: Instant, val now: Instant, val expired: Boolean, cause: CertificateException) :
    CertificateException(if (expired) "Certificate expired" else "Certificate not yet valid", cause) {
    companion object {
        fun of(leaf: X509Certificate, cause: CertificateException, now: Instant = Instant.now()) =
            CertificateDateException(leaf.notBefore.toInstant(), leaf.notAfter.toInstant(), now, cause !is CertificateNotYetValidException && now.isAfter(leaf.notAfter.toInstant()), cause)
    }
}

/**
 * Why a connection failed, in the user's terms: twelve causes, each with its own short sentence ([sentence]) and its own
 * next step ([nextStep]). Mapped by [networkFailure] from exception classes and cause chains, never from messages, so
 * the wording here is the only text the user sees. No internals, no stack text, no address.
 */
sealed interface NetworkFailure {
    val sentence: String
    val nextStep: String
    /** About the server's certificate: retrying does not help, and the Desk offers the certificate step. */
    val certificate: Boolean get() = false

    data object Untrusted : NetworkFailure {
        override val sentence = "This computer does not trust the server's certificate (self-signed or private CA)."
        override val nextStep = "A Proxmox server has its own private certificate unless a public one was installed, and normal TLS cannot verify that " +
            "unless this computer trusts the server's CA. Return to the Desk, check the server certificate, compare the fingerprint and use Trust and connect."
        override val certificate get() = true
    }

    data class Changed(val observed: String? = null, val observedSpki: String? = null) : NetworkFailure {
        override val sentence = "This server now shows a different certificate than the one you approved."
        override val nextStep = "Nothing was sent to it. Return to the Desk and check the server certificate: it shows the approved and the new " +
            "fingerprint and whether the server kept its key. Accept the new one only if you know why it changed."
        override val certificate get() = true
    }

    data class RenewedSameKey(val observed: String? = null) : NetworkFailure {
        override val sentence = "The server renewed its certificate with the same key."
        override val nextStep = "Proxmox does this when it renews its own certificate. Nothing was sent to it. Return to the Desk, " +
            "compare the new fingerprint with the server's, and accept the renewed certificate there."
        override val certificate get() = true
    }

    /** [notBefore] and [notAfter] are null when the JDK refused the dates of a chain it validated and did not say them. */
    data class CertificateDates(val expired: Boolean, val now: Instant, val notBefore: Instant? = null, val notAfter: Instant? = null) : NetworkFailure {
        override val sentence = when {
            expired && notAfter != null -> "The server's certificate expired on ${certificateDay(notAfter)} (this computer's clock says ${clockReading(now)})."
            !expired && notBefore != null -> "The server's certificate is valid only from ${certificateDay(notBefore)} (this computer's clock says ${clockReading(now)})."
            expired -> "The server's certificate has expired (this computer's clock says ${clockReading(now)})."
            else -> "The server's certificate is not valid yet (this computer's clock says ${clockReading(now)})."
        }
        override val nextStep = "Renew the certificate on the server (pvecm updatecerts --force), or correct this computer's clock if it is wrong. " +
            "An out-of-date certificate is never accepted here."
        override val certificate get() = true
    }

    data object NameMismatch : NetworkFailure {
        override val sentence = "The server's certificate is not issued for this address."
        override val nextStep = "Connect with a name or address the certificate lists, or check the server certificate on the Desk and trust that exact certificate."
        override val certificate get() = true
    }

    data object PlainHttp : NetworkFailure {
        override val sentence = "The server answered without TLS on this port."
        override val nextStep = "Check the address and port: Proxmox VE uses https on port 8006, Datacenter Manager on 8443."
    }

    data object OldTls : NetworkFailure {
        override val sentence = "The server and this app have no TLS version in common."
        override val nextStep = "RingMASTER needs TLS 1.2 or newer. Update the server, or its TLS settings."
    }

    data object Refused : NetworkFailure {
        override val sentence = "Nothing is listening on that port: the connection was refused."
        override val nextStep = "Check the port, and that the web service runs on the server (systemctl status pveproxy)."
    }

    data object TimedOut : NetworkFailure {
        override val sentence = "The server did not answer in time."
        override val nextStep = "Check that this computer can reach it: VPN, firewall, the server being on. Then try again."
    }

    data object NameNotFound : NetworkFailure {
        override val sentence = "The server's name could not be found."
        override val nextStep = "Check the spelling, or use the address. On a VPN, check that its name service is in use."
    }

    data object NoRoute : NetworkFailure {
        override val sentence = "This computer has no route to the server."
        override val nextStep = "Check the network connection or the VPN, then try again."
    }

    /**
     * The secure session never started: the other end (or something between) closed or reset the connection during the
     * TLS handshake. No certificate was judged, so this must not read as a certificate problem.
     */
    data object ClosedDuringHandshake : NetworkFailure {
        override val sentence = "The connection was closed while the secure session was starting."
        override val nextStep = "A firewall, a TLS-inspecting proxy or another service on that port can do this. Check the address and port, then try again."
    }

    companion object {
        /** Every cause, for tables and pictures. */
        fun examples(now: Instant): List<NetworkFailure> = listOf(
            Untrusted, Changed(), RenewedSameKey(),
            CertificateDates(true, now, Instant.parse("2024-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            NameMismatch, PlainHttp, OldTls, Refused, TimedOut, NameNotFound, NoRoute, ClosedDuringHandshake,
        )
    }
}

private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(16).toList()

/**
 * Map [error] to its [NetworkFailure], or null when it is none of the named causes (the caller then keeps its general sentence).
 * [knownSpki] is the key recorded with the pin this connection used: a changed leaf with that key is a renewal.
 * [now] is this computer's clock, for a date refusal the JDK reported without dates.
 */
fun networkFailure(error: Throwable, knownSpki: String? = null, now: Instant = Instant.now()): NetworkFailure? {
    val chain = causes(error)
    chain.firstNotNullOfOrNull { it as? PinMismatchException }?.let {
        return if (knownSpki != null && it.observedSpki == knownSpki) NetworkFailure.RenewedSameKey(it.observed) else NetworkFailure.Changed(it.observed, it.observedSpki)
    }
    chain.firstNotNullOfOrNull { it as? CertificateDateException }?.let { return NetworkFailure.CertificateDates(it.expired, it.now, it.notBefore, it.notAfter) }
    if (chain.any { it is CertificateExpiredException || (it is CertPathValidatorException && it.reason == CertPathValidatorException.BasicReason.EXPIRED) })
        return NetworkFailure.CertificateDates(true, now)
    if (chain.any { it is CertificateNotYetValidException || (it is CertPathValidatorException && it.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID) })
        return NetworkFailure.CertificateDates(false, now)
    val tls = chain.any { it is SSLException }
    if (tls && chainProblem(error)) return NetworkFailure.Untrusted
    // The JDK's host name check throws a bare CertificateException (no subclass, no cause); every chain refusal carries a cause.
    if (tls && chain.any { it.javaClass == CertificateException::class.java && it.cause == null }) return NetworkFailure.NameMismatch
    if (chain.any { it is SocketTimeoutException || it is HttpTimeoutException }) return NetworkFailure.TimedOut
    if (chain.any { it is UnknownHostException }) return NetworkFailure.NameNotFound
    if (chain.any { it is NoRouteToHostException }) return NetworkFailure.NoRoute
    if (chain.any { it is ConnectException }) return NetworkFailure.Refused
    // A TLS handshake cut off by an end of stream or a socket error, with no certificate anywhere in the chain: nothing was
    // judged, the connection just went away (a firewall, an inspecting proxy, another service on the port).
    if (tls && chain.none { it is CertificateException } && chain.any { it is EOFException || it is SocketException }) return NetworkFailure.ClosedDuringHandshake
    if (chain.any { it is CertificateException || it is EOFException || (it is SocketException) }) return null
    val ssl = chain.firstOrNull { it is SSLException } ?: return null
    // A record that is not TLS at all: the JDK raises a plain SSLException (not a handshake exception) with no cause.
    if (ssl.javaClass == SSLException::class.java && ssl.cause == null) return NetworkFailure.PlainHttp
    // A handshake the server or this side ended for want of a common protocol or cipher: a handshake exception without
    // a cause. A handshake cut off by the network carries an EOF or socket cause and was excluded above.
    if (ssl is SSLHandshakeException && ssl.cause == null) return NetworkFailure.OldTls
    return null
}

/** What a session shows when TLS refused the server's certificate for a reason none of the [NetworkFailure] causes names. */
const val CERTIFICATE_ERROR = "The server certificate is untrusted, expired, or does not match the supplied fingerprint."

/** The general sentence for an unreachable server, when no [NetworkFailure] cause names the reason. */
const val UNREACHABLE_ERROR = "The server could not be reached. Check the network and try again."

/** One sentence for [error]: its [NetworkFailure] sentence when it has one, else the general sentence for its kind. */
fun readableError(error: Exception, knownSpki: String? = null): String = networkFailure(error, knownSpki)?.sentence ?: when (error) {
    is SSLException -> CERTIFICATE_ERROR
    is java.io.IOException -> UNREACHABLE_ERROR
    is IllegalArgumentException -> "The server response or connection settings were invalid."
    is IllegalStateException -> error.message ?: "The request could not be completed."
    else -> "The request could not be completed. Try again."
}

private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val clockFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** A certificate date as a day on this computer's calendar. */
fun certificateDay(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayFormat.format(at.atZone(zone))

/** This computer's clock, to the minute. */
fun clockReading(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = clockFormat.format(at.atZone(zone))

/**
 * The reason a background run gives when the server could not be used. A changed or out-of-date certificate says so and
 * where to review it; anything else keeps the general sentence. Never more than the server's own facts.
 */
fun scheduleNotSentReason(error: Throwable): String = when (val failure = networkFailure(error)) {
    is NetworkFailure.Changed, is NetworkFailure.RenewedSameKey -> "The server's certificate changed since it was trusted; review it on the Desk. Nothing was sent."
    is NetworkFailure.CertificateDates -> "${failure.sentence} Nothing was sent."
    null -> "The server could not be reached securely. Nothing was sent."
    else -> "${failure.sentence} Nothing was sent."
}
