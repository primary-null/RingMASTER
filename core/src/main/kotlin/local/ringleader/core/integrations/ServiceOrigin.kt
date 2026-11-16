package local.ringleader.core.integrations

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Where a linked service answers: scheme, host, port and base path, canonicalised so two spellings of one place compare
 * equal. Never holds user-info, a query or a fragment. [host] is lower case; an IPv6 literal is stored without brackets.
 */
data class ServiceOrigin private constructor(val scheme: String, val host: String, val port: Int, val basePath: String) {
    val secure get() = scheme == "https"
    val ipv6Literal get() = ':' in host
    /** The host as it appears in a URL or a `Host` header: brackets around an IPv6 literal. */
    val hostLiteral get() = if (ipv6Literal) "[$host]" else host
    /** `Host` header value: the default port of the scheme is left out (RFC 9110 7.2). */
    val authority get() = if (port == defaultPort(scheme)) hostLiteral else "$hostLiteral:$port"
    /** `http://[fd00::5]:8080/base`, never with a trailing slash. Stable: links store it and acknowledgements bind to it. */
    val canonical get() = "$scheme://$authority$basePath"
    override fun toString() = canonical

    /** Same scheme, host and port: what a plain-HTTP acknowledgement and a redirect check compare. */
    fun sameEndpoint(other: ServiceOrigin) = scheme == other.scheme && host == other.host && port == other.port

    fun withBasePath(path: String): ServiceOrigin = ServiceOrigin(scheme, host, port, cleanBase(path) ?: throw IllegalArgumentException(BAD_PATH))

    companion object {
        const val BAD_ADDRESS = "Use an http or https address with a host, such as http://192.0.2.5 or https://pihole.lan:443."
        const val BAD_PATH = "The path may hold letters, digits and - _ . ~ / only."
        fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        /** Null with a sentence in [error] when [text] is not a usable service address. */
        fun parse(text: String): Result<ServiceOrigin> = runCatching { parseOrThrow(text) }

        fun parseOrThrow(text: String): ServiceOrigin {
            val trimmed = text.trim()
            require(trimmed.length in 1..200 && trimmed.none { it.isISOControl() || it.isWhitespace() }) { BAD_ADDRESS }
            val uri = try { URI(trimmed) } catch (_: Exception) { throw IllegalArgumentException(BAD_ADDRESS) }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { BAD_ADDRESS }
            require(uri.rawUserInfo == null && !trimmed.substringAfter("://").substringBefore('/').contains('@')) {
                "An address cannot include a user name or password."
            }
            require(uri.rawQuery == null && uri.rawFragment == null && '?' !in trimmed && '#' !in trimmed) { "An address cannot include a query or a fragment." }
            val rawHost = uri.host ?: throw IllegalArgumentException(BAD_ADDRESS)
            val host = canonicalHost(rawHost) ?: throw IllegalArgumentException(BAD_ADDRESS)
            val port = if (uri.port == -1) defaultPort(scheme) else uri.port
            require(port in 1..65535) { "The port is out of range." }
            val base = cleanBase(uri.rawPath.orEmpty()) ?: throw IllegalArgumentException(BAD_PATH)
            return ServiceOrigin(scheme, host, port, base)
        }

        fun of(scheme: String, host: String, port: Int, basePath: String = ""): ServiceOrigin {
            val s = scheme.lowercase()
            require(s == "http" || s == "https") { BAD_ADDRESS }
            val h = canonicalHost(host) ?: throw IllegalArgumentException(BAD_ADDRESS)
            require(port in 1..65535) { "The port is out of range." }
            return ServiceOrigin(s, h, port, cleanBase(basePath) ?: throw IllegalArgumentException(BAD_PATH))
        }

        private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Lower-case DNS name, dotted IPv4, or a compressed IPv6 literal without brackets. No lookup is made. */
        internal fun canonicalHost(raw: String): String? {
            val bare = raw.removePrefix("[").removeSuffix("]").lowercase()
            if (bare.isEmpty()) return null
            if (':' in bare) {
                if ('%' in bare) return null // zone ids are machine-local and never stored
                if (!bare.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) return null
                val parsed = runCatching { InetAddress.getByName(bare) }.getOrNull() ?: return null
                return when (parsed) {
                    is Inet6Address -> compressIpv6(parsed)
                    is Inet4Address -> parsed.hostAddress // ::ffff:a.b.c.d is an IPv4 address
                    else -> null
                }
            }
            if (IPV4.matches(bare)) {
                val parts = bare.split('.').map { it.toInt() }
                if (parts.any { it > 255 }) return null
                return parts.joinToString(".")
            }
            if (bare.all { it.isDigit() || it == '.' }) return null // 127.1 and friends are refused, not guessed
            val ascii = runCatching { IDN.toASCII(bare, IDN.USE_STD3_ASCII_RULES) }.getOrNull()?.lowercase()?.removeSuffix(".") ?: return null
            if (ascii.isEmpty() || ascii.length > 253) return null
            return ascii.takeIf { name -> name.split('.').all { HOST_LABEL.matches(it) } }
        }

        /** RFC 5952 text form: lower case, the longest run of zero groups as `::`. */
        internal fun compressIpv6(address: Inet6Address): String {
            val b = address.address
            val groups = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
            var bestStart = -1; var bestLen = 0; var i = 0
            while (i < 8) {
                if (groups[i] == 0) {
                    var j = i; while (j < 8 && groups[j] == 0) j++
                    if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                    i = j
                } else i++
            }
            val hex = groups.map { Integer.toHexString(it) }
            if (bestStart < 0) return hex.joinToString(":")
            val left = hex.subList(0, bestStart).joinToString(":")
            val right = hex.subList(bestStart + bestLen, 8).joinToString(":")
            return "$left::$right"
        }

        private val PATH_CHARS = Regex("[A-Za-z0-9._~/-]*")
        /** "" or "/a/b" without a trailing slash. No `..`, no empty segment, no percent-encoding. */
        internal fun cleanBase(path: String): String? {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return ""
            if (!p.startsWith('/') || !PATH_CHARS.matches(p)) return null
            val segments = p.substring(1).split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            return p
        }
    }
}
