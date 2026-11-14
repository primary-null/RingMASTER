package local.ringleader.core.tools

import java.time.Duration
import java.time.Instant
import local.ringleader.core.VpnBackend
import local.ringleader.core.VpnSnapshot
import local.ringleader.core.WireGuardPeer
import local.ringleader.core.localVpnBackend
import local.ringleader.core.vpnFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Read-only view of a WireGuard or Tailscale peer. Bringing tunnels up or down stays on the VPN page.
 */
class WireGuardToolPlugin(
    private val backendProvider: () -> VpnBackend? = ::localVpnBackend,
    private val pinger: suspend (String, (ToolEvent) -> Unit) -> ToolResult = { host, emit ->
        PingPlugin().run(mapOf("host" to host, "count" to "3", "timeout" to "1000"), emit)
    },
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val clock: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
) : ToolPlugin {
    override val id = "wireguard"
    override val name = "WireGuard"
    override val summary = "Handshake age, throughput and a reachability ping. Read-only."

    @Volatile var snapshot: VpnSnapshot? = null
        private set
    @Volatile var loadError: String? = null
        private set

    override val inputs: List<ToolInput>
        get() {
            val options = peerOptions(snapshot)
            return listOf(
                ToolInput(
                    "peer", "Peer", ToolInputKind.Choice, options.firstOrNull()?.value.orEmpty(),
                    "WireGuard allowed IP or Tailscale address",
                    choices = options.map { it.value }, choiceLabels = options.map { it.label },
                ),
                ToolInput("seconds", "Seconds", ToolInputKind.Number, "10", "How long to sample", min = 4, max = 120),
            )
        }

    override fun available(): ToolAvailability {
        if (backendProvider() == null) return ToolAvailability(false, "WireGuard and Tailscale are not available on this platform.")
        loadError?.let { return ToolAvailability(false, it) }
        if (snapshot != null && peerOptions(snapshot).isEmpty()) return ToolAvailability(false, "No WireGuard peer or Tailscale peer was found.")
        return ToolAvailability(true)
    }

    fun refreshSnapshot(): VpnSnapshot? {
        val backend = backendProvider() ?: return null
        return try {
            backend.snapshot().also { snapshot = it; loadError = null }
        } catch (e: Exception) {
            loadError = vpnFailureMessage(e.message ?: "Tunnel status could not be read.")
            null
        }
    }

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        val backend = backendProvider() ?: return ToolResult(false, "WireGuard and Tailscale are not available on this platform.")
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val selected = clean.getValue("peer")
        val parts = selected.split('|', limit = 2)
        if (parts.size != 2 || !isHostToken(parts[1])) return ToolResult(false, "Choose a peer.")
        val tunnelId = parts[0]
        val address = parts[1]
        val seconds = clean.getValue("seconds").toInt()
        emit(ToolEvent.Status("Reading $address"))
        var previous: Pair<Long?, Long?>? = null
        var lastRx = 0.0
        var lastTx = 0.0
        var handshake = "no handshake"
        var endpoint = ""
        var elapsed = 0
        var ping: ToolResult? = null
        var previousAt = nanoTime()
        try {
            while (elapsed <= seconds) {
                coroutineContext.ensureActive()
                val snap = withContext(Dispatchers.IO) { backend.snapshot() }
                val sampleAt = nanoTime()
                snapshot = snap
                val counters = peerCounters(snap, tunnelId, address)
                handshake = handshakeAge(counters.handshake, clock())
                endpoint = counters.endpoint.orEmpty()
                emit(ToolEvent.Line("handshake $handshake${if (endpoint.isBlank()) "" else " · $endpoint"}"))
                if (previous != null) {
                    val interval = (sampleAt - previousAt) / 1_000_000_000.0
                    rateMbps(previous.first, counters.rx, interval)?.let { rate ->
                        lastRx = rate
                        emit(ToolEvent.Sample("receive", elapsed.toDouble(), rate, "Mbit/s"))
                    }
                    rateMbps(previous.second, counters.tx, interval)?.let { rate ->
                        lastTx = rate
                        emit(ToolEvent.Sample("send", elapsed.toDouble(), rate, "Mbit/s"))
                    }
                    emit(ToolEvent.Line("receive ${formatNumber(lastRx)} Mbit/s · send ${formatNumber(lastTx)} Mbit/s"))
                }
                previous = counters.rx to counters.tx
                previousAt = sampleAt
                if (ping == null) {
                    emit(ToolEvent.Status("Checking $address"))
                    ping = pinger(address, emit)
                }
                if (elapsed >= seconds) break
                pause(2_000)
                elapsed += 2
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, vpnFailureMessage(e.message ?: "Tunnel status could not be read."))
        }
        val reach = ping?.summary ?: "no reachability result"
        val summary = "handshake $handshake · receive ${formatNumber(lastRx)} Mbit/s · send ${formatNumber(lastTx)} Mbit/s · $reach"
        return ToolResult(ping?.ok != false, oneLine(summary), mapOf(
            "handshake" to handshake,
            "endpoint" to endpoint,
            "receive" to formatNumber(lastRx),
            "send" to formatNumber(lastTx),
            "reachable" to (ping?.ok == true).toString(),
        ))
    }
}

