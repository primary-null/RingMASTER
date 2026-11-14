package local.ringleader.core

import java.nio.file.*
import java.net.*
import java.time.Instant
import java.util.Base64
import java.util.concurrent.*
import kotlinx.serialization.json.*

fun findExecutable(name: String): String? = findExecutableIn(name, System.getenv("PATH"), extraExecutableDirs())

/** Directories after PATH. On Linux, admin tools often live outside a desktop user's PATH. */
internal fun extraExecutableDirs(): List<String> =
    if (System.getProperty("os.name").startsWith("Windows", true)) emptyList() else listOf("/usr/local/bin", "/usr/sbin", "/sbin")

internal fun findExecutableIn(name: String, path: String?, extra: List<String>): String? {
    val direct = runCatching { Path.of(name) }.getOrNull() ?: return null
    // A full path is checked as given; only bare names are looked up on PATH.
    if (direct.isAbsolute || name.contains('/') || name.contains('\\')) return direct.toString().takeIf { Files.isRegularFile(direct) }
    return (path.orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() } + extra).asSequence().distinct()
        .flatMap { dir -> sequenceOf(runCatching { Path.of(dir, name) }.getOrNull(), runCatching { Path.of(dir, "$name.exe") }.getOrNull()).filterNotNull() }
        .firstOrNull { Files.isRegularFile(it) }?.toString()
}

/** `nmcli`, `ip` and `wg` translate their text. C locale keeps the words the parser knows. */
internal fun vpnCommandEnvironment(command: List<String>): Map<String, String> {
    val file = command.firstOrNull()?.substringAfterLast('\\')?.substringAfterLast('/').orEmpty().lowercase()
    val base = file.removeSuffix(".exe")
    return if (base == "nmcli" || base == "ip" || base == "wg") mapOf("LC_ALL" to "C", "LANG" to "C", "LANGUAGE" to "C") else emptyMap()
}

internal fun applyVpnCommandEnvironment(builder: ProcessBuilder, command: List<String>) {
    vpnCommandEnvironment(command).forEach { (key, value) -> builder.environment()[key] = value }
}

