package local.ringleader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Radios: which Bluetooth and Wi-Fi adapters each Proxmox VE node has, which guest each one is given to and how, and which
// guests hold a radio. Everything here is read-only. Sources, read on a PVE 9 host and in pve-common, pve-manager,
// qemu-server, pve-container and pve-guest-common (2026-11-16):
//  - GET /nodes/{node}/hardware/usb needs Sys.Modify on / and answers 403 "Permission check failed (/, Sys.Modify)" without
//    it. Each row: busnum, devnum, port, level, usbpath ("2.3", absent on root hubs), class (bDeviceClass as a number: the
//    device class only, never interface classes), vendid and prodid as four hex digits, speed, product, manufacturer.
//  - GET /nodes/{node}/hardware/pci needs Sys.Audit or Sys.Modify on /. Each row: id (0000:00:14.3), class ("0x028000"),
//    vendor and device ("0x8086"), subsystem ids, vendor_name, device_name, iommugroup. The default list leaves out
//    memory controllers, bridges and processors (classes 05, 06, 0b).
//  - GET /cluster/mapping/usb and /pci list only the mappings this identity holds Mapping.Audit, Use or Modify on. An empty
//    list therefore does not mean there are none. A map entry is "node=..,id=vvvv:pppp[,path=1-2.3]" (USB) or
//    "node=..,path=0000:01:00.0[;0000:02:00.0],id=vvvv:dddd[,...]" (PCI, the first free path is taken at start).
//  - GET /nodes/{node}/network lists a wireless interface as type "eth"; only its name (wl...) and its altnames (one of which
//    is usually the PCI path name, wlp0s20f3) tell it apart. "exists" is set only while the interface is present.
//  - A USB or PCI device given to a VM stays in the node's hardware lists (sysfs); its network interface disappears.

enum class RadioKind(val label: String) { Bluetooth("Bluetooth"), WiFi("Wi-Fi") }
enum class RadioBus(val label: String) { Usb("USB"), Pci("PCI") }

/** How sure a classification is. [Certain] comes from class codes; anything less is shown as "looks like". */
enum class RadioCertainty { Certain, Likely, Guess }

/** How a guest is given a radio, or reaches one. */
enum class RadioMeans {
    /** VM `usbN: host=vvvv:pppp`. */ UsbDevice,
    /** VM `usbN: host=bus-port`. */ UsbPort,
    /** VM `usbN: mapping=id`. */ UsbMapping,
    /** VM `hostpciN: 0000:01:00.0`. */ PciDevice,
    /** VM `hostpciN: mapping=id`. */ PciMapping,
    /** Container `devN: /dev/bus/usb/BBB/DDD` or a mount of one device node. */ ContainerDevice,
    /** Container mount or cgroup rule for every USB device node (major 189). */ ContainerUsbBus,
    /** Container `lxc.net.N.type: phys` with a Wi-Fi interface as its link. */ ContainerInterface,
    /** Container mount of the node's D-Bus socket, through which it can use the node's Bluetooth service. */ ContainerDbus,
    /** Container access to /dev/rfkill (the radio switch). */ ContainerRfkill,
    /** Container access to /dev/vhci (virtual Bluetooth controllers). */ ContainerVhci,
}

/** How firmly a guest entry is tied to one adapter. */
enum class RadioTie {
    /** By address, port or a unique id. */ Exact,
    /** By vendor and product id, and the node has more than one such adapter. */ OneOfSeveral,
    /** A broad rule (every USB device node, the node's D-Bus) that lets the guest reach the adapter. */ Possible,
    /** No adapter could be named. */ Unresolved,
}

enum class RadioChange { None, AtNextStart, RemovedAtNextStart }

/**
 * One Bluetooth or Wi-Fi adapter of a node. [id] is stable for this server, node and place: the USB port, the PCI address,
 * the vendor and product id when only a guest names it, or the interface name when only the node's network list shows it.
 */
data class Radio(
    val id: String,
    val remote: String,
    val node: String,
    val kind: RadioKind,
    val bus: RadioBus?,
    val name: String,
    val certainty: RadioCertainty,
    /** Why it is taken for a radio, as a short sentence for the screen. */
    val evidence: String,
    val vendorName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    /** PCI address (0000:00:14.3) or USB port (1-2.3). */
    val address: String? = null,
    /** The node's own network interface for this adapter (wlo1), when present on the node now. */
    val interfaceName: String? = null,
    /** False when only a guest's config names the adapter: the node's own list did not show it, or could not be read. */
    val listedOnNode: Boolean = true,
    /** Whether the node has that interface up now; null when its network list did not say. */
    val interfaceActive: Boolean? = null,
) {
    val ids: String? get() = if (vendorId != null && productId != null) hex4(vendorId) + ":" + hex4(productId) else null
}

/** One guest config entry that gives a radio to the guest, or lets it reach one. */
data class RadioUse(
    val guest: ResourceId,
    val guestName: String,
    /** "VM" or "Container". */
    val guestKind: String,
    val node: String,
    val running: Boolean,
    /** The config key: usb0, hostpci1, dev0, lxc.mount.entry, lxc.net.0.link. */
    val key: String,
    /** The entry as the API returned it. */
    val value: String,
    val means: RadioMeans,
    val radioId: String?,
    val tie: RadioTie,
    val change: RadioChange = RadioChange.None,
    val note: String? = null,
)

/** The radio side of one guest. [others] are USB and PCI entries that are not known radios. */
data class GuestRadios(
    val guest: ResourceId,
    val name: String,
    val kind: String,
    val node: String,
    val running: Boolean,
    val uses: List<RadioUse> = emptyList(),
    val radios: List<Radio> = emptyList(),
    /** `usbN: spice` keys: slots that can take a USB device (such as a Bluetooth dongle) from the computer running the console. */
    val spiceSlots: List<String> = emptyList(),
    val others: List<RadioUse> = emptyList(),
    /** Why this guest's config could not be read. */
    val note: String? = null,
) {
    /** True when an adapter is given to the guest, not merely reachable through a broad rule. */
    val holdsRadio: Boolean get() = uses.any { it.radioId != null && (it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral) }
    val reachesRadio: Boolean get() = uses.isNotEmpty()
}

data class NodeRadios(
    val node: String,
    val radios: List<Radio> = emptyList(),
    /** Short sentences, such as which list this token may not read. */
    val notes: List<String> = emptyList(),
    val usbListed: Boolean = false,
    val pciListed: Boolean = false,
    val networkListed: Boolean = false,
)

/** Radios across one Proxmox VE connection. [unavailable] says why nothing could be read (a PDM session, for example). */
data class FleetRadios(
    val remote: String,
    val nodes: List<NodeRadios> = emptyList(),
    val guests: List<GuestRadios> = emptyList(),
    val unavailable: String? = null,
    val notes: List<String> = emptyList(),
    val readAt: Instant? = null,
    /** API calls the last read made, and how long it took. */
    val calls: Int = 0,
    val elapsedMillis: Long = 0,
) {
    val radios: List<Radio> get() = nodes.flatMap { it.radios }
    fun radio(id: String): Radio? = radios.firstOrNull { it.id == id }
}

// ---- both directions ----------------------------------------------------------------------------------------------------

/** The radio side of [guest], or null when the last read did not include it. */
fun radiosOf(fleet: FleetRadios, guest: ResourceId): GuestRadios? = fleet.guests.firstOrNull { it.guest == guest }

