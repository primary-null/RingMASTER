package local.ringleader.core

import java.time.Duration
import java.time.Instant

/*
 * The live ticker's model: from the trace's vitals and metrics, the live-loop state and a small snapshot of what the app is
 * doing, a short ordered list of tiles. Pure: no clock of its own, no I/O, nothing added to what the trace captures.
 *
 * A tile is a reading, not a report. Each has a label, one headline value, at most one second line, a tone, an optional
 * small history, a target (the area of Under the Hood that explains it) and up to three links, each with a target of its own.
 * Anything with an identity in it (a guest, a user, an address, a file) is left out unless the person may see it.
 */

/** What a tile reads. */
enum class TickerTileId { Errors, Now, Network, LiveLoop, Activity, Latency }

enum class TickerTone { Calm, Busy, Warning, Error }

/** The part of Under the Hood a click opens and lights. */
sealed interface HoodFocus {
    /** The stream, filtered to [source] (null: every source). */
    data class Stream(val source: TraceSource?) : HoodFocus
    /** The stream on one operation: selected, in view, with the inspector open on it. */
    data class Operation(val id: Long) : HoodFocus
    data object Engine : HoodFocus
    data object Latency : HoodFocus
    data object Requests : HoodFocus
    data object Slowest : HoodFocus
    data object Errors : HoodFocus
    data object Network : HoodFocus
    data object Sessions : HoodFocus
}

/** Where a click goes: an area of Under the Hood, or another part of the app. */
sealed interface TickerTarget {
    data class Hood(val focus: HoodFocus) : TickerTarget
    data object Desk : TickerTarget
    data object Scan : TickerTarget
    /** The VPN page; [kind] is the VPN that carries the connection, so the page can light its card. */
    data class Vpn(val kind: VpnKind? = null) : TickerTarget
    data object Tools : TickerTarget
    data object Storage : TickerTarget
    data object Bluetooth : TickerTarget
    /** Activity, on one task when [upid] is given. */
    data class Activity(val upid: String? = null) : TickerTarget
    /** Fleet, on one guest (or its console) when [guest] is given. */
    data class Fleet(val guest: ResourceId? = null, val console: Boolean = false) : TickerTarget
}

data class TickerLink(val label: String, val target: TickerTarget)

data class TickerTile(
    val id: TickerTileId,
    /** Upper-case mono label: what the reading is. */
    val label: String,
    /** The one value, already rounded for reading ("38 ms", "12/min"). */
    val headline: String,
    val detail: String? = null,
    val tone: TickerTone = TickerTone.Calm,
    /** A small history for a sparkline, oldest first. Empty for none. */
    val history: List<Int> = emptyList(),
    /** Where a click on the tile's body goes. */
    val target: TickerTarget,
    /** At most [HoodTicker.MAX_LINKS]. */
    val links: List<TickerLink> = emptyList(),
    /** What the collapsed pill says when this tile leads, when the headline alone would not read ("2 errors"). */
    val pill: String? = null,
) {
    init { require(links.size <= HoodTicker.MAX_LINKS) { "A tile holds at most ${HoodTicker.MAX_LINKS} links." } }
}

/** The collapsed tray's one reading. */
data class TickerPill(val reading: String, val tone: TickerTone)

/**
 * What a sample leaves behind so the next one can tell what changed. [loaded] counts the sessions whose first read is in,
 * [latestErrorId] is the newest error of the last five minutes, [running] names the long operations that are on.
 */
data class TickerMarks(
    val loaded: Int = 0,
    val latestErrorId: Long? = null,
    val running: Set<String> = emptySet(),
    val backingOff: Boolean = false,
)

enum class TickerCue { FirstData, Error, Started, Finished, BackOff }

data class TickerModel(val tiles: List<TickerTile> = emptyList(), val marks: TickerMarks = TickerMarks()) {
    val isEmpty get() = tiles.isEmpty()
    /** The worst tone on show: it colours the tray's edge. */
    val tone: TickerTone get() = tiles.maxOfOrNull { it.tone } ?: TickerTone.Calm
    /** The most telling single reading: the first tile is the most important by construction. */
    val pill: TickerPill? get() = tiles.firstOrNull()?.let { TickerPill(it.pill ?: it.headline, it.tone) }
}

/** How one open connection is reached, as the route observer saw it. */
data class TickerRoute(val kind: RouteKind, val vpn: VpnKind? = null, val medium: RouteMedium? = null)

/** Why a connection is not usable, in words that name no server. */
enum class TickerFailure(val short: String) {
    Certificate("certificate refused"), SignIn("sign-in refused"), Unreachable("server not reached"), Other("connection failed")
}

