package local.ringleader.desktop.web

import java.net.InetAddress

/** Default browser port. Outside the 8000-9999 band Windows (Hyper-V) often reserves; the next free one is used if taken. */
const val WEB_DEFAULT_PORT = 18780

/** [portExplicit] is true when the user named a port: then only that port is tried. */
data class WebOptions(val bind: String = "127.0.0.1", val port: Int = WEB_DEFAULT_PORT, val allowRemote: Boolean = false, val keystore: String? = null, val maxSessions: Int = 4, val portExplicit: Boolean = false)
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
            "--tls-keystore" -> result.copy(keystore = value())
            else -> error("Unknown option: $flag")
        }
    }
    require(InetAddress.getByName(result.bind).isLoopbackAddress || result.allowRemote) { "Remote binding requires --allow-remote" }
    return result
}
