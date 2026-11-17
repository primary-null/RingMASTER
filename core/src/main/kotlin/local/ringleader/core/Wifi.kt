package local.ringleader.core

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

/**
 * Shared Wi-Fi model. Every source (this PC on Windows, this PC on Linux, a scan run inside a VM) produces the same
 * [WifiScan], so the UI renders one shape: the adapters, the current link of each, and the networks in range, each with
 * its access points. Addresses are upper-case colon form ("AA:BB:CC:DD:EE:FF"). Nothing here connects to a network
 * or reads a saved password.
 */

/** Where a Wi-Fi scan ran: the same identity the Bluetooth scans use, this computer or a guest. */
typealias WifiSource = BtSource

enum class WifiBand(val label: String) { Ghz24("2.4 GHz"), Ghz5("5 GHz"), Ghz6("6 GHz") }

/** The radio standard an access point or a link uses. [Unknown] when the system did not say. */
enum class WifiPhy(val standard: String) {
    Unknown(""), B("802.11b"), A("802.11a"), G("802.11g"), N("802.11n"), Ac("802.11ac"), Ax("802.11ax"), Be("802.11be"), Ad("802.11ad"),
}

/** Security in plain words. The system's own name for it stays in [WifiAccessPoint.securityRaw]. */
enum class WifiSecurity(val label: String) {
    Open("Open"),
    EnhancedOpen("Open, encrypted"),
    Wep("WEP"),
    Wpa("WPA Personal"),
    WpaWpa2("WPA/WPA2 Personal"),
    Wpa2("WPA2 Personal"),
    Wpa2Wpa3("WPA2/WPA3 Personal"),
    Wpa3("WPA3 Personal"),
    Enterprise("Enterprise"),
    Unknown("Unknown"),
}

/** Why a radio cannot transmit, as the system reports it. [Software] covers airplane mode and a switch in the settings. */
enum class RadioBlock { None, Software, Hardware, Both, Unknown }

/** Hard and soft block flags (null when unknown) as one [RadioBlock]. */
fun radioBlockOf(soft: Boolean?, hard: Boolean?): RadioBlock = when {
    soft == null && hard == null -> RadioBlock.Unknown
    soft == true && hard == true -> RadioBlock.Both
    hard == true -> RadioBlock.Hardware
    soft == true -> RadioBlock.Software
    else -> RadioBlock.None
}

/** What the system says about the hardware behind an adapter. Every field is null when the system did not say. */
data class AdapterHardware(
    /** The driver bound to the device: "iwlwifi", "btusb", "MediaTek driver version". */
    val driver: String? = null,
    /** How the device is attached: "pci", "usb", "sdio", "platform". */
    val bus: String? = null,
    val usbVendorId: Int? = null,
    val usbProductId: Int? = null,
    /** "0000:56:00.0": the address a Proxmox `hostpci` entry names. */
    val pciAddress: String? = null,
    val pciVendorId: Int? = null,
    val pciDeviceId: Int? = null,
    /** The device's own product and maker strings (a USB descriptor), when it has them. */
    val product: String? = null,
    val maker: String? = null,
)

data class WifiAdapter(
    val id: String,                 // "wlan0" on Linux, the interface GUID on Windows
    val name: String,               // the maker's name for the adapter, or the interface name
    val address: String? = null,
    /** Radio on and not blocked. Null when the system did not say. */
    val powered: Boolean? = null,
    val block: RadioBlock = RadioBlock.Unknown,
    val hardware: AdapterHardware = AdapterHardware(),
    /** "phy0" on Linux: the radio behind this interface. */
    val phy: String? = null,
    /** Bands the adapter can use, when the system said. */
    val bands: List<WifiBand> = emptyList(),
    /** False for a radio the kernel knows that has no network interface, so it cannot scan. */
    val hasInterface: Boolean = true,
)

/** One signal reading in dBm, [ms] after the scan started. */
data class WifiSample(val ms: Long, val dbm: Int)

const val WIFI_SAMPLE_CAP = 240

