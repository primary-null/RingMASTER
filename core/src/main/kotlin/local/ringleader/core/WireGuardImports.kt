package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            if (-not (Test-Path -LiteralPath $store -PathType Container)) { exit 4 }
            ${'$'}target = Join-Path $store ${psQuote("$name.conf")}
            if ((Test-Path -LiteralPath ${'$'}target) -or (Test-Path -LiteralPath (${'$'}target + '.dpapi')) -or (Get-Service -Name ${service(name)} -ErrorAction SilentlyContinue)) { exit 3 }
            if (-not (Test-Path -LiteralPath ${psQuote(source)} -PathType Leaf)) { exit 7 }
            Copy-Item -LiteralPath ${psQuote(source)} -Destination ${'$'}target
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
