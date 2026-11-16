@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/** Sets what the page shows without a scan: the session's state, as the page reads it. */
@Suppress("UNCHECKED_CAST")
internal fun ScanSession.show(progress: NetworkProgress?, running: Boolean = false, message: String = "", seconds: Long = 0) {
    fun set(name: String, value: Any?) { (javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(this) as androidx.compose.runtime.MutableState<Any?>).value = value }
    set("progress", progress); set("running", running); set("message", message); set("durationSeconds", seconds)
}

private fun fp(seed: Int) = (0 until 32).joinToString(":") { "%02X".format((seed * 37 + it * 11) and 255) }
private fun pve(ip: String, seed: Int, name: String, port: Int = 8006, kind: ScanKind = ScanKind.PVE) =
    ScanHit(ip, port, true, kind, "$name - Proxmox", fp(seed), "CN=$name.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", false, "CN=Proxmox,OU=PVE Cluster Manager CA", 1_900_000_000_000L)
private fun attachedPlan(vararg cidr: String) = NetworkPlan(cidr.map { c ->
    val base = c.substringBefore(".0/")
    ScanNetwork(ScanInterface("Ethernet", "$base.8", 24), c, null, (1..254).map { "$base.$it" }, onLink = true) })

/** The Scan page's device cards: one machine is one card, whatever number of addresses and services answered. */
class ScanDevicesSceneTest {
    private class Bench(context: CoroutineContext, width: Int, height: Int, hits: List<ScanHit>, plan: NetworkPlan, val known: ScanKnown = ScanKnown.NONE, web: Boolean = false) : AutoCloseable {
        private val job = SupervisorJob()
        val session = quietSession(CoroutineScope(Dispatchers.Unconfined + job))
        val used = mutableListOf<ScanHit>()
        val scene = ImageComposeScene(width, height, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides AppHost(web)) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().padding(16.dp)) { ScanScreen(session, emptyList(), known = known) { used += it } } }
            } }
        }
        val ui = CreateScene(scene)
        init { session.show(NetworkProgress(plan, 254, hits = hits, finished = true)) }
        suspend fun settle() { ui.frames(); delay(20); ui.frames() }
        fun texts(): List<String> = ui.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        fun has(text: String) = ui.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun count(text: String) = ui.nodes().count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun focusedLabel(): String = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text } ?: "(nothing)"
        override fun close() { scene.close(); job.cancel() }
    }

    private val east1 = pve("192.0.2.21", 1, "pve-east"); private val east2 = pve("198.51.100.21", 1, "pve-east"); private val west = pve("192.0.2.22", 2, "pve-west")

    @Test fun aMachineOnTwoAddressesIsOneCardThatSaysWhereElseItAnswers() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1400, listOf(east1, west, east2), attachedPlan("192.0.2.0/24"))
        try {
            b.settle()
            assertTrue(b.has("PROXMOX 2") && b.has("ALL 2"), "a machine is counted once: " + b.texts())
            assertTrue(b.has("Also reachable at 198.51.100.21:8006"), b.texts().toString())
            assertEquals(2, b.count("Use"), "one Use for each machine, not each address")
            assertTrue(b.has("Use connects to 192.0.2.21:8006."), "the address on a network this computer is attached to is the one Use takes")
            assertTrue(b.texts().any { it.startsWith("2 Proxmox") || it.contains("2 Proxmox") }, "the line over the results counts machines: " + b.texts())
        } finally { b.close() }
    }

    @Test fun theAddressOnAnAttachedNetworkIsThePrimaryEvenWhenItWasFoundSecond() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, listOf(east2, east1), attachedPlan("192.0.2.0/24"))
        try {
            b.settle()
            assertTrue(b.has("192.0.2.21:8006  ·  Proxmox VE"), b.texts().toString()); assertTrue(b.has("Also reachable at 198.51.100.21:8006"))
            b.ui.click("Use"); assertEquals(listOf(east1), b.used)
        } finally { b.close() }
    }

    @Test fun useOffersTheOtherAddressesAndHandsOverTheOneChosen() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, listOf(east1, east2), attachedPlan("192.0.2.0/24"))
        try {
            b.settle()
            assertFalse(b.has("Use 198.51.100.21:8006"), "the choices are not on the page until asked for")
            b.ui.click("Other address"); b.settle()
            assertTrue(b.has("Use 198.51.100.21:8006") && b.has("Hide other addresses"))
            b.ui.click("Use 198.51.100.21:8006"); assertEquals(listOf(east2), b.used)
            b.ui.click("Use"); assertEquals(listOf(east2, east1), b.used, "the plain Use still takes the preferred address")
        } finally { b.close() }
    }

    @Test fun theChoicesAreReachedAndUsedFromTheKeyboard() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, listOf(east1, east2), attachedPlan("192.0.2.0/24"))
        try {
            b.settle()
            var label = ""
            repeat(40) { if (!label.contains("OTHER ADDRESS", ignoreCase = true)) { b.ui.key(Key.Tab); label = b.focusedLabel() } }
            assertTrue(label.contains("OTHER ADDRESS", ignoreCase = true), label)
            b.ui.key(Key.Enter); b.settle()
            repeat(3) { if (!label.contains("198.51.100.21")) { b.ui.key(Key.Tab); label = b.focusedLabel() } }
            assertTrue(label.contains("USE 198.51.100.21:8006", ignoreCase = true), label)
            b.ui.key(Key.Enter)
            assertEquals(listOf(east2), b.used)
        } finally { b.close() }
    }

    @Test fun aHostWithAnotherProxmoxServiceStillHasOneCardAndNamesTheOther() = runSceneTest {
        val backup = pve("192.0.2.21", 3, "backup", 8007, ScanKind.PBS)
        val b = Bench(coroutineContext, 1280, 1000, listOf(east1, backup), attachedPlan("192.0.2.0/24"))
        try {
            b.settle()
            assertTrue(b.has("PROXMOX 1") && b.has("Also on this device: Proxmox Backup Server 192.0.2.21:8007"), b.texts().toString())
            assertEquals(1, b.count("Use")); assertEquals(1, b.count("Web UI"))
            assertFalse(b.has("Other address"), "one address only")
        } finally { b.close() }
    }

    @Test fun theSavedTagIsThereForAnAddressAndForAPinnedCertificateAndForNothingElse() = runSceneTest {
        val none = Bench(coroutineContext, 1280, 800, listOf(east1, west), attachedPlan("192.0.2.0/24"), known = ScanKnown(origins = listOf("https://pve-east.example.test:8006")))
        try { none.settle(); assertEquals(0, none.count("Saved"), "a name is not an address") } finally { none.close() }
        val byAddress = Bench(coroutineContext, 1280, 800, listOf(east1, west), attachedPlan("192.0.2.0/24"), known = ScanKnown(origins = listOf("https://192.0.2.21:8006")))
        try { byAddress.settle(); assertEquals(1, byAddress.count("Saved")) } finally { byAddress.close() }
        val byPin = Bench(coroutineContext, 1280, 800, listOf(west, east2), attachedPlan("192.0.2.0/24"), known = ScanKnown(pins = mapOf(pinKey(fp(1))!! to "saved connection pve-east")))
        try { byPin.settle(); assertEquals(1, byPin.count("Saved"), "the machine at another address, known by its certificate") } finally { byPin.close() }
        assertEquals("A saved connection uses this address.", savedTagHelp(SavedMark(SavedBy.Address)))
        assertEquals("Its certificate is pinned: saved connection pve-east.", savedTagHelp(SavedMark(SavedBy.Pin, "saved connection pve-east")))
    }

    @Test fun showcaseHidesTheOtherAddressesAndTheChoices() = runSceneTest {
        Appearance.preview(false, showcase = true)
        val b = Bench(coroutineContext, 1280, 1000, listOf(east1, east2), attachedPlan("192.0.2.0/24"))
        try {
            b.settle(); b.ui.click("Other address"); b.settle()
            for (secret in listOf("Also reachable at 198.51.100.21:8006", "Use 198.51.100.21:8006", "Use connects to 192.0.2.21:8006.")) assertFalse(b.has(secret), "$secret is shown in showcase")
            assertTrue(b.texts().none { "198.51.100" in it || "192.0.2.21" in it }, b.texts().toString())
        } finally { Appearance.preview(false); b.close() }
    }

    @Test fun counting() {
        val devices = ScanDevices.group(listOf(east1, west, east2))
        assertEquals(listOf(3, 2, 1), scanDeviceCounts(devices, listOf(east1, west, east2, ScanHit("192.0.2.1", 443, true, ScanKind.WEB))))
        assertEquals(listOf(2, 1, 1), scanCounts(listOf(east1, ScanHit("192.0.2.1", 443, true, ScanKind.WEB))), "the old count of hits is unchanged")
    }
}