/** One access point (one BSSID). Hidden networks have a null [ssid]. */
data class WifiAccessPoint(
    val ssid: String?,
    val bssid: String,
    val signalDbm: Int? = null,
    /** 0 to 100 as the system gave it, or worked out from [signalDbm] when the system gave only dBm. */
    val signalPercent: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val band: WifiBand? = null,
    /** 20, 40, 80, 160 or 320. Null when the system does not report it (Windows does not). */
    val widthMhz: Int? = null,
    val phy: WifiPhy = WifiPhy.Unknown,
    val security: WifiSecurity = WifiSecurity.Unknown,
    /** The security as the system names it: "WPA2 WPA3", "RsnaPsk", "RSN PSK SAE". */
    val securityRaw: String? = null,
    /** The top rate the system lists for this access point, in Mbit/s. */
    val rateMbps: Int? = null,
    /** This computer is connected to this access point. */
    val connected: Boolean = false,
    val adapterId: String? = null,
    /** Live scans: when the access point was first and last heard, in ms after the scan started. */
    val firstSeenMs: Long? = null,
    val lastSeenMs: Long? = null,
    /** Live scans: signal readings in the order heard (capped at [WIFI_SAMPLE_CAP]). */
    val samples: List<WifiSample> = emptyList(),
    /** Smoothed signal in dBm, updated once per reading in a live scan. */
    val smoothedDbm: Float? = null,
) {
    /** "Wi-Fi 4" to "Wi-Fi 7", with 6E for 802.11ax in the 6 GHz band. Null for older standards or when unknown. */
    val generation: String? get() = wifiGenerationLabel(phy, band)
}

/** One network: the access points that share a name and a kind of security. */
data class WifiNetwork(
    /** Null for a hidden network. */
    val ssid: String?,
    val security: WifiSecurity,
    val accessPoints: List<WifiAccessPoint>,
) {
    val hidden: Boolean get() = ssid == null
    val strongest: WifiAccessPoint? get() = accessPoints.maxByOrNull { it.signalDbm ?: Int.MIN_VALUE }
    val signalDbm: Int? get() = strongest?.signalDbm
    val connected: Boolean get() = accessPoints.any { it.connected }
    val bands: List<WifiBand> get() = accessPoints.mapNotNull { it.band }.distinct().sorted()
}

/** The current link of one adapter. Everything after [connected] is null when the adapter is not connected or the system did not say. */
data class WifiLink(
    val adapterId: String,
    val connected: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val signalDbm: Int? = null,
    val signalPercent: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    /** Null when only a channel number is known and it fits two bands. */
    val band: WifiBand? = null,
    val widthMhz: Int? = null,
    val phy: WifiPhy = WifiPhy.Unknown,
    val security: WifiSecurity? = null,
    val securityRaw: String? = null,
    val rxMbps: Double? = null,
    val txMbps: Double? = null,
    val samples: List<WifiSample> = emptyList(),
    val smoothedDbm: Float? = null,
) {
    val generation: String? get() = wifiGenerationLabel(phy, band)
}

/** Why a scan could not run fully. [sentence] is the plain-language line the screen shows. */
enum class WifiIssue {
    NoAdapter, AdapterOff, AirplaneMode, HardwareSwitch,
    /** Windows hides network names and scan results while Location is off. */
    LocationOff,
    /** The radio exists but the system has no network interface for it. */
    NoInterface,
    /** Neither NetworkManager nor iw is here, so nothing can scan. */
    NoScanTool,
    /** Only iw is here and the user is not root: the adapter's last scan is shown. */
    CachedOnly,
    /** The Windows WLAN AutoConfig service is stopped. */
    ServiceOff,
    /** The adapter was asked and returned no scan; the note carries what the system said. */
    ScanFailed,
    EndedEarly;

    fun sentence(source: WifiSource = BtSource.Local): String {
        val where = if (source == BtSource.Local) "on this computer" else "in this VM"
        return when (this) {
            NoAdapter -> "No Wi-Fi adapter was found $where."
            AdapterOff -> "The Wi-Fi adapter is turned off."
            AirplaneMode -> "Airplane mode is on. Turn it off to use Wi-Fi."
            HardwareSwitch -> "The Wi-Fi adapter is switched off by a hardware switch or key."
            LocationOff -> WIFI_LOCATION_OFF
            NoInterface -> "The Wi-Fi radio has no network interface $where, so it cannot scan."
            NoScanTool -> "Nothing $where can scan for Wi-Fi networks. Install NetworkManager or the iw tool."
            CachedOnly -> "These are the networks the adapter saw last. A fresh scan needs NetworkManager or administrator rights."
            ServiceOff -> "The Windows WLAN AutoConfig service is not running, so Wi-Fi cannot be used."
            ScanFailed -> "The Wi-Fi adapter did not return a scan."
            EndedEarly -> WIFI_SCAN_ENDED_EARLY
        }
    }
}

