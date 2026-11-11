package local.ringleader.core

import java.nio.file.*
import java.net.*
import java.time.Instant
import java.util.Base64
import java.util.concurrent.*
import kotlinx.serialization.json.*

fun findExecutable(name: String): String? {
    val direct = runCatching { Path.of(name) }.getOrNull() ?: return null
    // A full path is checked as given; only bare names are looked up on PATH.
    if (direct.isAbsolute || name.contains('/') || name.contains('\\')) return direct.toString().takeIf { Files.isRegularFile(direct) }
    return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).asSequence().filter { it.isNotBlank() }
        .flatMap { dir -> sequenceOf(runCatching { Path.of(dir, name) }.getOrNull(), runCatching { Path.of(dir, "$name.exe") }.getOrNull()).filterNotNull() }
        .firstOrNull { Files.isRegularFile(it) }?.toString()
}
fun runVpnCommand(command: List<String>, timeout: Long): String {
    val trace = Trace.begin(TraceSource.Vpn, command.firstOrNull().orEmpty(), Redact.commandDetails(command))
    try {
    val p = ProcessBuilder(command).redirectInput(java.io.File(if(System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")).start()
    val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().use { it.readText() } }
    val err = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().use { it.readText() } }
    try {
        if(!p.waitFor(timeout, TimeUnit.SECONDS)) throw IllegalStateException("VPN command timed out.")
        val stderr = err.get(5, TimeUnit.SECONDS)
        if(p.exitValue() != 0) {
            val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            throw IllegalStateException(vpnFailureMessage(line))
        }
        // Read stdout before ending the span, so a failed read still ends it as an error.
        val stdout = out.get(5, TimeUnit.SECONDS)
        trace.endProcess(p.exitValue())
        return stdout
    } finally { if(p.isAlive) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() } }
    } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
}
internal fun psQuote(value: String) = "'${Regex("['‘’‚‛]").replace(value) { it.value + it.value }}'"
internal fun vpnPowerShell(script: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
fun windowsVpnReadScript() = """
${'$'}services = @(try { Get-Service -Name 'WireGuardTunnel${'$'}*' -ErrorAction Stop | Select-Object Name,@{n='Status';e={${'$'}_.Status.ToString()}} } catch {})
${'$'}wg = @(try { Get-NetAdapter -ErrorAction Stop | Where-Object InterfaceDescription -like 'WireGuard Tunnel*' | Select-Object Name,Status } catch {})
${'$'}ras = @(try { Get-VpnConnection -ErrorAction Stop | Select-Object Name,ServerAddress,@{n='ConnectionStatus';e={${'$'}_.ConnectionStatus.ToString()}},@{n='TunnelType';e={${'$'}_.TunnelType.ToString()}} } catch {}) + @(try { Get-VpnConnection -AllUserConnection -ErrorAction Stop | Select-Object Name,ServerAddress,@{n='ConnectionStatus';e={${'$'}_.ConnectionStatus.ToString()}},@{n='TunnelType';e={${'$'}_.TunnelType.ToString()}} } catch {})
${'$'}adapters = @(try { Get-NetAdapter -ErrorAction Stop | Where-Object { ${'$'}_.InterfaceDescription -match 'TAP-Windows|OpenVPN|Wintun|Cisco AnyConnect|Fortinet|GlobalProtect|ZeroTier' -and ${'$'}_.InterfaceDescription -notmatch 'Tailscale|WireGuard' } | Select-Object Name,InterfaceDescription,Status } catch {})
${'$'}aliases = @(${ '$'}wg.Name) + @(${ '$'}ras.Name) + @(${ '$'}adapters.Name)
${'$'}addresses = @(try { Get-NetIPAddress -ErrorAction Stop | Where-Object { ${'$'}_.InterfaceAlias -in ${'$'}aliases } | Select-Object IPAddress,PrefixLength,InterfaceAlias } catch {})
@{ services=${'$'}services; wireguardAdapters=${'$'}wg; ras=${'$'}ras; adapters=${'$'}adapters; addresses=${'$'}addresses } | ConvertTo-Json -Depth 5 -Compress
""".trimIndent()

class LocalVpnBackend(os: String = System.getProperty("os.name"), private val run: (List<String>, Long) -> String = ::runVpnCommand, private val which: (String) -> String? = ::findExecutable,
    private val imports: WireGuardImports = WireGuardImports()) : VpnBackend {
    private val windows = os.startsWith("Windows", true)
    override val platform = if(windows) "Windows" else "Linux"
    private fun ts() = if(windows) which("C:\\Program Files\\Tailscale\\tailscale.exe") ?: which("tailscale") else which("tailscale")
    private fun wg() = which(if(windows) "C:\\Program Files\\WireGuard\\wireguard.exe" else "wg")
    private fun exec(args: List<String>) = run(args, 30)
    private fun ps(script: String) = exec(vpnPowerShell(script))
    override fun snapshot(): VpnSnapshot {
        val notes = mutableListOf<String>(); val tunnels = mutableListOf<VpnTunnel>(); var status: TailscaleStatus? = null; var details = emptyList<WireGuardInterface>()
        val tsExe = ts(); val wgExe = wg()
        if(tsExe != null) try {
            val s = parseTailscaleStatus(exec(listOf(tsExe, "status", "--json"))); status = s
            val state = when(s.backendState) { "Running" -> VpnState.Up; "Stopped" -> VpnState.Down; "NeedsLogin", "NeedsMachineAuth" -> VpnState.NeedsLogin; "Starting" -> VpnState.Connecting; else -> VpnState.Unknown }
            tunnels += VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", state, if(windows) "Tailscale" else "tailscale0", s.self?.addresses.orEmpty(), s.exitNode?.let { "Exit node ${it.hostName}" } ?: "${s.peers.count { it.online }} of ${s.peers.size} peers online", state in listOf(VpnState.Up, VpnState.Down))
        } catch(_: Exception) { notes += "Tailscale is installed but its status could not be read." }
        if(windows) try {
            tunnels += parseWindowsVpnJson(ps(windowsVpnReadScript()))
            // Imported tunnels that are down have no service; they are listed from RingMASTER's own record of its imports.
            if(wgExe != null) tunnels += imports.names().filter { name -> tunnels.none { it.id == "wg:$name" } }
                .map { VpnTunnel("wg:$it", VpnKind.WireGuard, it, VpnState.Down, it, detail = "Imported · in WireGuard's store", canToggle = true, toggleNeedsAdmin = true, canRemove = true) }
        } catch(_: Exception) { notes += "Windows VPN status could not be read." }
        else {
            val ipTunnels = try { parseIpWireGuardJson(exec(listOf(which("ip") ?: "ip", "-j", "addr", "show", "type", "wireguard"))) } catch(_: Exception) { notes += "WireGuard interfaces could not be read."; emptyList() }
            if(wgExe != null) try { details = parseWgDump(exec(listOf(wgExe, "show", "all", "dump"))) } catch(_: Exception) { notes += "WireGuard peer details need root." }
            val nm = which("nmcli")
            val managed = if(nm != null) try { parseNmConnections(exec(listOf(nm, "-t", "-f", "UUID,NAME,TYPE,DEVICE,STATE", "connection", "show"))) } catch(_: Exception) { notes += "NetworkManager VPN status could not be read."; emptyList() } else emptyList()
            tunnels += managed.map { t -> t.copy(addresses = ipTunnels.firstOrNull { it.interfaceName == t.interfaceName }?.addresses.orEmpty()) }
            tunnels += ipTunnels.filter { i -> managed.none { it.interfaceName == i.interfaceName } }
        }
        return VpnSnapshot(Instant.now(), tunnels.sortedWith(compareBy({ it.state != VpnState.Up }, { when(it.kind) { VpnKind.Tailscale -> 0; VpnKind.WireGuard -> 1; else -> 2 } }, { it.name.lowercase() })), tsExe != null, status, wgExe != null, details, notes)
    }
    private fun tailscale(args: List<String>) { try { exec(listOf(ts() ?: error("Tailscale is not installed.")) + args) } catch(e: Exception) { if(!windows && Regex("permission|access|denied|root", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) error("Tailscale refused the change. On Linux run: sudo tailscale set --operator=${'$'}USER"); throw e } }
    /** Runs [script] elevated (one UAC prompt). [failures] maps the script's exit codes to the sentence shown for each. */
    private fun elevate(script: String, failures: Map<Int, String> = emptyMap()) {
        Trace.emit(TraceSource.Vpn, "elevated", "Request elevated VPN action")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val explain = failures.entries.joinToString(" ") { (code, message) -> "$code { [Console]::Error.WriteLine(${psQuote(message)}) }" }
        ps("try { ${'$'}p = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList '-NoProfile -NonInteractive -EncodedCommand $encoded' -Verb RunAs -Wait -PassThru -ErrorAction Stop } catch { [Console]::Error.WriteLine('Administrator approval was cancelled.'); exit 1 }; if (${'$'}p.ExitCode -ne 0) { switch (${'$'}p.ExitCode) { $explain }; exit 1 }")
    }
    override fun setTunnel(tunnel: VpnTunnel, up: Boolean) {
        require(tunnel.canToggle) { "This tunnel is read-only." }
        when {
            tunnel.id == "tailscale" -> tailscale(listOf(if(up) "up" else "down"))
            !windows && tunnel.id.startsWith("nm:") -> exec(listOf(which("nmcli") ?: error("NetworkManager is not installed."), "connection", if(up) "up" else "down", "uuid", tunnel.id.removePrefix("nm:")))
            windows && tunnel.id.startsWith("ras:") -> exec(listOf("rasdial", tunnel.id.removePrefix("ras:")) + if(up) emptyList() else listOf("/disconnect"))
            windows && tunnel.id.startsWith("wg:") -> {
                val name = tunnel.id.removePrefix("wg:"); require(validWireGuardName(name)); val exe = wg() ?: error("WireGuard is not installed.")
                elevate(if(up) wireGuardUpScript(exe, name) else wireGuardDownScript(exe, name, name in imports.names()), wireGuardFailures)
            }
            else -> error("Unsupported tunnel.")
        }
    }
    override fun setExitNode(peer: TailscalePeer?) { val address = peer?.addresses?.firstOrNull { ScanRules.ipv4(it) != null }; require(peer == null || address != null) { "Exit node needs an IPv4 address." }; tailscale(listOf("set", "--exit-node=${address.orEmpty()}")) }
    override fun importWireGuard(config: Path, name: String): String {
        require(validWireGuardName(name)) { "Invalid WireGuard name." }
        require(Files.size(config) <= 65536 && Files.readString(config).lineSequence().any { it.trim().equals("[Interface]", true) }) { "That file is not a WireGuard configuration." }
        if(windows) {
            // Straight from the chosen file into WireGuard's protected store, with administrator rights: the private key
            // is never staged anywhere user-writable, and the tunnel service later reads the store's copy on every start.
            val exe = wg() ?: error("WireGuard is not installed.")
            require(name !in imports.names()) { "A tunnel configuration with that name already exists." }
            elevate(wireGuardImportScript(exe, name, config.toAbsolutePath().toString()), wireGuardFailures)
            try { imports.add(name) } catch(e: Exception) { error("The tunnel is in WireGuard now, but RingMASTER could not record it. Bring it up from the WireGuard app.") }
            return "wg:$name"
        }
        val dir = Files.createTempDirectory("ringmaster-wg-")
        val copy = dir.resolve("$name.conf")
        try {
            Files.copy(config, copy)
            val nm = which("nmcli") ?: error("Importing WireGuard tunnels needs NetworkManager (nmcli).")
            val output = exec(listOf(nm, "connection", "import", "type", "wireguard", "file", copy.toString()))
            val uuid = Regex("\\(([0-9a-fA-F-]{36})\\)").find(output)?.groupValues?.get(1) ?: error("NetworkManager did not return a connection id.")
            // NetworkManager creates imported profiles with autoconnect on and may activate them at once (per its
            // developers). Importing must not change the network, so autoconnect is turned off and the profile brought
            // down again; "down" fails harmlessly when it never came up.
            runCatching { exec(listOf(nm, "connection", "modify", "uuid", uuid, "connection.autoconnect", "no")) }
            runCatching { exec(listOf(nm, "connection", "down", "uuid", uuid)) }
            return "nm:$uuid"
        } finally { Files.deleteIfExists(copy); Files.deleteIfExists(dir) }
    }
    override fun removeWireGuard(tunnel: VpnTunnel) {
        require(tunnel.kind == VpnKind.WireGuard && tunnel.canRemove) { "This tunnel cannot be removed." }
        if(windows && tunnel.id.startsWith("wg:")) {
            val name = tunnel.id.removePrefix("wg:"); require(validWireGuardName(name)); val imported = name in imports.names()
            elevate(wireGuardRemoveScript(wg() ?: error("WireGuard is not installed."), name, imported), wireGuardFailures)
            if(imported) runCatching { imports.remove(name) }
        }
        else if(!windows && tunnel.id.startsWith("nm:")) exec(listOf(which("nmcli") ?: error("NetworkManager is not installed."), "connection", "delete", "uuid", tunnel.id.removePrefix("nm:"))) else error("Unsupported tunnel.")
    }
    override fun routeTo(host: String, snapshot: VpnSnapshot?): VpnRoute {
        val address = try { if(ScanRules.ipv4(host) != null) host else if(':' in host) { InetAddress.getByName(host); host } else InetAddress.getAllByName(host).sortedBy { it !is Inet4Address }.first().hostAddress } catch(_: Exception) { return VpnRoute(host, null, null, null, null) }
        val alias = runCatching { if(windows) Json.parseToJsonElement(ps("Find-NetRoute -RemoteIPAddress ${psQuote(address)} | Select-Object -First 1 InterfaceAlias | ConvertTo-Json -Compress")).jsonObject.vs("InterfaceAlias") else Json.parseToJsonElement(exec(listOf(which("ip") ?: "ip", "-j", "route", "get", address))).jsonArray.first().jsonObject.vs("dev") }.getOrNull()
        val tunnel = matchRouteTunnel(alias, address, (snapshot ?: snapshot()).tunnels)
        return VpnRoute(host, address, alias, tunnel?.id, tunnel?.name)
    }
}
fun localVpnBackend(): VpnBackend? = System.getProperty("os.name").let { if(it.startsWith("Windows", true) || it.equals("Linux", true)) LocalVpnBackend(it) else null }



/**
 * A tool's first stderr line, made safe to show: lines that mention keys are dropped, and anything shaped like a
 * WireGuard key (44 characters of base64) is masked, so configuration material never reaches the screen.
 */
fun vpnFailureMessage(line: String): String {
    val trimmed = line.trim().removePrefix("Error:").removePrefix("error:").trim()
    return when {
        Regex("cancel|1223", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) -> "Administrator approval was cancelled."
        trimmed.isEmpty() || Regex("private|preshared|key\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) -> "VPN command failed."
        else -> trimmed.replace(Regex("[A-Za-z0-9+/]{42,}={0,2}"), "…").take(200)
    }
}
