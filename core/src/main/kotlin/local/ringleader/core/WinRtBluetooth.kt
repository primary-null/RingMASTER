package local.ringleader.core

import kotlinx.serialization.json.*
import java.time.Instant

private const val CLASSIC_PROTOCOL = "e0cbf06c-cd8b-4647-bb8a-263b43f0f974"
private const val LE_PROTOCOL = "bb7bb05e-5972-42b5-94fc-76eaa7084d49"

/**
 * Parses the JSON printed by `bluetooth/winrt-scan.ps1`. Windows reports one association endpoint per protocol, so a
 * dual-mode device (a phone, a TV) arrives twice; entries are merged by address into one [BtKind.Dual] device.
 * WinRT reports 0 dBm for devices it did not hear in this scan (typically connected Classic devices), so only
 * negative signal strengths are kept.
 */
fun parseWinRtScan(json: String, at: Instant): BtScan {
    val root = runCatching { Json.parseToJsonElement(json.trim()).jsonObject }.getOrElse { error("The Windows Bluetooth scan returned unreadable output.") }
    val adapters = (root["adapters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { a ->
        val id = a.str("id").orEmpty()
        val usb = Regex("VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})").find(id)
        BtAdapter(id, a.str("name")?.takeIf { it.isNotBlank() } ?: "Bluetooth adapter", normalizeBtAddress(a.str("address")), a.bool("powered"),
            usbVendorId = usb?.groupValues?.get(1)?.toInt(16), usbProductId = usb?.groupValues?.get(2)?.toInt(16))
    }
    val note = when (root.str("note")) { "NO_ADAPTER" -> BT_NO_ADAPTER; "ADAPTER_OFF" -> BT_ADAPTER_OFF; else -> null }
    data class Entry(val address: String, val name: String?, val rssi: Int?, val paired: Boolean?, val connected: Boolean?, val classic: Boolean, val le: Boolean, val category: String?)
    val entries = (root["devices"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { d ->
        val address = normalizeBtAddress(d.str("address")) ?: return@mapNotNull null
        val protocol = d.str("protocol").orEmpty().trim('{', '}').lowercase()
        val name = d.str("name")?.trim()?.takeIf { it.isNotEmpty() }
        val category = d.int("major")?.let { btCategoryFromClassOfDevice(it, d.int("minor") ?: 0) } ?: d.int("leCategory")?.let { btCategoryFromLeAppearance(it, name) }
        Entry(address, name, d.int("rssi")?.takeIf { it < 0 }, d.bool("paired"), d.bool("connected"), protocol == CLASSIC_PROTOCOL, protocol == LE_PROTOCOL, category)
    }
    val devices = entries.groupBy { it.address }.map { (address, group) ->
        val classic = group.any { it.classic }; val le = group.any { it.le }
        BtDevice(address, group.firstNotNullOfOrNull { it.name }, group.mapNotNull { it.rssi }.maxOrNull(),
            group.mapNotNull { it.paired }.takeIf { it.isNotEmpty() }?.any { it }, group.mapNotNull { it.connected }.takeIf { it.isNotEmpty() }?.any { it },
            when { classic && le -> BtKind.Dual; classic -> BtKind.Classic; le -> BtKind.LowEnergy; else -> BtKind.Unknown },
            group.firstNotNullOfOrNull { it.category }, adapters.firstOrNull()?.id)
    }.sortedWith(btDeviceOrder)
    return BtScan(BtSource.Local, adapters, devices, at, note)
}

/** Bluetooth Classic Class of Device (major 0-31, minor 6 bits) as a freedesktop-style icon name, like BlueZ's Icon. */
fun btCategoryFromClassOfDevice(major: Int, minor: Int): String? = when (major) {
    1 -> "computer"
    2 -> "phone"
    3 -> "network-wireless"
    4 -> when (minor) { 1, 2, 6 -> "audio-headset"; 9, 14, 15 -> "video-display"; 12, 13 -> "camera-video"; else -> "audio-card" }
    5 -> when ((minor shr 4) and 3) { 1 -> "input-keyboard"; 2 -> "input-mouse"; 3 -> "input-keyboard"; else -> if ((minor and 0xF) in 1..2) "input-gaming" else "input-tablet" }
    6 -> "camera-photo"
    7 -> "wearable"
    8 -> "toy"
    9 -> "health"
    else -> null
}

/** Bluetooth LE appearance category (the upper 10 bits of the GAP Appearance value) as an icon name. */
fun btCategoryFromLeAppearance(category: Int, name: String? = null): String? = when (category) {
    1 -> "phone"; 2 -> "computer"; 3 -> "wearable"; 5 -> "video-display"; 10 -> "audio-card"
    15 -> when { name?.contains("mouse", true) == true || name?.contains("mx master", true) == true -> "input-mouse"; name?.contains("key", true) == true -> "input-keyboard"; else -> "input-mouse" }
    else -> null
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

/**
 * Incremental parser for the line-per-event output of `bluetooth/winrt-scan.ps1`. Windows reports one association
 * endpoint per protocol, so a dual-mode device arrives twice; [BtLiveScan] merges them by address. Update events carry
 * only the endpoint id and the changed properties, so ids are mapped back to addresses here. Lines that are not JSON
 * (PowerShell progress noise) are ignored.
 */
class WinRtLiveParser(windowMs: Long, at: Instant = Instant.now()) {
    private val trace = Trace.emit(TraceSource.Bluetooth, "start", "WinRT scan · PowerShell helper")
    private val live = BtLiveScan(BtSource.Local, windowMs, at)
    private val endpoints = HashMap<String, Pair<String, BtKind>>()
    var done = false; private set
    var lastMs = 0L; private set

    /** True when the snapshot changed. */
    fun accept(line: String): Boolean {
        val o = line.trim().takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return false
        o.long("ms")?.let { lastMs = maxOf(lastMs, it) }
        val ms = o.long("ms") ?: lastMs
        when (o.str("t")) {
            "note" -> { live.note = when (o.str("note")) { "NO_ADAPTER" -> BT_NO_ADAPTER; "ADAPTER_OFF" -> BT_ADAPTER_OFF; else -> null }; return true }
            "adapter" -> {
                val id = o.str("id").orEmpty()
                val usb = Regex("VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})").find(id)
                live.adapters = listOf(BtAdapter(id, o.str("name")?.takeIf { it.isNotBlank() } ?: "Bluetooth adapter", normalizeBtAddress(o.str("address")), o.bool("powered"),
                    discovering = o.bool("powered"), usbVendorId = usb?.groupValues?.get(1)?.toInt(16), usbProductId = usb?.groupValues?.get(2)?.toInt(16)))
                return true
            }
            "add" -> {
                val address = normalizeBtAddress(o.str("address")) ?: return false
                val protocol = o.str("protocol").orEmpty().trim('{', '}').lowercase()
                val kind = when (protocol) { CLASSIC_PROTOCOL -> BtKind.Classic; LE_PROTOCOL -> BtKind.LowEnergy; else -> BtKind.Unknown }
                o.str("id")?.let { endpoints[it] = address to kind }
                val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                val category = o.int("major")?.let { btCategoryFromClassOfDevice(it, o.int("minor") ?: 0) } ?: o.int("leCategory")?.let { btCategoryFromLeAppearance(it, name) }
                live.sight(BtDevice(address, name, o.int("rssi"), o.bool("paired"), o.bool("connected"), kind, category, live.adapters.firstOrNull()?.id), ms)
                return true
            }
            "upd" -> {
                val (address, kind) = endpoints[o.str("id") ?: return false] ?: return false
                live.sight(BtDevice(address, rssi = o.int("SignalStrength"), paired = o.bool("IsPaired"), connected = o.bool("IsConnected"), kind = kind), ms)
                return true
            }
            "done" -> { Trace.emit(TraceSource.Bluetooth, "stop", "WinRT scan completed", correlation=trace.id.toString()); done = true; return true }
        }
        return false
    }

    fun snapshot(elapsedMs: Long, finished: Boolean): BtScan = live.snapshot(elapsedMs, finished)
}
