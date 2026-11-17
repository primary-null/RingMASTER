package local.ringleader.core

import java.time.Instant
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val SERIES_CAP = 120
const val LIVE_INTERVAL_MIN = 2
const val LIVE_INTERVAL_MAX = 120
const val LIVE_INTERVAL_DEFAULT = 5
/** The refresh choices offered in the app: 5 s (default), 10 s, 30 s, 2 min. */
val LIVE_INTERVAL_CHOICES = listOf(5, 10, 30, 120)
/** "5s", "30s", "2m". */
fun liveIntervalLabel(seconds: Int): String = if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"
const val LIVE_TASK_INTERVAL_SECONDS = 10
/**
 * PVE refreshes guest network counters about every 10 s, so a faster poll often sees the same values.
 * Within this window an unchanged pair of counters keeps the last rate instead of reporting zero.
 */
internal const val NET_COUNTER_HOLD_SECONDS = 25.0

private const val TAG_MAX_COUNT = 16
private const val TAG_MAX_CHARS = 40

/**
 * One fleet sample. Network fields are bytes per second, not counters. Newest samples stay at the end of a [Series].
 *
 * [held] marks a sample whose guest or node the server had not refreshed since the sample before it, so its values
 * repeat. Proxmox refreshes its numbers about every 10 seconds, each node on its own clock; a faster poll, or a poll of
 * several nodes, keeps meeting numbers it has already seen. A chart draws straight through held samples instead of
 * drawing a step.
 */
data class Sample(
    val at: Instant,
    val cpu: Double?,
    val mem: Long?,
    val memMax: Long?,
    val netInRate: Double?,
    val netOutRate: Double?,
    val held: Boolean = false,
)

/** Newest sample last. [appendCapped] keeps at most [SERIES_CAP] samples. */
data class Series(val samples: List<Sample> = emptyList())

data class FleetHistory(
    val guests: Map<ResourceId, Series> = emptyMap(),
    val nodes: Map<String, Series> = emptyMap(),
    val cluster: Series = Series(),
)

/** Running and stopped counts ignore templates. Any other status is included only in [guests]. */
data class FleetTotals(
    val running: Int,
    val stopped: Int,
    val templates: Int,
    val guests: Int,
    val nodes: Int,
    val cpuFraction: Double?,
    val memoryBytes: Long?,
    val memoryLimitBytes: Long?,
)

internal data class ClusterAggregate(val cpuFraction: Double?, val memoryBytes: Long?, val memoryLimitBytes: Long?)

internal data class NetCounters(val at: Instant, val netIn: Long?, val netOut: Long?)

/**
 * [readings] are the raw numbers of this read, per guest and node, to tell a refreshed one from a repeated one next
 * time. [fresh] is false when the server had refreshed nothing since the read before: then nothing was recorded.
 */
internal data class FleetHistoryStep(
    val history: FleetHistory, val counters: Map<ResourceId, NetCounters>,
    val readings: Map<Any, Any?> = emptyMap(), val fresh: Boolean = true,
)

fun clampLiveInterval(seconds: Int): Int = seconds.coerceIn(LIVE_INTERVAL_MIN, LIVE_INTERVAL_MAX)

/** 5, 10, 20, 40, then 60 seconds. Further failures stay at 60. */
internal fun nextLiveBackoff(current: Int?): Int = when (current) {
    null -> 5
    5 -> 10
    10 -> 20
    20 -> 40
    else -> 60
}

/** Why a history chart is empty when its RRD cannot be read now. */
internal const val HISTORY_NOT_CONNECTED = "Connect to a server to read history."
internal const val HISTORY_DIRECT_ONLY = "History needs a direct Proxmox VE connection."
internal const val HISTORY_NOTHING_SELECTED = "Select a guest or node to read its history."
internal const val HISTORY_STALE = "The inventory is out of date. History loads again when it is back."

/** Largest share of a wait that [liveWaitMillis] adds or takes away: about ±15%. */
internal const val LIVE_JITTER_FRACTION = 0.15
/** A tick that found another read still running looks again after this long instead of waiting a whole interval. */
internal const val LIVE_BUSY_RECHECK_MS = 1_000L

