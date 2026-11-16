package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Names of the WireGuard tunnels RingMASTER imported on Windows. The configurations themselves live in WireGuard's own
 * protected store (`%ProgramFiles%\WireGuard\Data\Configurations`, readable only by SYSTEM and administrators), so a
 * tunnel that is down has no service and RingMASTER could not list it without this record. Names only: no keys, no
 * addresses. A newer file (higher schema) is read as empty and left untouched.
 */
class WireGuardImports(private val file: Path = profileFile().resolveSibling("wireguard-imports.json")) {
    private var newer = false

    @Synchronized fun names(): List<String> = runCatching {
        if (!Files.isRegularFile(file) || Files.size(file) > 65536) return emptyList()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        if ((root["schema"]?.jsonPrimitive?.intOrNull ?: 1) > SCHEMA) { newer = true; return emptyList() }
        root["names"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.filter(::validWireGuardName).distinct()
    }.getOrDefault(emptyList())

    @Synchronized fun add(name: String) = write(names() + name)
    @Synchronized fun remove(name: String) { val current = names(); if (name in current) write(current - name) }

    private fun write(names: List<String>) {
        check(!newer) { "The WireGuard import record was written by a newer RingMASTER and is left untouched." }
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "wireguard-imports-", ".tmp")
        try {
            Files.writeString(temp, buildJsonObject { put("schema", SCHEMA); putJsonArray("names") { names.distinct().sorted().forEach { add(it) } } }.toString())
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    private companion object { const val SCHEMA = 1 }
}

/**
 * WireGuard for Windows keeps tunnel configurations here, beside its program files. Built from the string, not with
 * [Path]: this is always a Windows path, also when the tests run on Linux.
 */
internal fun wireGuardStore(exe: String): String = exe.substringBeforeLast('\\') + "\\Data\\Configurations"

private fun service(name: String) = psQuote("WireGuardTunnel\$$name")

/** Runs wireguard.exe and waits: it is a GUI-subsystem program, so `& exe …` would return at once with no exit code. */
private fun wireGuardRun(exe: String, vararg args: String) =
    "\$r = Start-Process -FilePath ${psQuote(exe)} -ArgumentList @(${args.joinToString(",")}) -Wait -PassThru -ErrorAction Stop; if (\$r.ExitCode -ne 0) { exit 6 }"

/** Exit codes the elevated scripts use, with the sentence shown for each. Anything else reads "VPN command failed." */
internal val wireGuardFailures = mapOf(
    3 to "A WireGuard tunnel with that name already exists.",
    4 to "WireGuard's configuration store was not found. Open the WireGuard app once, then try again.",
    5 to "WireGuard no longer has a configuration for this tunnel.",
    6 to "WireGuard refused the tunnel. Open the WireGuard app to see its log.",
    7 to "The configuration file could not be read with administrator rights.",
)

/**
 * Copies [source] into WireGuard's protected store as `<name>.conf`. WireGuard's manager service encrypts it and lists
 * the tunnel in the WireGuard app. Nothing is brought up, and the configuration never rests anywhere user-writable.
 */
internal fun wireGuardImportScript(exe: String, name: String, source: String): String {
    val store = psQuote(wireGuardStore(exe))
    // The source path is carried in the script as base64 of its UTF-16LE bytes, never as script text: -Verb RunAs does
    // not carry the caller's environment, so a quoted string would be the only alternative and would have to be shaped
    // past every PowerShell quote. One decode into ${'$'}source keeps the real path off the command line.
    val sourceB64 = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_16LE))
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            ${'$'}source = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('$sourceB64'))
            if (-not (Test-Path -LiteralPath $store -PathType Container)) { exit 4 }
            ${'$'}target = Join-Path $store ${psQuote("$name.conf")}
            if ((Test-Path -LiteralPath ${'$'}target) -or (Test-Path -LiteralPath (${'$'}target + '.dpapi')) -or (Get-Service -Name ${service(name)} -ErrorAction SilentlyContinue)) { exit 3 }
            if (-not (Test-Path -LiteralPath ${'$'}source -PathType Leaf)) { exit 7 }
            Copy-Item -LiteralPath ${'$'}source -Destination ${'$'}target
            exit 0
        } catch { exit 9 }
    """.trimIndent()
}

/** Up: start the tunnel's service when it exists, otherwise install it from the store (which starts it, automatic on boot). */
internal fun wireGuardUpScript(exe: String, name: String): String {
    val store = psQuote(wireGuardStore(exe))
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            if (Get-Service -Name ${service(name)} -ErrorAction SilentlyContinue) { Start-Service -Name ${service(name)}; exit 0 }
            ${'$'}conf = Join-Path $store ${psQuote("$name.conf.dpapi")}
            if (-not (Test-Path -LiteralPath ${'$'}conf)) { ${'$'}conf = Join-Path $store ${psQuote("$name.conf")} }
            if (-not (Test-Path -LiteralPath ${'$'}conf)) { exit 5 }
            ${wireGuardRun(exe, "'/installtunnelservice'", "('\"' + \$conf + '\"')")}
            exit 0
        } catch { exit 9 }
    """.trimIndent()
}