internal data class PeerChoice(val value: String, val label: String)

internal fun peerOptions(snap: VpnSnapshot?): List<PeerChoice> {
    if (snap == null) return emptyList()
    val options = mutableListOf<PeerChoice>()
    for (iface in snap.wireguard) {
        for (peer in iface.peers) {
            val address = peer.allowedIps.firstNotNullOfOrNull { candidate ->
                candidate.substringBefore('/').takeIf(::isHostToken)
            } ?: continue
            options += PeerChoice("wg:${iface.name}|$address", "${iface.name} → $address")
        }
    }
    snap.tailscale?.peers?.forEach { peer ->
        val address = peer.addresses.firstOrNull(::isHostToken) ?: return@forEach
        options += PeerChoice("tailscale|$address", "${peer.hostName} → $address")
    }
    return options.distinctBy { it.value }
}

internal data class PeerCounters(val rx: Long?, val tx: Long?, val handshake: Long?, val endpoint: String?)

internal fun peerCounters(snap: VpnSnapshot, tunnelId: String, address: String): PeerCounters {
    if (tunnelId == "tailscale") {
        val peer = snap.tailscale?.peers?.firstOrNull { address in it.addresses }
        val self = snap.tailscale?.self?.takeIf { address in it.addresses }
        val chosen = peer ?: self
        return PeerCounters(chosen?.rxBytes, chosen?.txBytes, chosen?.lastHandshake?.epochSecond, chosen?.direct ?: chosen?.relay)
    }
    val name = tunnelId.removePrefix("wg:")
    val iface = snap.wireguard.firstOrNull { it.name == name }
    val peer = iface?.peers?.firstOrNull { matchesAddress(it, address) }
    if (peer != null) return PeerCounters(peer.rxBytes, peer.txBytes, peer.lastHandshakeEpoch, peer.endpoint)
    val tunnel = snap.tunnels.firstOrNull { it.id == tunnelId }
    return PeerCounters(tunnel?.rxBytes, tunnel?.txBytes, tunnel?.lastHandshakeEpoch, tunnel?.detail)
}

private fun matchesAddress(peer: WireGuardPeer, address: String) =
    peer.allowedIps.any { it.substringBefore('/') == address }

internal fun rateMbps(previous: Long?, current: Long?, seconds: Double): Double? {
    if (previous == null || current == null || seconds <= 0) return null
    return (current - previous).coerceAtLeast(0) * 8.0 / seconds / 1_000_000.0
}

internal fun handshakeAge(epoch: Long?, now: Instant): String {
    if (epoch == null || epoch <= 0L) return "no handshake"
    val age = Duration.between(Instant.ofEpochSecond(epoch), now).coerceAtLeast(Duration.ZERO)
    return when {
        age.seconds < 120 -> "${age.seconds}s ago"
        age.toMinutes() < 180 -> "${age.toMinutes()}m ago"
        else -> "${age.toHours()}h ago"
    }
}
