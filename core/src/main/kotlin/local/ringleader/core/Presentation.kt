package local.ringleader.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.nio.file.Files

fun iec(value: Double?, rate: Boolean = false): String {
    if (value == null || !value.isFinite() || value < 0) return "Unavailable"
    var n = value; var unit = 0
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    while (n >= 1024 && unit < units.lastIndex) { n /= 1024; unit++ }
    return String.format(Locale.ROOT, if (unit == 0) "%.0f %s" else "%.1f %s", n, units[unit]) + if (rate) "/s" else ""
}
fun percentage(value: Double?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT,"%.1f%%",it * 100) } ?: "Unavailable"
fun duration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "Unavailable"
    val parts = listOf(seconds / 86400 to "d", seconds / 3600 % 24 to "h", seconds / 60 % 60 to "m")
    return parts.filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }.ifEmpty { "${seconds}s" }
}
/** Task run time: "45s", "2m 10s", or the coarse [duration] form from one hour up. */
fun taskDuration(seconds: Long?): String = when {
    seconds == null || seconds < 0 -> "Unavailable"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> duration(seconds)
}
fun yesNo(value: Boolean) = if (value) "Yes" else "No"
fun dateAndAge(epoch: Long?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (epoch == null) return "Unavailable"
    val instant = Instant.ofEpochSecond(epoch)
    val delta = now.epochSecond - epoch
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(zone).format(instant) + " · " +
        if (delta < 0) "in ${duration(-delta)}" else "${duration(delta)} ago"
}
fun parseOsRelease(text: String): String? {
    val values = text.lineSequence().filter { !it.trimStart().startsWith("#") && '=' in it }.associate {
        it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
    }
    return (values["PRETTY_NAME"]?.takeIf { it.isNotBlank() } ?: values["NAME"]?.takeIf { it.isNotBlank() })
}
fun platformLabel(): String {
    val os = System.getProperty("os.name")
    return when { os.startsWith("Windows") -> "WINDOWS"; os.startsWith("Linux") -> runCatching {
        parseOsRelease(Files.readString(java.nio.file.Path.of("/etc/os-release")))
    }.getOrNull() ?: os; else -> os }.uppercase(Locale.ROOT)
}
