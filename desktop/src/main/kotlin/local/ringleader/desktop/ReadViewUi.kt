package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import local.ringleader.core.ReadSection

/*
 * Shared pieces of the 0.21.4 read-only views (permissions, node services, system log, package updates, storage contents).
 * The views follow one rule: show the last good data, say when it is stale, and say "not permitted" in amber rather than as a fault.
 */

/** "14:03:22" in the system zone, or a dash. */
internal fun readClock(at: Instant?): String =
    at?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)) ?: "—"

/** Sizes in binary units. A value that rounds to 1024 of a unit is shown in the next unit. Unknown sizes are a dash. */
internal fun storageSize(value: Long?): String {
    if (value == null || value < 0L) return "—"
    if (value < 1024L) return "$value B"
    val units = arrayOf(
        1L shl 10 to "KiB",
        1L shl 20 to "MiB",
        1L shl 30 to "GiB",
        1L shl 40 to "TiB",
        1L shl 50 to "PiB",
    )
    var index = units.indexOfLast { value >= it.first }
    while (true) {
        val (unit, label) = units[index]
        val decimals = when (label) {
            "KiB" -> 0
            "MiB" -> if (value >= 100L shl 20) 0 else 1
            "GiB" -> if (value >= 100L shl 30) 0 else 1
            else -> 2
        }
        val text = String.format(Locale.ROOT, "%.${decimals}f", value.toDouble() / unit)
        if (text.toDouble() >= 1024.0 && index < units.lastIndex) {
            index++
            continue
        }
        return "$text $label"
    }
}

/** "2026-11-07 14:03" in the system zone, or a dash. */
internal fun volumeDate(at: Instant?): String =
    at?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)) ?: "—"

/**
 * The lines under a read-only view: a busy bar while a read runs, the sample label, the failure (amber when it is only
 * "not permitted", red otherwise) and a stale note when a refresh failed but older data is still shown.
 */
@Composable internal fun ReadNotices(section: ReadSection<*>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (section.loading) TechProgress()
        if (section.sample) AttentionTag("Sample data", help = "Demonstration data. Nothing was read from a server.")
        section.error?.let { message ->
            Text(message, color = if (section.denied) Tech.Amber else Tech.Red, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (section.stale && section.value != null) {
            Text("Showing the last good data. The latest refresh did not finish.", color = Tech.Amber, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
