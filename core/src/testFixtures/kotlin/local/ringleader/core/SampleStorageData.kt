package local.ringleader.core

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * What the sample fleet's storages hold and report: their volumes, their configuration and their usage history.
 * Handwritten and deterministic. Nothing here came from a server.
 */

private const val SAMPLE_MIB = 1024L * 1024
private const val SAMPLE_GIB = 1024L * SAMPLE_MIB
private const val SAMPLE_EPOCH = 1_794_000_000L

/**
 * The volumes of one sample storage, one set per content type the row lists. Deterministic. Proxmox Backup Server rows get verification states,
     as that is the only plugin that reports them.
 */
fun sampleStorageContent(storage: RecordedStorage): List<StorageVolume> {
    fun volume(volid: String, content: String, format: String, size: Long?, vmid: Int? = null, ageDays: Int? = null, used: Long? = null,
        notes: String? = null, protected: Boolean = false, verification: String? = null) =
        StorageVolume("${storage.id}:$volid", content, format, size, null, used, ageDays?.let { Instant.ofEpochSecond(SAMPLE_EPOCH - it * 86_400L) },
            vmid, notes, protected, verification)
    val types = storage.content.orEmpty().split(',').map { it.trim() }.filter { it in STORAGE_CONTENT_TYPES }
    val pbs = storage.pluginType == "pbs"
    return types.flatMap { type ->
        when (type) {
            "images" -> listOf(
                volume("vm-120-disk-0", "images", "raw", 32 * SAMPLE_GIB, 120, used = 11 * SAMPLE_GIB),
                volume("vm-120-disk-1", "images", "raw", 128 * SAMPLE_GIB, 120, 30, used = 40 * SAMPLE_GIB),
                volume("vm-130-disk-0", "images", "raw", 16 * SAMPLE_GIB, 130, 12, used = 3 * SAMPLE_GIB),
            )
            "rootdir" -> listOf(volume("subvol-121-disk-0", "rootdir", "subvol", 8 * SAMPLE_GIB, 121, 40, used = 2 * SAMPLE_GIB))
            "iso" -> listOf(
                volume("iso/debian-13.1.0-amd64-netinst.iso", "iso", "iso", 783 * SAMPLE_MIB, ageDays = 9),
                volume("iso/ubuntu-26.04-live-server-amd64.iso", "iso", "iso", 2_100 * SAMPLE_MIB, ageDays = 3),
                volume("iso/virtio-win-0.1.266.iso", "iso", "iso", 708 * SAMPLE_MIB, ageDays = 160, notes = "Windows drivers"),
            )
            "vztmpl" -> listOf(
                volume("vztmpl/debian-13-standard_13.0-1_amd64.tar.zst", "vztmpl", "tzst", 123 * SAMPLE_MIB, ageDays = 21),
                volume("vztmpl/ubuntu-24.04-standard_24.04-2_amd64.tar.zst", "vztmpl", "tzst", 133 * SAMPLE_MIB, ageDays = 75),
            )
            "backup" -> if (pbs) listOf(
                volume("backup/vm/120/2026-11-07T02:00:01Z", "backup", "pbs-vm", 2_300 * SAMPLE_MIB, 120, 2, notes = "nightly", verification = "ok"),
                volume("backup/ct/121/2026-11-06T02:00:02Z", "backup", "pbs-ct", 540 * SAMPLE_MIB, 121, 3, protected = true, notes = "before upgrade",
                    verification = "ok"),
                volume("backup/vm/130/2026-11-01T02:00:05Z", "backup", "pbs-vm", 1_200 * SAMPLE_MIB, 130, 8, verification = "failed"),
            ) else listOf(
                volume("backup/vzdump-qemu-120-2026_11_07-02_00_01.vma.zst", "backup", "vma.zst", 2_300 * SAMPLE_MIB, 120, 2, notes = "nightly"),
                volume("backup/vzdump-lxc-121-2026_11_06-02_00_02.tar.zst", "backup", "tar.zst", 540 * SAMPLE_MIB, 121, 3, notes = "before upgrade",
                    protected = true),
                volume("backup/vzdump-qemu-130-2026_11_01-02_00_05.vma.zst", "backup", "vma.zst", 1_200 * SAMPLE_MIB, 130, 8),
            )
            "snippets" -> listOf(
                volume("snippets/user-data.yaml", "snippets", "snippet", 2 * 1024L, ageDays = 14),
                volume("snippets/hookscript.pl", "snippets", "snippet", 3 * 1024L, ageDays = 50, notes = "Pre-start check"),
            )
            "import" -> listOf(volume("import/debian-12-genericcloud-amd64.qcow2", "import", "qcow2", 420 * SAMPLE_MIB, ageDays = 33))
            else -> emptyList()
        }
    }
}

/**
 * The storage configuration: the storages of the sample fleet plus one disabled storage (`old-nfs`), which the resource
 * list leaves out, and retention rules on `shared-data`. Only allowlisted fields exist, as after [parseStorageConfig].
 */
val sampleStorageConfig: List<StorageConfigRow> = listOf(
    StorageConfigRow("local", "dir", setOf("iso", "vztmpl", "import"), disabled = false, shared = false, nodes = setOf("demo-node-a"),
        pruneBackups = null, maxProtected = null),
    StorageConfigRow("local-lvm", "lvmthin", setOf("images", "rootdir"), disabled = false, shared = false,
        nodes = setOf("demo-node-a"), pruneBackups = null, maxProtected = null),
    StorageConfigRow("old-nfs", "nfs", setOf("iso", "backup"), disabled = true, shared = true,
        nodes = null, pruneBackups = KeepRules(last = 2), maxProtected = null),
    StorageConfigRow("shared-data", "dir", setOf("images", "backup"), disabled = false, shared = true,
        nodes = null, pruneBackups = KeepRules(last = 3, weekly = 2), maxProtected = 5),
)

private const val SAMPLE_HISTORY_POINTS = 120

/**
 * Usage history of one sample storage ending at [now]: a slow climb to the row's current used bytes, with a drop about
 * two thirds of the way along (a prune), and a constant total. MAX reads a little higher, never above the total. The shape is
 * the same for every call with the same arguments. A row without figures gives samples without values (gaps).
 */
fun sampleStorageHistory(storage: RecordedStorage, timeframe: String, cf: String, now: Instant): List<HistoryPoint> {
    historySuffix(timeframe, cf)
    val end = now.epochSecond.toDouble()
    val span = historySeconds(timeframe)
    val total = storage.maxDiskBytes?.toDouble()
    val used = storage.diskBytes?.toDouble()
    val last = SAMPLE_HISTORY_POINTS - 1
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
