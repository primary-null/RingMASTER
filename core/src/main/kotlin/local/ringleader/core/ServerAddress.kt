package local.ringleader.core

import java.net.IDN

/*
 * One canonical form for the address of a Proxmox server.
 *
 * Nine places parse a server address by themselves today (serverOrigin, completeServerAddress, certificateOrigin, routeTarget,
 * ScanHit.origin, PveWebUi.base, spiceProxyHost, downloadUrlError and the Tools' validateHttpUrl) and they disagree about brackets,
 * case, scheme case, zones and ports, so one server can be several origins, several trust entries and several sessions.
 * ServerAddress.parse is the single parser. It is NOT used by any of them yet: adopting it changes the text that profiles, trust,
 * schedules, watches and pins are keyed by, which needs a migration (see the audit follow-up). Until then it is a type with tests.
 */

enum class AddressFamily { Ipv4, Ipv6, Name }

/** What was wrong with a typed server address. */
enum class ServerAddressProblem {
    Empty, NotHttps, UserInfoOrPath, Port, Host, Ipv4LeadingZero, Ipv4Malformed, Ipv6Malformed, Zone,
}

/** A typed server address that cannot be used. [message] is a plain sentence that says why. */
class ServerAddressException(val problem: ServerAddressProblem, message: String) : IllegalArgumentException(message)

/**
 * A server address in one canonical form.
 *
 *  - [host] is what a socket and a certificate check use: lower case, a name as ASCII (punycode), no trailing dot, an IPv4 address as
 *    four plain numbers, an IPv6 address as RFC 5952 text without brackets and without its zone.
 *  - [port] is explicit. [canonical] leaves `:443` out, because `https://host` and `https://host:443` are the same server.
 *  - [zone] is the interface of a link-local IPv6 address, decoded from `%25` or `%`. It names an interface of this computer only, so it
 *    is never part of [canonical] and must never be stored; use [socketHost] to connect.
 *  - [display] is `host:port` for screens and the connection tag (an IPv6 address in brackets, the port always shown).
 */
class ServerAddress private constructor(val host: String, val port: Int, val family: AddressFamily, val zone: String?) {
    /** The host as it goes into a URL: an IPv6 address in square brackets. */
    val urlHost: String get() = if (family == AddressFamily.Ipv6) "[$host]" else host

    /** `https://host`, with `:port` unless it is 443. The identity of the server in every stored key. */
    val canonical: String get() = "https://$urlHost" + if (port == 443) "" else ":$port"

    val display: String get() = "$urlHost:$port"

    /** The host to give a socket: the IPv6 address with `%zone` when there is a zone, otherwise [host]. */
    val socketHost: String get() = if (zone != null) "$host%$zone" else host

    /** True for fe80::/10. Such an address is meaningless without a zone, and a zone is meaningless on another computer. */
    val linkLocal: Boolean get() = family == AddressFamily.Ipv6 && IpLiteral.parseV6(host)?.let { (it[0].toInt() and 255) == 0xfe && (it[1].toInt() and 0xc0) == 0x80 } == true

    override fun equals(other: Any?) = other is ServerAddress && other.canonical == canonical
    override fun hashCode() = canonical.hashCode()
    override fun toString() = canonical

