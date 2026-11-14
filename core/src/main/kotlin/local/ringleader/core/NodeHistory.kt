package local.ringleader.core

val historyTimeframes = listOf("hour", "day", "week", "month", "year")
val historyConsolidations = listOf("AVERAGE", "MAX")
fun historySeconds(timeframe: String): Double {
    require(timeframe in historyTimeframes)
    return when(timeframe) { "hour" -> 3600.0; "day" -> 86400.0; "week" -> 604800.0; "month" -> 2592000.0; else -> 31536000.0 }
}
fun historySuffix(timeframe: String, cf: String): String {
    require(timeframe in historyTimeframes); require(cf in historyConsolidations)
    return "/rrddata?timeframe=$timeframe&cf=$cf"
}
fun nearestHistory(points: List<HistoryPoint>, time: Double): HistoryPoint? = points.minByOrNull { kotlin.math.abs(it.time-time) }
/**
 * The sample under a pointer [fraction] (0 = left edge, 1 = right edge) of a plot that spans [points]. The crosshair,
 * the markers and the readout all use this one sample, so they name the same moment.
 */
fun historyAt(points: List<HistoryPoint>, fraction: Double): HistoryPoint? {
    if (points.isEmpty()) return null
    val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
    return nearestHistory(points, points.first().time + (points.last().time - points.first().time) * f)
}
/**
 * Axis time pattern for the visible span in seconds, so a zoomed week or month still shows the time of day.
 * Up to 2 hours: clock with seconds; up to 8 days: day and time; up to 120 days: day; longer: day and year.
 */
fun historyAxisPattern(spanSeconds: Double): String = when {
    !spanSeconds.isFinite() || spanSeconds <= 2 * 3600.0 -> "HH:mm:ss"
    spanSeconds <= 8 * 86400.0 -> "dd MMM HH:mm"
    spanSeconds <= 120 * 86400.0 -> "dd MMM"
    else -> "dd MMM yy"
}
/** Readout time pattern: one sample, so it always carries the time of day; the year only on a long span. */
fun historyReadoutPattern(spanSeconds: Double): String = when {
    !spanSeconds.isFinite() || spanSeconds <= 2 * 3600.0 -> "HH:mm:ss"
    spanSeconds <= 120 * 86400.0 -> "dd MMM HH:mm"
    else -> "dd MMM yy HH:mm"
}
fun historyRange(a: Double, b: Double): ClosedFloatingPointRange<Double>? = if(a.isFinite() && b.isFinite() && a != b) minOf(a,b)..maxOf(a,b) else null
/** A history time (epoch seconds) as ISO-8601 in UTC, such as "2026-11-11T09:30:00Z". Empty when it is not a usable time. */
fun historyUtc(time: Double): String =
    if (!time.isFinite()) "" else runCatching { java.time.Instant.ofEpochMilli(kotlin.math.round(time * 1000).toLong()).toString() }.getOrDefault("")
/** Epoch seconds as a plain number: Double.toString writes "1.7943894E9" for a real time. */
internal fun historyEpoch(time: Double): String =
    if (!time.isFinite()) "" else java.math.BigDecimal.valueOf(time).stripTrailingZeros().toPlainString()
/** The numeric epoch-seconds column stays first for scripts; the same moment follows as ISO-8601 UTC for people and spreadsheets. */
fun historyCsv(points: List<HistoryPoint>, keys: List<String>): String =
    "time,time_utc," + keys.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n" +
        points.joinToString("\n") { p -> historyEpoch(p.time) + "," + historyUtc(p.time) + "," + keys.joinToString(",") { p.values[it]?.toString().orEmpty() } } + "\n"
fun demoNodeHistory(timeframe: String, cf: String): List<HistoryPoint> {
    historySuffix(timeframe,cf)
    val now = java.time.Instant.now().epochSecond.toDouble()
    return (0..119).map { n ->
        val wave = (1 + kotlin.math.sin(n/9.0))/2
        val scale = if(cf == "MAX") 1.3 else 1.0
        HistoryPoint(now-(119-n)*historySeconds(timeframe)/119, historyKeys.associateWith { k -> when(k) {
            "cpu" -> (.1+.4*wave)*scale; "iowait" -> .03*wave*scale; "loadavg" -> 1+wave*3
            "memused" -> 8e9+wave*2e9; "memtotal" -> 16e9; "swapused" -> wave*1e8; "swaptotal" -> 2e9
            "netin", "netout" -> (1e5+wave*5e6)*scale
            else -> if(k.startsWith("pressure")) wave*4*scale else null
        } })
    }
}