const val WIFI_SCAN_SECONDS_DEFAULT = 8
const val WIFI_SCAN_ENDED_EARLY = BT_SCAN_ENDED_EARLY
const val WIFI_LOCATION_OFF = "Windows blocks Wi-Fi scans while Location is off. In Settings, open Privacy & security, then Location, and turn on Location services and Let desktop apps access your location."

data class WifiScan(
    val source: WifiSource,
    val adapters: List<WifiAdapter>,
    /** One entry per adapter, connected or not. */
    val links: List<WifiLink>,
    val networks: List<WifiNetwork>,
    val at: Instant,
    /** Plain-language note when the scan could not run fully. Never an empty list that looks like "no networks". */
    val note: String? = null,
    /** The typed form of [note], so a screen can offer the matching action. */
    val issue: WifiIssue? = null,
    /** Live scans: the listening window and how far into it this snapshot is. */
    val windowMs: Long? = null,
    val elapsedMs: Long? = null,
    /** False for the partial snapshots a live scan reports while it is still listening. */
    val finished: Boolean = true,
) {
    val connectedLink: WifiLink? get() = links.firstOrNull { it.connected }
}

fun wifiIssueScan(source: WifiSource, issue: WifiIssue, at: Instant, adapters: List<WifiAdapter> = emptyList(), links: List<WifiLink> = emptyList()) =
    WifiScan(source, adapters, links, emptyList(), at, issue.sentence(source), issue)

/** One platform backend. [scan] blocks for about [seconds], at least one scan, and must not throw for "no adapter": return a [WifiScan] with a note. */
interface WifiScanner {
    val platform: String
    fun scan(seconds: Int): WifiScan
    /**
     * Live variant: [onUpdate] receives growing partial snapshots ([WifiScan.finished] false), one per finished scan, from
     * the scanning thread. Backends that cannot repeat report only the final result.
     */
    fun scan(seconds: Int, onUpdate: (WifiScan) -> Unit): WifiScan = scan(seconds).also(onUpdate)

    /** Stops the scan in progress and kills its process tree. Does nothing when no scan is running. */
    fun cancel() {}
}

/**
 * One local scan at a time. [cancel] kills the process tree the scan registered with [bind].
 * [exclusive] waits briefly for a scan that is already stopping, then refuses a second one.
 */
class WifiScanControl {
    private val lock = ReentrantLock()
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    @Volatile private var kill: (() -> Unit)? = null

    fun <T> exclusive(block: () -> T): T {
        if (!lock.tryLock(8, TimeUnit.SECONDS)) error("A Wi-Fi scan is already running.")
        cancelled.set(false)
        timedOut.set(false)
        try {
            return block()
        } finally {
            kill = null
            lock.unlock()
        }
    }

    fun bind(action: () -> Unit) { kill = action }
    fun cancel() { cancelled.set(true); kill?.invoke() }
    fun markTimedOut() { timedOut.set(true) }
    fun wasCancelled(): Boolean = cancelled.get()
    fun didTimeOut(): Boolean = timedOut.get()
}

/**
 * Accumulates repeated scans into [WifiScan] snapshots. Not thread-safe: feed it from one thread.
 * Each [cycle] merges the access points one scan found by BSSID and records a signal reading for each; an access point that a
 * later scan does not find keeps its last values and its older [WifiAccessPoint.lastSeenMs]. The current link of each
 * adapter gets a reading per cycle too.
 */
class WifiLiveScan(private val source: WifiSource, private val windowMs: Long, private val at: Instant = Instant.now()) {
    var adapters: List<WifiAdapter> = emptyList()
    var note: String? = null
    var issue: WifiIssue? = null
    private var links: List<WifiLink> = emptyList()
    private val points = LinkedHashMap<String, WifiAccessPoint>()
    private val linkReadings = HashMap<String, List<WifiSample>>()
    private val linkSmooth = HashMap<String, Pair<Float, Long>>()

