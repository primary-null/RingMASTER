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
internal const val LIVE_DEMO_SEED = 14L

private const val TAG_MAX_COUNT = 16
private const val TAG_MAX_CHARS = 40
private const val DEMO_GIB = 1024L * 1024 * 1024

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

/**
 * One seeded step of the demo inventory. CPU stays inside 0..1, memory stays inside 0..max,
 * and network counters only increase. A guest that is not running contributes no CPU or memory.
 */
internal fun stepDemoOverview(overview: Overview, random: Random): Overview {
    val guests = overview.guests.map { guest ->
        if (guest.status != "running") {
            guest.copy(metrics = GuestListMetrics(
                cpuFraction = null,
                maxCpu = guest.metrics?.maxCpu,
                memoryBytes = null,
                memoryLimitBytes = guest.metrics?.memoryLimitBytes,
                diskBytes = guest.metrics?.diskBytes,
                diskLimitBytes = guest.metrics?.diskLimitBytes,
                uptimeSeconds = guest.metrics?.uptimeSeconds,
                netIn = null,
                netOut = null,
            ))
        } else {
            val base = demoRunningMetrics(guest)
            val maxMem = base.memoryLimitBytes?.takeIf { it > 0L } ?: (8L * DEMO_GIB)
            // Each demo guest has its own load profile: drift back toward a per-VMID target, noise, rare spikes.
            val target = demoCpuTarget(guest.id)
            val previous = base.cpuFraction ?: target
            val spike = if (random.nextDouble() < 0.06) random.nextDouble(0.15, 0.4) else 0.0
            val cpu = (previous + (target - previous) * 0.25 + (random.nextDouble() - 0.5) * 0.09 + spike).coerceIn(0.005, 0.98)
            val swing = (maxMem / 40L).coerceAtLeast(1L)
            val memTarget = (maxMem * demoMemTarget(guest.id)).toLong()
            val memPrev = base.memoryBytes ?: memTarget
            val mem = (memPrev + (memTarget - memPrev) / 5 + random.nextLong(-swing, swing + 1)).coerceIn(0L, maxMem)
            val netIn = (base.netIn ?: (10L * 1024 * 1024)) + random.nextLong(1L, 65_537L)
            val netOut = (base.netOut ?: (4L * 1024 * 1024)) + random.nextLong(1L, 32_769L)
            val uptime = (base.uptimeSeconds ?: (3_600L * (6 + guest.id % 90))) + LIVE_INTERVAL_DEFAULT
            guest.copy(metrics = base.copy(cpuFraction = cpu, memoryBytes = mem, memoryLimitBytes = maxMem, netIn = netIn, netOut = netOut, uptimeSeconds = uptime))
        }
    }
    val members = overview.members.map { member ->
        val cpu = member.cpuFraction?.takeIf { it.isFinite() }?.let { (it + (random.nextDouble() - 0.5) * 0.08).coerceIn(0.0, 1.0) }
        val maxMem = member.maxMemoryBytes
        val used = member.memoryBytes
        val memory = if (used != null && maxMem != null && maxMem > 0L) {
            val swing = (maxMem / 20L).coerceAtLeast(1L)
            (used + random.nextLong(-swing, swing + 1)).coerceIn(0L, maxMem)
        } else {
            used
        }
        member.copy(cpuFraction = cpu, memoryBytes = memory)
    }
    return overview.copy(guests = guests, members = members)
}

/** Stable per-guest demo load: 3%..68% CPU, 22%..78% memory, from the VMID. */
internal fun demoCpuTarget(vmid: Int): Double = 0.03 + ((vmid.toLong() * 2654435761L).mod(1000L)) / 1000.0 * 0.65
internal fun demoMemTarget(vmid: Int): Double = 0.22 + ((vmid.toLong() * 40503L).mod(1000L)) / 1000.0 * 0.56

private fun demoRunningMetrics(guest: Guest): GuestListMetrics {
    val existing = guest.metrics
    return GuestListMetrics(
        cpuFraction = existing?.cpuFraction?.takeIf { it.isFinite() && it >= 0.0 } ?: demoCpuTarget(guest.id),
        maxCpu = existing?.maxCpu ?: 4.0,
        memoryBytes = existing?.memoryBytes ?: (2L * DEMO_GIB),
        memoryLimitBytes = existing?.memoryLimitBytes?.takeIf { it > 0L } ?: (8L * DEMO_GIB),
        diskBytes = existing?.diskBytes,
        diskLimitBytes = existing?.diskLimitBytes,
        uptimeSeconds = existing?.uptimeSeconds,
        netIn = existing?.netIn ?: (10L * 1024 * 1024),
        netOut = existing?.netOut ?: (4L * 1024 * 1024),
    )
}

internal fun demoLiveDetails(guest: Guest): GuestDetails {
    val metrics = guest.metrics ?: return demoDetails(guest)
    val maxCpu = metrics.maxCpu
    val cpus = maxCpu?.takeIf { it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
    return GuestDetails(
        guest, guest.status, cpus, metrics.cpuFraction,
        metrics.memoryBytes, metrics.memoryLimitBytes?.takeIf { it > 0L },
        if (guest.kind == "Container") metrics.diskBytes else null, metrics.diskLimitBytes?.takeIf { it > 0L },
        metrics.uptimeSeconds, metrics.netIn, metrics.netOut,
    )
}
