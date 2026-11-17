package local.ringleader.core

import java.time.Instant
import kotlin.math.roundToInt

/*
 * Wi-Fi on Linux: NetworkManager's terse output first (stable, needs no root), then iw (the link, the channel width and,
 * without NetworkManager, the scan), /proc/net/wireless and sysfs. Every parser here takes text, so the same code reads
 * a scan run on this computer, a scan run through a guest agent and a scan a helper in a guest hands over.
 */

// ---------------------------------------------------------------------------------------------------------------
// nmcli --terse --escape yes
// ---------------------------------------------------------------------------------------------------------------

/** A terse line split at colons that are not escaped. `\:` is a colon and `\\` a backslash inside a value. */
internal fun splitNmcliTerse(line: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '\\' && i + 1 < line.length) { cur.append(line[i + 1]); i += 2; continue }
        if (c == ':') { out += cur.toString(); cur.setLength(0) } else cur.append(c)
        i++
    }
    out += cur.toString()
    return out
}

/** One row of `nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status`. [state] is NetworkManager's word: connected, disconnected, unavailable, unmanaged ... */
internal data class NmDevice(val name: String, val type: String, val state: String, val connection: String?)

internal fun parseNmcliDevices(text: String): List<NmDevice> = text.lineSequence().mapNotNull { line ->
    val f = splitNmcliTerse(line.trimEnd())
    if (f.size < 3 || f[0].isBlank()) null else NmDevice(f[0], f[1], f[2], f.getOrNull(3)?.takeIf { it.isNotEmpty() && it != "--" })
}.toList()

/** One row of `nmcli -t -f IN-USE,BSSID,SSID,MODE,CHAN,FREQ,RATE,SIGNAL,SECURITY,DEVICE[,BANDWIDTH] device wifi list`. */
internal data class NmAccessPoint(
    val inUse: Boolean, val bssid: String, val ssid: String?, val mode: String, val channel: Int?, val mhz: Int?, val rateMbps: Int?,
    val strength: Int?, val security: String, val device: String?, val widthMhz: Int?,
)