    fun cycle(found: List<WifiAccessPoint>, currentLinks: List<WifiLink>, ms: Long) {
        for (heard in found) {
            val old = points[heard.bssid]
            val dbm = heard.signalDbm?.takeIf { it < 0 }
            val merged = heard.copy(
                firstSeenMs = old?.firstSeenMs ?: ms,
                lastSeenMs = ms,
                samples = if (dbm == null) old?.samples.orEmpty() else (old?.samples.orEmpty() + WifiSample(ms, dbm)).takeLast(WIFI_SAMPLE_CAP),
                smoothedDbm = if (dbm == null) old?.smoothedDbm else smoothBtRssi(old?.smoothedDbm, old?.lastSeenMs, dbm, ms),
            )
            points[heard.bssid] = merged
        }
        val seen = found.mapTo(HashSet()) { it.bssid }
        for ((bssid, old) in points.toList()) if (bssid !in seen && old.connected) points[bssid] = old.copy(connected = false)
        links = currentLinks.map { link ->
            val dbm = link.signalDbm?.takeIf { it < 0 }
            if (dbm == null) link.copy(samples = linkReadings[link.adapterId].orEmpty())
            else {
                val readings = ((linkReadings[link.adapterId].orEmpty()) + WifiSample(ms, dbm)).takeLast(WIFI_SAMPLE_CAP)
                linkReadings[link.adapterId] = readings
                val previous = linkSmooth[link.adapterId]
                val smooth = smoothBtRssi(previous?.first, previous?.second, dbm, ms)
                linkSmooth[link.adapterId] = smooth to ms
                link.copy(samples = readings, smoothedDbm = smooth)
            }
        }
    }

    fun snapshot(elapsedMs: Long, finished: Boolean): WifiScan =
        WifiScan(source, adapters, links, groupWifiNetworks(points.values.toList()), at, note, issue, windowMs, elapsedMs, finished)
}

/** Access points grouped into networks (same name, same kind of security) in display order. A hidden access point is its own network. */
fun groupWifiNetworks(points: List<WifiAccessPoint>): List<WifiNetwork> =
    points.groupBy { (it.ssid ?: "hidden:${it.bssid}") to it.security }
        .map { (key, group) -> WifiNetwork(group.first().ssid, key.second, group.sortedWith(wifiAccessPointOrder)) }
        .sortedWith(wifiNetworkOrder)

private val lastName = Char(0xFFFF).toString()

/** Sort for display: connected, then strongest signal, then BSSID. */
val wifiAccessPointOrder: Comparator<WifiAccessPoint> = compareByDescending<WifiAccessPoint> { it.connected }
    .thenByDescending { it.signalDbm ?: Int.MIN_VALUE }
    .thenBy { it.bssid }

/** Sort for display: connected, then strongest signal, then name. */
val wifiNetworkOrder: Comparator<WifiNetwork> = compareByDescending<WifiNetwork> { it.connected }
    .thenByDescending { it.signalDbm ?: Int.MIN_VALUE }
    .thenBy { it.ssid?.lowercase() ?: lastName }

/** Upper-case colon MAC, or null if [value] is not a 48-bit address in colon, dash or bare-hex form. */
fun normalizeWifiAddress(value: String?): String? = normalizeBtAddress(value)

// ---------------------------------------------------------------------------------------------------------------
// Channels, bands and widths
// ---------------------------------------------------------------------------------------------------------------

/** Band of a centre frequency in MHz, or null outside the bands Wi-Fi uses. 4.9 GHz public-safety channels count as 5 GHz. */
fun wifiBandOfMhz(mhz: Int): WifiBand? = when (mhz) {
    in 2400..2500 -> WifiBand.Ghz24
    in 4900..5924 -> WifiBand.Ghz5
    in 5925..7125 -> WifiBand.Ghz6
    else -> null
}

/** The channel number of a centre frequency: 2.4 GHz 1 to 14, 5 GHz 32 to 196, 6 GHz 1 to 233. Null when [mhz] is not a channel centre. */
fun wifiChannelOfMhz(mhz: Int): Int? = when (wifiBandOfMhz(mhz)) {
    WifiBand.Ghz24 -> when { mhz == 2484 -> 14; mhz in 2412..2472 && (mhz - 2407) % 5 == 0 -> (mhz - 2407) / 5; else -> null }
    WifiBand.Ghz5 -> when {
        mhz in 5160..5905 && (mhz - 5000) % 5 == 0 -> (mhz - 5000) / 5
        mhz in 4915..4980 && (mhz - 4000) % 5 == 0 -> (mhz - 4000) / 5
        else -> null
    }
    WifiBand.Ghz6 -> when { mhz == 5935 -> 2; mhz in 5955..7115 && (mhz - 5950) % 5 == 0 -> (mhz - 5950) / 5; else -> null }
    null -> null
}

