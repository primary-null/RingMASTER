package local.ringleader.core

import kotlinx.serialization.json.*

/*
 * The reads the create wizards make, their parsed rows, the storage choices, and the fixed choice lists.
 *
 * Maintainer decision D1 (C1-CREATE-GUEST-BUILD.md): the storage index (`GET /nodes/{node}/storage`) is never read, not
 * even with a content filter. `isForbiddenNodeRead` keeps refusing it. Storage choices come from the recorded inventory
 * rows ([RecordedStorage], from `/cluster/resources`) and the disk formats from [STORAGE_FORMATS_BY_PLUGIN]. The only
 * storage read is one storage's content (`storageContentPath`), when the user picks that storage for ISO images,
 * templates or import images, as the Storage page already does.
 */

// ---------------------------------------------------------------------------------------------------------------------
// D1: storage choices from the recorded inventory
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The disk formats a storage type offers. [default] is the storage's own default when no format is sent.
 * [selectExisting] is true for storages where a new volume cannot be allocated and an existing one must be chosen
 * (the web interface's `select_existing`).
 */
data class StorageFormats(val supported: List<String>, val default: String, val selectExisting: Boolean = false) {
    init { require(default in supported) { "A storage's default format is one it supports." } }
}

private val FILE_FORMATS = StorageFormats(listOf("raw", "qcow2", "vmdk"), "raw")
private val RAW_ONLY = StorageFormats(listOf("raw"), "raw")
private val RAW_EXISTING = StorageFormats(listOf("raw"), "raw", selectExisting = true)

/**
 * Disk formats by Proxmox storage plugin type, for VM disks (`images`). Written from the research and the pve-storage
 * plugin definitions: file-based storages offer raw, qcow2 and vmdk; block and object storages offer raw only; iSCSI
 * storages expose existing LUNs only. Btrfs is file-based but its plugin offers raw only (plus subvol for containers).
 * Types that hold no VM disks (cephfs, pbs, esxi) are absent. The server checks the format in the end: a wrong guess
 * comes back as a field error.
 */
val STORAGE_FORMATS_BY_PLUGIN: Map<String, StorageFormats> = mapOf(
    "dir" to FILE_FORMATS,
    "nfs" to FILE_FORMATS,
    "cifs" to FILE_FORMATS,
    "glusterfs" to FILE_FORMATS,
    "btrfs" to RAW_ONLY,
    "lvm" to RAW_ONLY,
    "lvmthin" to RAW_ONLY,
    "zfspool" to RAW_ONLY,
    "zfs" to RAW_ONLY,
    "rbd" to RAW_ONLY,
    "iscsi" to RAW_EXISTING,
    "iscsidirect" to RAW_EXISTING,
)

/** The format table entry for [pluginType], or null when the type is unknown: then no format is offered or sent. */
fun storageFormats(pluginType: String?): StorageFormats? = pluginType?.let { STORAGE_FORMATS_BY_PLUGIN[it.trim().lowercase()] }

/** Shown under every storage list of a create wizard. */
const val STORAGE_NOT_LISTED_NOTE = "Storage this account cannot audit is not listed."

/** Shown beside a storage's free figure, which is total minus used, not Proxmox's own "available". */
const val STORAGE_FREE_IS_DIFFERENCE = "Free is total minus used, as recorded."

/**
 * One storage a disk, an image or a template can come from, built from a recorded inventory row of [node].
 * [content] is the row's content types. [formats] is the table entry for its plugin type (null = unknown type).
 */
data class StorageChoice(
    val node: String,
    val id: String,
    val pluginType: String?,
    val content: Set<String>,
    val shared: Boolean?,
    val usedBytes: Long?,
    val totalBytes: Long?,
    val status: String,
    val formats: StorageFormats? = storageFormats(pluginType),
) {
    /** Total minus used, never below zero; null when either figure is missing. Label it as [STORAGE_FREE_IS_DIFFERENCE] says. */
    val totalMinusUsedBytes: Long? get() {
        val total = totalBytes ?: return null
        val used = usedBytes ?: return null
        return (total - used).coerceAtLeast(0)
    }

    val selectExisting: Boolean get() = formats?.selectExisting == true
}

/**
 * The storages of [node] in [rows] that carry [content] (`images`, `rootdir`, `iso`, `vztmpl` or `import`) and whose
 * recorded status is `available`, sorted by id. Never reads the server.
 */
fun storageChoices(rows: List<RecordedStorage>, node: String, content: String): List<StorageChoice> = rows.filter { it.node == node && it.status == "available" && content in it.content.orEmpty().split(',', ';', ' ') }.sortedBy { it.id }.map { StorageChoice(it.node, it.id, it.pluginType, it.content.orEmpty().split(',', ';', ' ').filter { s -> s.isNotBlank() }.toSet(), it.shared, it.diskBytes, it.maxDiskBytes, it.status) }

// ---------------------------------------------------------------------------------------------------------------------
// Read results
// ---------------------------------------------------------------------------------------------------------------------

/** `GET /cluster/nextid[?vmid=N]`: a free ID, or the ID is taken (HTTP 400 with `errors.vmid`). */
sealed interface NextIdReply {
    data class Free(val id: Int) : NextIdReply
    /** [message] is the cleaned server text, such as "VM 105 already exists". */
    data class Taken(val id: Int?, val message: String) : NextIdReply
}

/** The typed-ID check shown beside the ID field: "checking…", "free", "in use". */
sealed interface IdCheck {
    data object Idle : IdCheck
    data class Checking(val id: Int) : IdCheck
    data class Free(val id: Int) : IdCheck
    data class Taken(val id: Int, val text: String) : IdCheck
    /** The check did not answer. Nothing was sent. */
    data class Failed(val id: Int, val text: String) : IdCheck
}

/** A resource pool from `GET /pools`. */
data class PoolChoice(val id: String, val comment: String? = null)

/** A bridge or vnet from `GET /nodes/{node}/network?type=any_bridge`. [type] is `bridge`, `OVSBridge` or `vnet`. */
data class BridgeChoice(
    val name: String,
    val type: String,
    val active: Boolean? = null,
    val vlanAware: Boolean = false,
    val vlanIds: String? = null,
    val comment: String? = null,
)

/** A CPU model from `GET /nodes/{node}/capabilities/qemu/cpu`. */
data class CpuModelChoice(val name: String, val vendor: String? = null, val custom: Boolean = false, val abstract: Boolean = false)

/** A CPU flag from `GET /nodes/{node}/capabilities/qemu/cpu-flags`. */
data class CpuFlagChoice(val name: String, val description: String? = null, val supportedOn: List<String> = emptyList())

/**
 * The datacenter tag rules from `GET /cluster/options`. [userAllow] is `none`, `list`, `existing` or `free` (null when
 * not reported); [allowedTags] is `user-allow-list`; [registeredTags] is `registered-tags`.
 */
data class ClusterTagRules(
    val userAllow: String? = null,
    val allowedTags: List<String> = emptyList(),
    val registeredTags: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------------------------------------------------
// Paths (pure). Every one is a GET. The storage index is not among them (D1).
// ---------------------------------------------------------------------------------------------------------------------

const val NEXT_ID_PATH = "/api2/json/cluster/nextid"
const val POOLS_PATH = "/api2/json/pools"
const val CLUSTER_OPTIONS_PATH = "/api2/json/cluster/options"

/** `/cluster/nextid`, or `/cluster/nextid?vmid=N` for the check of a typed ID. */
fun nextIdPath(vmid: Int? = null): String = if (vmid == null) NEXT_ID_PATH else { require(vmid in CREATE_VMID_MIN..CREATE_VMID_MAX); "$NEXT_ID_PATH?vmid=$vmid" }

/** `/nodes/{node}/network?type=any_bridge`, node encoded. Throws IllegalArgumentException for a node that is not a `pve-node`. */
fun bridgesPath(node: String): String = createNodePath(node) + "/network?type=any_bridge"

/** `/nodes/{node}/capabilities/qemu/cpu[?arch=]`. */
fun cpuModelsPath(node: String, arch: String?): String = createNodePath(node) + "/capabilities/qemu/cpu" + createArchQuery(arch)

/** `/nodes/{node}/capabilities/qemu/cpu-flags[?arch=&accel=kvm|tcg]`. */
fun cpuFlagsPath(node: String, arch: String?, accel: String): String = run { require(accel in listOf("kvm", "tcg")); createNodePath(node) + "/capabilities/qemu/cpu-flags" + createArchQuery(arch) + (if (arch == null) "?" else "&") + "accel=$accel" }

// ---------------------------------------------------------------------------------------------------------------------
// Parsers (pure). They follow the existing style: tolerate missing optional keys, drop malformed rows, refuse control
// characters, accept booleans as 0/1 or JSON booleans. A body that is not the expected shape throws
// IllegalArgumentException.
// ---------------------------------------------------------------------------------------------------------------------

/** `{"data":140}` (or `"140"`) → 140. */
fun parseNextId(body: String): Int = (createData(body) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in CREATE_VMID_MIN..CREATE_VMID_MAX } ?: throw IllegalArgumentException("Invalid next ID response.")

/** The 400 body of a taken ID → [NextIdReply.Taken] with the cleaned `errors.vmid` text; null when the body says nothing about `vmid`. */
fun parseNextIdTaken(body: String, vmid: Int): NextIdReply.Taken? = runCatching { (Json.parseToJsonElement(body).jsonObject["errors"] as? JsonObject)?.get("vmid")?.jsonPrimitive?.contentOrNull?.let { NextIdReply.Taken(vmid, "ID $vmid is in use.") } }.getOrNull()

fun parsePools(body: String): List<PoolChoice> = createRows(body).mapNotNull { r -> r.clean("poolid")?.let { PoolChoice(it, r.clean("comment")) } }

fun parseBridges(body: String): List<BridgeChoice> = createRows(body).mapNotNull { r -> val name = r.clean("iface"); val type = r.clean("type"); if (name == null || type !in listOf("bridge", "OVSBridge", "vnet")) null else BridgeChoice(name, type!!, r.flag("active"), r.flag("bridge_vlan_aware") == true, r.clean("bridge_vids"), r.clean("comments")) }

fun parseCpuModels(body: String): List<CpuModelChoice> = createRows(body).mapNotNull { r -> r.clean("name")?.let { CpuModelChoice(it, r.clean("vendor"), r.flag("custom") == true, r.flag("abstract") == true) } }

fun parseCpuFlags(body: String): List<CpuFlagChoice> = createRows(body).mapNotNull { r -> r.clean("name")?.let { CpuFlagChoice(it, r.clean("description"), (r["supported-on"] as? JsonArray)?.mapNotNull { v -> (v as? JsonPrimitive)?.contentOrNull } ?: emptyList()) } }

fun parseClusterTagRules(body: String): ClusterTagRules {
    val r = createData(body) as? JsonObject ?: throw IllegalArgumentException("Invalid cluster options.")
    fun list(value: JsonElement?): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.none { c -> c.isISOControl() } } }
        is JsonPrimitive -> value.content.split(';').filter { it.isNotBlank() && it.none { c -> c.isISOControl() } }
        else -> emptyList()
    }
    val access = r["user-tag-access"]
    val objectAccess = access as? JsonObject
    val properties = (access as? JsonPrimitive)?.content.orEmpty().split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
    return ClusterTagRules(objectAccess?.clean("user-allow") ?: properties["user-allow"],
        objectAccess?.let { list(it["user-allow-list"]) } ?: properties["user-allow-list"].orEmpty().split(';').filter { it.isNotBlank() }, list(r["registered-tags"]))
}

