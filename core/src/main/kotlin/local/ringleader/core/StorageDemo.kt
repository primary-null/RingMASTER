package local.ringleader.core

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * Demo data for the storage view (design section 6). Handwritten and deterministic; every caller marks it as sample data.
 * Nothing here came from a server. The demo listings stay in StorageContent.kt ([demoStorageContent]).
 */

/**
 * The demo storage configuration: the two storages of [demoOverview] plus one disabled storage (`old-nfs`), which the resource
 * list leaves out, and retention rules on `shared-data`. Only allowlisted fields exist, as after [parseStorageConfig].
 */
val demoStorageConfig: List<StorageConfigRow> = listOf(
    StorageConfigRow("local", "dir", setOf("iso", "vztmpl", "import"), disabled = false, shared = false, nodes = setOf("demo-node-a"), pruneBackups = null, maxProtected = null),
    StorageConfigRow("local-lvm", "lvmthin", setOf("images", "rootdir"), disabled = false, shared = false,
        nodes = setOf("demo-node-a"), pruneBackups = null, maxProtected = null),
    StorageConfigRow("old-nfs", "nfs", setOf("iso", "backup"), disabled = true, shared = true,
        nodes = null, pruneBackups = KeepRules(last = 2), maxProtected = null),
    StorageConfigRow("shared-data", "dir", setOf("images", "backup"), disabled = false, shared = true,
        nodes = null, pruneBackups = KeepRules(last = 3, weekly = 2), maxProtected = 5),
)

private const val DEMO_HISTORY_POINTS = 120

/**
 * Sample usage history of one demo storage ending at [now]: a slow climb to the row's current used bytes, with a drop about
 * two thirds of the way along (a prune), and a constant total. MAX reads a little higher, never above the total. The shape is
 * the same for every call with the same arguments. A row without figures gives samples without values (gaps).
 */
fun demoStorageHistory(storage: RecordedStorage, timeframe: String, cf: String, now: Instant): List<HistoryPoint> {
    historySuffix(timeframe, cf)
    val end = now.epochSecond.toDouble()
    val span = historySeconds(timeframe)
    val total = storage.maxDiskBytes?.toDouble()
    val used = storage.diskBytes?.toDouble()
    val last = DEMO_HISTORY_POINTS - 1
    val dropAt = (last * 2) / 3
    return (0..last).map { n ->
        val time = end - (last - n) * span / last
        val value = if (used == null) null else {
            val progress = n.toDouble() / last
            // Climb from 70 % of today's figure; the prune takes 8 % away and the climb resumes from there.
            val climb = 0.70 + 0.30 * progress - if (n >= dropAt) 0.08 * (1 - (n - dropAt).toDouble() / (last - dropAt)) else 0.0
            val ripple = 0.004 * sin(n / 5.0)
            val point = used * (climb + ripple)
            val peaked = if (cf == "MAX") point * 1.02 else point
            (if (total != null) peaked.coerceAtMost(total) else peaked).coerceAtLeast(0.0).roundToLong().toDouble()
        }
        HistoryPoint(time, mapOf("used" to value, "total" to total))
    }
}