/**
 * Down. A tunnel RingMASTER imported is deactivated the way the WireGuard app does it (the service is uninstalled, so
 * it stays down after a reboot; the configuration stays in the store). Any other tunnel's service is only stopped.
 */
internal fun wireGuardDownScript(exe: String, name: String, imported: Boolean): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    try {
        ${if (imported) "if (Get-Service -Name ${service(name)} -ErrorAction SilentlyContinue) { ${wireGuardRun(exe, "'/uninstalltunnelservice'", psQuote(name))} }" else "Stop-Service -Name ${service(name)}"}
        exit 0
    } catch { exit 9 }
""".trimIndent()

/** Remove: uninstall the service; for a tunnel RingMASTER imported, also delete its configuration from the store. */
internal fun wireGuardRemoveScript(exe: String, name: String, imported: Boolean): String {
    val store = psQuote(wireGuardStore(exe))
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            if (Get-Service -Name ${service(name)} -ErrorAction SilentlyContinue) { ${wireGuardRun(exe, "'/uninstalltunnelservice'", psQuote(name))} }
            ${if (imported) "Remove-Item -LiteralPath (Join-Path $store ${psQuote("$name.conf.dpapi")}), (Join-Path $store ${psQuote("$name.conf")}) -Force -ErrorAction SilentlyContinue" else ""}
            exit 0
        } catch { exit 9 }
    """.trimIndent()
}

/** WireGuard configurations are small; anything larger is refused before it is read. */
const val WIREGUARD_CONFIG_LIMIT = 65536L

/**
 * What a WireGuard configuration file would do beyond a plain tunnel, for the import confirmation. Only key names and
 * non-secret facts are kept: never a key, a script's command line or an endpoint.
 */
data class WireGuardConfigReview(
    /** The file has an [Interface] section. */
    val isConfig: Boolean,
    val tooLarge: Boolean = false,
    /** One entry per PreUp/PostUp/PreDown/PostDown line, in file order, spelled canonically. */
    val scripts: List<String> = emptyList(),
    /** The `Table` value when it is a plain token ("off", "auto", "1234"), "set" for anything else, null when absent. */
    val table: String? = null,
    val saveConfig: Boolean = false,
    /** Full-tunnel `AllowedIPs` entries found: "0.0.0.0/0", "::/0". */
    val fullTunnel: List<String> = emptyList(),
) {
    val hasFindings get() = scripts.isNotEmpty() || table != null || saveConfig || fullTunnel.isNotEmpty()
}

private val SCRIPT_KEYS = listOf("PreUp", "PostUp", "PreDown", "PostDown").associateBy { it.lowercase() }

/**
 * Reads a configuration the way WireGuard's own parsers tolerate it: section names and keys in any case, spaces around
 * `=`, `#` comments, CRLF or LF. Script keys are listed wherever they appear (a stricter parser would reject them in a
 * [Peer] section; listing them anyway errs towards saying more).
 */
fun reviewWireGuardConfig(text: String): WireGuardConfigReview {
    var isConfig = false
    val scripts = mutableListOf<String>()
    var table: String? = null
    var save = false
    val full = linkedSetOf<String>()
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[")) { if (line.filterNot { it.isWhitespace() }.equals("[Interface]", ignoreCase = true)) isConfig = true; continue }
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        when {
            key in SCRIPT_KEYS -> scripts += SCRIPT_KEYS.getValue(key)
            key == "table" -> table = value.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) } ?: "set"
            key == "saveconfig" -> save = save || value.equals("true", ignoreCase = true)
            key == "allowedips" -> value.split(',').map { it.trim() }.forEach { ip ->
                when (ip.lowercase()) { "0.0.0.0/0" -> full += "0.0.0.0/0"; "::/0", "::0/0", "0::0/0", "0:0:0:0:0:0:0:0/0" -> full += "::/0" }
            }
        }
    }
    return WireGuardConfigReview(isConfig, false, scripts, table, save, full.toList())
}