    companion object {
        /**
         * Reads what a person typed or pasted: `host`, `host:port`, `[v6]`, `[v6]:port`, a bare IPv6 address, or the same behind `https://`
         * (any letter case). [defaultPort] is the product's port (8006 for Proxmox VE, 8443 for Datacenter Manager).
         *
         *  - Only https. No login, path (a lone `/` is dropped), query or fragment.
         *  - A host with no port means [defaultPort]; with `https://` and no port it means 443, as the connection itself reads it.
         *    A colon with nothing after it means "no port" too, not 443. `:443` and no port are the same server. 0 and above 65535 are refused.
         *  - An IPv4 address is four numbers without leading zeros: `010` is 8 to some programs and 10 to others, so the same text could mean
         *    two computers, and it is refused with that reason.
         *  - An IPv6 address is read in every valid form and written as RFC 5952 (lower case, longest zero run as `::`). An IPv4-mapped one
         *    stays its own identity (`::ffff:192.0.2.10`), not the IPv4 address: the JDK connects over IPv4 for both, but a certificate, a
         *    pin or a trust entry recorded for one is not silently the other.
         *  - A zone is accepted after `%` or `%25` on a link-local address, as the interface number (and on other systems than Windows also
         *    its name), kept in [zone] and left out of [canonical].
         *  - A name is lower case, an international one is turned into ASCII, a trailing dot is dropped, an underscore is allowed.
         *    A name whose last part is a number is refused (it would be an IPv4 lookalike).
         */
        fun parse(text: String, defaultPort: Int, windows: Boolean = windowsHost()): ServerAddress {
            val typed = text.trim()
            if (typed.isEmpty()) throw refuse(ServerAddressProblem.Empty, "Enter the server address.")
            if (typed.any { it.isISOControl() || it.isWhitespace() }) throw refuse(ServerAddressProblem.Host, "A server address has no spaces or control characters.")
            var rest = typed
            var hadScheme = false
            val schemeEnd = typed.indexOf("://")
            if (schemeEnd >= 0) {
                val scheme = typed.substring(0, schemeEnd)
                if (!scheme.equals("https", ignoreCase = true)) throw refuse(ServerAddressProblem.NotHttps, "Use an https address. Proxmox VE and Datacenter Manager only speak https.")
                hadScheme = true
                rest = typed.substring(schemeEnd + 3)
            }
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
            val after = if (authorityEnd >= 0) rest.substring(authorityEnd) else ""
            if (after.isNotEmpty() && after != "/") throw refuse(ServerAddressProblem.UserInfoOrPath, USER_OR_PATH)
            if ('@' in authority) throw refuse(ServerAddressProblem.UserInfoOrPath, USER_OR_PATH)
            if (authority.isEmpty()) throw refuse(ServerAddressProblem.Empty, "Enter the server address.")

            // Split the authority into host text and port text. A bare IPv6 address (several colons, no brackets) has no port.
            val hostText: String
            var portText: String? = null
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) throw refuse(ServerAddressProblem.Ipv6Malformed, "An IPv6 address in square brackets needs the closing ].")
                hostText = authority.substring(1, close)
                if (hostText.isEmpty()) throw refuse(ServerAddressProblem.Ipv6Malformed, "That is not a valid IPv6 address: nothing between the square brackets.")
                val tail = authority.substring(close + 1)
                if (tail.isNotEmpty()) {
                    if (!tail.startsWith(":")) throw refuse(ServerAddressProblem.Host, "Nothing may follow the closing ] except :port.")
                    portText = tail.substring(1)
                }
            } else if (authority.count { it == ':' } > 1) {
                if (hadScheme) throw refuse(ServerAddressProblem.Ipv6Malformed, "Put an IPv6 address in square brackets when it comes with https://, like https://[2001:db8::10]:8006.")
                hostText = authority
            } else if (':' in authority) {
                hostText = authority.substringBefore(':')
                portText = authority.substringAfter(':')
            } else hostText = authority

