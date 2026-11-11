package local.ringleader.core.tools

import java.nio.file.Path
import local.ringleader.core.profileFile

data class ToolCatalog(val plugins: List<ToolPlugin>, val skipped: List<SkippedPlugin>)

fun builtinPlugins(): List<ToolPlugin> = listOf(
    PingPlugin(),
    TraceroutePlugin(),
    PortCheckPlugin(),
    DnsPlugin(),
    HttpTlsPlugin(),
    Iperf3Plugin(),
    WireGuardToolPlugin(),
)

/** JSON plug-ins live in the plugins directory beside the profile file. */
fun toolPluginDirectory(): Path = profileFile().resolveSibling("plugins")

fun loadToolCatalog(directory: Path = toolPluginDirectory()): ToolCatalog {
    val builtins = builtinPlugins()
    val (users, skipped) = loadUserPlugins(directory, builtins.map { it.id }.toSet())
    return ToolCatalog(builtins + users, skipped)
}

/**
 * Tools that act on the server host (JSON plug-ins and iperf's server or custom-path modes) are refused in the
 * browser unless the operator started RingMASTER with `--allow-host-actions`. Read-only probes (ping, traceroute,
 * port check, DNS, HTTP/TLS, iperf client from PATH, the read-only WireGuard tool) are not refused.
 */
fun browserRefusal(plugin: ToolPlugin, values: Map<String, String>): String? {
    val reason = "Not available in the browser without --allow-host-actions."
    if (plugin is UserToolPlugin) return reason
    if (plugin is Iperf3Plugin) {
        if (values["server"] == "true") return reason
        if (values["executable"].orEmpty().isNotBlank()) return reason
    }
    return null
}