enum class TickerSessionPhase { Loading, Connected, Failed }

/** One open connection. [label] and [address] are identities: tiles never carry them. */
data class TickerSession(
    val id: Long,
    val inView: Boolean,
    val phase: TickerSessionPhase,
    val kind: String = DIRECT_PVE_KIND,
    val label: String = "",
    val address: String = "",
    /** "API token" or "Password": the type only. */
    val signIn: String? = null,
    /** True for a pinned certificate, false for normal TLS, null when not known. */
    val pinned: Boolean? = null,
    /** Null when the route was not observed (only the session in view is observed). */
    val route: TickerRoute? = null,
    val live: Boolean = false,
    val liveSeconds: Int = 0,
    val backoffSeconds: Int? = null,
    val updatedAt: Instant? = null,
    val failure: TickerFailure? = null,
) {
    val usable get() = phase == TickerSessionPhase.Connected
}

/** A Proxmox task that is running now. [subject] is an identity (a guest id or name). */
data class TickerTask(val upid: String, val label: String, val subject: String? = null, val since: Instant? = null)

data class TickerScan(val percent: Int, val hosts: Int)

/** A console the page holds beyond the one on screen, with the guest it shows. [subject] is an identity. */
data class TickerConsole(val guest: ResourceId? = null, val subject: String? = null)

/** What the desktop knows that the trace does not. */
data class TickerActivity(
    val sessions: List<TickerSession> = emptyList(),
    val scan: TickerScan? = null,
    val vpnBusy: Boolean = false,
    val toolRunning: Boolean = false,
    /** Running tasks of the session in view, oldest first. */
    val tasks: List<TickerTask> = emptyList(),
    /** The latest task that failed in the last few minutes, for the Errors tile's link into Activity. */
    val failedTask: TickerTask? = null,
    val uploading: Boolean = false,
    val uploadPercent: Int? = null,
    /** Consoles the session in view keeps open, newest last. */
    val kept: List<TickerConsole> = emptyList(),
) {
    val inView get() = sessions.firstOrNull { it.inView }
}

data class TickerInput(
    val now: Instant,
    val vitals: TraceVitals,
    val metrics: TraceMetrics,
    val loop: TraceLiveLoop = TraceLiveLoop(),
    val activity: TickerActivity = TickerActivity(),
    /** Showcase hides identities everywhere. */
    val showcase: Boolean = false,
    /** True when the page the tray sits on already shows guest names, so a tile may say which guest. */
    val names: Boolean = false,
)

object HoodTicker {
    const val MAX_TILES = 4
    const val MAX_LINKS = 3
    /** An error is fresh for this long: its tile leads and is red. After that it stays for five minutes, amber. */
    const val FRESH_ERROR_SECONDS = 60L
    private const val BUSY_PER_MINUTE = 90
    private const val BUSY_IN_FLIGHT = 4
    private const val SLOW_MS = 1000L

    /**
     * The tiles worth showing now, at most [MAX_TILES], most telling first. A fresh error or a started long operation leads;
     * a failed connection or a loop that is backing off comes next; then the calm readings. The route is only a tile when it
     * is notable (a VPN, a proxy, this computer, a failure, several connections): the page heading already names an ordinary one.
     */
    fun build(input: TickerInput): TickerModel {
        val identities = !input.showcase && input.names
        val errors = errorsTile(input)
        val running = runningTile(input, identities)
        val network = networkTile(input)
        val loop = loopTile(input)
        val fresh = errors?.tone == TickerTone.Error
        val urgent = listOfNotNull(network, loop).filter { it.tone >= TickerTone.Warning }
        val calm = listOfNotNull(requestsTile(input), latencyTile(input)) + listOfNotNull(loop, network).filter { it.tone < TickerTone.Warning }
        val ordered = (listOfNotNull(errors?.takeIf { fresh }, running, errors?.takeIf { !fresh }) + urgent + calm).take(MAX_TILES)
        return TickerModel(ordered, marks(input))
    }

    /** The marks a sample leaves behind for [cues]. */
    fun marks(input: TickerInput): TickerMarks = TickerMarks(
        input.activity.sessions.count { it.usable && it.updatedAt != null },
        input.metrics.errors.firstOrNull()?.id, runningKeys(input.activity), input.loop.state == "backoff")