            val port = when {
                portText == null || portText.isEmpty() -> if (hadScheme && portText == null) 443 else defaultPort
                else -> {
                    if (portText.length > 5 || !portText.all { it in '0'..'9' }) throw refuse(ServerAddressProblem.Port, PORT)
                    portText.toInt().also { if (it !in 1..65535) throw refuse(ServerAddressProblem.Port, PORT) }
                }
            }
            return withHost(hostText, port, windows)
        }

        /** [parse], or null when the text is not a usable server address. */
        fun parseOrNull(text: String, defaultPort: Int, windows: Boolean = windowsHost()): ServerAddress? =
            try { parse(text, defaultPort, windows) } catch (_: ServerAddressException) { null }

        private fun withHost(hostText: String, port: Int, windows: Boolean): ServerAddress {
            if (hostText.isEmpty()) throw refuse(ServerAddressProblem.Empty, "Enter the server address.")
            if (':' in hostText) return ipv6(hostText, port, windows)
            if (hostText.all { it in '0'..'9' || it == '.' } && '.' in hostText) return ipv4(hostText, port)
            return name(hostText, port)
        }

        private fun ipv4(text: String, port: Int): ServerAddress {
            IpLiteral.parseV4(text)?.let { return ServerAddress(IpLiteral.formatV4(it), port, AddressFamily.Ipv4, null) }
            val parts = text.split('.')
            val leadingZero = parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255 } && parts.any { it.length > 1 && it.startsWith("0") }
            if (leadingZero) {
                throw refuse(ServerAddressProblem.Ipv4LeadingZero,
                    "Write each number of an IPv4 address without a leading zero. Some programs read 010 as octal (8) and others as 10, so the same text could mean two different computers.")
            }
            throw refuse(ServerAddressProblem.Ipv4Malformed, "That is not a valid IPv4 address: it needs four numbers from 0 to 255 separated by dots.")
        }

        private fun ipv6(text: String, port: Int, windows: Boolean): ServerAddress {
            val percent = text.indexOf('%')
            val addressText = if (percent >= 0) text.substring(0, percent) else text
            val bytes = IpLiteral.parseV6(addressText) ?: throw refuse(ServerAddressProblem.Ipv6Malformed, "That is not a valid IPv6 address: up to eight groups of one to four hex digits, and \"::\" only once.")
            var zone: String? = null
            if (percent >= 0) {
                // RFC 6874 writes the % of a zone as %25 inside a URL.
                val raw = text.substring(percent + 1)
                val name = if (raw.startsWith("25") && raw.length > 2) raw.substring(2) else raw
                val linkLocal = (bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
                if (!linkLocal) throw refuse(ServerAddressProblem.Zone, "A zone (%...) can only follow a link-local address (fe80::...).")
                if (name.isEmpty()) throw refuse(ServerAddressProblem.Zone, "The zone after % is empty: give the interface number, like fe80::1%12.")
                val numeric = name.length <= 10 && name.all { it in '0'..'9' }
                if (!numeric) {
                    if (windows) throw refuse(ServerAddressProblem.Zone, "On Windows the zone is the interface number, like fe80::1%12.")
                    val ok = name.length <= 15 && name[0] != '-' && name.all { (it in '0'..'9') || (it in 'a'..'z') || (it in 'A'..'Z') || it == '_' || it == '.' || it == '-' }
                    if (!ok) throw refuse(ServerAddressProblem.Zone, "The zone after % must be an interface number or name.")
                }
                zone = name
            }
            return ServerAddress(IpLiteral.formatV6(bytes), port, AddressFamily.Ipv6, zone)
        }

        private fun name(text: String, port: Int): ServerAddress {
            var name = text.trimEnd('.')
            if (name.isEmpty()) throw refuse(ServerAddressProblem.Host, NAME)
            if (name.any { it.code > 127 }) {
                name = try { IDN.toASCII(name, IDN.ALLOW_UNASSIGNED) } catch (_: IllegalArgumentException) { throw refuse(ServerAddressProblem.Host, NAME) }
            }
            name = name.lowercase()
            if (name.length > 253) throw refuse(ServerAddressProblem.Host, "A host name is at most 253 characters, and each part between dots at most 63.")
            val labels = name.split('.')
            for (label in labels) {
                if (label.isEmpty()) throw refuse(ServerAddressProblem.Host, "A host name cannot have an empty part (two dots in a row, or a dot at the start).")
                if (label.length > 63) throw refuse(ServerAddressProblem.Host, "A host name is at most 253 characters, and each part between dots at most 63.")
                if (label.startsWith("-") || label.endsWith("-")) throw refuse(ServerAddressProblem.Host, "A part of a host name cannot start or end with a hyphen.")
                if (!label.all { (it in '0'..'9') || (it in 'a'..'z') || it == '-' || it == '_' }) throw refuse(ServerAddressProblem.Host, NAME)
            }
            if (labels.last().all { it in '0'..'9' }) throw refuse(ServerAddressProblem.Host, "A host name cannot end in a number: that would look like an IPv4 address.")
            return ServerAddress(name, port, AddressFamily.Name, null)
        }

        private fun refuse(problem: ServerAddressProblem, message: String) = ServerAddressException(problem, message)

        private fun windowsHost(): Boolean = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

        private const val USER_OR_PATH = "Enter only the server address, without a login, page path, or query."
        private const val PORT = "The server port must be a whole number from 1 to 65535."
        private const val NAME = "That is not a valid host name: use letters, digits, hyphens and underscores, in parts separated by dots."
    }
}