private fun leadingNumber(text: String?): Int? = text?.trim()?.let { Regex("^(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

internal fun parseNmcliWifiList(text: String): List<NmAccessPoint> = text.lineSequence().mapNotNull { line ->
    if (line.isBlank()) return@mapNotNull null
    val f = splitNmcliTerse(line.trimEnd('\r'))
    if (f.size < 10) return@mapNotNull null
    val bssid = normalizeWifiAddress(f[1]) ?: return@mapNotNull null
    NmAccessPoint(
        inUse = f[0].trim() == "*", bssid = bssid, ssid = f[2].takeIf { it.isNotBlank() }, mode = f[3],
        channel = leadingNumber(f[4]), mhz = leadingNumber(f[5]), rateMbps = leadingNumber(f[6]), strength = leadingNumber(f[7])?.coerceIn(0, 100),
        security = f[8].trim(), device = f[9].takeIf { it.isNotBlank() }, widthMhz = parseWifiWidthMhz(f.getOrNull(10)),
    )
}.toList()

internal fun NmAccessPoint.toAccessPoint(): WifiAccessPoint {
    val band = mhz?.let(::wifiBandOfMhz)
    return WifiAccessPoint(
        ssid = ssid, bssid = bssid, signalDbm = strength?.let(::wifiDbmFromNetworkManagerStrength), signalPercent = strength,
        frequencyMhz = mhz, channel = channel ?: mhz?.let(::wifiChannelOfMhz), band = band, widthMhz = widthMhz,
        security = wifiSecurityFromNetworkManager(security), securityRaw = security.ifEmpty { "--" }, rateMbps = rateMbps,
        connected = inUse, adapterId = device,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// iw
// ---------------------------------------------------------------------------------------------------------------

/** One interface of `iw dev`. [channel], [mhz] and [widthMhz] are the channel it is on now (a connected or listening interface). */
internal data class IwInterface(
    val name: String, val phyIndex: Int?, val address: String?, val ssid: String?, val type: String?,
    val channel: Int?, val mhz: Int?, val widthMhz: Int?,
)

internal fun parseIwDev(text: String): List<IwInterface> {
    class Builder(val name: String, val phy: Int?) {
        var address: String? = null; var ssid: String? = null; var type: String? = null
        var channel: Int? = null; var mhz: Int? = null; var width: Int? = null
        fun read(line: String) {
            when {
                line.startsWith("addr ") -> address = normalizeWifiAddress(line.removePrefix("addr ").trim())
                line.startsWith("ssid ") -> ssid = line.removePrefix("ssid ").trim().takeIf { it.isNotEmpty() }
                line.startsWith("type ") -> type = line.removePrefix("type ").trim()
                line.startsWith("channel ") -> {
                    val m = Regex("^channel (\\d+) \\((\\d+) MHz\\)(.*)$").matchEntire(line)
                    if (m != null) { channel = m.groupValues[1].toInt(); mhz = m.groupValues[2].toInt(); width = parseWifiWidthMhz(m.groupValues[3].substringAfter("width:", "")) }
                }
            }
        }
        fun build() = IwInterface(name, phy, address, ssid, type, channel, mhz, width)
    }
    val out = mutableListOf<IwInterface>()
    var phy: Int? = null
    var current: Builder? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("phy#") -> { current?.let { out += it.build() }; current = null; phy = line.removePrefix("phy#").trim().toIntOrNull() }
            line.startsWith("Interface ") -> { current?.let { out += it.build() }; current = Builder(line.removePrefix("Interface ").trim(), phy) }
            line.startsWith("Unnamed/non-netdev interface") -> { current?.let { out += it.build() }; current = null }
            else -> current?.read(line)
        }
    }
    current?.let { out += it.build() }
    return out
}

/** `iw dev <if> link`. [connected] false for "Not connected."; the other fields are null then. */
internal data class IwLink(
    val connected: Boolean, val bssid: String? = null, val ssid: String? = null, val mhz: Int? = null, val signalDbm: Int? = null,
    val rxMbps: Double? = null, val txMbps: Double? = null, val phy: WifiPhy = WifiPhy.Unknown, val widthMhz: Int? = null,
)

/** Null when the text is neither a link nor "Not connected." (the interface is gone, or iw printed an error). */
internal fun parseIwLink(text: String): IwLink? {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val first = lines.firstOrNull() ?: return null
    if (first.startsWith("Not connected", true)) return IwLink(false)
    val head = Regex("^Connected to ([0-9A-Fa-f:]{17})").find(first) ?: return null
    var ssid: String? = null; var mhz: Int? = null; var signal: Int? = null
    var rx: Double? = null; var tx: Double? = null; var phy = WifiPhy.Unknown; var width: Int? = null
    fun rate(line: String): Triple<Double, WifiPhy, Int?>? {
        val m = Regex("^(?:rx|tx) bitrate:\\s*([0-9.]+) MBit/s(.*)$").matchEntire(line) ?: return null
        val tail = m.groupValues[2]
        val kind = when {
            "EHT-MCS" in tail -> WifiPhy.Be
            "HE-MCS" in tail -> WifiPhy.Ax
            "VHT-MCS" in tail -> WifiPhy.Ac
            Regex("\\bMCS \\d+").containsMatchIn(tail) -> WifiPhy.N
            else -> WifiPhy.Unknown
        }
        return Triple(m.groupValues[1].toDouble(), kind, parseWifiWidthMhz(tail))
    }
    for (line in lines.drop(1)) {
        when {
            line.startsWith("SSID:") -> ssid = unescapeIwSsid(line.removePrefix("SSID:").trim())
            line.startsWith("freq:") -> mhz = line.removePrefix("freq:").trim().toDoubleOrNull()?.roundToInt()
            line.startsWith("signal:") -> signal = Regex("^signal:\\s*(-?\\d+(?:\\.\\d+)?)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
            line.startsWith("rx bitrate:") -> rate(line)?.let { rx = it.first; if (it.second != WifiPhy.Unknown) phy = it.second; it.third?.let { w -> width = w } }
            line.startsWith("tx bitrate:") -> rate(line)?.let { tx = it.first; if (phy == WifiPhy.Unknown) phy = it.second; if (width == null) width = it.third }
        }
    }
    return IwLink(true, normalizeWifiAddress(head.groupValues[1]), ssid, mhz, signal, rx, tx, phy, width)
}

/** iw writes an SSID byte by byte: printable ASCII as it is, everything else as `\xNN`, a backslash as `\\`. All zero bytes is a hidden name. */
internal fun unescapeIwSsid(text: String): String? {
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val high = if (c == '\\' && text.startsWith("\\x", i)) text.getOrNull(i + 2)?.digitToIntOrNull(16) else null
        val low = if (high != null) text.getOrNull(i + 3)?.digitToIntOrNull(16) else null
        when {
            high != null && low != null -> { bytes.write(high * 16 + low); i += 4 }
            c == '\\' && text.startsWith("\\\\", i) -> { bytes.write('\\'.code); i += 2 }
            else -> { bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
    }
    val raw = bytes.toByteArray()
    if (raw.isEmpty() || raw.all { it == 0.toByte() }) return null
    return String(raw, Charsets.UTF_8).takeIf { it.isNotBlank() }
}

/** One BSS of `iw dev <if> scan` or `scan dump`: what the information elements say about the access point. */
internal data class IwBss(
    val bssid: String, val adapter: String?, val associated: Boolean, val mhz: Int?, val signalDbm: Int?, val signalPercent: Int?,
    val ssid: String?, val channel: Int?, val widthMhz: Int?, val phy: WifiPhy, val rsn: Set<String>?, val wpa: Set<String>?, val privacy: Boolean,
)

internal fun parseIwScan(text: String): List<IwBss> {
    class Block(val bssid: String, val adapter: String?, val associated: Boolean) {
        var mhz: Int? = null; var dbm: Int? = null; var percent: Int? = null; var ssid: String? = null; var ds: Int? = null
        var privacy = false; var rsn: MutableSet<String>? = null; var wpa: MutableSet<String>? = null
        var ht = false; var vht = false; var he = false; var eht = false
        var wideWidth: Int? = null
        var offset: String? = null; var staWide = false
        var rates = 0.0
        var section = ""
        fun build(): IwBss {
            val band = mhz?.let(::wifiBandOfMhz)
            // 40 MHz needs a secondary channel and a station width of "any"; the VHT, HE and EHT operation elements name the wider ones.
            val narrow = if ((offset == "above" || offset == "below") && staWide) 40 else 20
            val width = if (mhz == null && wideWidth == null) null else maxOf(narrow, wideWidth ?: 0)
            val phy = when {
                eht -> WifiPhy.Be; he -> WifiPhy.Ax; vht -> WifiPhy.Ac; ht -> WifiPhy.N
                band == WifiBand.Ghz5 -> WifiPhy.A
                band == WifiBand.Ghz24 -> if (rates >= 6.0) WifiPhy.G else if (rates > 0.0) WifiPhy.B else WifiPhy.Unknown
                else -> WifiPhy.Unknown
            }
            return IwBss(bssid, adapter, associated, mhz, dbm, percent, ssid, mhz?.let(::wifiChannelOfMhz) ?: ds, width, phy, rsn, wpa, privacy)
        }
        fun read(raw: String) {
            val line = raw.trim()
            if (line.startsWith("*")) { item(line.removePrefix("*").trim()); return }
            when {
                line.startsWith("freq:") -> mhz = line.removePrefix("freq:").trim().toDoubleOrNull()?.roundToInt()
                line.startsWith("signal:") -> {
                    val dbmText = Regex("^signal:\\s*(-?\\d+(?:\\.\\d+)?) dBm").find(line)?.groupValues?.get(1)
                    if (dbmText != null) dbm = dbmText.toDouble().roundToInt()
                    else Regex("^signal:\\s*(\\d+)/100").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { percent = it }
                }
                line.startsWith("capability:") -> privacy = Regex("\\bPrivacy\\b").containsMatchIn(line)
                line.startsWith("SSID:") -> ssid = unescapeIwSsid(line.removePrefix("SSID:").trim())
                line.startsWith("DS Parameter set: channel") -> ds = line.substringAfter("channel").trim().toIntOrNull()
                line.startsWith("Supported rates:") || line.startsWith("Extended supported rates:") ->
                    line.substringAfter(':').split(' ').mapNotNull { it.trimEnd('*').toDoubleOrNull() }.maxOrNull()?.let { rates = maxOf(rates, it) }
                line.startsWith("HT capabilities:") -> { ht = true; section = "ht" }
                line.startsWith("HT operation:") -> { ht = true; section = "htop" }
                line.startsWith("VHT capabilities:") -> { vht = true; section = "vht" }
                line.startsWith("VHT operation:") -> { vht = true; section = "vhtop" }
                line.startsWith("HE capabilities:") || line.startsWith("HE MAC Capabilities") -> { he = true; section = "he" }
                line.startsWith("HE operation:") -> { he = true; section = "heop" }
                line.startsWith("EHT capabilities:") -> { eht = true; section = "eht" }
                line.startsWith("EHT operation:") -> { eht = true; section = "ehtop" }
                line.startsWith("RSN:") -> { section = "rsn"; if (rsn == null) rsn = mutableSetOf(); line.removePrefix("RSN:").trim().takeIf { it.startsWith("*") }?.let { item(it.removePrefix("*").trim()) } }
                line.startsWith("WPA:") -> { section = "wpa"; if (wpa == null) wpa = mutableSetOf(); line.removePrefix("WPA:").trim().takeIf { it.startsWith("*") }?.let { item(it.removePrefix("*").trim()) } }
                line.isNotEmpty() && Regex("^[A-Za-z][A-Za-z0-9 /()_-]*:").containsMatchIn(line) && !line.startsWith("Capabilities") -> {
                    // Any other element ends the one before it.
                    if (section in setOf("rsn", "wpa", "htop", "vhtop", "heop", "ehtop")) section = ""
                }
            }
        }
        fun item(text: String) {
            val key = text.substringBefore(':').trim().lowercase()
            val value = text.substringAfter(':', "").trim()
            when (section) {
                "rsn", "wpa" -> if (key == "authentication suites") (if (section == "rsn") rsn else wpa)?.addAll(value.split(' ').filter { it.isNotEmpty() })
                "htop" -> when (key) {
                    "secondary channel offset" -> offset = when { value.startsWith("above") -> "above"; value.startsWith("below") -> "below"; else -> "none" }
                    "sta channel width" -> staWide = value.startsWith("any")
                }
                "vhtop", "heop", "ehtop" -> if (key == "channel width") {
                    // 0 means "20 or 40 MHz as the HT element says". The other codes carry the width in brackets.
                    val code = value.substringBefore(' ').toIntOrNull()
                    val width = if (code == 0) null else parseWifiWidthMhz(value) ?: when (code) { 1 -> 80; 2, 3 -> 160; else -> null }
                    width?.let { w -> wideWidth = maxOf(wideWidth ?: 0, w) }
                }
            }
        }
    }
    val head = Regex("^BSS ([0-9A-Fa-f:]{17})(?:\\(on ([^)]+)\\))?(.*)$")
    val out = mutableListOf<IwBss>()
    var block: Block? = null
    for (raw in text.lineSequence()) {
        val m = head.matchEntire(raw.trimEnd())
        if (m != null) {
            block?.let { out += it.build() }
            block = normalizeWifiAddress(m.groupValues[1])?.let { Block(it, m.groupValues[2].ifEmpty { null }, m.groupValues[3].contains("associated")) }
            continue
        }
        block?.read(raw)
    }
    block?.let { out += it.build() }
    return out
}

internal fun IwBss.toAccessPoint(): WifiAccessPoint {
    val band = mhz?.let(::wifiBandOfMhz)
    val security = wifiSecurityFromSuites(rsn, wpa, privacy)
    val raw = when {
        rsn != null -> ("RSN " + rsn.joinToString(" ")).trim() + (wpa?.let { " + WPA " + it.joinToString(" ") }.orEmpty())
        wpa != null -> "WPA " + wpa.joinToString(" ")
        privacy -> "WEP"
        else -> "Open"
    }
    return WifiAccessPoint(ssid, bssid, signalDbm, signalPercent ?: signalDbm?.let(::wifiPercentFromDbm), mhz, channel, band, widthMhz, phy, security, raw, null, associated, adapter)
}

// ---------------------------------------------------------------------------------------------------------------
// /proc/net/wireless
// ---------------------------------------------------------------------------------------------------------------

/** One interface of /proc/net/wireless. [levelDbm] is null when the driver reports no dBm; [quality] is the driver's own scale (often 0 to 70). */
internal data class ProcWireless(val name: String, val quality: Double?, val levelDbm: Int?)

internal fun parseProcNetWireless(text: String): List<ProcWireless> = text.lineSequence().mapNotNull { raw ->
    if ('|' in raw || ':' !in raw) return@mapNotNull null
    val name = raw.substringBefore(':').trim()
    val tokens = raw.substringAfter(':').trim().split(Regex("\\s+"))
    if (name.isEmpty() || tokens.size < 3) return@mapNotNull null
    val quality = tokens[1].trimEnd('.').toDoubleOrNull()
    val level = tokens[2].let { token ->
        val v = token.trimEnd('.').toDoubleOrNull() ?: return@let null
        when { v < 0 -> v.roundToInt(); token.endsWith(".") && v in 1.0..255.0 -> v.roundToInt() - 256; else -> null }
    }
    ProcWireless(name, quality, level)
}.toList()

// ---------------------------------------------------------------------------------------------------------------
// The whole scan
// ---------------------------------------------------------------------------------------------------------------

/** Wi-Fi scans through NetworkManager and iw need this much time for one pass of [wifiScanCommand]. */
fun wifiScanBudgetSeconds(seconds: Int): Int = seconds.coerceIn(5, 60)

/** The command that runs the scan script once. This computer runs it with `ProcessBuilder`; a guest agent or a helper runs the same one. */
fun wifiScanCommand(seconds: Int = 20): List<String> {
    val budget = wifiScanBudgetSeconds(seconds)
    return radioScriptCommand(RadioScripts.wifiScan, budget + 12, budget.toString())
}

/**
 * Turns the text of the scan script into a [WifiScan]. The unavailable states are notes with a typed [WifiIssue], never an
 * empty list that looks like "no networks". Throws [IllegalStateException] when the text is not this script's output.
 */
fun parseLinuxWifiScan(stdout: String, source: WifiSource, at: Instant): WifiScan {
    val frame = parseRadioFrame(stdout, "wifi")
    val switches = rfkillSwitches(frame)
    val uid = frame.records("uid").firstOrNull()?.items?.firstOrNull()?.trim()?.toIntOrNull()
    val tools = frame.records("tools").firstOrNull()?.items?.map { it.trim() }.orEmpty().toSet()
    val nmDevices = frame.section("nm-devices")?.let(::parseNmcliDevices).orEmpty().associateBy { it.name }
    val nmList = frame.section("nm-list")?.let(::parseNmcliWifiList).orEmpty()
    val iwInterfaces = frame.section("iw-dev")?.let(::parseIwDev).orEmpty().associateBy { it.name }
    val iwLinks = frame.sectionsWith("iw-link").mapNotNull { (name, text) -> parseIwLink(text)?.let { name to it } }.toMap()
    val iwBss = (frame.sectionsWith("iw-scan") + frame.sectionsWith("iw-scan-dump")).flatMap { (name, text) -> parseIwScan(text).map { it.copy(adapter = it.adapter ?: name) } }
    val proc = frame.section("proc-wireless")?.let(::parseProcNetWireless).orEmpty().associateBy { it.name }
    val networkManager = frame.section("nm-devices") != null

    val interfaces = frame.records("wifi")
    val adapters = interfaces.mapNotNull { r -> r.field("id")?.let { id ->
        val hardware = radioHardware(r)
        val block = radioBlockFor(r.field("phy"), switches)
        val nmState = nmDevices[id]?.state
        val powered = when {
            nmState == "unavailable" -> false
            block == RadioBlock.None -> true
            block == RadioBlock.Unknown -> null
            else -> false
        }
        WifiAdapter(id, hardware.product ?: hardware.driver?.let { "$it adapter" } ?: "Wi-Fi adapter", normalizeWifiAddress(r.field("mac")), powered, block, hardware, r.field("phy"))
    } } + frame.records("wifiphy").mapNotNull { r -> r.field("id")?.let { id ->
        val hardware = radioHardware(r)
        WifiAdapter(id, hardware.product ?: hardware.driver?.let { "$it adapter" } ?: "Wi-Fi radio", null, null, radioBlockFor(id, switches), hardware, id, hasInterface = false)
    } }

    val links = interfaces.mapNotNull { r ->
        val id = r.field("id") ?: return@mapNotNull null
        val iw = iwLinks[id]; val iwDev = iwInterfaces[id]; val nm = nmList.firstOrNull { it.inUse && it.device == id }; val pw = proc[id]
        val carrierUp = r.field("operstate") == "up" && r.field("carrier") == "1"
        val connected = iw?.connected ?: (nm != null || carrierUp)
        if (!connected) return@mapNotNull WifiLink(id, false)
        val bssid = iw?.bssid ?: nm?.bssid
        val scanned = iwBss.firstOrNull { it.bssid == bssid }
        val mhz = iw?.mhz ?: iwDev?.mhz ?: nm?.mhz ?: scanned?.mhz
        val dbm = iw?.signalDbm ?: scanned?.signalDbm ?: pw?.levelDbm ?: nm?.strength?.let(::wifiDbmFromNetworkManagerStrength)
        val security = nm?.let { wifiSecurityFromNetworkManager(it.security) } ?: scanned?.let { wifiSecurityFromSuites(it.rsn, it.wpa, it.privacy) }
        WifiLink(
            adapterId = id, connected = true, ssid = iw?.ssid ?: iwDev?.ssid ?: nm?.ssid ?: scanned?.ssid, bssid = bssid,
            signalDbm = dbm, signalPercent = nm?.strength ?: dbm?.let(::wifiPercentFromDbm),
            frequencyMhz = mhz, channel = mhz?.let(::wifiChannelOfMhz) ?: iwDev?.channel ?: nm?.channel, band = mhz?.let(::wifiBandOfMhz),
            widthMhz = iwDev?.widthMhz ?: iw?.widthMhz ?: nm?.widthMhz ?: scanned?.widthMhz,
            phy = iw?.phy?.takeIf { it != WifiPhy.Unknown } ?: scanned?.phy ?: WifiPhy.Unknown,
            security = security, securityRaw = nm?.security ?: scanned?.let { it.toAccessPoint().securityRaw },
            rxMbps = iw?.rxMbps, txMbps = iw?.txMbps,
        )
    }

    // Access points: NetworkManager's list first; iw's scan adds the width and the standard, and is the whole list where NetworkManager has none.
    val fromIw = iwBss.map { it.toAccessPoint() }
    val fromNm = nmList.map { it.toAccessPoint() }
    val points = if (fromNm.isNotEmpty()) fromNm.map { nm ->
        val iw = fromIw.firstOrNull { it.bssid == nm.bssid } ?: return@map nm
        nm.copy(
            signalDbm = iw.signalDbm ?: nm.signalDbm, widthMhz = nm.widthMhz ?: iw.widthMhz, phy = if (nm.phy == WifiPhy.Unknown) iw.phy else nm.phy,
            security = if (nm.security == WifiSecurity.Unknown) iw.security else nm.security, connected = nm.connected || iw.connected,
        )
    } else fromIw

    val issue = when {
        adapters.isEmpty() -> WifiIssue.NoAdapter
        adapters.none { it.hasInterface } -> WifiIssue.NoInterface
        adapters.filter { it.hasInterface }.all { it.powered == false } ->
            if (adapters.any { it.block == RadioBlock.Hardware || it.block == RadioBlock.Both }) WifiIssue.HardwareSwitch else WifiIssue.AdapterOff
        !frame.complete -> WifiIssue.EndedEarly
        !networkManager && "iw" !in tools -> WifiIssue.NoScanTool
        !networkManager && uid != null && uid != 0 -> WifiIssue.CachedOnly
        else -> null
    }
    return WifiScan(source, adapters, links, groupWifiNetworks(points), at, issue?.sentence(source), issue)
}

// ---------------------------------------------------------------------------------------------------------------
// This computer on Linux
// ---------------------------------------------------------------------------------------------------------------

/** The shortest time between the starts of two scan passes in one live scan. */
const val WIFI_MIN_PASS_MS = 3000L

/** Most text one scan may print (the script caps each section; this caps the whole). */
const val WIFI_OUTPUT_LIMIT = 4 * 1024 * 1024

/**
 * Runs [command] with no input and reads stdout up to [limit] bytes. The process tree is killed at [timeoutSeconds] or when
 * [control] cancels, and what was printed until then is returned (the parser reports a script that was cut off). A timeout
 * with no output at all throws.
 */
internal fun runWifiCommand(command: List<String>, timeoutSeconds: Long, control: WifiScanControl, limit: Int = WIFI_OUTPUT_LIMIT): String {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(java.io.File("/dev/null")).start()
    val kill = {
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        if (process.isAlive) process.destroyForcibly()
    }
    control.bind(kill)
    if (control.wasCancelled()) kill()
    val out = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.use { readCapped(it, limit, kill) } }
    try {
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) { control.markTimedOut(); kill() }
        val text = out.get(5, java.util.concurrent.TimeUnit.SECONDS)
        if (control.didTimeOut() && text.isBlank()) error("The Wi-Fi scan timed out.")
        return text
    } finally { if (process.isAlive) kill() }
}

/**
 * The Linux backend: runs the scan script (NetworkManager first, then iw) as often as the window allows and folds the
 * results into live snapshots with a signal reading per pass. Never connects and never reads a saved password.
 * [run] is the one-shot runner; tests inject it.
 */
class LinuxWifiScanner(
    private val control: WifiScanControl = WifiScanControl(),
    private val run: (List<String>, Long) -> String = { command, timeout -> runWifiCommand(command, timeout, control) },
    private val clock: () -> Long = System::nanoTime,
    private val now: () -> Instant = Instant::now,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : WifiScanner {
    override val platform = "Linux · NetworkManager and iw"
    override fun cancel() = control.cancel()
    override fun scan(seconds: Int): WifiScan = scan(seconds) {}

    override fun scan(seconds: Int, onUpdate: (WifiScan) -> Unit): WifiScan = control.exclusive {
        val window = seconds.coerceIn(5, 60)
        val perScan = window.coerceIn(10, 25)
        val trace = Trace.begin(TraceSource.Tools, "Wi-Fi scan · sh helper", emptyMap())
        try {
            val started = clock()
            val live = WifiLiveScan(BtSource.Local, window * 1000L, now())
            fun elapsed() = (clock() - started).coerceAtLeast(0L) / 1_000_000L
            do {
                val cycleStart = elapsed()
                val text = run(wifiScanCommand(perScan), perScan + 20L)
                val pass = parseLinuxWifiScan(text, BtSource.Local, now())
                live.adapters = pass.adapters; live.note = pass.note; live.issue = pass.issue
                live.cycle(pass.networks.flatMap { it.accessPoints }, pass.links, elapsed())
                val cycleMs = elapsed() - cycleStart
                // Passes are at least WIFI_MIN_PASS_MS apart: a rescan asked for more often is refused or ignored by NetworkManager and the adapter.
                val step = maxOf(cycleMs, WIFI_MIN_PASS_MS)
                val again = !control.wasCancelled() && pass.issue == null && pass.networks.isNotEmpty() && elapsed() + step <= window * 1000L
                if (again) { onUpdate(live.snapshot(elapsed(), finished = false)); pause(step - cycleMs) }
            } while (again && !control.wasCancelled())
            if (control.wasCancelled() || control.didTimeOut()) {
                if (live.issue == null) { live.issue = WifiIssue.EndedEarly; live.note = WifiIssue.EndedEarly.sentence() }
            }
            val result = live.snapshot(elapsed(), finished = true)
            onUpdate(result)
            result
        } catch (t: Throwable) { trace.end(error = t); throw t } finally { trace.end() }
    }

    /** Waits [ms] in short slices so a cancel is noticed at once. */
    private fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !control.wasCancelled()) { val slice = minOf(left, 100L); sleep(slice); left -= slice }
    }
}