/** Reviews [path] without reading more than [WIREGUARD_CONFIG_LIMIT] bytes. */
fun readWireGuardReview(path: Path): WireGuardConfigReview {
    if (Files.size(path) > WIREGUARD_CONFIG_LIMIT) return WireGuardConfigReview(isConfig = false, tooLarge = true)
    val bytes = Files.newInputStream(path).use { it.readNBytes(WIREGUARD_CONFIG_LIMIT.toInt() + 1) }
    if (bytes.size > WIREGUARD_CONFIG_LIMIT) return WireGuardConfigReview(isConfig = false, tooLarge = true)
    return reviewWireGuardConfig(String(bytes, Charsets.UTF_8))
}

/** Whether a tunnel's PreUp/PostUp/PreDown/PostDown lines would run on this computer. */
enum class WireGuardScriptPolicy {
    /** Windows with `HKLM\Software\WireGuard\DangerousScriptExecution` = 1: the tunnel service runs them as Local System. */
    RunAsSystem,
    /** Windows with the value absent or not 1: WireGuard keeps the lines and does not run them. */
    NotRun,
    /** Linux: NetworkManager's importer drops them silently. */
    DroppedOnImport,
    /** The Windows setting could not be read. Treated as possibly running them. */
    Unknown,
}

/**
 * `reg query HKLM\Software\WireGuard /v DangerousScriptExecution` output: true for a REG_DWORD of 1, false for any other
 * value, null when the line is not there.
 */
fun parseDangerousScriptExecution(output: String): Boolean? {
    val line = output.lineSequence().firstOrNull { it.trim().startsWith("DangerousScriptExecution", ignoreCase = true) } ?: return null
    val value = line.trim().split(Regex("\\s+")).lastOrNull() ?: return null
    val number = if (value.startsWith("0x", true)) value.drop(2).toLongOrNull(16) else value.toLongOrNull()
    return number == 1L
}

/** The script lines need the user to accept running them as Local System before import. */
fun wireGuardScriptsNeedConsent(review: WireGuardConfigReview, policy: WireGuardScriptPolicy) =
    review.scripts.isNotEmpty() && (policy == WireGuardScriptPolicy.RunAsSystem || policy == WireGuardScriptPolicy.Unknown)

/** Plain lines for the import confirmation. Key names and facts only; nothing from a value except a Table token. */
fun wireGuardReviewLines(review: WireGuardConfigReview, policy: WireGuardScriptPolicy): List<String> = buildList {
    review.scripts.forEach { add("$it: a script (a command line that runs when the tunnel ${if (it.endsWith("Up")) "comes up" else "goes down"})") }
    if (review.scripts.isNotEmpty()) add(when (policy) {
        WireGuardScriptPolicy.RunAsSystem -> "WireGuard on this computer runs configuration scripts (DangerousScriptExecution is on): these will run as Local System, with full control of this computer."
        WireGuardScriptPolicy.Unknown -> "Whether WireGuard runs configuration scripts could not be read. If an administrator turned DangerousScriptExecution on, these will run as Local System, with full control of this computer."
        WireGuardScriptPolicy.NotRun -> "WireGuard on this computer does not run configuration scripts, so these lines are kept but will not run. A tunnel that depends on them may not work."
        WireGuardScriptPolicy.DroppedOnImport -> "NetworkManager does not run these scripts: it drops them on import. A tunnel that depends on them may not work."
    })
    review.table?.let { add(if (it == "set") "Table: changes which routing table the tunnel's routes go into." else "Table = $it: changes which routing table the tunnel's routes go into.") }
    if (review.saveConfig) add("SaveConfig = true: WireGuard writes the running tunnel's state back into its configuration.")
    if ("0.0.0.0/0" in review.fullTunnel) add("AllowedIPs 0.0.0.0/0: all IPv4 traffic from this computer goes through the tunnel when it is up.")
    if ("::/0" in review.fullTunnel) add("AllowedIPs ::/0: all IPv6 traffic from this computer goes through the tunnel when it is up.")
}