/**
 * How long the live loop waits before its next read (GET only; a mutation is never retried on this schedule).
 *
 * The base is the larger of the chosen interval and the error backoff, so a failure never makes the loop poll faster than
 * the user asked. [jitter] is in -1..1 and moves the base by up to [LIVE_JITTER_FRACTION]; the loop passes 0 on a normal
 * tick and a random value after a failure or a resume, so several sessions do not poll in the same second. A jittered
 * wait that would fall below the interval is folded back above it: the wait is never shorter than the interval.
 */
internal fun liveWaitMillis(intervalSeconds: Int, backoffSeconds: Int?, jitter: Double = 0.0): Long {
    val interval = clampLiveInterval(intervalSeconds) * 1000L
    val base = maxOf(interval, (backoffSeconds ?: 0) * 1000L)
    val shift = (base * LIVE_JITTER_FRACTION * jitter.coerceIn(-1.0, 1.0)).toLong()
    val jittered = base + shift
    return if (jittered < interval) 2 * interval - jittered else jittered
}

internal fun Series.appendCapped(sample: Sample): Series = Series((samples + sample).takeLast(SERIES_CAP))

/**
 * Cluster CPU is the capacity-weighted node average. A node with no CPU, no capacity, or zero capacity is left out.
 * Memory sums only the nodes that reported that field. An all-missing total stays null rather than zero.
 */
internal fun clusterAggregate(members: List<RecordedMember>): ClusterAggregate {
    var weighted = 0.0
    var capacity = 0.0
    var memory = 0L
    var memorySeen = false
    var limit = 0L
    var limitSeen = false
    for (member in members) {
        val cpu = member.cpuFraction
        val maxCpu = member.maxCpu
        if (cpu != null && maxCpu != null && cpu.isFinite() && maxCpu.isFinite() && cpu >= 0.0 && maxCpu > 0.0) {
            weighted += cpu * maxCpu
            capacity += maxCpu
        }
        val used = member.memoryBytes
        if (used != null && used >= 0L) {
            memory += used
            memorySeen = true
        }
        val maxMemory = member.maxMemoryBytes
        if (maxMemory != null && maxMemory > 0L) {
            limit += maxMemory
            limitSeen = true
        }
    }
    return ClusterAggregate(
        if (capacity > 0.0) weighted / capacity else null,
        if (memorySeen) memory else null,
        if (limitSeen) limit else null,
    )
}

internal fun clusterSample(members: List<RecordedMember>, at: Instant): Sample {
    val aggregate = clusterAggregate(members)
    return Sample(at, aggregate.cpuFraction, aggregate.memoryBytes, aggregate.memoryLimitBytes, null, null)
}

fun fleetTotals(overview: Overview): FleetTotals {
    val guests = overview.guests
    val aggregate = clusterAggregate(overview.members)
    return FleetTotals(
        running = guests.count { !it.template && it.status == "running" },
        stopped = guests.count { !it.template && it.status == "stopped" },
        templates = guests.count { it.template },
        guests = guests.size,
        nodes = if (overview.members.isNotEmpty()) overview.members.size else overview.nodes,
        cpuFraction = aggregate.cpuFraction,
        memoryBytes = aggregate.memoryBytes,
        memoryLimitBytes = aggregate.memoryLimitBytes,
    )
}

/** Negative delta means the counter reset. The first sample, a missing counter, and a non-positive gap have no rate. */
internal fun counterRate(previous: Long?, current: Long?, elapsedSeconds: Double?): Double? {
    if (previous == null || current == null || elapsedSeconds == null || elapsedSeconds <= 0.0) return null
    val delta = current - previous
    if (delta < 0L) return null
    return delta.toDouble() / elapsedSeconds
}

/**
 * One step of the live history. [readings] are the raw numbers of the read before (empty on the first read).
 *
 * A read in which the server refreshed nothing records nothing: with one node polled every 5 seconds that is every
 * other read, and recording it drew every value twice (a staircase) and moved the charts without new data. In a read
 * where only some nodes refreshed, the others get a [Sample.held] sample, so all series keep the same time base.
 */
