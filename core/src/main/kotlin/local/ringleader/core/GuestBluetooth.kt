package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.time.Instant

/** How a USB port on a VM is fed: a SPICE redirection channel, a host device, or a PVE resource mapping. */
enum class UsbKind { Spice, Host, Mapping, Unknown }

/**
 * One `usbN` entry from a VM config. A Bluetooth adapter reaches a VM either as a host device / mapping (an adapter
 * plugged into the PVE node) or through a SPICE channel (an adapter on the computer running the console).
 */
data class UsbAttachment(val key: String, val kind: UsbKind, val target: String?, val usb3: Boolean) {
    private val ids = target?.let { Regex("^([0-9a-fA-F]{4}):([0-9a-fA-F]{4})$").find(it) }
    val vendorId: Int? get() = ids?.groupValues?.get(1)?.toInt(16)
    val productId: Int? get() = ids?.groupValues?.get(2)?.toInt(16)
}

/** `usb0`…`usb14` from a config `data` object; `spice`, `host=vvvv:pppp`, `host=1-2`, `mapping=id`, with `usb3=1`. */
fun parseUsbAttachments(row: JsonObject): List<UsbAttachment> = row.entries.filter { it.key.matches(Regex("usb[0-9]+")) }.mapNotNull { (key, value) ->
    val raw = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val parts = raw.split(',').map { it.trim() }
    val props = parts.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    val bare = parts.firstOrNull()?.takeIf { '=' !in it }
    val kind = when { bare == "spice" || props["host"] == "spice" -> UsbKind.Spice; props["mapping"] != null -> UsbKind.Mapping; props["host"] != null || bare != null -> UsbKind.Host; else -> UsbKind.Unknown }
    UsbAttachment(key, kind, when (kind) { UsbKind.Mapping -> props["mapping"]; UsbKind.Host -> props["host"] ?: bare; else -> null }, props["usb3"] == "1" || props["usb3"] == "true")
}.sortedBy { it.key.removePrefix("usb").toIntOrNull() ?: 99 }

const val GUEST_BT_NOT_RUNNING = "Start the VM to scan inside it."
const val GUEST_BT_NO_PERMISSION = "This token cannot run commands in the guest (needs VM.GuestAgent.Unrestricted on the VM)."
const val GUEST_BT_AGENT = "The QEMU guest agent did not answer. It must be installed and running inside the VM."
const val GUEST_BT_TIMEOUT = "The scan inside the VM did not finish in time."

data class AgentExecStatus(val exited: Boolean, val exitCode: Int?, val out: String, val err: String, val truncated: Boolean)

internal fun agentExecBody(command: List<String>): ByteArray = buildJsonObject { putJsonArray("command") { command.forEach { add(it) } } }.toString().toByteArray(Charsets.UTF_8)
fun parseAgentExecPid(body: String): Long =
    ((Json.parseToJsonElement(body).jsonObject["data"] as? JsonObject)?.get("pid") as? JsonPrimitive)?.longOrNull ?: error(GUEST_BT_AGENT)
fun parseAgentExecStatus(body: String): AgentExecStatus {
    val d = Json.parseToJsonElement(body).jsonObject["data"] as? JsonObject ?: error(GUEST_BT_AGENT)
    fun s(k: String) = (d[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun b(k: String) = (d[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull?.let { n -> n != 0 }) } ?: false
    return AgentExecStatus(b("exited"), (d["exitcode"] as? JsonPrimitive)?.intOrNull, s("out-data"), s("err-data"), b("out-truncated"))
}

fun guestBluetoothSource(guest: Guest) = BtSource(guest.resource.toString(), "${guest.kind} ${guest.id} · ${guest.name.ifBlank { "guest" }}")

interface GuestBluetoothGateway {
    suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan
}

class LiveGuestBluetoothGateway(private val client: PveClient = PveClient()) : GuestBluetoothGateway {
    override suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan = runInterruptible(Dispatchers.IO) { client.guestBluetoothScan(connection, guest, seconds) }
}

/** Sample scan for demo mode, so the panel can be explored without a server. */
fun demoGuestBluetoothScan(guest: Guest) = BtScan(guestBluetoothSource(guest),
    listOf(BtAdapter("hci0", "${guest.name}-bt", "02:00:00:00:00:10", powered = true)),
    listOf(BtDevice("02:00:00:00:01:01", "Sample headset", -58, paired = true, connected = false, kind = BtKind.Classic, category = "audio-headset", adapterId = "hci0"),
        BtDevice("02:00:00:00:01:02", null, -81, kind = BtKind.LowEnergy, adapterId = "hci0")),
    Instant.now(), "Sample data")
