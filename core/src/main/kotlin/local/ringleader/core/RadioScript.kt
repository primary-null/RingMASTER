package local.ringleader.core

/*
 * The text side of the radio probe and the Linux Wi-Fi scan. A script (core/src/main/resources/radio) prints a header and
 * then records and raw tool output behind a random token; the parsers here turn that text into the shared models. None of
 * it knows how the text was fetched: this computer runs `sh` itself, a VM's guest agent runs the same command, and a
 * helper installed in a guest or on a node can run it too and hand the text to the same parsers.
 */

const val RADIO_OUTPUT_UNREADABLE = "The radio scan returned unreadable output."
const val RADIO_OUTPUT_VERSION = "The radio scan answered in a format this version of RingMASTER does not know."

/** The scripts, as text. Loaded once. Line ends are normalised so a script checked out with CRLF still runs under `sh`. */
object RadioScripts {
    private fun load(name: String): String =
        requireNotNull(RadioScripts::class.java.getResourceAsStream("/radio/$name")) { "missing script $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")

    /** Lists the Bluetooth controllers and Wi-Fi interfaces the system sees. Header kind "probe". */
    val probe: String by lazy { load("prelude.sh") + "\n" + load("probe.sh") }

    /** Asks the Wi-Fi adapters for a scan and lists what the system knows. Header kind "wifi". Takes the scan budget in seconds as $1. */
    val wifiScan: String by lazy { load("prelude.sh") + "\n" + load("wifi-scan.sh") }
}

/**
 * `sh -c` around [script], under `timeout` when the system has it, so a hung tool cannot outlive the call.
 * [args] become $1, $2 ... of the script. Every transport (a process here, the guest agent, a helper) sends this same command.
 */
fun radioScriptCommand(script: String, budgetSeconds: Int, vararg args: String): List<String> {
    val quoted = radioQuote(script)
    val rest = args.joinToString("") { " " + radioQuote(it) }
    return listOf("sh", "-c", """
        if command -v timeout >/dev/null 2>&1; then
            timeout -k 2 $budgetSeconds sh -c $quoted ringmaster-radio$rest
        else
            sh -c $quoted ringmaster-radio$rest
        fi
    """.trimIndent())
}

private fun radioQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** One record line: its kind and its items. Items of the form key=value are also in [fields]. */
data class RadioRecord(val kind: String, val items: List<String>) {
    val fields: Map<String, String> = items.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
    fun field(key: String): String? = fields[key]?.takeIf { it.isNotEmpty() }
}

/**
 * The parsed text of a script. [complete] is false when the closing line never came (the script was cut off by a time
 * limit or the output was truncated); [early] is true when the script itself reported being stopped.
 */
class RadioFrame(
    val kind: String,
    val records: List<RadioRecord>,
    val sections: List<Pair<String, String>>,
    val complete: Boolean,
    val early: Boolean,
) {
    fun records(kind: String): List<RadioRecord> = records.filter { it.kind == kind }
    fun section(name: String): String? = sections.firstOrNull { it.first == name }?.second

    /** Sections named "<prefix> <argument>", with the argument: `sectionsWith("iw-link")` gives ("wlan0", text). */
    fun sectionsWith(prefix: String): List<Pair<String, String>> =
        sections.filter { it.first.startsWith("$prefix ") }.map { it.first.removePrefix("$prefix ").trim() to it.second }
}

private val radioHeader = Regex("^RINGMASTER-RADIO (\\d+) ([a-z]+) ([0-9A-Za-z]{4,64})$")

/**
 * Reads a script's text strictly: the first line must be the header with the expected [expectKind], and only lines that
 * start with the token the script chose count as records and markers, so network or device names cannot forge them.
 * Anything else outside a section is ignored. Throws [IllegalStateException] for text that is not this script's output.
 */