internal fun nextFleetHistory(
    history: FleetHistory,
    counters: Map<ResourceId, NetCounters>,
    overview: Overview,
    at: Instant,
    readings: Map<Any, Any?> = emptyMap(),
): FleetHistoryStep {
    val nextReadings = HashMap<Any, Any?>(overview.guests.size + overview.members.size)
    overview.guests.forEach { nextReadings[it.resource] = it.status to it.metrics }
    overview.members.forEach { nextReadings[memberReadingKey(it)] = it }
    if (readings.isNotEmpty() && nextReadings == readings) return FleetHistoryStep(history, counters, readings, fresh = false)
    fun repeated(key: Any) = readings.containsKey(key) && readings[key] == nextReadings[key]
    val nextCounters = HashMap<ResourceId, NetCounters>(overview.guests.size)
    val guests = overview.guests.associate { guest ->
        val metrics = guest.metrics
        val previous = counters[guest.resource]
        val elapsed = previous?.let { (at.toEpochMilli() - it.at.toEpochMilli()) / 1000.0 }
        // Counters not refreshed yet: hold the last rate and keep the old counters, so the next change is averaged over the whole gap.
        val held = previous != null && elapsed != null && elapsed > 0.0 && elapsed < NET_COUNTER_HOLD_SECONDS &&
            metrics?.netIn != null && metrics.netIn == previous.netIn && metrics.netOut == previous.netOut
        val last = history.guests[guest.resource]?.samples?.lastOrNull()
        val sample = Sample(
            at,
            metrics?.cpuFraction?.takeIf { it.isFinite() && it >= 0.0 },
            metrics?.memoryBytes?.takeIf { it >= 0L },
            metrics?.memoryLimitBytes?.takeIf { it >= 0L },
            if (held) last?.netInRate else counterRate(previous?.netIn, metrics?.netIn, elapsed),
            if (held) last?.netOutRate else counterRate(previous?.netOut, metrics?.netOut, elapsed),
            held = repeated(guest.resource),
        )
        nextCounters[guest.resource] = if (held) previous!! else NetCounters(at, metrics?.netIn, metrics?.netOut)
        guest.resource to (history.guests[guest.resource] ?: Series()).appendCapped(sample)
    }
    val nodes = overview.members.associate { member ->
        val sample = Sample(
            at,
            member.cpuFraction?.takeIf { it.isFinite() && it >= 0.0 },
            member.memoryBytes?.takeIf { it >= 0L },
            member.maxMemoryBytes?.takeIf { it >= 0L },
            null,
            null,
            held = repeated(memberReadingKey(member)),
        )
        member.name to (history.nodes[member.name] ?: Series()).appendCapped(sample)
    }
    // Cluster network is the sum of guest rates from this tick; null when no guest has a rate yet.
    fun total(rate: (Sample) -> Double?) = guests.values.mapNotNull { it.samples.lastOrNull()?.let(rate) }.takeIf { it.isNotEmpty() }?.sum()
    val cluster = clusterSample(overview.members, at).copy(netInRate = total { it.netInRate }, netOutRate = total { it.netOutRate })
    return FleetHistoryStep(
        FleetHistory(guests, nodes, history.cluster.appendCapped(cluster)),
        nextCounters,
        nextReadings,
    )
}

private fun memberReadingKey(member: RecordedMember): Any = "node" to (member.remote to member.name)

/** Split a resource-list tags string on ';', ',', and whitespace. Control characters are ignored. */
fun parseResourceTags(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val filtered = buildString(raw.length) {
        for (ch in raw) {
            if (ch.isISOControl() && !ch.isWhitespace()) continue
            append(ch)
        }
    }
    return filtered.split(Regex("[;,\\s]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(TAG_MAX_CHARS) }
        .distinct()
        .take(TAG_MAX_COUNT)
}

internal fun parseResourceTags(value: JsonElement?): List<String> {
    val primitive = value as? JsonPrimitive ?: return emptyList()
    if (!primitive.isString) return emptyList()
    return parseResourceTags(primitive.contentOrNull)
}