/** Every guest entry that gives [radio] to a guest or lets a guest reach it, firmest first. */
fun usersOf(fleet: FleetRadios, radio: Radio): List<RadioUse> =
    fleet.guests.flatMap { it.uses }.filter { it.radioId == radio.id }.sortedWith(compareBy({ it.tie.ordinal }, { it.guest.vmid }, { it.key }))

/** No guest holds [radio] (broad rules do not count). */
fun radioFree(fleet: FleetRadios, radio: Radio): Boolean = usersOf(fleet, radio).none { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }

/** Guests that hold a radio, in id order. */
fun guestsWithRadios(fleet: FleetRadios): List<GuestRadios> = fleet.guests.filter { it.holdsRadio }.sortedBy { it.guest.vmid }

/** Counts for a dashboard tile. [given] and [free] split [bluetooth] + [wifi]; [nodeNotes] counts nodes with something unread. */
data class RadioSummary(val bluetooth: Int, val wifi: Int, val given: Int, val free: Int, val guestsHolding: Int, val nodeNotes: Int)

fun radioSummary(fleet: FleetRadios): RadioSummary {
    val radios = fleet.radios
    val given = radios.count { !radioFree(fleet, it) }
    return RadioSummary(radios.count { it.kind == RadioKind.Bluetooth }, radios.count { it.kind == RadioKind.WiFi }, given, radios.size - given,
        guestsWithRadios(fleet).size, fleet.nodes.count { it.notes.isNotEmpty() })
}

/** The radio kinds [guest] holds, for a badge in the guest list. Empty when it holds none or was not read. */
fun radioKindsOf(fleet: FleetRadios, guest: ResourceId): Set<RadioKind> =
    radiosOf(fleet, guest)?.let { g -> g.uses.filter { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }.mapNotNull { u -> u.radioId?.let(fleet::radio)?.kind }.toSet() }.orEmpty()

/** "Bluetooth" or "Looks like Wi-Fi". */
fun radioTitle(radio: Radio): String = if (radio.certainty == RadioCertainty.Certain) radio.kind.label else "Looks like " + radio.kind.label

/** Where the adapter is: free on the node, the node's own Wi-Fi, or given to which guests. */
fun radioPlaceText(fleet: FleetRadios, radio: Radio): String {
    val held = usersOf(fleet, radio).filter { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }
    if (held.isNotEmpty()) {
        val names = held.distinctBy { it.guest }.joinToString(", ") { guestLabel(it.guestKind, it.guest.vmid, it.guestName) }
        return if (held.all { it.tie == RadioTie.OneOfSeveral }) "Given by its id to $names (one of several identical adapters)" else "Given to $names"
    }
    if (radio.interfaceName != null && radio.kind == RadioKind.WiFi) return "The node's own Wi-Fi (${radio.interfaceName})"
    return if (radio.listedOnNode) "On the node, not given to a guest" else "Named by a guest; the node did not list it"
}

/** How a guest entry reaches its radio, in plain words. */
fun radioMeansText(use: RadioUse): String {
    val target = entryTarget(use.value)
    return when (use.means) {
        RadioMeans.UsbDevice -> "USB device $target"
        RadioMeans.UsbPort -> "USB port $target"
        RadioMeans.UsbMapping -> "USB mapping $target"
        RadioMeans.PciDevice -> "PCI device $target"
        RadioMeans.PciMapping -> "PCI mapping $target"
        RadioMeans.ContainerDevice -> "Passes a device node ($target)"
        RadioMeans.ContainerUsbBus -> "Every USB device node of the node"
        RadioMeans.ContainerInterface -> "Wi-Fi interface $target"
        RadioMeans.ContainerDbus -> "The node's D-Bus (its Bluetooth service)"
        RadioMeans.ContainerRfkill -> "The radio switch (/dev/rfkill)"
        RadioMeans.ContainerVhci -> "Virtual Bluetooth (/dev/vhci)"
    }
}

private fun entryTarget(value: String): String {
    val first = value.trim().split(' ').first().split(',').first()
    return first.substringAfter('=', first)
}

private val MIDDLE_DOT = Char(0xB7)
fun guestLabel(kind: String, vmid: Int, name: String): String = "$kind $vmid" + if (name.isBlank()) "" else " $MIDDLE_DOT $name"

// ---- node lists --------------------------------------------------------------------------------------------------------

data class NodeUsbDevice(
    val bus: Int, val devnum: Int, val port: Int, val level: Int,
    /** Port path below the root hub ("2.3"); absent on root hubs. */
    val path: String?,
    val deviceClass: Int?, val vendorId: Int?, val productId: Int?,
    val product: String?, val manufacturer: String?, val speed: String?,
) {
    /** "1-2.3", the form a VM's `host=` and a mapping's `path=` use. */
    val address: String? get() = path?.let { "$bus-$it" }
}

data class NodePciDevice(
    val address: String, val classCode: Int?, val vendorId: Int?, val deviceId: Int?,
    val vendorName: String?, val deviceName: String?,
    val subsystemVendorId: Int?, val subsystemDeviceId: Int?, val iommuGroup: Int?,
)

/** A node interface whose name or one of whose altnames starts with `wl`. */
data class NodeRadioInterface(val name: String, val altNames: List<String>, val present: Boolean, val active: Boolean, val type: String)

data class MappingEntry(val node: String, val vendorId: Int?, val productId: Int?, val paths: List<String>, val description: String?)
data class HardwareMapping(val id: String, val description: String?, val entries: List<MappingEntry>, val errors: List<String> = emptyList())

private fun rows(body: String): List<JsonObject> =
    (Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: error("The answer was not a list.")).mapNotNull { it as? JsonObject }
private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.let { it.intOrNull == 1 || it.booleanOrNull == true || it.contentOrNull == "1" } == true
private fun clean(text: String?, max: Int = 120): String? = text?.filter { !it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }

/** "0x8086", "8086" or a number; null when it is not hex. */
internal fun hexId(text: String?): Int? = text?.trim()?.lowercase()?.removePrefix("0x")?.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }?.toInt(16)
internal fun hex4(value: Int): String = value.toString(16).padStart(4, '0')

fun parseNodeUsbDevices(body: String): List<NodeUsbDevice> = rows(body).mapNotNull { row ->
    val bus = row.int("busnum") ?: return@mapNotNull null
    NodeUsbDevice(bus, row.int("devnum") ?: 0, row.int("port") ?: 0, row.int("level") ?: 0,
        row.str("usbpath")?.takeIf { it.matches(usbPortPath) }, usbClass(row["class"]), hexId(row.str("vendid")), hexId(row.str("prodid")),
        clean(row.str("product")), clean(row.str("manufacturer")), clean(row.str("speed"), 16))
}

/** PVE sends bDeviceClass through Perl hex(), so it is a decimal number; a "0x" string is read as hex. */
private fun usbClass(element: JsonElement?): Int? {
    val p = element as? JsonPrimitive ?: return null
    p.intOrNull?.let { return it }
    val text = p.contentOrNull?.trim() ?: return null
    return if (text.startsWith("0x")) hexId(text) else text.toIntOrNull()
}

private val usbPortPath = Regex("[0-9]+([.][0-9]+)*")
private val pciAddress = Regex("([0-9a-f]{4,}:)?[0-9a-f]{2}:[0-9a-f]{2}([.][0-9a-f])?")