fun runVpnCommand(command: List<String>, timeout: Long): String {
    val trace = Trace.begin(TraceSource.Vpn, command.firstOrNull().orEmpty(), Redact.commandDetails(command))
    try {
    val builder = ProcessBuilder(command).redirectInput(java.io.File(if(System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
    applyVpnCommandEnvironment(builder, command)
    val p = builder.start()
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
internal fun windowsSystemRoot(): String = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: System.getenv("WINDIR")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
internal fun windowsPowerShell(): String = windowsSystemRoot() + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
internal fun windowsRasdial(): String = windowsSystemRoot() + "\\System32\\rasdial.exe"
/** UTF-8 without a BOM, so a non-ASCII tunnel name survives the trip back to the JVM. */
internal fun psUtf8Prefix() = "[Console]::OutputEncoding = [Text.UTF8Encoding]::new(${'$'}false)\n${'$'}OutputEncoding = [Console]::OutputEncoding\n"
internal fun vpnPowerShell(script: String) = listOf(windowsPowerShell(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
fun windowsVpnReadScript() = psUtf8Prefix() + """
${'$'}services = @(try { Get-Service -Name 'WireGuardTunnel${'$'}*' -ErrorAction Stop | Select-Object Name,@{n='Status';e={${'$'}_.Status.ToString()}} } catch {})
${'$'}wg = @(try { Get-NetAdapter -ErrorAction Stop | Where-Object InterfaceDescription -like 'WireGuard Tunnel*' | Select-Object Name,Status } catch {})
${'$'}ras = @(try { Get-VpnConnection -ErrorAction Stop | Select-Object Name,ServerAddress,@{n='ConnectionStatus';e={${'$'}_.ConnectionStatus.ToString()}},@{n='TunnelType';e={${'$'}_.TunnelType.ToString()}} } catch {}) + @(try { Get-VpnConnection -AllUserConnection -ErrorAction Stop | Select-Object Name,ServerAddress,@{n='ConnectionStatus';e={${'$'}_.ConnectionStatus.ToString()}},@{n='TunnelType';e={${'$'}_.TunnelType.ToString()}} } catch {})
${'$'}adapters = @(try { Get-NetAdapter -ErrorAction Stop | Where-Object { ${'$'}_.InterfaceDescription -match 'TAP-Windows|OpenVPN|Wintun|Cisco AnyConnect|Fortinet|GlobalProtect|ZeroTier' -and ${'$'}_.InterfaceDescription -notmatch 'Tailscale|WireGuard' } | Select-Object Name,InterfaceDescription,Status } catch {})
${'$'}aliases = @(${ '$'}wg.Name) + @(${ '$'}ras.Name) + @(${ '$'}adapters.Name)
${'$'}addresses = @(try { Get-NetIPAddress -ErrorAction Stop | Where-Object { ${'$'}_.InterfaceAlias -in ${'$'}aliases } | Select-Object IPAddress,PrefixLength,InterfaceAlias } catch {})
${'$'}statistics = @(try { Get-NetAdapterStatistics -ErrorAction Stop | Where-Object { ${'$'}_.Name -in ${'$'}aliases } | Select-Object Name,ReceivedBytes,SentBytes } catch {})
@{ services=${'$'}services; wireguardAdapters=${'$'}wg; ras=${'$'}ras; adapters=${'$'}adapters; addresses=${'$'}addresses; statistics=${'$'}statistics } | ConvertTo-Json -Depth 5 -Compress
""".trimIndent()
internal fun windowsRouteScript(address: String) = psUtf8Prefix() + "Find-NetRoute -RemoteIPAddress ${psQuote(address)} | Select-Object -First 1 InterfaceAlias | ConvertTo-Json -Compress"

/**
 * Reads are always allowed. A change throws [VPN_HOST_ACTIONS_REASON] unless [hostActions] is true: the desktop app
 * passes true, and the browser passes true only when it was started with `--allow-host-actions`.
 */
class LocalVpnBackend(os: String = System.getProperty("os.name"), private val run: (List<String>, Long) -> String = ::runVpnCommand, private val which: (String) -> String? = ::findExecutable,
    private val imports: WireGuardImports = WireGuardImports(), private val hostActions: Boolean = false, private val stagingRoot: Path? = null,
    private val counters: (String) -> Pair<Long?, Long?> = ::interfaceCounters,
    private val routes: RouteProbe = LocalRouteProbe(os, run, which)) : VpnBackend {
    private val windows = os.startsWith("Windows", true)
    override val platform = if(windows) "Windows" else "Linux"
    private fun ts() = if(windows) which("C:\\Program Files\\Tailscale\\tailscale.exe") ?: which("tailscale") else which("tailscale")
    private fun wg() = which(if(windows) "C:\\Program Files\\WireGuard\\wireguard.exe" else "wg")
    private fun exec(args: List<String>, timeout: Long = 30) = run(args, timeout)
    private fun ps(script: String) = exec(vpnPowerShell(script))
    private fun requireHostActions() { check(hostActions) { VPN_HOST_ACTIONS_REASON } }
    private fun noted(summary: String, error: Exception): String {
        val detail = vpnFailureMessage(error.message.orEmpty())
        return if (detail.isBlank() || summary.contains(detail)) summary else "$summary $detail"
    }
    override fun snapshot(): VpnSnapshot {
        val notes = mutableListOf<String>(); val tunnels = mutableListOf<VpnTunnel>(); var status: TailscaleStatus? = null; var details = emptyList<WireGuardInterface>()
        val tsExe = ts(); val wgExe = wg()
        if(tsExe != null) try {
            val s = parseTailscaleStatus(exec(listOf(tsExe, "status", "--json"))); status = s
            val state = when(s.backendState) { "Running" -> VpnState.Up; "Stopped" -> VpnState.Down; "NeedsLogin", "NeedsMachineAuth" -> VpnState.NeedsLogin; "Starting" -> VpnState.Connecting; else -> VpnState.Unknown }
            val traffic = tailscaleTraffic(s)
            tunnels += VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", state, if(windows) "Tailscale" else "tailscale0", s.self?.addresses.orEmpty(), s.exitNode?.let { "Exit node ${it.hostName}" } ?: "${s.peers.count { it.online }} of ${s.peers.size} peers online", state in listOf(VpnState.Up, VpnState.Down), rxBytes = traffic.first, txBytes = traffic.second, lastHandshakeEpoch = traffic.third)
        } catch(e: Exception) { notes += noted("Tailscale is installed but its status could not be read.", e) }
        if(windows) try {
            val imported = imports.names().toSet()
            tunnels += parseWindowsVpnJson(ps(windowsVpnReadScript())).map { t -> if(t.id.startsWith("wg:")) t.copy(removeDeletesConfig = t.id.removePrefix("wg:") in imported) else t }
            // Imported tunnels that are down have no service; they are listed from RingMASTER's own record of its imports.
            if(wgExe != null) tunnels += imported.filter { name -> tunnels.none { it.id == "wg:$name" } }
                .map { VpnTunnel("wg:$it", VpnKind.WireGuard, it, VpnState.Down, it, detail = "Imported · in WireGuard's store", canToggle = true, toggleNeedsAdmin = true, canRemove = true, removeDeletesConfig = true) }
        } catch(e: Exception) { notes += noted("Windows VPN status could not be read.", e) }
        else {
            val ipTunnels = try { parseIpWireGuardJson(exec(listOf(which("ip") ?: "ip", "-j", "addr", "show", "type", "wireguard"))) } catch(e: Exception) { notes += noted("WireGuard interfaces could not be read.", e); emptyList() }
            if(wgExe != null) try { details = parseWgDump(exec(listOf(wgExe, "show", "all", "dump"))) } catch(e: Exception) { notes += noted(WIREGUARD_PEER_NOTE, e) }
            val nm = which("nmcli")
            val managed = if(nm != null) try { nmTunnels(nm) } catch(e: Exception) { notes += noted("NetworkManager VPN status could not be read.", e); emptyList() } else emptyList()
            tunnels += managed.map { t -> t.copy(addresses = ipTunnels.firstOrNull { it.interfaceName == t.interfaceName }?.addresses.orEmpty()) }
            tunnels += ipTunnels.filter { i -> managed.none { it.interfaceName == i.interfaceName } }
            for(i in tunnels.indices) tunnels[i] = withUnprivilegedCounters(tunnels[i], details)
        }
        return VpnSnapshot(Instant.now(), tunnels.sortedWith(compareBy({ it.state != VpnState.Up }, { when(it.kind) { VpnKind.Tailscale -> 0; VpnKind.WireGuard -> 1; else -> 2 } }, { it.name.lowercase() })), tsExe != null, status, wgExe != null, details, notes)
    }
    /** Active connections decide Up, so a translated STATE word cannot mark a live tunnel as down. */
    private fun nmTunnels(nm: String): List<VpnTunnel> {
        val shown = parseNmConnections(exec(listOf(nm, "-t", "--escape", "yes", "-f", "UUID,NAME,TYPE,DEVICE", "connection", "show")))
        val active = exec(listOf(nm, "-t", "--escape", "yes", "-f", "UUID", "connection", "show", "--active")).lineSequence().map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }.toSet()
        return shown.map { t -> t.copy(state = if(t.id.removePrefix("nm:") in active) VpnState.Up else VpnState.Down) }
    }
    private fun withUnprivilegedCounters(tunnel: VpnTunnel, details: List<WireGuardInterface>): VpnTunnel {
        val name = tunnel.interfaceName
        val (sysRx, sysTx) = if(tunnel.rxBytes == null && tunnel.txBytes == null && name != null) counters(name) else tunnel.rxBytes to tunnel.txBytes
        val handshake = details.firstOrNull { it.name == tunnel.interfaceName || it.name == tunnel.name }?.peers?.mapNotNull { it.lastHandshakeEpoch }?.maxOrNull()
        return tunnel.copy(rxBytes = sysRx ?: tunnel.rxBytes, txBytes = sysTx ?: tunnel.txBytes, lastHandshakeEpoch = handshake ?: tunnel.lastHandshakeEpoch)
    }
    private fun tailscale(args: List<String>) { try { exec(listOf(ts() ?: error("Tailscale is not installed.")) + args) } catch(e: Exception) { if(!windows && Regex("permission|access|denied|root", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) error("Tailscale refused the change. On Linux run: sudo tailscale set --operator=${'$'}USER"); throw e } }
    /** Runs [script] elevated (one UAC prompt). [failures] maps the script's exit codes to the sentence shown for each. */
    private fun elevate(script: String, failures: Map<Int, String> = emptyMap()) {
        Trace.emit(TraceSource.Vpn, "elevated", "Request elevated VPN action")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val explain = failures.entries.joinToString(" ") { (code, message) -> "$code { [Console]::Error.WriteLine(${psQuote(message)}) }" }
        val shell = psQuote(windowsPowerShell())
        // UAC consent is part of this wait. Reads stay at 30 s; approval gets three minutes.
        run(vpnPowerShell("try { ${'$'}p = Start-Process -FilePath $shell -WindowStyle Hidden -ArgumentList '-NoProfile -NonInteractive -EncodedCommand $encoded' -Verb RunAs -Wait -PassThru -ErrorAction Stop } catch { [Console]::Error.WriteLine('Administrator approval was cancelled.'); exit 1 }; if (${'$'}p.ExitCode -ne 0) { switch (${'$'}p.ExitCode) { $explain }; exit 1 }"), 180)
    }
    override fun setTunnel(tunnel: VpnTunnel, up: Boolean) {
        requireHostActions()
        require(tunnel.canToggle) { "This tunnel is read-only." }
        when {
            tunnel.id == "tailscale" -> tailscale(listOf(if(up) "up" else "down"))
            !windows && tunnel.id.startsWith("nm:") -> exec(listOf(which("nmcli") ?: error("NetworkManager is not installed."), "connection", if(up) "up" else "down", "uuid", tunnel.id.removePrefix("nm:")))
            windows && tunnel.id.startsWith("ras:") -> exec(listOf(windowsRasdial(), tunnel.id.removePrefix("ras:")) + if(up) emptyList() else listOf("/disconnect"))
            windows && tunnel.id.startsWith("wg:") -> {
                val name = tunnel.id.removePrefix("wg:"); require(validWireGuardName(name)); val exe = wg() ?: error("WireGuard is not installed.")
                elevate(if(up) wireGuardUpScript(exe, name) else wireGuardDownScript(exe, name, name in imports.names()), wireGuardFailures)
            }
            else -> error("Unsupported tunnel.")
        }
    }
    override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) {
        requireHostActions()
        val address = peer?.addresses?.firstOrNull { ScanRules.ipv4(it) != null }
        require(peer == null || address != null) { "Exit node needs an IPv4 address." }
        if(peer == null) tailscale(listOf("set", "--exit-node="))
        else tailscale(listOf("set", "--exit-node=$address", "--exit-node-allow-lan-access=${if(allowLanAccess) "true" else "false"}"))
    }
    override fun importWireGuard(config: Path, name: String): String {
        requireHostActions()
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
        val nm = which("nmcli") ?: error("Importing WireGuard tunnels needs NetworkManager (nmcli).")
        // The copy exists only so NetworkManager takes the tunnel name from the file name. It lives on a per-user
        // tmpfs when one is available, and it is removed before this function returns.
        val dir = createStage()
        val copy = dir.resolve("$name.conf")
        try {
            Files.copy(config, copy)
            ownerOnly(copy)
            val output = exec(listOf(nm, "connection", "import", "type", "wireguard", "file", copy.toString()))
            val uuid = Regex("\\(([0-9a-fA-F-]{36})\\)").find(output)?.groupValues?.get(1) ?: error("NetworkManager did not return a connection id.")
            // NetworkManager creates imported profiles with autoconnect on and may activate them at once (per its
            // developers). Importing must not change the network, so autoconnect is turned off and the profile brought
            // down again. A "down" that says the profile was never active is the result we wanted.
            val problems = mutableListOf<String>()
            try { exec(listOf(nm, "connection", "modify", "uuid", uuid, "connection.autoconnect", "no")) }
            catch(e: Exception) { problems += "Auto-connect could not be turned off (${vpnFailureMessage(e.message.orEmpty())})." }
            try { exec(listOf(nm, "connection", "down", "uuid", uuid)) }
            catch(e: Exception) {
                val text = e.message.orEmpty()
                if(!Regex("not active|inactive|not connected|unknown connection", RegexOption.IGNORE_CASE).containsMatchIn(text)) problems += "The tunnel could not be brought down (${vpnFailureMessage(text)})."
            }
            if(problems.isNotEmpty()) error("Tunnel $name was imported, but ${problems.joinToString(" ")}")
            return "nm:$uuid"
        } finally { Files.deleteIfExists(copy); Files.deleteIfExists(dir) }
    }
    override fun removeWireGuard(tunnel: VpnTunnel) {
        requireHostActions()
        require(tunnel.kind == VpnKind.WireGuard && tunnel.canRemove) { "This tunnel cannot be removed." }
        if(windows && tunnel.id.startsWith("wg:")) {
            val name = tunnel.id.removePrefix("wg:"); require(validWireGuardName(name)); val imported = name in imports.names()
            elevate(wireGuardRemoveScript(wg() ?: error("WireGuard is not installed."), name, imported), wireGuardFailures)
            if(imported) runCatching { imports.remove(name) }
        }
        else if(!windows && tunnel.id.startsWith("nm:")) exec(listOf(which("nmcli") ?: error("NetworkManager is not installed."), "connection", "delete", "uuid", tunnel.id.removePrefix("nm:"))) else error("Unsupported tunnel.")
    }
    /**
     * The route for the first address Java resolves for [host] (the one its HTTPS connection uses). The tunnel is matched
     * from the route's interface and source address only; a failed route read leaves the interface and tunnel empty.
     */
    override fun routeTo(host: String, snapshot: VpnSnapshot?): VpnRoute {
        val address = try { (ipLiteral(host) ?: InetAddress.getByName(host)).hostAddress.substringBefore('%') } catch(_: Exception) { return VpnRoute(host, null, null, null, null) }
        val route = runCatching { routes.route(address) }.getOrNull()
        val s = snapshot ?: snapshot()
        val tunnel = matchRouteTunnel(route?.iface?.name, route?.source, s.tunnels, s.tailscale?.self?.addresses.orEmpty())
        return VpnRoute(host, address, route?.iface?.name, tunnel?.id, tunnel?.name, route?.source)
    }

    private fun createStage(): Path {
        val parent = stagingRoot ?: if(windows) null else linuxKeyStagingParent()
        val dir = if(parent != null) Files.createTempDirectory(parent, "ringmaster-wg-") else Files.createTempDirectory("ringmaster-wg-")
        ownerOnly(dir)
        return dir
    }
}
fun localVpnBackend(hostActions: Boolean = false): VpnBackend? = System.getProperty("os.name").let { if(it.startsWith("Windows", true) || it.equals("Linux", true)) LocalVpnBackend(it, hostActions = hostActions) else null }

private fun tailscaleTraffic(status: TailscaleStatus): Triple<Long?, Long?, Long?> {
    val rx = status.peers.mapNotNull { it.rxBytes }.takeIf { it.isNotEmpty() }?.sum()
    val tx = status.peers.mapNotNull { it.txBytes }.takeIf { it.isNotEmpty() }?.sum()
    val handshake = status.peers.mapNotNull { it.lastHandshake }.maxOrNull()?.epochSecond
    return Triple(rx, tx, handshake)
}

/** Start of the snapshot note for unreadable WireGuard peer details. It does not mean a tunnel is missing from the list. */
const val WIREGUARD_PEER_NOTE = "WireGuard peer details need root."

/** Parent for a short-lived Linux key copy: the user runtime dir, then /dev/shm, else the JVM temp dir. */
internal fun linuxKeyStagingParent(env: Map<String, String> = System.getenv(), isDir: (Path) -> Boolean = { Files.isDirectory(it) }): Path? {
    val xdg = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of)
    if(xdg != null && isDir(xdg)) return xdg
    val shm = Path.of("/dev/shm")
    if(isDir(shm)) return shm
    return null
}

private fun ownerOnly(path: Path) {
    runCatching {
        val perms = setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE) +
            if(Files.isDirectory(path)) setOf(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE) else emptySet()
        Files.setPosixFilePermissions(path, perms)
    }
}

/** Linux interface counters that do not need root. A name that could leave `/sys/class/net` is ignored. */
internal fun interfaceCounters(name: String): Pair<Long?, Long?> {
    if(!name.matches(Regex("[A-Za-z0-9_.:-]{1,15}"))) return null to null
    val dir = Path.of("/sys/class/net", name, "statistics")
    fun read(file: String) = runCatching { Files.readString(dir.resolve(file)).trim().toLong() }.getOrNull()
    return read("rx_bytes") to read("tx_bytes")
}



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
