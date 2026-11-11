package local.ringleader.core

import java.time.Instant

/**
 * Shared Bluetooth model. Every source (this PC on Windows, this PC on Linux, a scan run inside a VM) produces the
 * same [BtScan], so the UI renders one shape. Addresses are upper-case colon form ("AA:BB:CC:DD:EE:FF").
 */
enum class BtKind { Classic, LowEnergy, Dual, Unknown }

/** Where a scan ran: this computer, or inside a guest (resource id text and display label). */
data class BtSource(val id: String, val label: String) {
    companion object { val Local = BtSource("local", "This computer") }
}

data class BtAdapter(
    val id: String,                 // "hci0" on Linux, PnP instance id on Windows
    val name: String,               // "MediaTek Bluetooth Adapter", BlueZ Alias
    val address: String? = null,
    val powered: Boolean? = null,
    val discovering: Boolean? = null,
    val usbVendorId: Int? = null,   // 0x13D3 when known (used for pass-through later)
    val usbProductId: Int? = null,
)

data class BtDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int? = null,          // dBm, null when not seen in this scan
    val paired: Boolean? = null,
    val connected: Boolean? = null,
    val kind: BtKind = BtKind.Unknown,
    /** Freedesktop-style icon/category when known: audio-headset, audio-card, input-keyboard, input-mouse, phone, computer… */
    val category: String? = null,
    val adapterId: String? = null,
    /** Live scans: when the device was first and last heard, in ms after the scan started. Null for one-shot results. */
    val firstSeenMs: Long? = null,
    val lastSeenMs: Long? = null,
    /** Live scans: signal readings in the order heard (capped at [BT_SAMPLE_CAP]). */
    val samples: List<BtSample> = emptyList(),
)

/** One signal reading, [ms] after the scan started. */
data class BtSample(val ms: Long, val rssi: Int)

const val BT_SAMPLE_CAP = 240

data class BtScan(
    val source: BtSource,
    val adapters: List<BtAdapter>,
    val devices: List<BtDevice>,
    val at: Instant,
    /** Plain-language note when the scan could not run fully: no adapter, adapter off, BlueZ missing. */
    val note: String? = null,
    /** Live scans: the listening window and how far into it this snapshot is. */
    val windowMs: Long? = null,
    val elapsedMs: Long? = null,
    /** False for the partial snapshots a live scan reports while it is still listening. */
    val finished: Boolean = true,
)

/** One platform backend. [scan] blocks for about [seconds] and must not throw for "no adapter": return a [BtScan] with a note. */
interface BluetoothScanner {
    val platform: String
    fun scan(seconds: Int): BtScan
    /**
     * Live variant: [onUpdate] receives growing partial snapshots ([BtScan.finished] false) while the scan listens,
     * from the scanning thread. Backends that cannot stream report only the final result.
     */
    fun scan(seconds: Int, onUpdate: (BtScan) -> Unit): BtScan = scan(seconds).also(onUpdate)
}

/**
 * Accumulates live sightings into [BtScan] snapshots. Not thread-safe: feed it from one thread.
 * [sight] merges what is known about a device (name, flags, kind, category) and records a signal reading when one
 * is given; devices first reported without a reading (paired but out of range) keep a null [BtDevice.firstSeenMs]
 * until they are actually heard.
 */
class BtLiveScan(private val source: BtSource, private val windowMs: Long, private val at: Instant = Instant.now()) {
    var adapters: List<BtAdapter> = emptyList()
    var note: String? = null
    private val devices = LinkedHashMap<String, BtDevice>()

    fun sight(update: BtDevice, ms: Long) {
        val old = devices[update.address]
        val rssi = update.rssi?.takeIf { it < 0 }
        val merged = (old ?: update.copy(rssi = null, samples = emptyList(), firstSeenMs = null, lastSeenMs = null)).let { d ->
            d.copy(
                name = update.name?.takeIf { it.isNotBlank() } ?: d.name,
                paired = update.paired ?: d.paired,
                connected = update.connected ?: d.connected,
                kind = mergeKind(d.kind, update.kind),
                category = d.category ?: update.category,
                adapterId = d.adapterId ?: update.adapterId,
            )
        }
        devices[update.address] = if (rssi == null) merged else merged.copy(
            rssi = rssi,
            firstSeenMs = merged.firstSeenMs ?: ms,
            lastSeenMs = ms,
            samples = (merged.samples + BtSample(ms, rssi)).takeLast(BT_SAMPLE_CAP),
        )
    }

    fun snapshot(elapsedMs: Long, finished: Boolean): BtScan =
        BtScan(source, adapters, devices.values.toList(), at, note, windowMs, elapsedMs, finished)

    private fun mergeKind(a: BtKind, b: BtKind): BtKind = when {
        a == b || b == BtKind.Unknown -> a
        a == BtKind.Unknown -> b
        else -> BtKind.Dual
    }
}

const val BT_SCAN_SECONDS_DEFAULT = 10
const val BT_NO_ADAPTER = "No Bluetooth adapter was found on this computer."
const val BT_ADAPTER_OFF = "The Bluetooth adapter is turned off."

/** Upper-case colon MAC, or null if [value] is not a 48-bit address in colon, dash or bare-hex form. */
fun normalizeBtAddress(value: String?): String? {
    val hex = value?.trim()?.replace(":", "")?.replace("-", "")?.uppercase() ?: return null
    if (!hex.matches(Regex("[0-9A-F]{12}"))) return null
    return hex.chunked(2).joinToString(":")
}

/** Sort for display: connected, then paired, then strongest signal, then name. */
val btDeviceOrder: Comparator<BtDevice> = compareByDescending<BtDevice> { it.connected == true }
    .thenByDescending { it.paired == true }
    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
    .thenBy { it.name?.lowercase() ?: "￿" }
