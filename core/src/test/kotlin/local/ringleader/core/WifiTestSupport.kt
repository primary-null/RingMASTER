package local.ringleader.core

/** Loads a file from `src/test/resources/wifi-samples`. Line ends are made LF so a CRLF checkout reads the same. */
internal object WifiSamples {
    fun text(name: String): String =
        requireNotNull(WifiSamples::class.java.getResource("/wifi-samples/$name")) { "missing sample $name" }
            .readText(Charsets.UTF_8).replace("\r\n", "\n")
}

/**
 * Builds the text of a radio script the way the script prints it: a header, then lines that start with a token and a tab.
 * The sections take the sample files as their bodies.
 */
internal class RadioText(private val kind: String, private val token: String = "0123456789abcdef") {
    private val lines = mutableListOf<String>()

    fun rec(recordKind: String, vararg items: String) = apply { lines += "$token\t$recordKind" + items.joinToString("") { "\t$it" } }
    fun section(name: String, body: String) = apply {
        lines += "$token\tbegin\t$name"
        lines += body.trimEnd('\n')
        lines += ""
        lines += "$token\tend\t$name"
    }
    fun tools(vararg names: String) = rec("tools", *names)
    fun uid(value: Int) = rec("uid", value.toString())
    fun rfkill(name: String, type: String, soft: Int, hard: Int) = rec("rfkill", "name=$name", "type=$type", "soft=$soft", "hard=$hard")
    fun wifi(id: String, phy: String = "phy0", mac: String = "02:00:00:00:00:01", operstate: String = "up", carrier: String = "1",
        driver: String = "iwlwifi", bus: String = "pci", pci: String = "0000:03:00.0", vendor: String = "0x8086", device: String = "0x2725", product: String = "") =
        rec("wifi", "id=$id", "driver=$driver", "bus=$bus", "pci=$pci", "vendor=$vendor", "device=$device", "product=$product", "maker=", "phy=$phy", "mac=$mac", "operstate=$operstate", "carrier=$carrier")
    fun wifiPhy(id: String) = rec("wifiphy", "id=$id", "driver=", "bus=", "pci=", "vendor=", "device=", "product=", "maker=", "phy=$id")
    fun bt(id: String, driver: String = "btusb", bus: String = "usb", vendor: String = "13d3", device: String = "3585", product: String = "") =
        rec("bt", "id=$id", "driver=$driver", "bus=$bus", "pci=", "vendor=$vendor", "device=$device", "product=$product", "maker=")
    fun done(early: Boolean = false) = apply { lines += "$token\tdone" + if (early) "\tearly" else "" }
    fun text(): String = "RINGMASTER-RADIO 1 $kind $token\n" + lines.joinToString("\n") + "\n"
}