    /**
     * What is worth opening for between two samples. The first sample is only a baseline: whatever was already there when the
     * tray started watching (an old error, a task from before) never opens it.
     */
    fun cues(previous: TickerMarks?, next: TickerMarks): Set<TickerCue> {
        if (previous == null) return emptySet()
        return buildSet {
            if (next.loaded > previous.loaded) add(TickerCue.FirstData)
            if (next.latestErrorId != null && next.latestErrorId != previous.latestErrorId) add(TickerCue.Error)
            if ((next.running - previous.running).isNotEmpty()) add(TickerCue.Started)
            if ((previous.running - next.running).isNotEmpty()) add(TickerCue.Finished)
            if (next.backingOff && !previous.backingOff) add(TickerCue.BackOff)
        }
    }

    // ---- the tiles ----

    private fun requestsTile(input: TickerInput): TickerTile? {
        val v = input.vitals
        if (v.requestsPerMinute == 0 && v.inFlight.isEmpty()) return null
        val links = input.metrics.sources.filter { it.second > 0 }.take(MAX_LINKS)
            .map { (source, _) -> TickerLink(source.friendly(), TickerTarget.Hood(HoodFocus.Stream(source))) }
            .ifEmpty { listOf(TickerLink("Stream", TickerTarget.Hood(HoodFocus.Stream(null)))) }
        val flight = v.inFlight.size
        return TickerTile(TickerTileId.Activity, "REQUESTS", formatPerMinute(v.requestsPerMinute),
            when (flight) { 0 -> "none in flight"; 1 -> "1 in flight"; else -> "$flight in flight" },
            if (v.requestsPerMinute >= BUSY_PER_MINUTE || flight >= BUSY_IN_FLIGHT) TickerTone.Busy else TickerTone.Calm,
            v.requests, TickerTarget.Hood(HoodFocus.Requests), links)
    }

    private fun latencyTile(input: TickerInput): TickerTile? {
        val p50 = input.metrics.p50 ?: return null
        val p95 = input.metrics.p95
        val links = listOfNotNull(
            input.metrics.slowest.firstOrNull()?.let { TickerLink("Slowest", TickerTarget.Hood(HoodFocus.Operation(it.id))) },
            TickerLink("All slow", TickerTarget.Hood(HoodFocus.Slowest)),
        )
        val slow = (p95 != null && p95 >= SLOW_MS) || p50 >= SLOW_MS / 2
        return TickerTile(TickerTileId.Latency, "LATENCY", formatMs(p50), p95?.let { "slow end ${formatMs(it)}" },
            if (slow) TickerTone.Warning else TickerTone.Calm, emptyList(), TickerTarget.Hood(HoodFocus.Latency), links)
    }

    private fun errorsTile(input: TickerInput): TickerTile? {
        val errors = input.metrics.errors
        val latest = errors.firstOrNull() ?: return null
        val age = Duration.between(latest.endedAt ?: latest.at, input.now).seconds
        val a = input.activity
        val page: TickerLink? = when (latest.source) {
            TraceSource.Vpn -> TickerLink("VPN", TickerTarget.Vpn())
            TraceSource.Scan -> TickerLink("Scan", TickerTarget.Scan)
            TraceSource.Task -> TickerLink("Activity", TickerTarget.Activity(a.failedTask?.upid))
            TraceSource.Tools -> TickerLink("Tools", TickerTarget.Tools)
            TraceSource.Console, TraceSource.Terminal -> TickerLink("Guest", TickerTarget.Fleet(a.kept.lastOrNull()?.guest, console = true))
            TraceSource.Auth -> TickerLink("Desk", TickerTarget.Desk)
            TraceSource.Bluetooth -> TickerLink("Bluetooth", TickerTarget.Bluetooth)
            else -> null
        }
        val links = listOfNotNull(
            TickerLink("Latest", TickerTarget.Hood(HoodFocus.Operation(latest.id))),
            page,
            TickerLink("All ${latest.source.friendly()}", TickerTarget.Hood(HoodFocus.Stream(latest.source))),
        ).take(MAX_LINKS)
        val what = listOfNotNull(latest.source.friendly(), latest.status?.takeIf { it > 0 }?.toString(), generalSummary(latest.summary).takeIf { it.isNotBlank() })
        val count = errors.size
        return TickerTile(TickerTileId.Errors, "ERRORS · 5 MIN", count.toString(), shorten(what.joinToString(" "), 44),
            if (age < FRESH_ERROR_SECONDS) TickerTone.Error else TickerTone.Warning, input.vitals.errors,
            TickerTarget.Hood(HoodFocus.Errors), links, pill = "$count ${if (count == 1) "error" else "errors"}")
    }