fun parseNodePciDevices(body: String): List<NodePciDevice> = rows(body).mapNotNull { row ->
    val id = row.str("id")?.lowercase()?.takeIf { it.matches(pciAddress) } ?: return@mapNotNull null
    NodePciDevice(normalPci(id), hexId(row.str("class")), hexId(row.str("vendor")), hexId(row.str("device")),
        clean(row.str("vendor_name")), clean(row.str("device_name")), hexId(row.str("subsystem_vendor")), hexId(row.str("subsystem_device")),
        row.int("iommugroup")?.takeIf { it >= 0 })
}

/** 01:00.0 becomes 0000:01:00.0. A missing function stays missing: it means every function of the device. */
internal fun normalPci(address: String): String {
    val a = address.trim().lowercase()
    return if (a.count { it == ':' } == 1) "0000:$a" else a
}

fun parseWirelessInterfaces(body: String): List<NodeRadioInterface> = rows(body).mapNotNull { row ->
    val name = row.str("iface") ?: return@mapNotNull null
    val type = row.str("type") ?: "unknown"
    if (type in setOf("bridge", "bond", "vlan", "OVSBridge", "OVSBond", "OVSIntPort", "alias")) return@mapNotNull null
    val alt = (row["altnames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
    if (!name.startsWith("wl") && alt.none { it.startsWith("wl") }) return@mapNotNull null
    NodeRadioInterface(name, alt, row.flag("exists"), row.flag("active"), type)
}.sortedBy { it.name }

fun parseHardwareMappings(body: String): List<HardwareMapping> = rows(body).mapNotNull { row ->
    val id = row.str("id") ?: return@mapNotNull null
    val entries = (row["map"] as? JsonArray).orEmpty().mapNotNull { item ->
        val text = (item as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val props = text.split(',').filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val node = props["node"] ?: return@mapNotNull null
        val ids = props["id"]?.split(':')
        MappingEntry(node, ids?.getOrNull(0)?.let(::hexId), ids?.getOrNull(1)?.let(::hexId),
            props["path"]?.split(';')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty(), props["description"])
    }
    // The schema calls the field "error"; the handler fills "errors".
    val errors = listOf("errors", "error").flatMap { key -> (row[key] as? JsonArray).orEmpty() }
        .mapNotNull { (it as? JsonObject)?.str("message") }
    HardwareMapping(id, row.str("description"), entries, errors)
}

// ---- guest configs -----------------------------------------------------------------------------------------------------

data class GuestRadioEntry(val key: String, val value: String, val change: RadioChange = RadioChange.None)

/** The radio-related entries of one guest config: usbN, hostpciN, devN and raw lxc.* lines. */
data class GuestRadioConfig(val digest: String?, val entries: List<GuestRadioEntry>)

private val radioKey = Regex("(usb|hostpci|dev)[0-9]+")

/**
 * A VM's `GET .../pending` (a list of key, value, pending, delete) or a guest's `GET .../config` (an object; a container's raw
 * lines come as `lxc: [[key, value], ...]`, which `/pending` leaves out). A pending value applies at the next start.
 */
fun parseGuestRadioConfig(body: String): GuestRadioConfig {
    val data = Json.parseToJsonElement(body).jsonObject["data"] ?: error("Guest configuration was missing.")
    val entries = mutableListOf<GuestRadioEntry>()
    var digest: String? = null
    when (data) {
        is JsonArray -> data.mapNotNull { it as? JsonObject }.forEach { row ->
            val key = row.str("key") ?: return@forEach
            if (key == "digest") { digest = row.str("value"); return@forEach }
            if (!key.matches(radioKey)) return@forEach
            val value = row.str("value")
            val pending = row.str("pending")
            val delete = (row["delete"] as? JsonPrimitive)?.let { it.intOrNull in setOf(1, 2) || it.contentOrNull == "true" } == true
            if (value != null) entries += GuestRadioEntry(key, value, if (delete || (pending != null && pending != value)) RadioChange.RemovedAtNextStart else RadioChange.None)
            if (pending != null && pending != value) entries += GuestRadioEntry(key, pending, RadioChange.AtNextStart)
        }
        is JsonObject -> {
            digest = data.str("digest")
            data.entries.filter { it.key.matches(radioKey) }.forEach { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { entries += GuestRadioEntry(key, it) }
            }
            (data["lxc"] as? JsonArray)?.forEach { pair ->
                val kv = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
                if (kv.size == 2 && kv[0].startsWith("lxc.")) entries += GuestRadioEntry(kv[0].trim(), kv[1].trim())
            }
        }
        else -> error("Guest configuration was not readable.")
    }
    // Raw lxc lines keep the order the API gave; the sort is stable.
    return GuestRadioConfig(digest, entries.sortedWith(compareBy({ keyOrder(it.key) }, { if (it.key.startsWith("lxc.")) 0 else it.key.filter(Char::isDigit).toIntOrNull() ?: 0 })))
}

private fun keyOrder(key: String) = listOf("usb", "hostpci", "dev", "lxc").indexOfFirst { key.startsWith(it) }.let { if (it < 0) 9 else it }

// ---- classification ----------------------------------------------------------------------------------------------------

data class RadioGuess(val kind: RadioKind, val certainty: RadioCertainty, val evidence: String, val knownName: String? = null)

private class Known(val kind: RadioKind, val name: String)
private fun bt(name: String) = Known(RadioKind.Bluetooth, name)
private fun wifi(name: String) = Known(RadioKind.WiFi, name)

/** Well-known USB adapter ids. Kept small on purpose: an id here is only "likely", never certain. */
private val knownUsbRadios: Map<String, Known> = mapOf(
    "8087:0025" to bt("Intel Bluetooth"), "8087:0026" to bt("Intel Bluetooth"), "8087:0029" to bt("Intel Bluetooth"),
    "8087:0032" to bt("Intel Bluetooth"), "8087:0033" to bt("Intel Bluetooth"), "8087:0036" to bt("Intel Bluetooth"),
    "8087:07dc" to bt("Intel Bluetooth"), "8087:0a2a" to bt("Intel Bluetooth"), "8087:0a2b" to bt("Intel Bluetooth"),
    "8087:0aaa" to bt("Intel Bluetooth"),
    "0a12:0001" to bt("Cambridge Silicon Radio Bluetooth"),
    "0a5c:21e6" to bt("Broadcom Bluetooth"), "0a5c:21e8" to bt("Broadcom Bluetooth"),
    "0bda:8771" to bt("Realtek Bluetooth"), "0bda:b85b" to bt("Realtek Bluetooth"),
    "2357:0604" to bt("TP-Link UB500 Bluetooth"),
    "0b05:190e" to bt("ASUS USB-BT500 Bluetooth"), "0b05:17cb" to bt("ASUS USB-BT400 Bluetooth"),
    "0e8d:0608" to bt("MediaTek Bluetooth"), "13d3:3563" to bt("MediaTek Bluetooth"),
    "0cf3:3004" to bt("Qualcomm Atheros Bluetooth"), "0cf3:e300" to bt("Qualcomm Atheros Bluetooth"),
    "0bda:8179" to wifi("Realtek RTL8188EUS Wi-Fi"), "0bda:8176" to wifi("Realtek RTL8188CUS Wi-Fi"), "0bda:818b" to wifi("Realtek RTL8192EU Wi-Fi"),
    "0bda:8812" to wifi("Realtek RTL8812AU Wi-Fi"), "0bda:8813" to wifi("Realtek RTL8814AU Wi-Fi"), "0bda:b812" to wifi("Realtek RTL88x2BU Wi-Fi"),
    "0bda:c811" to wifi("Realtek RTL8811CU Wi-Fi"), "0bda:f179" to wifi("Realtek RTL8188FTV Wi-Fi"),
    "148f:2870" to wifi("Ralink RT2870 Wi-Fi"), "148f:3070" to wifi("Ralink RT3070 Wi-Fi"), "148f:5370" to wifi("Ralink RT5370 Wi-Fi"),
    "148f:5572" to wifi("Ralink RT5572 Wi-Fi"), "148f:7601" to wifi("MediaTek MT7601U Wi-Fi"), "148f:761a" to wifi("MediaTek MT7610U Wi-Fi"),
    "0e8d:7612" to wifi("MediaTek MT7612U Wi-Fi"), "0e8d:7961" to wifi("MediaTek MT7921AU Wi-Fi"),
    "0cf3:9271" to wifi("Qualcomm Atheros AR9271 Wi-Fi"), "0cf3:7015" to wifi("Qualcomm Atheros AR7010 Wi-Fi"),
    "2357:010c" to wifi("TP-Link TL-WN722N Wi-Fi"), "2357:0109" to wifi("TP-Link TL-WN823N Wi-Fi"),
    "7392:7811" to wifi("Edimax EW-7811Un Wi-Fi"),
    "0b05:17d2" to wifi("ASUS USB-AC56 Wi-Fi"),
)

/** PCI vendors whose class 0280 devices are wireless network cards (Intel, Realtek, MediaTek, Qualcomm Atheros, Qualcomm, Broadcom, Ralink, Marvell). */
private val wirelessPciVendors = setOf(0x8086, 0x10ec, 0x14c3, 0x168c, 0x17cb, 0x14e4, 0x1814, 0x11ab)

private fun knownUsb(vendorId: Int?, productId: Int?): Known? =
    if (vendorId == null || productId == null) null else knownUsbRadios[hex4(vendorId) + ":" + hex4(productId)]

/** The kind a device name points to, or null. Receivers, mice, headsets and the like are not adapters. */
fun radioKindFromName(text: String?): RadioKind? {
    val t = text?.lowercase() ?: return null
    if (notAdapter.containsMatchIn(t)) return null
    return when {
        btName.containsMatchIn(t) -> RadioKind.Bluetooth
        wifiName.containsMatchIn(t) -> RadioKind.WiFi
        else -> null
    }
}
private val btName = Regex("(^|[^a-z])bluetooth([^a-z]|$)")
private val wifiName = Regex("wi-?fi|(^|[^a-z])wlan([^a-z]|$)|802[.]11|wireless (lan|network|adapter|nic)|wireless-(ac|n)([^a-z]|$)")
private val notAdapter = Regex("receiver|mouse|keyboard|headset|headphone|speaker|presenter|gamepad|earbud")

/**
 * A USB device as a radio, or null. Device class e0 (wireless controller) is Bluetooth in practice, but PVE gives only the
 * device class, not the subclass and protocol, so e0 alone is "likely"; e0 with a known id or a Bluetooth name is certain.
 * Root hubs and hubs are never radios.
 */
fun classifyUsbRadio(deviceClass: Int?, vendorId: Int?, productId: Int?, product: String?, manufacturer: String?): RadioGuess? {
    if (vendorId == 0x1d6b || deviceClass == 0x09) return null
    val known = knownUsb(vendorId, productId)
    val named = radioKindFromName(listOfNotNull(manufacturer, product).joinToString(" "))
    val ids = if (vendorId != null && productId != null) hex4(vendorId) + ":" + hex4(productId) else null
    if (deviceClass == 0xe0 && (known == null || known.kind == RadioKind.Bluetooth)) {
        val sure = known != null || named == RadioKind.Bluetooth
        return RadioGuess(RadioKind.Bluetooth, if (sure) RadioCertainty.Certain else RadioCertainty.Likely,
            "USB class e0 (wireless controller)" + when { known != null -> " and a known Bluetooth id"; named != null -> " and its name"; else -> "" }, known?.name)
    }
    if (known != null) return RadioGuess(known.kind, RadioCertainty.Likely, "Known ${known.kind.label} adapter id $ids", known.name)
    if (named != null) return RadioGuess(named, RadioCertainty.Guess, "Its name says ${named.label}")
    return null
}

/** A PCI device as a radio, or null. Class 0d11 is Bluetooth and 0d20/0d21 are 802.11; Wi-Fi cards report 0280 (other network). */
fun classifyPciRadio(classCode: Int?, vendorId: Int?, name: String?): RadioGuess? {
    val base = classCode?.let { it shr 8 }
    val named = radioKindFromName(name)
    return when {
        base == 0x0d11 -> RadioGuess(RadioKind.Bluetooth, RadioCertainty.Certain, "PCI class 0d11 (Bluetooth)")
        base == 0x0d20 || base == 0x0d21 -> RadioGuess(RadioKind.WiFi, RadioCertainty.Certain, "PCI class ${hex4(base)} (802.11)")
        base == 0x0280 && named == RadioKind.WiFi -> RadioGuess(RadioKind.WiFi, RadioCertainty.Certain, "PCI class 0280 (network, other) and its name")
        base == 0x0280 && vendorId in wirelessPciVendors -> RadioGuess(RadioKind.WiFi, RadioCertainty.Likely, "PCI class 0280 (network, other) from a Wi-Fi maker")
        base == 0x0280 -> RadioGuess(RadioKind.WiFi, RadioCertainty.Guess, "PCI class 0280 (network, other)")
        named != null && (base == null || base shr 8 == 0x02 || base shr 8 == 0x0d) -> RadioGuess(named, RadioCertainty.Guess, "Its name says ${named.label}")
        else -> null
    }
}

/** What a predictable interface name says about where the adapter sits. */
data class InterfacePlace(val pciAddress: String?, val usbPorts: String?, val usb: Boolean)

/**
 * Decodes systemd path names: wlp0s20f3 is PCI 0000:00:14.3, wlP1p2s0 adds domain 1, a `u` part (wlp0s20f0u2u1) is a USB
 * adapter on that controller's ports 2.1. wlx (by MAC) is a USB adapter in practice; wlo (onboard) has no path.
 */
fun interfacePlace(name: String): InterfacePlace? {
    if (name.startsWith("wlx")) return InterfacePlace(null, null, usb = true)
    val m = pathName.matchEntire(name) ?: return null
    val (dom, bus, slot, fn, ports) = m.destructured
    fun hex(text: String, width: Int) = text.ifEmpty { "0" }.toInt().toString(16).padStart(width, '0')
    val address = hex(dom, 4) + ":" + hex(bus, 2) + ":" + hex(slot, 2) + "." + hex(fn, 1)
    val usb = ports.split('u').filter { it.isNotEmpty() }.joinToString(".").ifEmpty { null }
    return InterfacePlace(address, usb, usb != null)
}
private val pathName = Regex("wl(?:P([0-9]+))?p([0-9]+)s([0-9]+)(?:f([0-9]+))?((?:u[0-9]+)*)(?:c[0-9]+)?(?:i[0-9]+)?(?:[nd][0-9]+)?")

// ---- assembly ----------------------------------------------------------------------------------------------------------

/** One list read: its value, or the sentence that says why it is missing. */
data class RadioSection<T>(val value: T? = null, val error: String? = null)

data class NodeRadioInputs(
    val node: String,
    val online: Boolean = true,
    val usb: RadioSection<List<NodeUsbDevice>> = RadioSection(),
    val pci: RadioSection<List<NodePciDevice>> = RadioSection(),
    val network: RadioSection<List<NodeRadioInterface>> = RadioSection(),
)

data class GuestRadioInput(val guest: Guest, val config: GuestRadioConfig? = null, val error: String? = null)

data class FleetRadioInputs(
    val remote: String,
    val nodes: List<NodeRadioInputs>,
    val guests: List<GuestRadioInput>,
    val usbMappings: RadioSection<List<HardwareMapping>> = RadioSection(emptyList()),
    val pciMappings: RadioSection<List<HardwareMapping>> = RadioSection(emptyList()),
)

const val RADIOS_NEED_DIRECT = "Radios need a direct Proxmox VE connection. A PDM connection cannot read node hardware."
const val RADIOS_NODE_OFFLINE = "The node is offline."
const val RADIOS_USB_REFUSED = "This token may not list USB devices (it needs Sys.Modify on /). USB adapters show only where a guest names them."
const val RADIOS_PCI_REFUSED = "This token may not list PCI devices (it needs Sys.Audit on /)."
const val RADIOS_GUEST_REFUSED = "Not permitted for this token"
const val RADIOS_COMBO_HINT = "Wi-Fi cards like this one usually carry Bluetooth on USB as well. This token cannot list USB devices, so it is not shown."
const val RADIOS_MAPPINGS_HIDDEN = "Mappings are listed only where this token holds Mapping.Audit."

private class NodeBuild(val inputs: NodeRadioInputs, val remote: String) {
    val radios = linkedMapOf<String, Radio>()
    val notes = mutableListOf<String>()
    val usb get() = inputs.usb.value
    val pci get() = inputs.pci.value
    fun id(local: String) = "$remote|${inputs.node}|$local"
}

/** Builds the fleet's radios from what was read. Pure: the live reader and the tests both end here. */
fun assembleFleetRadios(inputs: FleetRadioInputs, readAt: Instant? = null, calls: Int = 0, elapsedMillis: Long = 0): FleetRadios {
    val builds = inputs.nodes.associate { it.node to NodeBuild(it, inputs.remote) }
    builds.values.forEach { buildNode(it) }
    val guests = inputs.guests.sortedBy { it.guest.id }.map { input ->
        val build = builds[input.guest.node]
        val config = input.config
        val base = GuestRadios(input.guest.resource, input.guest.name, input.guest.kind, input.guest.node, input.guest.status == "running")
        when {
            config == null -> base.copy(note = input.error ?: "Not read")
            build == null -> base.copy(note = "Its node was not read.")
            else -> guestRadios(base, config, build, inputs)
        }
    }
    val fleetNotes = buildList {
        val usesMapping = inputs.guests.any { g -> g.config?.entries?.any { it.value.contains("mapping=") } == true }
        if (usesMapping && (inputs.usbMappings.value.isNullOrEmpty() || inputs.pciMappings.value.isNullOrEmpty())) add(RADIOS_MAPPINGS_HIDDEN)
    }
    return FleetRadios(inputs.remote,
        builds.values.map { b ->
            NodeRadios(b.inputs.node, b.radios.values.sortedWith(compareBy({ it.kind.ordinal }, { it.bus?.ordinal ?: 9 }, { it.address ?: it.interfaceName ?: it.id })),
                b.notes.distinct(), b.usb != null, b.pci != null, b.inputs.network.value != null)
        }.sortedBy { it.node },
        guests, notes = fleetNotes, readAt = readAt, calls = calls, elapsedMillis = elapsedMillis)
}

private fun buildNode(b: NodeBuild) {
    val inputs = b.inputs
    if (!inputs.online) { b.notes += RADIOS_NODE_OFFLINE; return }
    inputs.usb.error?.let { b.notes += it }
    inputs.pci.error?.let { b.notes += it }
    inputs.network.error?.let { b.notes += it }
    b.usb.orEmpty().forEach { d ->
        val address = d.address ?: return@forEach
        val guess = classifyUsbRadio(d.deviceClass, d.vendorId, d.productId, d.product, d.manufacturer) ?: return@forEach
        b.radios[b.id("usb:$address")] = Radio(b.id("usb:$address"), b.remote, inputs.node, guess.kind, RadioBus.Usb,
            d.product ?: guess.knownName ?: "USB device " + usbIds(d.vendorId, d.productId), guess.certainty, guess.evidence,
            d.manufacturer, d.vendorId, d.productId, address)
    }
    b.pci.orEmpty().forEach { d ->
        val guess = classifyPciRadio(d.classCode, d.vendorId, d.deviceName) ?: return@forEach
        b.radios[b.id("pci:${d.address}")] = Radio(b.id("pci:${d.address}"), b.remote, inputs.node, guess.kind, RadioBus.Pci,
            d.deviceName ?: "PCI device " + usbIds(d.vendorId, d.deviceId), guess.certainty, guess.evidence,
            d.vendorName, d.vendorId, d.deviceId, d.address)
    }
    val present = inputs.network.value.orEmpty().filter { it.present }
    val untied = mutableListOf<NodeRadioInterface>()
    present.forEach { nic ->
        val names = listOf(nic.name) + nic.altNames
        val place = names.firstNotNullOfOrNull { n -> interfacePlace(n)?.takeIf { it.pciAddress != null } }
        val pciAddress = place?.pciAddress
        if (pciAddress != null && place.usbPorts == null) {
            val id = b.id("pci:$pciAddress")
            val known = b.radios[id]
            val device = b.pci?.firstOrNull { it.address == pciAddress }
            b.radios[id] = when {
                known != null -> known.copy(interfaceName = nic.name, kind = RadioKind.WiFi,
                    certainty = RadioCertainty.Certain, evidence = known.evidence + "; the node names its interface ${nic.name}")
                device != null -> Radio(id, b.remote, inputs.node, RadioKind.WiFi, RadioBus.Pci, device.deviceName ?: "PCI device " + usbIds(device.vendorId, device.deviceId),
                    RadioCertainty.Certain, "The node names its interface ${nic.name}", device.vendorName, device.vendorId, device.deviceId, pciAddress, nic.name)
                else -> Radio(id, b.remote, inputs.node, RadioKind.WiFi, RadioBus.Pci, "Wi-Fi interface ${nic.name}",
                    RadioCertainty.Likely, "The node names its interface ${nic.name}", address = pciAddress, interfaceName = nic.name)
            }
            return@forEach
        }
        val ports = place?.usbPorts
        if (ports != null) {
            val match = b.usb.orEmpty().filter { it.path == ports && it.deviceClass != 0x09 }.singleOrNull()
            if (match?.address != null) {
                val id = b.id("usb:${match.address}")
                val known = b.radios[id]
                b.radios[id] = known?.copy(interfaceName = nic.name, kind = RadioKind.WiFi, evidence = known.evidence + "; the node names its interface ${nic.name}")
                    ?: Radio(id, b.remote, inputs.node, RadioKind.WiFi, RadioBus.Usb, match.product ?: "USB device " + usbIds(match.vendorId, match.productId),
                        RadioCertainty.Likely, "The node names its interface ${nic.name}", match.manufacturer, match.vendorId, match.productId, match.address, nic.name)
                return@forEach
            }
        }
        untied += nic
    }
    // A lone onboard name (wlo1) with a lone Wi-Fi card, or a lone wlx name with a lone USB Wi-Fi adapter, belong together.
    fun pairLone(nics: List<NodeRadioInterface>, bus: RadioBus) {
        val free = b.radios.values.filter { it.kind == RadioKind.WiFi && it.bus == bus && it.interfaceName == null && it.listedOnNode }
        if (nics.size == 1 && free.size == 1) {
            val r = free.single()
            b.radios[r.id] = r.copy(interfaceName = nics.single().name, evidence = r.evidence + "; the node's only matching interface is ${nics.single().name}")
            untied -= nics.single()
        }
    }
    pairLone(untied.filter { it.name.startsWith("wlo") }, RadioBus.Pci)
    pairLone(untied.filter { interfacePlace(it.name)?.usb == true }, RadioBus.Usb)
    untied.forEach { nic ->
        val usb = interfacePlace(nic.name)?.usb == true
        val id = b.id("net:${nic.name}")
        b.radios[id] = Radio(id, b.remote, inputs.node, RadioKind.WiFi, if (usb) RadioBus.Usb else if (nic.name.startsWith("wlo")) RadioBus.Pci else null,
            "Wi-Fi interface ${nic.name}", RadioCertainty.Likely, "The node names its interface ${nic.name}; names starting with wl are Wi-Fi", interfaceName = nic.name)
    }
    // Whether the node itself has each adapter's interface up now.
    val up = present.associate { it.name to it.active }
    b.radios.replaceAll { _, r -> r.interfaceName?.let { name -> up[name] }?.let { r.copy(interfaceActive = it) } ?: r }
    if (b.usb == null && inputs.usb.error != null && b.radios.values.any { it.kind == RadioKind.WiFi && it.bus == RadioBus.Pci && it.listedOnNode }) b.notes += RADIOS_COMBO_HINT
}

private fun usbIds(vendorId: Int?, productId: Int?) = if (vendorId != null && productId != null) hex4(vendorId) + ":" + hex4(productId) else "(no id)"

private fun guestRadios(base: GuestRadios, config: GuestRadioConfig, b: NodeBuild, inputs: FleetRadioInputs): GuestRadios {
    val uses = mutableListOf<RadioUse>()
    val others = mutableListOf<RadioUse>()
    val spice = mutableListOf<String>()
    val vm = base.kind == "VM"
    fun use(entry: GuestRadioEntry, means: RadioMeans, radioId: String?, tie: RadioTie, note: String? = null) =
        RadioUse(base.guest, base.name, base.kind, base.node, base.running, entry.key, entry.value, means, radioId, tie, entry.change, note)
    fun usbMatches(entry: GuestRadioEntry, means: RadioMeans, devices: List<NodeUsbDevice>, exactAddress: Boolean, vendorId: Int?, productId: Int?, missing: String) {
        if (b.usb == null) {
            val guess = classifyUsbRadio(null, vendorId, productId, null, null)
            if (guess != null && vendorId != null && productId != null) {
                val id = b.id("usb-id:" + hex4(vendorId) + ":" + hex4(productId))
                b.radios.getOrPut(id) {
                    Radio(id, b.remote, base.node, guess.kind, RadioBus.Usb, guess.knownName ?: "USB device " + usbIds(vendorId, productId), guess.certainty,
                        guess.evidence, vendorId = vendorId, productId = productId, listedOnNode = false)
                }
                uses += use(entry, means, id, RadioTie.Exact, "Known only from this guest's config; this token cannot list USB devices.")
            } else others += use(entry, means, null, RadioTie.Unresolved, "This token cannot list USB devices to tell what it is.")
            return
        }
        if (devices.isEmpty()) {
            val guess = classifyUsbRadio(null, vendorId, productId, null, null)
            if (guess != null && vendorId != null && productId != null) {
                val id = b.id("usb-id:" + hex4(vendorId) + ":" + hex4(productId))
                b.radios.getOrPut(id) {
                    Radio(id, b.remote, base.node, guess.kind, RadioBus.Usb, guess.knownName ?: "USB device " + usbIds(vendorId, productId), guess.certainty,
                        guess.evidence, vendorId = vendorId, productId = productId, listedOnNode = false)
                }
                uses += use(entry, means, id, RadioTie.Exact, missing)
            } else others += use(entry, means, null, RadioTie.Unresolved, missing)
            return
        }
        val tie = if (exactAddress || devices.size == 1) RadioTie.Exact else RadioTie.OneOfSeveral
        devices.forEach { d ->
            val id = d.address?.let { b.id("usb:$it") }
            if (id != null && b.radios[id] != null) uses += use(entry, means, id, tie)
            else others += use(entry, means, null, tie, d.product ?: ("USB device " + usbIds(d.vendorId, d.productId)))
        }
    }
    fun pciMatches(entry: GuestRadioEntry, means: RadioMeans, paths: List<String>) {
        val tie = if (paths.size > 1) RadioTie.OneOfSeveral else RadioTie.Exact
        var found = false
        paths.map(::normalPci).forEach { path ->
            val all = '.' !in path.substringAfterLast(':')
            val matching = b.radios.values.filter { r -> r.bus == RadioBus.Pci && r.address != null && (r.address == path || (all && r.address.startsWith("$path."))) }
            matching.forEach { uses += use(entry, means, it.id, tie); found = true }
        }
        if (!found) others += use(entry, means, null, if (b.pci == null) RadioTie.Unresolved else tie,
            if (b.pci == null) "This token cannot list PCI devices to tell what it is." else null)
    }
    config.entries.forEach { entry ->
        val parts = entry.value.split(',').map { it.trim() }
        val props = parts.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val bare = parts.firstOrNull()?.takeIf { '=' !in it }
        when {
            vm && entry.key.startsWith("usb") -> {
                val host = props["host"] ?: bare
                val mapping = props["mapping"]
                when {
                    host != null && host.equals("spice", ignoreCase = true) -> spice += entry.key
                    host != null && usbIdPattern.matches(host) -> {
                        val (v, p) = host.lowercase().split(':').map { hexId(it)!! }
                        usbMatches(entry, RadioMeans.UsbDevice, b.usb.orEmpty().filter { it.vendorId == v && it.productId == p && it.address != null }, false, v, p,
                            "Not plugged into the node now.")
                    }
                    host != null && usbPortPattern.matches(host) -> {
                        val device = b.usb.orEmpty().filter { it.address == host }
                        if (b.usb != null && device.isEmpty()) others += use(entry, RadioMeans.UsbPort, null, RadioTie.Unresolved, "Nothing is plugged into that port now.")
                        else usbMatches(entry, RadioMeans.UsbPort, device, true, device.firstOrNull()?.vendorId, device.firstOrNull()?.productId, "")
                    }
                    mapping != null -> {
                        val map = inputs.usbMappings.value?.firstOrNull { it.id == mapping }
                        val here = map?.entries?.firstOrNull { it.node == base.node }
                        when {
                            map == null -> others += use(entry, RadioMeans.UsbMapping, null, RadioTie.Unresolved,
                                "Uses mapping $mapping, which this token cannot read (Mapping.Audit on /mapping/usb/$mapping).")
                            here == null -> others += use(entry, RadioMeans.UsbMapping, null, RadioTie.Unresolved, "Mapping $mapping has no entry for this node.")
                            else -> {
                                val byPath = here.paths.firstOrNull()
                                val devices = b.usb.orEmpty().filter { d ->
                                    if (byPath != null) d.address == byPath else d.vendorId == here.vendorId && d.productId == here.productId && d.address != null
                                }
                                usbMatches(entry, RadioMeans.UsbMapping, devices, byPath != null, here.vendorId, here.productId, "The mapped device is not plugged in now.")
                            }
                        }
                    }
                }
            }
            vm && entry.key.startsWith("hostpci") -> {
                val mapping = props["mapping"]
                if (mapping != null) {
                    val map = inputs.pciMappings.value?.firstOrNull { it.id == mapping }
                    val here = map?.entries?.filter { it.node == base.node }.orEmpty()
                    when {
                        map == null -> others += use(entry, RadioMeans.PciMapping, null, RadioTie.Unresolved,
                            "Uses mapping $mapping, which this token cannot read (Mapping.Audit on /mapping/pci/$mapping).")
                        here.isEmpty() -> others += use(entry, RadioMeans.PciMapping, null, RadioTie.Unresolved, "Mapping $mapping has no entry for this node.")
                        else -> pciMatches(entry, RadioMeans.PciMapping, here.flatMap { it.paths })
                    }
                } else (props["host"] ?: bare)?.let { host -> pciMatches(entry, RadioMeans.PciDevice, host.split(';').map { it.trim() }.filter { it.isNotEmpty() }) }
            }
            !vm && entry.key.startsWith("dev") -> containerDevice(entry, props["path"] ?: bare, b, uses, others, ::use)
            !vm && entry.key.startsWith("lxc.") -> Unit
        }
    }
    if (!vm) containerRaw(config.entries.filter { it.key.startsWith("lxc.") }, b, uses, others, ::use)
    val radios = uses.mapNotNull { it.radioId }.distinct().mapNotNull { b.radios[it] }
    return base.copy(uses = uses, radios = radios, spiceSlots = spice, others = others)
}

private val usbIdPattern = Regex("(0x)?[0-9a-fA-F]{4}:(0x)?[0-9a-fA-F]{4}")
private val usbPortPattern = Regex("[0-9]+-[0-9]+([.][0-9]+)*")
private val usbDeviceNode = Regex("/dev/bus/usb/([0-9]{1,3})/([0-9]{1,3})")

private typealias UseMaker = (GuestRadioEntry, RadioMeans, String?, RadioTie, String?) -> RadioUse

private fun containerDevice(entry: GuestRadioEntry, path: String?, b: NodeBuild, uses: MutableList<RadioUse>, others: MutableList<RadioUse>, use: UseMaker) {
    path ?: return
    val node = usbDeviceNode.matchEntire(path)
    when {
        node != null -> {
            val (bus, dev) = node.destructured
            if (b.usb == null) { others += use(entry, RadioMeans.ContainerDevice, null, RadioTie.Unresolved, "Passes a device node; this token cannot list USB devices to tell what it is."); return }
            val device = b.usb.orEmpty().firstOrNull { it.bus == bus.toInt() && it.devnum == dev.toInt() }
            val id = device?.address?.let { b.id("usb:$it") }
            when {
                device == null -> others += use(entry, RadioMeans.ContainerDevice, null, RadioTie.Unresolved, "Passes a device node that no USB device has now.")
                id != null && b.radios[id] != null -> uses += use(entry, RadioMeans.ContainerDevice, id, RadioTie.Exact,
                    "Matches while the device keeps its number; the number changes when it is plugged in again.")
                else -> others += use(entry, RadioMeans.ContainerDevice, null, RadioTie.Exact, device.product ?: ("USB device " + usbIds(device.vendorId, device.productId)))
            }
        }
        path == "/dev/bus/usb" || path.startsWith("/dev/bus/usb/") -> broadUsb(entry, b, uses, use, "Passes USB device nodes")
        path == "/dev/rfkill" -> uses += use(entry, RadioMeans.ContainerRfkill, null, RadioTie.Unresolved, null)
        path == "/dev/vhci" -> uses += use(entry, RadioMeans.ContainerVhci, null, RadioTie.Unresolved, null)
    }
}

private fun broadUsb(entry: GuestRadioEntry, b: NodeBuild, uses: MutableList<RadioUse>, use: UseMaker, what: String) {
    val usbRadios = b.radios.values.filter { it.bus == RadioBus.Usb && it.listedOnNode && it.address != null }
    if (usbRadios.isEmpty()) uses += use(entry, RadioMeans.ContainerUsbBus, null, RadioTie.Unresolved,
        if (b.usb == null) "$what; this token cannot list USB devices to tell which radios that reaches." else "$what; the node has no USB radio now.")
    else usbRadios.forEach { uses += use(entry, RadioMeans.ContainerUsbBus, it.id, RadioTie.Possible, what) }
}

private fun containerRaw(lines: List<GuestRadioEntry>, b: NodeBuild, uses: MutableList<RadioUse>, others: MutableList<RadioUse>, use: UseMaker) {
    val nets = mutableMapOf<String, MutableMap<String, GuestRadioEntry>>()
    lines.forEach { entry ->
        val key = entry.key
        val value = entry.value.trim()
        when {
            key == "lxc.cgroup2.devices.allow" || key == "lxc.cgroup.devices.allow" -> {
                val rule = value.split(' ').filter { it.isNotEmpty() }
                val (major, minor) = rule.getOrNull(1)?.split(':')?.let { it.getOrNull(0) to it.getOrNull(1) } ?: (null to null)
                when {
                    rule.firstOrNull() == "c" && major == "189" -> broadUsb(entry, b, uses, use, "Allows USB device nodes")
                    rule.firstOrNull() == "c" && major == "10" && minor == "242" -> uses += use(entry, RadioMeans.ContainerRfkill, null, RadioTie.Unresolved, null)
                    rule.firstOrNull() == "c" && major == "10" && minor == "137" -> uses += use(entry, RadioMeans.ContainerVhci, null, RadioTie.Unresolved, null)
                }
            }
            key == "lxc.mount.entry" -> {
                val source = value.split(' ').firstOrNull { it.isNotEmpty() } ?: return@forEach
                when {
                    source == "/run/dbus" || source == "/var/run/dbus" || source.startsWith("/run/dbus/") || source.startsWith("/var/run/dbus/") -> {
                        val bt = b.radios.values.filter { it.kind == RadioKind.Bluetooth && it.listedOnNode }
                        if (bt.isEmpty()) uses += use(entry, RadioMeans.ContainerDbus, null, RadioTie.Unresolved,
                            if (b.usb == null) "This token cannot list USB devices to tell which Bluetooth that reaches." else "The node has no Bluetooth adapter now.")
                        else bt.forEach { uses += use(entry, RadioMeans.ContainerDbus, it.id, RadioTie.Possible, null) }
                    }
                    else -> containerDevice(entry, source, b, uses, others, use)
                }
            }
            key.matches(lxcNetKey) -> {
                val (index, field) = lxcNetKey.matchEntire(key)!!.destructured
                nets.getOrPut(index) { mutableMapOf() }[field] = entry
            }
        }
    }
    nets.values.forEach { net ->
        if (net["type"]?.value?.trim() != "phys") return@forEach
        val link = net["link"] ?: return@forEach
        val name = link.value.trim()
        val interfaces = b.inputs.network.value.orEmpty()
        val known = interfaces.firstOrNull { it.name == name || name in it.altNames }
        val wireless = name.startsWith("wl") || known != null
        if (!wireless) return@forEach
        val place = (listOf(name) + known?.altNames.orEmpty()).firstNotNullOfOrNull { n -> interfacePlace(n)?.takeIf { it.pciAddress != null && it.usbPorts == null } }
        val byName = b.radios.values.firstOrNull { it.interfaceName == name || (known != null && it.interfaceName == known.name) }
        val byPath = place?.pciAddress?.let { b.radios[b.id("pci:$it")] }
        val radio = byName ?: byPath ?: run {
            val id = b.id("net:$name")
            b.radios.getOrPut(id) {
                Radio(id, b.remote, b.inputs.node, RadioKind.WiFi, if (interfacePlace(name)?.usb == true) RadioBus.Usb else null, "Wi-Fi interface $name",
                    RadioCertainty.Likely, "A container takes the interface $name; names starting with wl are Wi-Fi", listedOnNode = false)
            }
        }
        uses += use(link, RadioMeans.ContainerInterface, radio.id, RadioTie.Exact, null)
    }
}
private val lxcNetKey = Regex("lxc[.]net[.]([0-9]+)[.](type|link)")

// ---- reading -----------------------------------------------------------------------------------------------------------

/** At most this many radio reads run at once. pveproxy starts three workers, and the rest of the app shares them. */
const val RADIO_READ_CONCURRENCY = 3
/** A guest config read within this time is reused unless the guest's status changed or a refresh is forced. */
val RADIO_GUEST_CONFIG_MAX_AGE: Duration = Duration.ofMinutes(5)
/** The slow cadence for an open Radios view. Never tie these reads to the inventory poll. */
val RADIO_REFRESH_CADENCE: Duration = Duration.ofMinutes(5)

/**
 * The fleet's radios for one session. Read when a Radios view opens, on [RADIO_REFRESH_CADENCE] while it stays open, and on
 * Refresh; never on every inventory poll. Every call is a GET.
 */
interface FleetRadioSource {
    suspend fun read(connection: Connection?, overview: Overview, force: Boolean = false): FleetRadios
    /** Re-reads one guest's config (after the user opens it or changes it) and returns [fleet] with that guest updated. */
    suspend fun readGuest(connection: Connection?, guest: Guest, fleet: FleetRadios): FleetRadios
}

/** One GET of a direct PVE path, as the API answered it. Throws [GuestPermissionException] on 403. */
fun interface RadioWire { fun get(connection: Connection, path: String): String }

internal class TransportRadioWire(private val http: ProxmoxTransport = ProxmoxTransport()) : RadioWire {
    override fun get(connection: Connection, path: String): String = Trace.asking { http.direct(connection, path, missing = "The server did not find $path.") }
}

internal fun radioNodePath(node: String, suffix: String): String =
    "/api2/json/nodes/" + encodePathSegment(pveNodeNameOrNull(node) ?: throw IllegalArgumentException("The node name cannot be requested.")) + suffix
internal const val USB_MAPPINGS_PATH = "/api2/json/cluster/mapping/usb"
internal const val PCI_MAPPINGS_PATH = "/api2/json/cluster/mapping/pci"
/** A VM's pending view carries what applies at the next start; a container's raw lxc lines are only in its config. */
internal fun radioGuestPath(connection: Connection, guest: Guest): String =
    boundGuestPath(connection, guest).removeSuffix("/status/current") + if (guest.kind == "VM") "/pending" else "/config"

class LiveFleetRadioSource internal constructor(
    private val wire: RadioWire,
    private val clock: () -> Instant,
    private val concurrency: Int,
) : FleetRadioSource {
    constructor() : this(TransportRadioWire(), Instant::now, RADIO_READ_CONCURRENCY)

    private class Cached(val status: String, val readAt: Instant, val config: GuestRadioConfig)
    private val cache = ConcurrentHashMap<ResourceId, Cached>()
    @Volatile private var last: FleetRadioInputs? = null

    override suspend fun read(connection: Connection?, overview: Overview, force: Boolean): FleetRadios {
        val remote = connection?.remoteKey.orEmpty()
        if (connection == null || connection.kind != DIRECT_PVE_KIND) return FleetRadios(remote, unavailable = RADIOS_NEED_DIRECT)
        val started = System.nanoTime()
        val calls = AtomicInteger()
        val gate = Semaphore(concurrency)
        val guests = overview.guests.filter { it.remote == remote && it.scope.isEmpty() }
        val members = overview.members.filter { it.remote == remote }
        val nodes = if (members.isNotEmpty()) members.map { it.name to (it.status == "online") } else guests.map { it.node }.distinct().map { it to true }
        cache.keys.retainAll(guests.map { it.resource }.toSet())
        val inputs = coroutineScope {
            val usbMaps = async { section(gate, calls, null) { parseHardwareMappings(wire.get(connection, USB_MAPPINGS_PATH)) } }
            val pciMaps = async { section(gate, calls, null) { parseHardwareMappings(wire.get(connection, PCI_MAPPINGS_PATH)) } }
            val nodeReads = nodes.map { (node, online) ->
                async {
                    if (!online) NodeRadioInputs(node, online = false) else {
                        val usb = async { section(gate, calls, RADIOS_USB_REFUSED) { parseNodeUsbDevices(wire.get(connection, radioNodePath(node, "/hardware/usb"))) } }
                        val pci = async { section(gate, calls, RADIOS_PCI_REFUSED) { parseNodePciDevices(wire.get(connection, radioNodePath(node, "/hardware/pci"))) } }
                        val net = async { section(gate, calls, null) { parseWirelessInterfaces(wire.get(connection, radioNodePath(node, "/network"))) } }
                        NodeRadioInputs(node, true, usb.await(), pci.await(), net.await())
                    }
                }
            }
            val now = clock()
            val guestReads = guests.map { guest ->
                async {
                    val cached = cache[guest.resource]
                    if (!force && cached != null && cached.status == guest.status && Duration.between(cached.readAt, now) < RADIO_GUEST_CONFIG_MAX_AGE)
                        GuestRadioInput(guest, cached.config)
                    else readGuestInput(connection, guest, gate, calls)
                }
            }
            FleetRadioInputs(remote, nodeReads.awaitAll(), guestReads.awaitAll(), usbMaps.await(), pciMaps.await())
        }
        last = inputs
        return assembleFleetRadios(inputs, clock(), calls.get(), (System.nanoTime() - started) / 1_000_000)
    }

    override suspend fun readGuest(connection: Connection?, guest: Guest, fleet: FleetRadios): FleetRadios {
        val previous = last
        if (connection == null || connection.kind != DIRECT_PVE_KIND || previous == null || previous.remote != connection.remoteKey) return fleet
        val started = System.nanoTime()
        val calls = AtomicInteger()
        val input = readGuestInput(connection, guest, Semaphore(1), calls)
        val inputs = previous.copy(guests = previous.guests.filter { it.guest.resource != guest.resource } + input)
        last = inputs
        return assembleFleetRadios(inputs, clock(), calls.get(), (System.nanoTime() - started) / 1_000_000)
    }

    private suspend fun readGuestInput(connection: Connection, guest: Guest, gate: Semaphore, calls: AtomicInteger): GuestRadioInput {
        val result = section(gate, calls, RADIOS_GUEST_REFUSED) { parseGuestRadioConfig(wire.get(connection, radioGuestPath(connection, guest))) }
        val config = result.value ?: return GuestRadioInput(guest, error = result.error)
        cache[guest.resource] = Cached(guest.status, clock(), config)
        return GuestRadioInput(guest, config)
    }

    private suspend fun <T> section(gate: Semaphore, calls: AtomicInteger, refused: String?, block: () -> T): RadioSection<T> = gate.withPermit {
        calls.incrementAndGet()
        try { RadioSection(runInterruptible(Dispatchers.IO) { block() }) }
        catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: GuestPermissionException) { RadioSection(error = refused ?: RADIOS_GUEST_REFUSED) }
        catch (e: Exception) { RadioSection(error = e.message ?: "Unavailable") }
    }
}