// ---------------------------------------------------------------------------------------------------------------------
// Fixed choice lists (pure data, the Proxmox web interface's values and labels)
// ---------------------------------------------------------------------------------------------------------------------

/** One choice. [value] null is "Default": nothing is sent and the server default applies. */
data class CreateChoice(val value: String?, val label: String)

/** One guest OS version. [family] groups the versions (the OS step's type choice). */
data class OsChoice(val value: String, val family: String, val label: String)

const val OS_LINUX = "Linux"
const val OS_WINDOWS = "Microsoft Windows"
const val OS_SOLARIS = "Solaris Kernel"
const val OS_OTHER = "Other"

/** `ostype` values in the web interface's order (R§4.2). aarch64 offers only the Linux and Other families. */
val OS_CHOICES: List<OsChoice> = listOf(
    OsChoice("l26", OS_LINUX, "7.x - 2.6 Kernel"),
    OsChoice("l24", OS_LINUX, "2.4 Kernel"),
    OsChoice("win11", OS_WINDOWS, "11/2022/2025"),
    OsChoice("win10", OS_WINDOWS, "10/2016/2019"),
    OsChoice("win8", OS_WINDOWS, "8.x/2012/2012r2"),
    OsChoice("win7", OS_WINDOWS, "7/2008r2"),
    OsChoice("w2k8", OS_WINDOWS, "Vista/2008"),
    OsChoice("wxp", OS_WINDOWS, "XP/2003"),
    OsChoice("w2k", OS_WINDOWS, "2000"),
    OsChoice("solaris", OS_SOLARIS, "-"),
    OsChoice("other", OS_OTHER, "-"),
)