    private fun loopTile(input: TickerInput): TickerTile? {
        val loop = input.loop
        val seconds = loop.nextTickAt?.let { Duration.between(input.now, it).seconds.coerceAtLeast(0) }
        val every = if (loop.intervalMs > 0) "every ${formatSeconds(loop.intervalMs / 1000)}" else null
        fun tile(headline: String, detail: String?, tone: TickerTone) = TickerTile(TickerTileId.LiveLoop, "REFRESH", headline, detail, tone,
            emptyList(), TickerTarget.Hood(HoodFocus.Engine), listOf(TickerLink("Engine", TickerTarget.Hood(HoodFocus.Engine))))
        return when (loop.state) {
            "refreshing" -> tile("Reading now", every, TickerTone.Busy)
            "waiting" -> seconds?.let { tile("in ${formatSeconds(it)}", every, TickerTone.Calm) }
            "backoff" -> tile("Backing off", listOfNotNull(seconds?.let { "retry in ${formatSeconds(it)}" }, loop.backoffSeconds.takeIf { it > 0 }?.let { "waits ${formatSeconds(it)}" })
                .joinToString(" · ").ifBlank { null }, TickerTone.Warning)
            else -> null
        }
    }

    private fun networkTile(input: TickerInput): TickerTile? {
        val sessions = input.activity.sessions
        val view = sessions.firstOrNull { it.inView } ?: return null
        val several = sessions.size > 1
        val tail = if (several) "${sessions.size} connections" else null
        val route = view.route
        val body = TickerTarget.Hood(HoodFocus.Network)
        return when {
            view.phase == TickerSessionPhase.Failed -> TickerTile(TickerTileId.Network, "CONNECTION", "Failed",
                listOfNotNull(view.failure?.short, tail).joinToString(" · ").ifBlank { null }, TickerTone.Error, emptyList(), body,
                listOf(TickerLink("Desk", TickerTarget.Desk), TickerLink("Engine", TickerTarget.Hood(HoodFocus.Engine))), pill = "Connection failed")
            else -> {
                val notable = several || route?.kind in NOTABLE_ROUTES
                if (!notable) return null
                TickerTile(TickerTileId.Network, "ROUTE", route?.let(::routeHeadline) ?: "Not read yet",
                    listOfNotNull(trustWords(view), tail).joinToString(" · ").ifBlank { null },
                    if (route?.kind == RouteKind.Proxy) TickerTone.Warning else TickerTone.Calm, emptyList(), body,
                    listOfNotNull(route?.takeIf { it.kind == RouteKind.Vpn }?.let { TickerLink("VPN", TickerTarget.Vpn(it.vpn)) }))
            }
        }
    }

    private val NOTABLE_ROUTES = setOf(RouteKind.Vpn, RouteKind.Proxy, RouteKind.Local)

    private fun runningTile(input: TickerInput, identities: Boolean): TickerTile? {
        val a = input.activity
        data class Item(val headline: String, val detail: String?, val link: TickerLink)
        val items = buildList {
            if (a.uploading) add(Item(a.uploadPercent?.let { "Upload $it %" } ?: "Uploading", "to storage", TickerLink("Storage", TickerTarget.Storage)))
            a.tasks.forEach { task ->
                val since = task.since?.let { Duration.between(it, input.now).seconds }?.takeIf { it >= 0 }?.let { "running ${formatSeconds(it)}" }
                val subject = task.subject?.takeIf { identities && it.isNotBlank() }
                add(Item(task.label, listOfNotNull(subject, since).joinToString(" · ").ifBlank { "running" }, TickerLink("Activity", TickerTarget.Activity(task.upid))))
            }
            a.scan?.let { add(Item("Scan ${it.percent} %", "${it.hosts} ${if (it.hosts == 1) "host" else "hosts"} answering", TickerLink("Scan", TickerTarget.Scan))) }
            if (a.toolRunning) add(Item("Tool running", "network tools", TickerLink("Tools", TickerTarget.Tools)))
            if (a.vpnBusy) add(Item("VPN change", "in progress", TickerLink("VPN", TickerTarget.Vpn())))
        }
        val consoles = input.vitals.sessions.count { it.event.source == TraceSource.Console || it.event.source == TraceSource.Terminal }
        val kept = a.kept.lastOrNull()
        val console = if (consoles == 0) null else Item(if (consoles == 1) "Console open" else "$consoles consoles open",
            kept?.subject?.takeIf { identities && it.isNotBlank() }, TickerLink("Guest", TickerTarget.Fleet(kept?.guest, console = true)))
        val shown = items + listOfNotNull(console)
        val first = shown.firstOrNull() ?: return null
        val more = shown.size - 1
        return TickerTile(TickerTileId.Now, "NOW", first.headline, listOfNotNull(first.detail, if (more > 0) "+$more more" else null).joinToString(" · ").ifBlank { null },
            TickerTone.Busy, emptyList(), TickerTarget.Hood(HoodFocus.Sessions), shown.map { it.link }.distinctBy { it.label }.take(MAX_LINKS))
    }