/** The centre frequency of a 20 MHz channel in [band], or null when no such channel exists. */
fun wifiMhzOfChannel(channel: Int, band: WifiBand): Int? = when (band) {
    WifiBand.Ghz24 -> when (channel) { in 1..13 -> 2407 + 5 * channel; 14 -> 2484; else -> null }
    WifiBand.Ghz5 -> when (channel) { in 32..181 -> 5000 + 5 * channel; in 183..196 -> 4000 + 5 * channel; else -> null }
    WifiBand.Ghz6 -> when (channel) { 2 -> 5935; in 1..233 -> 5950 + 5 * channel; else -> null }
}

/**
 * The band a bare channel number belongs to, or null when it fits two. Windows gives the current channel without the
 * band while Location is off. 6 GHz channels are 1, 5, 9 ... (one more than a multiple of four), 5 GHz ones are
 * multiples of four up to 144 and then 149, 153 ... 177, so 1, 5, 9, 13 (2.4 or 6 GHz) and 149 to 177 (5 or 6 GHz) fit two bands.
 */
fun wifiBandOfChannel(channel: Int): WifiBand? = when {
    channel < 1 || channel > 233 -> null
    channel in 1..14 -> if (channel % 4 == 1) null else WifiBand.Ghz24
    channel < 32 -> if (channel % 4 == 1) WifiBand.Ghz6 else null
    channel in 149..177 && channel % 4 == 1 -> null
    channel % 4 == 1 -> WifiBand.Ghz6
    else -> WifiBand.Ghz5
}

/** A width in MHz from text such as "80 MHz", "80MHz", "160+160 MHz" (read as 160) or "20 MHz (no HT)". Null when there is none. */
fun parseWifiWidthMhz(text: String?): Int? {
    val match = Regex("(\\d{2,3})(?:\\+(\\d{2,3}))?\\s*MHz", RegexOption.IGNORE_CASE).find(text.orEmpty()) ?: return null
    val first = match.groupValues[1].toInt()
    val width = if (match.groupValues[2].isNotEmpty()) 160 else first
    return width.takeIf { it in setOf(5, 10, 20, 40, 80, 160, 320) }
}

/** "Wi-Fi 4" for 802.11n, 5 for ac, 6 for ax (6E in the 6 GHz band), 7 for be. Older standards have no number. */
fun wifiGenerationLabel(phy: WifiPhy, band: WifiBand?): String? = when (phy) {
    WifiPhy.N -> "Wi-Fi 4"
    WifiPhy.Ac -> "Wi-Fi 5"
    WifiPhy.Ax -> if (band == WifiBand.Ghz6) "Wi-Fi 6E" else "Wi-Fi 6"
    WifiPhy.Be -> "Wi-Fi 7"
    else -> null
}

