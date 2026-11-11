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
fun historyRange(a: Double, b: Double): ClosedFloatingPointRange<Double>? = if(a.isFinite() && b.isFinite() && a != b) minOf(a,b)..maxOf(a,b) else null
fun historyCsv(points: List<HistoryPoint>, keys: List<String>): String =
    "time," + keys.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n" + points.joinToString("\n") { p -> p.time.toString() + "," + keys.joinToString(",") { p.values[it]?.toString().orEmpty() } } + "\n"
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