fun parseRadioFrame(text: String, expectKind: String): RadioFrame {
    val lines = text.lineSequence().map { it.trimEnd('\r') }.dropWhile { it.isBlank() }.toList()
    val head = lines.firstOrNull()?.let { radioHeader.matchEntire(it.trim()) } ?: error(RADIO_OUTPUT_UNREADABLE)
    if (head.groupValues[1] != "1") error(RADIO_OUTPUT_VERSION)
    if (head.groupValues[2] != expectKind) error(RADIO_OUTPUT_UNREADABLE)
    val prefix = head.groupValues[3] + "\t"
    val records = mutableListOf<RadioRecord>()
    val sections = mutableListOf<Pair<String, String>>()
    var openName: String? = null
    val body = StringBuilder()
    var done = false
    var early = false
    fun closeSection() {
        openName?.let { sections += it to body.toString().trimEnd('\n') }
        openName = null
        body.setLength(0)
    }
    for (line in lines.drop(1)) {
        if (done) break
        val control = line.startsWith(prefix)
        val parts = if (control) line.split('\t') else emptyList()
        if (openName != null) {
            if (control && parts.getOrNull(1) == "end" && parts.getOrNull(2) == openName) closeSection()
            else body.append(line).append('\n')
            continue
        }
        if (!control) continue
        when (val kind = parts.getOrNull(1).orEmpty()) {
            "begin" -> openName = parts.getOrNull(2).orEmpty().ifEmpty { "?" }
            "end" -> {}
            "done" -> { done = true; early = parts.getOrNull(2) == "early" }
            "" -> {}
            else -> records += RadioRecord(kind, parts.drop(2))
        }
    }
    closeSection()
    return RadioFrame(expectKind, records, sections, complete = done && !early, early = early)
}

/** A hardware description from a `wifi`, `wifiphy` or `bt` record. The script prints PCI ids as 0x14c3 and USB ids as 13d3. */
internal fun radioHardware(record: RadioRecord): AdapterHardware {
    fun hex(v: String?) = v?.trim()?.removePrefix("0x")?.removePrefix("0X")?.takeIf { it.isNotEmpty() }?.toIntOrNull(16)
    val bus = record.field("bus")
    val vendor = hex(record.field("vendor"))
    val device = hex(record.field("device"))
    val pci = record.field("pci")
    return AdapterHardware(
        driver = record.field("driver"),
        bus = bus,
        usbVendorId = vendor.takeIf { bus == "usb" },
        usbProductId = device.takeIf { bus == "usb" },
        pciAddress = pci,
        pciVendorId = vendor.takeIf { pci != null },
        pciDeviceId = device.takeIf { pci != null },
        product = record.field("product"),
        maker = record.field("maker"),
    )
}

/** One rfkill switch: [name] is "phy0" or "hci0", [type] is "wlan" or "bluetooth". A block flag is null when it was not readable. */
data class RfkillSwitch(val name: String, val type: String, val soft: Boolean?, val hard: Boolean?)

/** The switches the script listed from sysfs, or, when the kernel exposed none, from `rfkill list` text. */
internal fun rfkillSwitches(frame: RadioFrame): List<RfkillSwitch> {
    val fromSysfs = frame.records("rfkill").mapNotNull { r ->
        val name = r.field("name") ?: return@mapNotNull null
        RfkillSwitch(name, r.field("type").orEmpty(), r.field("soft")?.let { it == "1" }, r.field("hard")?.let { it == "1" })
    }
    return fromSysfs.ifEmpty { frame.section("rfkill-list")?.let(::parseRfkillList).orEmpty() }
}

/** `rfkill list`: "0: phy0: Wireless LAN" then "Soft blocked: no" and "Hard blocked: no" lines. */
fun parseRfkillList(text: String): List<RfkillSwitch> {
    val out = mutableListOf<RfkillSwitch>()
    var name: String? = null; var type = ""; var soft: Boolean? = null; var hard: Boolean? = null
    fun flush() { name?.let { out += RfkillSwitch(it, type, soft, hard) }; name = null; soft = null; hard = null }
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        val head = Regex("^\\d+:\\s*([^:]+):\\s*(.+)$").matchEntire(line)
        when {
            head != null -> {
                flush()
                name = head.groupValues[1].trim()
                type = when (head.groupValues[2].trim().lowercase()) { "wireless lan" -> "wlan"; "bluetooth" -> "bluetooth"; else -> head.groupValues[2].trim().lowercase() }
            }
            line.startsWith("Soft blocked:", true) -> soft = line.substringAfter(':').trim().equals("yes", true)
            line.startsWith("Hard blocked:", true) -> hard = line.substringAfter(':').trim().equals("yes", true)
        }
    }
    flush()
    return out
}

/** The block state of the radio named [name] (phy0, hci0). Unknown when no switch carries that name. */
internal fun radioBlockFor(name: String?, switches: List<RfkillSwitch>): RadioBlock {
    val s = switches.firstOrNull { it.name == name } ?: return RadioBlock.Unknown
    return radioBlockOf(s.soft, s.hard)
}
