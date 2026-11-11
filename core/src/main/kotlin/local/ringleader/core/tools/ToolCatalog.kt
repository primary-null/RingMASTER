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
