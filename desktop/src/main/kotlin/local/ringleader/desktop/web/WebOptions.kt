package local.ringleader.desktop.web

import java.net.InetAddress

/** Default browser port. Outside the 8000-9999 band Windows (Hyper-V) often reserves; the next free one is used if taken. */
const val WEB_DEFAULT_PORT = 18780

/** [portExplicit] is true when the user named a port: then only that port is tried. */
data class WebOptions(val bind: String = "127.0.0.1", val port: Int = WEB_DEFAULT_PORT, val allowRemote: Boolean = false, val keystore: String? = null, val maxSessions: Int = 4, val portExplicit: Boolean = false, val publicOrigin: String? = null, val hostActions: Boolean = false)
fun parseWebArgs(args: List<String>): WebOptions? {
    if (args.isEmpty()) return null
    require(args.first() == "--web") { "Expected --web" }
    var result = WebOptions()
    var i = 1
    while (i < args.size) {
        val flag = args[i++]
        fun value(): String { require(i < args.size) { "Missing value for $flag" }; return args[i++] }
        result = when(flag) {
            "--bind" -> result.copy(bind = value())
            "--port" -> result.copy(port = value().toInt().also { require(it in 0..65535) }, portExplicit = true)
            "--allow-remote" -> result.copy(allowRemote = true)
            "--allow-host-actions" -> result.copy(hostActions = true)
            "--tls-keystore" -> result.copy(keystore = value())
            "--public-origin" -> {
                val raw = value()
                val uri = java.net.URI(raw)
                require(uri.scheme?.lowercase() == "https") { "--public-origin must use https" }
                require(uri.host != null) { "--public-origin must have a host" }
                require(uri.userInfo == null) { "--public-origin must not contain user info" }
                require(uri.query == null) { "--public-origin must not contain a query" }
                require(uri.fragment == null) { "--public-origin must not contain a fragment" }
                require(uri.path.isNullOrEmpty() || uri.path == "/") { "--public-origin must not contain a path" }
                val host = uri.host.lowercase()
                val portSuffix = if (uri.port == 443 || uri.port == -1) "" else ":${uri.port}"
                result.copy(publicOrigin = "https://$host$portSuffix")
            }
            else -> error("Unknown option: $flag")
        }
    }
    require(InetAddress.getByName(result.bind).isLoopbackAddress || result.allowRemote) { "Remote binding requires --allow-remote" }
    return result
}
