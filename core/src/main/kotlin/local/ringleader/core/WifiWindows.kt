package local.ringleader.core

import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.math.roundToInt

/*
 * Wi-Fi on Windows. The bundled PowerShell script prints one JSON object per line (adapter, link, ap, cycle, note, error,
 * done); this parser turns them into the shared model. Windows 11 24H2 and later answer "no" to scans, network names and the
 * connection details while Location is off, and the script says so with a LOCATION_OFF note: that is a typed issue here,
 * never an empty list that looks like "no networks".
 */

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
private fun JsonObject.dbl(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

/** The PCI or USB ids in a Windows PnP device id: `PCI\VEN_14C3&DEV_7922&SUBSYS_...` or `USB\VID_0E8D&PID_7922\...`. */
internal fun windowsAdapterHardware(pnp: String?, pciAddress: String?, driver: String?): AdapterHardware {
    val text = pnp.orEmpty()
    val pci = Regex("^PCI\\\\VEN_([0-9A-Fa-f]{4})&DEV_([0-9A-Fa-f]{4})", RegexOption.IGNORE_CASE).find(text)
    val usb = Regex("^USB\\\\VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})", RegexOption.IGNORE_CASE).find(text)
    return AdapterHardware(
        driver = driver?.takeIf { it.isNotBlank() },
        bus = when { pci != null -> "pci"; usb != null -> "usb"; else -> null },
        usbVendorId = usb?.groupValues?.get(1)?.toInt(16), usbProductId = usb?.groupValues?.get(2)?.toInt(16),
        pciAddress = pciAddress?.takeIf { it.isNotBlank() },
        pciVendorId = pci?.groupValues?.get(1)?.toInt(16), pciDeviceId = pci?.groupValues?.get(2)?.toInt(16),
    )
}

/**
 * Incremental parser for the line-per-event output of `wifi/wifi-scan.ps1`. [accept] returns true when the snapshot changed.
 * Lines that are not JSON (PowerShell progress noise) are ignored. One scan pass is the events up to a `cycle` line; each pass is
 * folded into a [WifiLiveScan], so the signal readings accumulate.
 */
class WindowsWifiLiveParser(private val windowMs: Long, at: Instant = Instant.now()) {
    private val live = WifiLiveScan(BtSource.Local, windowMs, at)
    private class Facts(val adapter: WifiAdapter, val radio: String?, val hwRadio: String?, val airplane: Boolean, val rx: Double?, val tx: Double?)
    private val adapters = LinkedHashMap<String, Facts>()
    private val links = LinkedHashMap<String, WifiLink>()
    private val points = ArrayList<WifiAccessPoint>()
    private var noAdapter = false
    private var serviceOff = false
    private var locationOff = false
    private var scanError: String? = null
    private var cycles = 0
    var done = false; private set
    var lastMs = 0L; private set

    fun accept(line: String): Boolean {
        val o = line.trim().takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return false
        o.long("ms")?.let { lastMs = maxOf(lastMs, it) }
        val ms = o.long("ms") ?: lastMs
        when (o.str("t")) {
            "adapter" -> {
                val id = o.str("id") ?: return false
                val radio = o.str("radio"); val hw = o.str("hwRadio")
                val block = radioBlockOf(radio?.takeIf { it != "unknown" }?.let { it == "off" }, hw?.takeIf { it != "unknown" }?.let { it == "off" })
                val powered = when { radio == "off" || hw == "off" -> false; radio == "on" && hw != "off" -> true; else -> null }
                adapters[id] = Facts(WifiAdapter(id, o.str("name")?.takeIf { it.isNotBlank() } ?: "Wi-Fi adapter", normalizeWifiAddress(o.str("address")), powered, block,
                    windowsAdapterHardware(o.str("pnp"), o.str("pci"), o.str("driver"))), radio, hw, o.bool("airplane") == true, o.dbl("rxMbps"), o.dbl("txMbps"))
            }
            "link" -> {
                val id = o.str("adapter") ?: return false
                links[id] = link(id, o)
            }
            // Access points wait for their cycle line; nothing visible changes until then.
            "ap" -> { accessPoint(o)?.let { points += it }; return false }
            "cycle" -> flush(ms)
            "note" -> when (o.str("note")) {
                "NO_ADAPTER" -> noAdapter = true
                "WLAN_SERVICE" -> serviceOff = true
                "LOCATION_OFF" -> locationOff = true
                else -> return false
            }
            "error" -> { scanError = o.str("message")?.take(200)?.ifBlank { null } ?: "unknown error" }
            "done" -> { if (cycles == 0 && (links.isNotEmpty() || adapters.isNotEmpty())) flush(ms); done = true }
            else -> return false
        }
        refresh()
        return true
    }

    private fun link(id: String, o: JsonObject): WifiLink {
        if (o.bool("connected") != true) return WifiLink(id, false)
        val facts = adapters[id]
        val channel = o.int("channel")?.takeIf { it > 0 }
        val band = channel?.let(::wifiBandOfChannel)
        val quality = o.int("quality")?.takeIf { it in 0..100 }
        val dbm = o.int("rssi")?.takeIf { it < 0 } ?: quality?.let(::wifiDbmFromWindowsQuality)
        val auth = o.str("auth"); val cipher = o.str("cipher")
        return WifiLink(
            adapterId = id, connected = true, ssid = o.str("ssid")?.takeIf { it.isNotBlank() }, bssid = normalizeWifiAddress(o.str("bssid")),
            signalDbm = dbm, signalPercent = quality ?: dbm?.let(::wifiPercentFromDbm),
            frequencyMhz = if (channel != null && band != null) wifiMhzOfChannel(channel, band) else null, channel = channel, band = band,
            phy = wifiPhyFromWindows(o.str("phy")?.takeIf { it != "-1" }), security = auth?.let { wifiSecurityFromWindows(it, cipher) },
            securityRaw = auth?.let { listOfNotNull(it, cipher?.takeIf { c -> c != "None" }).joinToString(" ") },
            rxMbps = o.dbl("rxMbps")?.takeIf { it > 0 } ?: facts?.rx, txMbps = o.dbl("txMbps")?.takeIf { it > 0 } ?: facts?.tx,
        )
    }

    private fun accessPoint(o: JsonObject): WifiAccessPoint? {
        val bssid = normalizeWifiAddress(o.str("bssid")) ?: return null
        val mhz = o.int("khz")?.let { (it / 1000.0).roundToInt() }?.takeIf { it > 0 }
        val dbm = o.dbl("rssi")?.roundToInt()?.takeIf { it < 0 }
        val auth = o.str("auth"); val enc = o.str("enc")
        return WifiAccessPoint(
            ssid = o.str("ssid")?.takeIf { it.isNotBlank() }, bssid = bssid, signalDbm = dbm, signalPercent = dbm?.let(::wifiPercentFromDbm),
            frequencyMhz = mhz, channel = mhz?.let(::wifiChannelOfMhz), band = mhz?.let(::wifiBandOfMhz), widthMhz = null,
            phy = wifiPhyFromWindows(o.str("phy")), security = wifiSecurityFromWindows(auth, enc),
            securityRaw = listOfNotNull(auth, enc?.takeIf { it != "None" }).joinToString(" ").ifEmpty { null }, adapterId = o.str("adapter"),
        )
    }

    /** Folds one pass into the live scan. A connected link whose BSSID was in the scan takes that access point's frequency, band and security. */
    private fun flush(ms: Long) {
        cycles++
        val seen = points.toList(); points.clear()
        val merged = links.values.map { link ->
            val ap = link.bssid?.let { b -> seen.firstOrNull { it.bssid == b } }
            if (!link.connected || ap == null) link
            else link.copy(
                frequencyMhz = ap.frequencyMhz ?: link.frequencyMhz, channel = ap.channel ?: link.channel, band = ap.band ?: link.band,
                phy = if (link.phy == WifiPhy.Unknown) ap.phy else link.phy, security = link.security ?: ap.security, securityRaw = link.securityRaw ?: ap.securityRaw, ssid = link.ssid ?: ap.ssid,
            )
        }
        val connectedBssids = merged.filter { it.connected }.mapNotNull { it.bssid }.toSet()
        live.adapters = adapters.values.map { it.adapter }
        live.cycle(seen.map { it.copy(connected = it.bssid in connectedBssids) }, merged, ms)
    }

    /** The typed reason this scan is not whole, and its sentence. Windows' own Location refusal outranks nothing but a missing or switched-off adapter. */
    private fun refresh() {
        live.adapters = adapters.values.map { it.adapter }
        val all = adapters.values.toList()
        val off = all.isNotEmpty() && all.all { it.radio == "off" || it.hwRadio == "off" }
        val issue = when {
            noAdapter -> WifiIssue.NoAdapter
            serviceOff -> WifiIssue.ServiceOff
            off && all.any { it.hwRadio == "off" } -> WifiIssue.HardwareSwitch
            off && all.any { it.airplane } -> WifiIssue.AirplaneMode
            off -> WifiIssue.AdapterOff
            locationOff -> WifiIssue.LocationOff
            done && scanError != null && live.snapshot(0, true).networks.isEmpty() -> WifiIssue.ScanFailed
            else -> null
        }
        live.issue = issue
        live.note = when {
            issue == WifiIssue.ScanFailed -> WifiIssue.ScanFailed.sentence() + " " + scanError
            issue != null -> issue.sentence()
            else -> null
        }
    }

    fun snapshot(elapsedMs: Long, finished: Boolean): WifiScan = live.snapshot(elapsedMs, finished)
}