/** `vga` (R§4.3). A `serialN` choice also sends `serialN=socket`. */
val VGA_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default"),
    CreateChoice("std", "Standard VGA"),
    CreateChoice("vmware", "VMware compatible"),
    CreateChoice("qxl", "SPICE"),
    CreateChoice("qxl2", "SPICE dual monitor"),
    CreateChoice("qxl3", "SPICE three monitors"),
    CreateChoice("qxl4", "SPICE four monitors"),
    CreateChoice("serial0", "Serial terminal 0"),
    CreateChoice("serial1", "Serial terminal 1"),
    CreateChoice("serial2", "Serial terminal 2"),
    CreateChoice("serial3", "Serial terminal 3"),
    CreateChoice("virtio", "VirtIO-GPU"),
    CreateChoice("virtio-gl", "VirGL GPU"),
    CreateChoice("none", "none"),
)

/** `machine` on x86_64. aarch64 offers Default (virt) only. */
val MACHINE_CHOICES: List<CreateChoice> = listOf(CreateChoice(null, "Default (i440fx)"), CreateChoice("q35", "q35"))

/** `bios` on x86_64. aarch64 offers OVMF only. */
val BIOS_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default (SeaBIOS)"),
    CreateChoice("seabios", "SeaBIOS"),
    CreateChoice("ovmf", "OVMF (UEFI)"),
)

