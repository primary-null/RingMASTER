package local.ringleader.core

import java.time.Instant
import kotlin.math.ceil

/** Computed once per engine tick from the bounded operation snapshot. */
data class TraceMetrics(
    val p50: Long?, val p95: Long?, val histogram: List<Int>,
    val sources: List<Pair<TraceSource, Int>>, val slowest: List<TraceEvent>, val errors: List<TraceEvent>
) {
    companion object {
        val bucketLabels = listOf("<10", "<50", "<100", "<500", "<1k", "1k+")
        fun from(events: List<TraceEvent>, now: Instant): TraceMetrics {
            fun recent(e: TraceEvent, seconds: Long): Boolean {
                val time = e.endedAt ?: e.at
                return !time.isBefore(now.minusSeconds(seconds)) && !time.isAfter(now)
            }
            val minute = events.filter { recent(it, 60) }
            val durations = minute.filter { it.kind == "end" }.mapNotNull { it.durationMs }.sorted()
            fun percentile(p: Double) = durations.takeIf { it.isNotEmpty() }?.get((ceil(durations.size * p).toInt()-1).coerceAtLeast(0))
            val histogram = MutableList(6) { 0 }
            durations.forEach { ms -> histogram[when { ms<10 -> 0; ms<50 -> 1; ms<100 -> 2; ms<500 -> 3; ms<1000 -> 4; else -> 5 }]++ }
            val requests = minute.filter { it.kind == "end" && it.source in setOf(TraceSource.PveApi, TraceSource.PdmApi, TraceSource.Auth) }
            val fiveMinutes = events.filter { recent(it, 300) }
            return TraceMetrics(percentile(.5), percentile(.95), histogram,
                requests.groupingBy { it.source }.eachCount().toList().sortedByDescending { it.second },
                fiveMinutes.filter { it.kind == "end" && it.durationMs != null }.sortedByDescending { it.durationMs }.take(5),
                fiveMinutes.filter { it.outcome == TraceOutcome.Error }.sortedByDescending { it.endedAt ?: it.at })
        }
    }
}
