package local.ringleader.core.integrations

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant

/** Where an address lives, as far as sending a credential over plain HTTP is concerned. */
enum class AddressScope {
    /** 127.0.0.0/8, ::1 */
    Loopback,
    /** 169.254.0.0/16, fe80::/10 */
    LinkLocal,
    /** RFC 1918: 10/8, 172.16/12, 192.168/16 */
    Private,
    /** RFC 4193 fc00::/7, except the tailnet block below */
    UniqueLocal,
    /** 100.64.0.0/10 (RFC 6598, the Tailscale range) and fd7a:115c:a1e0::/48 (Tailscale's IPv6 block). WireGuard encrypts it. */
    Tailnet,
    /** Anything else that can be connected to. */
    Public,
    /** Unspecified, multicast, broadcast and reserved addresses: never connected to. */
    Unusable,
}

/** Classifies [address] without any lookup. An IPv4-mapped IPv6 address counts as its IPv4 address. */
fun addressScope(address: InetAddress): AddressScope = when (address) {
    is Inet4Address -> ipv4Scope(address.address)
    is Inet6Address -> ipv6Scope(address.address)
    else -> AddressScope.Unusable
}

private fun ipv4Scope(b: ByteArray): AddressScope {
    val a0 = b[0].toInt() and 0xff; val a1 = b[1].toInt() and 0xff
    return when {
        a0 == 0 -> AddressScope.Unusable
        a0 == 127 -> AddressScope.Loopback
        a0 == 169 && a1 == 254 -> AddressScope.LinkLocal
        a0 == 10 -> AddressScope.Private
        a0 == 172 && a1 in 16..31 -> AddressScope.Private
        a0 == 192 && a1 == 168 -> AddressScope.Private
        a0 == 100 && a1 in 64..127 -> AddressScope.Tailnet
        a0 >= 224 -> AddressScope.Unusable // multicast, reserved, broadcast
        else -> AddressScope.Public
    }
}

private fun ipv6Scope(b: ByteArray): AddressScope {
    val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
    if (mapped) return ipv4Scope(b.copyOfRange(12, 16))
    val first = b[0].toInt() and 0xff; val second = b[1].toInt() and 0xff
    return when {
        b.all { it.toInt() == 0 } -> AddressScope.Unusable
        (0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1 -> AddressScope.Loopback
        first == 0xfe && (second and 0xc0) == 0x80 -> AddressScope.LinkLocal
        first == 0xfd && second == 0x7a && (b[2].toInt() and 0xff) == 0x11 && (b[3].toInt() and 0xff) == 0x5c &&
            (b[4].toInt() and 0xff) == 0xa1 && (b[5].toInt() and 0xff) == 0xe0 -> AddressScope.Tailnet
        (first and 0xfe) == 0xfc -> AddressScope.UniqueLocal
        first == 0xff -> AddressScope.Unusable
        else -> AddressScope.Public
    }
}

/**
 * The owner's explicit "I understand, use http on this network" for one link. Bound to the exact endpoint (scheme, host,
 * port) that was shown: a changed address needs a new acknowledgement. [credential] and [power] are the words the dialog
 * showed ("Pi-hole app password", what it can change), kept so the record says what was accepted.
 */
data class PlainHttpAck(val endpoint: String, val acknowledgedAt: Instant, val credential: String, val power: String) {
    fun covers(origin: ServiceOrigin) = endpoint == "${origin.scheme}://${origin.authority}"
    companion object {
        fun forOrigin(origin: ServiceOrigin, at: Instant, credential: String, power: String) =
            PlainHttpAck("${origin.scheme}://${origin.authority}", at, credential, power)
    }
}

/** How the standing chip on a plain-HTTP link reads. */
enum class UnencryptedMark { Unencrypted, InsideTailnet }

sealed interface PlainHttpDecision {
    data class Allowed(val mark: UnencryptedMark) : PlainHttpDecision
    /** A credential would cross a private network in plain text and the owner has not said yes for this endpoint. */
    data object NeedsAcknowledgement : PlainHttpDecision
    data class Refused(val reason: String) : PlainHttpDecision
}

/**
 * THE plain-HTTP rule (owner decision 2026-11-16, design §3.2). The one place it is decided; [ServiceWire] asks it on every
 * connect with the address it is about to connect to, so a name that now resolves somewhere else is judged again.
 *
 * - No credential: allowed anywhere it can connect, marked "Unencrypted".
 * - A credential: only to a loopback, link-local, private (RFC 1918), unique-local or tailnet address, and only with an
 *   acknowledgement that covers this exact endpoint. Refused for every other address.
 * - Unusable addresses (unspecified, multicast, broadcast, reserved): refused, credential or not.
 */
object PlainHttpRule {
    const val PUBLIC_REFUSED = "RingMASTER does not send a password or key over unencrypted http to an address on the internet."
    const val UNUSABLE = "That address cannot be connected to."

    fun decide(scope: AddressScope, carriesCredential: Boolean, ack: PlainHttpAck?, origin: ServiceOrigin): PlainHttpDecision {
        if (scope == AddressScope.Unusable) return PlainHttpDecision.Refused(UNUSABLE)
        val mark = if (scope == AddressScope.Tailnet) UnencryptedMark.InsideTailnet else UnencryptedMark.Unencrypted
        if (!carriesCredential) return PlainHttpDecision.Allowed(mark)
        if (scope == AddressScope.Public) return PlainHttpDecision.Refused(PUBLIC_REFUSED)
        return if (ack != null && ack.covers(origin)) PlainHttpDecision.Allowed(mark) else PlainHttpDecision.NeedsAcknowledgement
    }
}