/** `scsihw` on x86_64. aarch64 offers the two VirtIO controllers only. */
val SCSI_CONTROLLER_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default (LSI 53C895A)"),
    CreateChoice("lsi", "LSI 53C895A"),
    CreateChoice("lsi53c810", "LSI 53C810"),
    CreateChoice("megasas", "MegaRAID SAS 8708EM2"),
    CreateChoice("virtio-scsi-pci", "VirtIO SCSI"),
    CreateChoice("virtio-scsi-single", "VirtIO SCSI single"),
    CreateChoice("pvscsi", "VMware PVSCSI"),
)

/** Disk `cache`. */
val CACHE_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default (No cache)"),
    CreateChoice("directsync", "Direct sync"),
    CreateChoice("writethrough", "Write through"),
    CreateChoice("writeback", "Write back"),
    CreateChoice("unsafe", "Write back (unsafe)"),
    CreateChoice("none", "No cache"),
)

/** Disk `aio`. */
val AIO_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default (io_uring)"),
    CreateChoice("io_uring", "io_uring"),
    CreateChoice("native", "native"),
    CreateChoice("threads", "threads"),
)

/** VM NIC models offered by the wizard (the server accepts more). */
val NIC_MODEL_CHOICES: List<CreateChoice> = listOf(
    CreateChoice("e1000", "Intel E1000"),
    CreateChoice("e1000e", "Intel E1000E"),
    CreateChoice("virtio", "VirtIO (paravirtualized)"),
    CreateChoice("rtl8139", "Realtek RTL8139"),
    CreateChoice("vmxnet3", "VMware vmxnet3"),
)

/** `arch` (9.x, Advanced). A non-host choice runs emulated (`kvm=0`). */
val ARCH_CHOICES: List<CreateChoice> = listOf(
    CreateChoice(null, "Default (host)"),
    CreateChoice("x86_64", "x86-64"),
    CreateChoice("aarch64", "ARM 64-bit (tech preview)"),
)

/** TPM `version`. aarch64 offers v2.0 only. */
val TPM_VERSION_CHOICES: List<CreateChoice> = listOf(CreateChoice("v2.0", "v2.0"), CreateChoice("v1.2", "v1.2"))

/** Container mount options (`mountoptions`). The root disk does not offer `nodev` and `noexec`. */
val CT_MOUNT_OPTIONS: List<String> = listOf("discard", "lazytime", "noatime", "nodev", "noexec", "nosuid")
val CT_ROOTFS_MOUNT_OPTIONS: List<String> = CT_MOUNT_OPTIONS - setOf("nodev", "noexec")

internal fun createNodePath(node: String): String { require(pveNodeNameOrNull(node) != null); return "/api2/json/nodes/${encodePathSegment(node)}" }
private fun createArchQuery(arch: String?): String { require(arch == null || arch in listOf("x86_64", "aarch64")); return arch?.let { "?arch=$it" }.orEmpty() }
private fun createData(body: String): JsonElement = try { Json.parseToJsonElement(body).jsonObject["data"] ?: error("Missing data") } catch (_: Exception) { throw IllegalArgumentException("Invalid create list response.") }
private fun createRows(body: String): List<JsonObject> = (createData(body) as? JsonArray ?: throw IllegalArgumentException("Invalid create list response.")).mapNotNull { it as? JsonObject }
private fun JsonObject.clean(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.length <= 1024 && s.none { it.isISOControl() } }
private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }
