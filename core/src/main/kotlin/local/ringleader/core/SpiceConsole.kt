package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

const val SPICE_NOT_VM = "SPICE consoles are for QEMU VMs."
const val SPICE_NOT_RUNNING = "Start the VM to open its console."
const val SPICE_DEMO = "Consoles are not available with sample data."
const val SPICE_NO_PERMISSION = "This token cannot open consoles (needs VM.Console on the VM)."
const val SPICE_REQUEST_FAILED = "The server did not issue a SPICE ticket. The VM must be running with a SPICE display."
const val SPICE_BAD_TICKET = "The server returned an unusable SPICE ticket."

/**
 * One short-lived SPICE ticket from `spiceproxy`, ready for remote-viewer. It holds a one-time password,
 * so [toString] never prints the fields and the viewer file is written only by the launcher.
 */
class SpiceTicket internal constructor(val title: String, val proxy: String, private val fields: List<Pair<String, String>>) {
    /**
     * remote-viewer `.vv` file text. PVE already escapes newlines in the CA certificate as `\n`. `delete-this-file=1`
     * is added when PVE leaves it out, so remote-viewer removes the password file as soon as it has read it.
     */
    fun viewerFile(): String = buildString {
        append("[virt-viewer]\n")
        fields.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
        if (fields.none { it.first == "delete-this-file" }) append("delete-this-file=1\n")
    }
    /** True when [text] contains the one-time password, the proxy ticket in `host`, or another secret field. */
    fun mentionsSecret(text: String): Boolean = fields.any { (key, value) ->
        value.length >= 4 && (Redact.key(key) || key == "host") && value in text
    }
    override fun toString() = "SpiceTicket($title via $proxy)"
}

/** `vga` from a VM config body; null when the key is absent (standard VGA). */
fun spiceDisplayType(configBody: String): String? =
    (Json.parseToJsonElement(configBody).jsonObject["data"] as? JsonObject)?.get("vga")?.let { (it as? JsonPrimitive)?.contentOrNull }

/** PVE serves SPICE only for the QXL display types (qxl, qxl2, qxl3, qxl4), optionally with options after a comma. */
fun requireSpiceDisplay(vga: String?) {
    val type = vga?.substringBefore(',')?.substringAfter("type=")?.trim().orEmpty()
    require(type.startsWith("qxl")) {
        "The VM display is ${if (type.isEmpty()) "Default (VGA)" else type}. Set Hardware → Display to SPICE (qxl) to use a SPICE console."
    }
}

fun spiceProxyPath(guest: Guest): String {
    require(guest.kind == "VM") { SPICE_NOT_VM }
    return guestStatusPath(guest).removeSuffix("/status/current") + "/spiceproxy"
}

/** The proxy host the client should use: the host the user connected to, as the PVE web UI does. */
fun spiceProxyHost(address: String): String = serverOrigin(address).host.removePrefix("[").removeSuffix("]")

private val spiceKeyPattern = Regex("[a-z][a-z0-9-]{0,40}")

/**
 * Validates and keeps the `spiceproxy` answer. The proxy must be the host this client asked for, the type must be
 * SPICE, and no key or value may break the `.vv` line format.
 */
fun parseSpiceTicket(body: String, expectedProxyHost: String): SpiceTicket {
    val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonObject ?: error(SPICE_BAD_TICKET)
    val fields = data.entries.map { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull ?: error(SPICE_BAD_TICKET)
        check(key.matches(spiceKeyPattern) && text.none { it == '\n' || it == '\r' || it == '\u0000' }) { SPICE_BAD_TICKET }
        key to text
    }.sortedBy { it.first }
    val map = fields.toMap()
    check(map["type"] == "spice" && !map["password"].isNullOrEmpty() && !map["host"].isNullOrEmpty()) { SPICE_BAD_TICKET }
    val proxy = map["proxy"] ?: error(SPICE_BAD_TICKET)
    val proxyUri = runCatching { URI(proxy) }.getOrNull()
    check(proxyUri?.scheme == "http" && proxyUri.host?.removePrefix("[")?.removeSuffix("]").equals(expectedProxyHost, ignoreCase = true)) {
        "The SPICE proxy pointed at a different host than this connection."
    }
    return SpiceTicket(map["title"].orEmpty(), proxy, fields)
}

interface SpiceGateway {
    suspend fun ticket(connection: Connection, guest: Guest): SpiceTicket
}

class LiveSpiceGateway(private val client: PveClient = PveClient()) : SpiceGateway {
    override suspend fun ticket(connection: Connection, guest: Guest): SpiceTicket = runInterruptible(Dispatchers.IO) { Trace.call(TraceSource.Console, "POST spiceproxy") { client.spiceTicket(connection, guest) } }
}

/** JSON, like every other request body this client sends (`POWER_CONTENT_TYPE`). */
internal fun spiceRequestBody(address: String): ByteArray =
    JsonObject(mapOf("proxy" to JsonPrimitive(spiceProxyHost(address)))).toString().toByteArray(Charsets.UTF_8)
