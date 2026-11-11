package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/** Opt-in render probe of the VPN page: RINGMASTER_PROBE=1. PROBE_LIVE=1 reads this computer's tunnels (read-only). */
class VpnProbeTest {
    @Test fun probe() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val live = if (System.getenv("PROBE_LIVE") == "1") localVpnBackend() else null
        val snap = live?.snapshot() ?: sample()
        val route = live?.routeTo(System.getenv("PROBE_HOST") ?: "100.64.0.2", snap)
        if (System.getenv("PROBE_FIND") == "1") snap.tailscale?.let { ts -> runBlocking { val found = findTailnetProxmox(ts).toList().last(); println("FIND ${found.size}: " + found.joinToString { "${it.hit.kind}:${it.hit.port}" }) } }
        println("VPN tunnels=${snap.tunnels.size} peers=${snap.tailscale?.peers?.size} route=${route?.interfaceName}/${route?.tunnelId} notes=${snap.notes}")
        val fake = object : VpnBackend {
            override val platform = live?.platform ?: "Windows"
            override fun snapshot() = snap
            override fun routeTo(host: String, snapshot: VpnSnapshot?) = route ?: VpnRoute(host, host, "Tailscale", "tailscale", "Tailscale")
            override fun setTunnel(tunnel: VpnTunnel, up: Boolean) = error("probe")
            override fun setExitNode(peer: TailscalePeer?) = error("probe")
            override fun importWireGuard(config: Path, name: String) = error("probe")
            override fun removeWireGuard(tunnel: VpnTunnel) = error("probe")
        }
        val out = File("../artifacts/probe").apply { mkdirs() }
        for ((w, h, tag) in listOf(Triple(1800, 1100, "vpn-wide"), Triple(1100, 760, "vpn-narrow"))) for (light in listOf(false, true)) {
            if (light && tag == "vpn-narrow") continue
            Appearance.preview(light)
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val session = VpnSession(scope, fake)
            val scene = ImageComposeScene(w, h, Density(1.25f)) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize()) { VpnScreen(session, "https://${System.getenv("PROBE_HOST") ?: "100.64.0.2"}:8006", emptyList()) {} }
                } }
            }
            repeat(20) { scene.render(it * 100_000_000L); Thread.sleep(150) }
            File(out, "$tag${if (light) "-light" else ""}.png").writeBytes(scene.render(3_000_000_000L).encodeToData()!!.bytes)
            scene.close(); scope.cancel()
        }
        Appearance.preview(false)
    }

    private fun sample(): VpnSnapshot {
        fun peer(n: Int, online: Boolean, exit: Boolean = false) = TailscalePeer("p$n", "host-$n", "host-$n.example.ts.net", if (n % 2 == 0) "linux" else "windows", online, listOf("100.64.0.$n"), exit, false, if (n % 3 == 0) null else "dfw", if (n % 3 == 0) "192.0.2.$n:41641" else null, null, 1000L * n, 500L * n)
        val ts = TailscaleStatus("Running", "1.104.4", "example.ts.net", "example.ts.net", peer(1, true), (2..9).map { peer(it, it % 2 == 0, it == 4) }, null, emptyList())
        return VpnSnapshot(java.time.Instant.now(), listOf(
            VpnTunnel("tailscale", VpnKind.Tailscale, "example.ts.net", VpnState.Up, "Tailscale", listOf("100.64.0.1"), "4 of 8 peers online", canToggle = true),
            VpnTunnel("wg:office", VpnKind.WireGuard, "office", VpnState.Down, "office", listOf("10.8.0.2/24"), "Endpoint 203.0.113.4:51820", canToggle = true, toggleNeedsAdmin = true, canRemove = true),
            VpnTunnel("ras:Work", VpnKind.IpSec, "Work", VpnState.Down, detail = "Server vpn.example.com", canToggle = true),
        ), true, ts, true, emptyList(), listOf("WireGuard peer details need administrator rights."))
    }
}
