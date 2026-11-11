package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File

/** Opt-in render probe: RINGMASTER_PROBE=1 (PROBE_LIVE=1 records a real scan on this PC first). Writes to artifacts/probe. */
class BtRenderProbeTest {
    @Test fun probe() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val updates = mutableListOf<BtScan>()
        val final = if (System.getenv("PROBE_LIVE") == "1") WindowsBluetoothScanner().scan(10) { updates += it }
        else demoLive(updates)
        println("PROBE updates=${updates.size} devices=${final.devices.size}")
        val out = File("../artifacts/probe").apply { mkdirs() }
        val picks = listOf(updates.getOrNull(1), updates.getOrNull(updates.size / 3), updates.getOrNull(updates.size * 2 / 3), final).filterNotNull()
        for ((w, h, tag) in listOf(Triple(1800, 1000, "wide"), Triple(1100, 760, "narrow"))) for ((i, scan) in picks.withIndex()) for (light in listOf(false, true)) {
            if (light && i != picks.lastIndex) continue
            Appearance.preview(light)
            val scene = ImageComposeScene(w, h, Density(1.25f)) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface(Modifier.fillMaxSize()) { BtScanView(scan, scanning = !scan.finished, modifier = Modifier.padding(16.dp), platform = "Windows · WinRT", receivedAt = 0L) }
                } }
            }
            scene.render(0)
            if (scan.finished && !light) scene.sendPointerEvent(PointerEventType.Move, Offset(w * .7f, 330f))
            File(out, "$tag-$i${if (light) "-light" else ""}.png").writeBytes(scene.render(250_000_000L).encodeToData()!!.bytes)
            scene.close()
        }
        Appearance.preview(false)
    }

    private fun demoLive(updates: MutableList<BtScan>): BtScan {
        val live = BtLiveScan(BtSource.Local, 10_000)
        live.adapters = listOf(BtAdapter("bt-0", "MediaTek Bluetooth Adapter", "AA:BB:CC:DD:EE:01", powered = true))
        val rnd = java.util.Random(7)
        val devs = (1..24).map { n -> BtDevice("11:22:33:44:55:%02X".format(n), if (n % 3 == 0) null else "Device $n", -45 - rnd.nextInt(45), paired = n < 5, connected = n == 1, kind = BtKind.entries[n % 3]) }
        var ms = 0L
        while (ms < 10_000) {
            devs.forEachIndexed { i, d -> if (ms >= i * 380L && rnd.nextInt(5) == 0) live.sight(d.copy(rssi = d.rssi!! + rnd.nextInt(9) - 4), ms) }
            if (ms % 500 == 0L) updates += live.snapshot(ms, false)
            ms += 100
        }
        return live.snapshot(10_000, true)
    }
}
