package local.ringleader.core.integrations

import java.time.Instant
import java.util.Locale

/** What a reading's number or text means. Formatting belongs to the unit, not to the expression. */
enum class ReadingUnit(val id: String, val numeric: Boolean) {
    Count("count", true), Number("number", true), Percent("percent", true), Ratio("ratio", true),
    Bytes("bytes", true), BytesPerSecond("bytes_per_s", true), BitsPerSecond("bits_per_s", true),
    Milliseconds("ms", true), Seconds("seconds", true), Ticks("ticks", true), Epoch("epoch", true), Celsius("celsius", true),
    Text("text", false), State("state", false), Version("version", false);

    companion object {
        fun of(id: String): ReadingUnit? = entries.firstOrNull { it.id == id }
        val ids get() = entries.map { it.id }
    }
}

/** A reading's colour, never shown by colour alone: the UI pairs it with a word. */
enum class Level { Neutral, Good, Info, Warn, Bad }

/** Numeric thresholds. A reading with thresholds and a value inside them is Good; one without is Neutral. */
data class Levels(val warnAbove: Double? = null, val badAbove: Double? = null, val warnBelow: Double? = null, val badBelow: Double? = null) {
    fun of(value: Double): Level = when {
        badAbove != null && value > badAbove -> Level.Bad
        badBelow != null && value < badBelow -> Level.Bad
        warnAbove != null && value > warnAbove -> Level.Warn
        warnBelow != null && value < warnBelow -> Level.Warn
        else -> Level.Good
    }
}

/**
 * One formatter for every unit, so tiles, lists, the golden text and later the axes and the ticker agree. Root locale:
 * the UI may localise later, the goldens must not move.
 */
object ServiceUnits {
    fun format(value: XValue, unit: ReadingUnit, now: Instant = Instant.EPOCH): String = when (value) {
        is XValue.Gone -> "no data"
        is XValue.Num -> number(value.value, unit, now)
        is XValue.Text -> if (unit.numeric) value.value.toDoubleOrNull()?.let { number(it, unit, now) } ?: value.value.take(80) else value.value.take(80)
        is XValue.Bool -> value.value.toString()
        is XValue.Items -> "${value.items.size} items"
        is XValue.Obj -> "an object"
    }

    fun number(v: Double, unit: ReadingUnit, now: Instant = Instant.EPOCH): String = when (unit) {
        ReadingUnit.Count -> if (Math.abs(v) >= 1e15) String.format(Locale.ROOT, "%.3g", v) else String.format(Locale.ROOT, "%,d", Math.round(v))
        ReadingUnit.Number -> trim(v)
        ReadingUnit.Percent -> String.format(Locale.ROOT, "%.1f %%", v)
        ReadingUnit.Ratio -> String.format(Locale.ROOT, "%.1f %%", v * 100)
        ReadingUnit.Bytes -> iec(v)
        ReadingUnit.BytesPerSecond -> iec(v) + "/s"
        ReadingUnit.BitsPerSecond -> si(v) + "bit/s"
        ReadingUnit.Milliseconds -> if (v < 10) String.format(Locale.ROOT, "%.1f ms", v) else String.format(Locale.ROOT, "%,d ms", Math.round(v))
        ReadingUnit.Seconds -> duration(v)
        ReadingUnit.Ticks -> duration(v / 10_000_000.0)
        ReadingUnit.Epoch -> {
            val ago = now.epochSecond - v
            if (ago < -60) "in " + duration(-ago) else if (ago < 60) "just now" else duration(ago) + " ago"
        }
        ReadingUnit.Celsius -> String.format(Locale.ROOT, "%.1f \u00b0C", v)
        ReadingUnit.Text, ReadingUnit.State, ReadingUnit.Version -> TemplateExpr.plain(XValue.Num(v)) ?: v.toString()
    }

    private fun trim(v: Double): String = when {
        Math.abs(v) >= 1e15 -> String.format(Locale.ROOT, "%.3g", v)
        v == Math.rint(v) -> String.format(Locale.ROOT, "%,d", v.toLong())
        Math.abs(v) >= 100 -> String.format(Locale.ROOT, "%,.0f", v)
        Math.abs(v) >= 10 -> String.format(Locale.ROOT, "%.1f", v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }

    /** IEC binary units, as `compactIec` does elsewhere in the app. */
    fun iec(v: Double): String {
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var x = v; var i = 0
        while (Math.abs(x) >= 1024 && i < units.lastIndex) { x /= 1024; i++ }
        return if (i == 0) String.format(Locale.ROOT, "%d B", Math.round(x)) else String.format(Locale.ROOT, if (Math.abs(x) >= 100) "%.0f %s" else "%.1f %s", x, units[i])
    }

    private fun si(v: Double): String {
        val units = listOf("", "k", "M", "G", "T", "P")
        var x = v; var i = 0
        while (Math.abs(x) >= 1000 && i < units.lastIndex) { x /= 1000; i++ }
        return String.format(Locale.ROOT, if (i == 0) "%.0f %s" else "%.1f %s", x, units[i])
    }

    /** 45 s, 12 min, 3 h 5 min, 2 d 4 h. */
    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite()) return "no data"
        val s = Math.round(Math.abs(seconds))
        val sign = if (seconds < 0) "-" else ""
        return sign + when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h" + (s % 3600 / 60).let { if (it > 0) " $it min" else "" }
            else -> "${s / 86_400} d" + (s % 86_400 / 3600).let { if (it > 0) " $it h" else "" }
        }
    }

    /** The key a state reading looks up in its `states` map: text as is, booleans as true/false, whole numbers without ".0". */
    fun stateKey(value: XValue): String? = TemplateExpr.plain(value)
}