    private fun runningKeys(a: TickerActivity): Set<String> = buildSet {
        if (a.uploading) add("upload")
        a.tasks.forEach { add("task:${it.upid}") }
        if (a.scan != null) add("scan")
        if (a.toolRunning) add("tool")
        if (a.vpnBusy) add("vpn")
    }

    private fun routeHeadline(route: TickerRoute): String = when (route.kind) {
        RouteKind.Direct -> "Direct"
        RouteKind.Vpn -> vpnLabel(route.vpn) ?: "VPN"
        RouteKind.Proxy -> "Proxy"
        RouteKind.Local -> "This computer"
        RouteKind.Unknown -> "Not known"
    }

    private fun trustWords(session: TickerSession): String? = when (session.pinned) {
        true -> "pinned cert"
        false -> "normal TLS"
        null -> null
    }
}

// ---- words and numbers ----

/** The tooltip for a click: where it leads, in the app's own words. */
fun TickerTarget.describe(): String = when (this) {
    is TickerTarget.Hood -> "Open Under the Hood · ${focus.describe()}"
    TickerTarget.Desk -> "Open the Desk"
    TickerTarget.Scan -> "Open the Scan page"
    is TickerTarget.Vpn -> "Open the VPN page"
    TickerTarget.Tools -> "Open the Tools page"
    TickerTarget.Storage -> "Open Storage"
    TickerTarget.Bluetooth -> "Open the Bluetooth page"
    is TickerTarget.Activity -> if (upid != null) "Open Activity · this task" else "Open Activity"
    is TickerTarget.Fleet -> when { guest == null -> "Open Fleet"; console -> "Open the guest's console"; else -> "Open the guest in Fleet" }
}

fun HoodFocus.describe(): String = when (this) {
    is HoodFocus.Stream -> source?.let { "${it.friendly()} stream" } ?: "the stream"
    is HoodFocus.Operation -> "this operation"
    HoodFocus.Engine -> "Engine"
    HoodFocus.Latency -> "Latency"
    HoodFocus.Requests -> "Requests by source"
    HoodFocus.Slowest -> "Slowest"
    HoodFocus.Errors -> "Errors"
    HoodFocus.Network -> "Network"
    HoodFocus.Sessions -> "Sessions"
}

fun TraceSource.friendly(): String = when (this) {
    TraceSource.PveApi -> "PVE API"; TraceSource.PdmApi -> "PDM API"; TraceSource.Auth -> "Sign-in"
    TraceSource.Console -> "Console"; TraceSource.Terminal -> "Terminal"; TraceSource.Task -> "Tasks"
    TraceSource.LiveLoop -> "Refresh"; TraceSource.Scan -> "Scan"; TraceSource.Tools -> "Tools"; TraceSource.Vpn -> "VPN"
    TraceSource.Bluetooth -> "Bluetooth"; TraceSource.Settings -> "Settings"; TraceSource.App -> "App"; TraceSource.Web -> "Web"
    TraceSource.Services -> "Linked services"
}

fun vpnLabel(kind: VpnKind?): String? = when (kind) {
    VpnKind.Tailscale -> "Tailscale"; VpnKind.WireGuard -> "WireGuard"; VpnKind.OpenVpn -> "OpenVPN"; VpnKind.IpSec -> "IPsec"
    VpnKind.Other, null -> null
}

/** "38 ms", "1.2 s", "12 s". */
fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 10_000 -> "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
    else -> "${(ms + 500) / 1000} s"
}

/** "12/min", "1.2k/min". */
fun formatPerMinute(n: Int): String = if (n < 1000) "$n/min" else "%.1fk/min".format(java.util.Locale.ROOT, n / 1000.0)

/** "5 s", "2 min", "1 h": whole units. */
fun formatSeconds(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}

fun shorten(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"

private val namedPathParts = Regex("""(?i)/(nodes|qemu|lxc|storage|vms|ct|pools|users|tokens|sdn|backup)/[^/\s?]+""")

/**
 * An operation's summary without the names in it: "POST /nodes/pve-east/qemu/120/status/start?node=a" becomes
 * "POST /nodes/…/qemu/…/status/start". The summary is already redacted; this also keeps node and guest names off a tile.
 */
fun generalSummary(summary: String): String = namedPathParts.replace(summary.trim().substringBefore('?')) { "/${it.groupValues[1]}/…" }