/** A Windows PHY type: the number of DOT11_PHY_TYPE and WiFiPhyKind (4 is 802.11a, 7 is n, 10 is ax), or the WiFiPhyKind name. */
fun wifiPhyFromWindows(value: String?): WifiPhy {
    val v = value?.trim().orEmpty()
    val number = v.toIntOrNull()
    return when {
        number != null -> when (number) { 2, 5 -> WifiPhy.B; 4 -> WifiPhy.A; 6 -> WifiPhy.G; 7 -> WifiPhy.N; 8 -> WifiPhy.Ac; 9 -> WifiPhy.Ad; 10 -> WifiPhy.Ax; 11 -> WifiPhy.Be; else -> WifiPhy.Unknown }
        v.equals("Ofdm", true) -> WifiPhy.A
        v.equals("Hrdsss", true) || v.equals("Dsss", true) -> WifiPhy.B
        v.equals("Erp", true) -> WifiPhy.G
        v.equals("HT", true) -> WifiPhy.N
        v.equals("Vht", true) -> WifiPhy.Ac
        v.equals("Dmg", true) -> WifiPhy.Ad
        v.equals("HE", true) -> WifiPhy.Ax
        v.equals("Eht", true) -> WifiPhy.Be
        else -> WifiPhy.Unknown
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Signal
// ---------------------------------------------------------------------------------------------------------------

/** Windows reports a signal quality of 0 to 100 where 0 is -100 dBm and 100 is -50 dBm, linear in between (WLAN_SIGNAL_QUALITY). */
fun wifiDbmFromWindowsQuality(percent: Int): Int = (percent.coerceIn(0, 100) / 2.0 - 100.0).roundToInt()

/** The same scale the other way round: -100 dBm or less is 0, -50 dBm or more is 100. */
fun wifiPercentFromDbm(dbm: Int): Int = ((dbm + 100) * 2).coerceIn(0, 100)

/** NetworkManager's strength (0 to 100) as dBm. It maps -100 dBm to 0 and -40 dBm to 100, so this is a rough inverse. */
fun wifiDbmFromNetworkManagerStrength(percent: Int): Int = (percent.coerceIn(0, 100) * 0.6 - 100.0).roundToInt()

// ---------------------------------------------------------------------------------------------------------------
// Security, as each system names it, in plain words
// ---------------------------------------------------------------------------------------------------------------

/** NetworkManager's SECURITY column: "", "--", "WEP", "WPA1", "WPA2", "WPA3", "WPA2 WPA3", "WPA2 802.1X", "OWE". */
fun wifiSecurityFromNetworkManager(text: String?): WifiSecurity {
    val t = text?.trim().orEmpty()
    if (t.isEmpty() || t == "--") return WifiSecurity.Open
    val words = t.split(' ', ',').filter { it.isNotEmpty() }.map { it.uppercase() }.toSet()
    return when {
        "802.1X" in words -> WifiSecurity.Enterprise
        "WPA3" in words && "WPA2" in words -> WifiSecurity.Wpa2Wpa3
        "WPA3" in words -> WifiSecurity.Wpa3
        "WPA1" in words && "WPA2" in words -> WifiSecurity.WpaWpa2
        "WPA2" in words -> WifiSecurity.Wpa2
        "WPA1" in words || "WPA" in words -> WifiSecurity.Wpa
        "OWE" in words || "OWE-TM" in words -> WifiSecurity.EnhancedOpen
        "WEP" in words -> WifiSecurity.Wep
        else -> WifiSecurity.Unknown
    }
}

/**
 * Windows names: the WinRT NetworkAuthenticationType (None, Open80211, SharedKey80211, Wpa, WpaPsk, WpaNone, Rsna, RsnaPsk,
 * Wpa3, Wpa3Sae, Owe, Wpa3Enterprise) with its NetworkEncryptionType (None, Wep, Wep40, Wep104, Tkip, Ccmp, Gcmp ...).
 */
fun wifiSecurityFromWindows(authentication: String?, encryption: String? = null): WifiSecurity {
    val a = authentication?.trim()?.lowercase().orEmpty()
    val e = encryption?.trim()?.lowercase().orEmpty()
    return when (a) {
        "none", "open80211", "open" -> if (e.startsWith("wep")) WifiSecurity.Wep else WifiSecurity.Open
        "sharedkey80211", "shared" -> WifiSecurity.Wep
        "wpa", "rsna", "wpa3", "wpa3enterprise192bits", "wpa3enterprise" -> WifiSecurity.Enterprise
        "wpapsk", "wpanone" -> WifiSecurity.Wpa
        "rsnapsk" -> WifiSecurity.Wpa2
        "wpa3sae" -> WifiSecurity.Wpa3
        "owe" -> WifiSecurity.EnhancedOpen
        else -> WifiSecurity.Unknown
    }
}

/**
 * From the key-management suites an access point announces (iw prints "PSK", "SAE", "IEEE 802.1X", "FT/PSK", "OWE"),
 * whether it announced a WPA (version 1) element, and whether its capabilities say "Privacy" (an encrypted network
 * that announces neither: WEP).
 */
fun wifiSecurityFromSuites(rsnSuites: Set<String>?, wpaSuites: Set<String>?, privacy: Boolean): WifiSecurity {
    fun has(set: Set<String>?, word: String) = set?.any { it.contains(word, ignoreCase = true) } == true
    val enterprise = has(rsnSuites, "802.1X") || has(wpaSuites, "802.1X")
    return when {
        enterprise -> WifiSecurity.Enterprise
        has(rsnSuites, "SAE") && has(rsnSuites, "PSK") -> WifiSecurity.Wpa2Wpa3
        has(rsnSuites, "SAE") -> WifiSecurity.Wpa3
        has(rsnSuites, "PSK") && wpaSuites != null -> WifiSecurity.WpaWpa2
        has(rsnSuites, "PSK") -> WifiSecurity.Wpa2
        has(rsnSuites, "OWE") -> WifiSecurity.EnhancedOpen
        rsnSuites != null -> WifiSecurity.Wpa2
        wpaSuites != null -> WifiSecurity.Wpa
        privacy -> WifiSecurity.Wep
        else -> WifiSecurity.Open
    }
}
