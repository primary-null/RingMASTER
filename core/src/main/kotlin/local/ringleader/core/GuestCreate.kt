package local.ringleader.core

/*
 * Creating a VM or a container: the draft the wizard edits, the frozen request, the encoders that turn a draft into the
 * exact parameters the Proxmox VE web interface would send, the validation rules, and the defaults and dependencies.
 *
 * Specification: reviews/2026-11-12-round3/C0-CREATE-GUEST-DESIGN.md sections 6.2 to 6.5, with the maintainer decisions
 * in C1-CREATE-GUEST-BUILD.md. Proxmox facts: C0-CREATE-GUEST-RESEARCH.md (R§n below).
 *
 * Phase A0 (this file's first version) fixes the types and the signatures. Every function marked `TODO("phase A")` is
 * pure: it reads only its arguments and never sends anything. Secrets are never part of a draft, a request or a parameter.
 */

/** The kind of guest being created. Elsewhere in RingMASTER the kind strings stay "VM" and "Container" ([guestKind]). */
enum class NewGuestKind { Vm, Container }

/** The `Guest.kind` / `ResourceId.kind` string of this kind: "VM" or "Container". */
val NewGuestKind.guestKind: String get() = when (this) {
    NewGuestKind.Vm -> "VM"
    NewGuestKind.Container -> "Container"
}

/** The API segment of this kind: `qemu` or `lxc`. */
val NewGuestKind.apiSegment: String get() = when (this) {
    NewGuestKind.Vm -> "qemu"
    NewGuestKind.Container -> "lxc"
}

/** Parameter keys whose values are secrets. They never appear in [CreateParam]; [createBody] writes them from [CreateSecrets]. */
val CREATE_SECRET_KEYS: Set<String> = setOf("password", "ssh-public-keys")

// ---------------------------------------------------------------------------------------------------------------------
// Field identities. Validation results, server field errors and the step rail all use these strings.
// A field id is "<step>.<field>" (or "<step>.<row key>.<part>" for a disk or mount row); the step id is the part before
// the first dot, so the step rail can count problems per step with [CreateFields.step].
// ---------------------------------------------------------------------------------------------------------------------

/** A draft field, such as `general.vmid` or `disks.scsi0.size`. */
typealias FieldId = String

object CreateFields {
    // Steps. The VM wizard uses VM_STEPS, the container wizard CT_STEPS, in this order.
    const val GENERAL = "general"
    const val OS = "os"
    const val SYSTEM = "system"
    const val TEMPLATE = "template"
    const val DISKS = "disks"
    const val CPU = "cpu"
    const val MEMORY = "memory"
    const val NETWORK = "network"
    const val DNS = "dns"
    val VM_STEPS: List<String> = listOf(GENERAL, OS, SYSTEM, DISKS, CPU, MEMORY, NETWORK)
    val CT_STEPS: List<String> = listOf(GENERAL, TEMPLATE, DISKS, CPU, MEMORY, NETWORK, DNS)

    // General (both kinds unless noted).
    const val NODE = "general.node"
    const val VMID = "general.vmid"
    /** The VM name or the container hostname. */
    const val NAME = "general.name"
    const val POOL = "general.pool"
    const val HA = "general.ha"
    const val TAGS = "general.tags"
    const val ON_BOOT = "general.onboot"          // VM
    const val START_ORDER = "general.startOrder"  // VM
    const val START_UP = "general.startUp"        // VM
    const val START_DOWN = "general.startDown"    // VM
    const val ARCH = "general.arch"               // VM
    const val UNPRIVILEGED = "general.unprivileged" // container
    const val NESTING = "general.nesting"         // container
    const val PASSWORD = "general.password"       // container
    const val PASSWORD_CONFIRM = "general.passwordConfirm" // container
    const val SSH_KEYS = "general.sshKeys"        // container

    // OS (VM).
    const val INSTALL = "os.install"
    const val OS_TYPE = "os.type"
    const val DRIVER_DISC = "os.driverDisc"

    // System (VM).
    const val VGA = "system.vga"
    const val MACHINE = "system.machine"
    const val BIOS = "system.bios"
    const val EFI = "system.efi"
    const val SCSI_HW = "system.scsihw"
    const val AGENT = "system.agent"
    const val TPM = "system.tpm"

    // Template (container).
    const val TEMPLATE_STORAGE = "template.storage"
    const val TEMPLATE_VOLUME = "template.template"

    // CPU.
    const val SOCKETS = "cpu.sockets"
    const val CORES = "cpu.cores"
    const val CPU_TYPE = "cpu.type"
    const val VCPUS = "cpu.vcpus"
    const val CPU_LIMIT = "cpu.limit"
    const val AFFINITY = "cpu.affinity"
    const val CPU_UNITS = "cpu.units"
    const val NUMA = "cpu.numa"
    const val CPU_FLAGS = "cpu.flags"

    // Memory.
    const val MEMORY_MIB = "memory.memory"
    const val BALLOON = "memory.minimum"
    const val BALLOONING = "memory.ballooning"
    const val SHARES = "memory.shares"
    const val KSM = "memory.ksm"
    const val SWAP = "memory.swap"

    // Network (net0).
    const val NIC = "network.nic"
    const val NIC_NAME = "network.name"
    const val MODEL = "network.model"
    const val MAC = "network.mac"
    const val BRIDGE = "network.bridge"
    const val VLAN = "network.vlan"
    const val MTU = "network.mtu"
    const val RATE = "network.rate"
    const val QUEUES = "network.queues"
    const val IP4 = "network.ip4"
    const val GW4 = "network.gw4"
    const val IP6 = "network.ip6"
    const val GW6 = "network.gw6"

    // DNS (container).
    const val SEARCH_DOMAIN = "dns.searchdomain"
    const val NAMESERVERS = "dns.nameserver"

    /** A disk or mount row (`disks.scsi0`), or one part of it (`disks.scsi0.size`). Parts: bus, index, storage, size,
     *  format, volume, cache, path, limits, import, idmap, options. */
    fun disk(key: String, part: String? = null): FieldId = if (part == null) "$DISKS.$key" else "$DISKS.$key.$part"

    /** The step a field belongs to. */
    fun step(field: FieldId): String = field.substringBefore('.')
}

// ---------------------------------------------------------------------------------------------------------------------
// 6.2 Data classes: the VM draft
// Numbers are kept as the typed String so validation can name the exact problem. A blank String means "not set": the
// encoders send nothing for it and the server default applies. Defaults are the Proxmox 9.x wizard defaults for a Linux
// guest on x86_64 (R§4); defaultVmDraft / applyOsDefaults adjust them for the node, the OS type and the architecture.
// ---------------------------------------------------------------------------------------------------------------------

/** A disk bus. [prefix] is the parameter key prefix; [maxIndex] the highest device number Proxmox allows (R§4.4). */
enum class DiskBus(val prefix: String, val maxIndex: Int) {
    Ide("ide", 3), Sata("sata", 5), Scsi("scsi", 30), Virtio("virtio", 15),
}

/** Where a VM disk's volume comes from. */
sealed interface DiskSource {
    /** A new volume of [sizeGib] (as typed, decimals allowed) on [storage]. Blank [storage] = not chosen yet. */
    data class New(val storage: String = "", val sizeGib: String = "32") : DiskSource
    /** An existing volume on a storage that requires choosing one (`select_existing`, iSCSI-style). */
    data class Existing(val volid: String) : DiskSource
    /** 9.x disk import: `<targetStorage>:0,import-from=<fromVolid>`. */
    data class Import(val fromVolid: String, val targetStorage: String) : DiskSource
}

/** The eight bandwidth limits of one disk, as typed. Null or blank = unlimited, not sent (R§4.4 Bandwidth). */
data class DiskLimits(
    val mbpsRd: String? = null,
    val mbpsWr: String? = null,
    val iopsRd: String? = null,
    val iopsWr: String? = null,
    val mbpsRdMax: String? = null,
    val mbpsWrMax: String? = null,
    val iopsRdMax: String? = null,
    val iopsWrMax: String? = null,
)

/** One VM hard disk. The default is the wizard's first disk for Linux: scsi0, 32 GiB, IO thread on. */
data class VmDisk(
    val bus: DiskBus = DiskBus.Scsi,
    val index: Int = 0,
    val source: DiskSource = DiskSource.New(),
    /** Null = the storage default (the server decides). */
    val format: String? = null,
    /** Null = Default (no cache), not sent. */
    val cache: String? = null,
    val discard: Boolean = false,
    val ioThread: Boolean = true,
    val ssd: Boolean = false,
    val readOnly: Boolean = false,
    /** Off sends `backup=0`. */
    val backup: Boolean = true,
    /** On sends `replicate=no`. */
    val skipReplication: Boolean = false,
    /** Null = Default (io_uring), not sent. */
    val aio: String? = null,
    val limits: DiskLimits = DiskLimits(),
)

/** The parameter key of this disk, such as `scsi0`. */
val VmDisk.key: String get() = bus.prefix + index

/** What a CD drive holds. */
sealed interface CdMedia {
    /**
     * An ISO image. [storage] is the chosen ISO storage (kept while no image is chosen yet); [volid] is the volume,
     * such as `local:iso/debian-13.iso`. Blank [volid] = not chosen yet (required when the media is an ISO).
     */
    data class Iso(val storage: String = "", val volid: String = "") : CdMedia
    /** The host's physical drive (`cdrom`). Needs Sys.Console on `/`. */
    data object Physical : CdMedia
    /** An empty drive (`none`). */
    data object None : CdMedia
}

/** A CD drive. [slot] is `ide2` (x86_64 install disc), `scsi2` (aarch64 install disc) or `ide0` (Windows driver disc). */
data class CdDrive(val slot: String = "ide2", val media: CdMedia = CdMedia.Iso())

/**
 * The EFI disk, used when the firmware is OVMF and [VmDraft.addEfi] is on. Blank [storage] = not chosen (Proxmox does
 * not pre-select one). [volume] is the existing volume on a `select_existing` storage, else null.
 */
data class EfiDisk(
    val storage: String = "",
    val format: String? = null,
    val preEnrolledKeys: Boolean = true,
    val volume: String? = null,
)

/** The TPM state disk. [version] is `v2.0` or `v1.2`. [volume] as for [EfiDisk]. */
data class TpmState(
    val storage: String = "",
    val format: String? = null,
    val version: String = "v2.0",
    val volume: String? = null,
)

/** The VM's network device `net0`. Blank [mac] = generated by the server. Blank numbers = not sent. */
data class VmNic(
    val model: String = "virtio",
    val mac: String = "",
    val bridge: String = "",
    val vlan: String = "",
    val firewall: Boolean = true,
    val disconnect: Boolean = false,
    val mtu: String = "",
    val rate: String = "",
    val queues: String = "",
)

/** One extra CPU flag set to On (`'+'`) or Off (`'-'`). A flag left at Default is not in the list. */
data class CpuFlag(val name: String, val state: Char) {
    init { require(state == '+' || state == '-') { "A CPU flag is either + or -." } }
}

/**
 * What the user is typing in the VM wizard. The draft lives in the desktop (`remember`) and is passed to the pure
 * functions below. [touched] holds the [FieldId]s the user changed by hand; dependent defaults never overwrite them.
 */
data class VmDraft(
    val node: String,
    val vmid: String = "",
    val name: String = "",
    /** Null = no pool. */
    val pool: String? = null,
    val haManaged: Boolean = false,
    val onBoot: Boolean = false,
    val startOrder: String = "",
    val startUp: String = "",
    val startDown: String = "",
    /** Null = the host architecture (not sent). Else `x86_64` or `aarch64`; a non-host value also sends `kvm=0`. */
    val arch: String? = null,
    val tags: List<String> = emptyList(),
    /** The `ostype` value, such as `l26` or `win11`. */
    val osType: String = "l26",
    val install: CdDrive = CdDrive(),
    /** The Windows VirtIO driver disc on `ide0`, or null when off. */
    val driverDisc: CdDrive? = null,
    /** Null = Default (not sent). */
    val vga: String? = null,
    /** Null = Default (i440fx on x86_64, virt on aarch64), not sent. */
    val machine: String? = null,
    /** Null = Default (SeaBIOS), not sent. `ovmf` turns on the EFI disk group. */
    val bios: String? = null,
    val efi: EfiDisk = EfiDisk(),
    /** "Add EFI disk". Only meaningful while [bios] is `ovmf`. */
    val addEfi: Boolean = true,
    /** Null = Default (lsi), not sent. */
    val scsiHw: String? = "virtio-scsi-single",
    val agent: Boolean = false,
    /** Null = no TPM. */
    val tpm: TpmState? = null,
    val disks: List<VmDisk> = listOf(VmDisk()),
    val sockets: String = "1",
    val cores: String = "1",
    /** The CPU model. The web interface sends x86-64-v2-AES explicitly on x86_64; ARM defaults to `host`. */
    val cpuType: String = "x86-64-v2-AES",
    val vcpus: String = "",
    val cpuLimit: String = "",
    /** Only root@pam may set it (R§7.4); the field is disabled otherwise. */
    val affinity: String = "",
    val cpuUnits: String = "",
    /** Sent as `numa=0` even when untouched, as the web interface does. */
    val numa: Boolean = false,
    val cpuFlags: List<CpuFlag> = emptyList(),
    val memoryMib: String = "2048",
    val ballooning: Boolean = true,
    /** Blank or equal to [memoryMib] = no `balloon` sent. */
    val minMemoryMib: String = "",
    val shares: String = "",
    /** Off sends `allow-ksm=0` (9.x only). */
    val allowKsm: Boolean = true,
    /** Null = "No network device". */
    val nic: VmNic? = VmNic(),
    val touched: Set<FieldId> = emptySet(),
    val isoStorage: String = "",
)

// ---------------------------------------------------------------------------------------------------------------------
// 6.2 Data classes: the container draft
// ---------------------------------------------------------------------------------------------------------------------

/** Where a container mount's volume comes from. */
sealed interface MountSource {
    /** A new volume of [sizeGib] (as typed) on [storage]. Blank [storage] = not chosen yet. */
    data class New(val storage: String = "", val sizeGib: String = "8") : MountSource
    data class Existing(val volid: String) : MountSource
}

/** One ID map row: [type] `'u'` or `'g'`, then container ID, host ID and range size, as typed. */
data class IdRange(val type: Char, val containerId: String, val hostId: String, val count: String) {
    init { require(type == 'u' || type == 'g') { "An ID map row maps users (u) or groups (g)." } }
}

/** A mount's ID mapping (9.1.12+). */
sealed interface IdMap {
    data object None : IdMap
    data object Passthrough : IdMap
    data class Ranges(val rows: List<IdRange>) : IdMap
}

/**
 * The container root disk (`rootfs`) or a mount point (`mpN`). [path], [backup], [readOnly] and [keepAttrs] apply to
 * mount points only. [acl] null = Default (not sent). [mountOptions] from [CT_MOUNT_OPTIONS].
 */
data class CtMount(
    val key: String = "rootfs",
    val source: MountSource = MountSource.New(),
    val path: String = "",
    val backup: Boolean = true,
    val quota: Boolean = false,
    val readOnly: Boolean = false,
    val mountOptions: Set<String> = emptySet(),
    val acl: Boolean? = null,
    val skipReplication: Boolean = false,
    val keepAttrs: Boolean = false,
    val idMap: IdMap = IdMap.None,
)

/** IPv4 configuration of the container's `net0`. Static with a blank address sends no `ip`. */
sealed interface IpMode {
    data class Static(val cidr: String = "", val gateway: String = "") : IpMode
    data object Dhcp : IpMode
}

/** IPv6 configuration of the container's `net0`. SLAAC sends `ip6=auto`. */
sealed interface Ip6Mode {
    data class Static(val cidr: String = "", val gateway: String = "") : Ip6Mode
    data object Dhcp : Ip6Mode
    data object Slaac : Ip6Mode
}

/** The container's `net0`. The container wizard always has one network device; [bridge] is required. */
data class CtNic(
    val name: String = "eth0",
    val mac: String = "",
    val bridge: String = "",
    val vlan: String = "",
    val firewall: Boolean = true,
    val ipv4: IpMode = IpMode.Static(),
    val ipv6: Ip6Mode = Ip6Mode.Static(),
    val disconnect: Boolean = false,
    val mtu: String = "",
    val rate: String = "",
    /** 9.x only. */
    val hostManaged: Boolean = false,
)

/**
 * What the user is typing in the container wizard. The root password, its confirmation and the SSH keys are not here:
 * they stay in the wizard's text field states and travel only as [CreateSecrets] (design section 7).
 */
data class CtDraft(
    val node: String,
    val vmid: String = "",
    val hostname: String = "",
    val unprivileged: Boolean = true,
    /** Only for an unprivileged container; sends `features=nesting=1`. */
    val nesting: Boolean = true,
    val haManaged: Boolean = false,
    val pool: String? = null,
    val tags: List<String> = emptyList(),
    /** The storage the template list is read from. Not sent. */
    val templateStorage: String = "",
    /** The template volume (`ostemplate`), such as `local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst`. Required. */
    val template: String = "",
    /** `rootfs` first (never removable), then `mpN`. */
    val mounts: List<CtMount> = listOf(CtMount()),
    val cores: String = "1",
    val cpuLimit: String = "",
    val cpuUnits: String = "",
    val memoryMib: String = "512",
    val swapMib: String = "512",
    val nic: CtNic = CtNic(),
    val searchDomain: String = "",
    val nameservers: String = "",
    val touched: Set<FieldId> = emptySet(),
)

// ---------------------------------------------------------------------------------------------------------------------
// 6.2 The frozen request
// ---------------------------------------------------------------------------------------------------------------------

/** One parameter value: a JSON string, a JSON number or a JSON boolean in the request body (R§7.3). */
sealed interface CreateValue {
    data class Text(val value: String) : CreateValue
    data class Int(val value: Long) : CreateValue
    data class Bool(val value: Boolean) : CreateValue
}

/** One request parameter. A secret key ([CREATE_SECRET_KEYS]) is refused: secrets never become parameters. */
data class CreateParam(val key: String, val value: CreateValue) {
    init {
        require(key.isNotBlank() && key.none { it.isISOControl() || it.isWhitespace() }) { "The parameter name is not usable." }
        require(key !in CREATE_SECRET_KEYS) { "A secret is never a plain parameter." }
        require(value !is CreateValue.Text || value.value.none { it.isISOControl() }) { "The parameter value of $key is not usable." }
    }
}

/**
 * Frozen, validated parameters, in the order they will be sent. Secrets are not in here.
 * [name] is the VM name or the container hostname (may be blank). [params] holds `vmid` and never `start`: [start] is
 * the confirmation's "Start after it is created" and is appended as `start=1` by [createBody] and [createParamLines].
 */
data class CreateRequest(
    val kind: NewGuestKind,
    val node: String,
    val vmid: Int,
    val name: String,
    val params: List<CreateParam>,
    val start: Boolean = false,
) {
    init {
        require(pveNodeNameOrNull(node) != null) { "The node cannot be used for a new guest." }
        require(vmid in CREATE_VMID_MIN..CREATE_VMID_MAX) { "Use an ID from $CREATE_VMID_MIN to $CREATE_VMID_MAX." }
        require(name.none { it.isISOControl() }) { "The name is not usable." }
        require(params.map { it.key }.distinct().size == params.size) { "A parameter appears twice." }
        require(params.none { it.key == "start" }) { "Start after created is the request's own flag." }
        require(params.singleOrNull { it.key == "vmid" }?.value == CreateValue.Int(vmid.toLong())) { "The parameters must carry this ID." }
    }
}

const val CREATE_VMID_MIN = 100
const val CREATE_VMID_MAX = 999_999_999

/**
 * What the encoders and rules need from the server and the workspace. Built by [createContext] from the state and the
 * open [CreateSession]; the UI never assembles it by hand.
 *
 * @property release the Proxmox VE major release (8 or 9). 9.x-only fields are hidden and never encoded on 8.
 * @property hostArch the node's `host-arch` (x86_64 when the row has none).
 * @property cgroupMode the node's `cgroup-mode` (1 or 2), or null when unknown (then the v2 limits apply).
 * @property storages the storages of this node from the recorded inventory, with their format table entry (D1).
 * @property usedIds every guest ID in the overview.
 * @property nodeCpus the node's CPU count from the overview, for the overcommit hint.
 * @property nodeMemoryBytes the node's memory total from the overview.
 * @property nodeMemoryUsedBytes the node's memory in use from the overview.
 * @property identityIsRootPam true only for a password session whose user is exactly root@pam (CPU affinity).
 * @property idCheck the latest typed-ID check from the session.
 * @property tagRules the datacenter tag rules, or null when not read.
 */
data class CreateContext(
    val release: Int = 9,
    val hostArch: String = "x86_64",
    val cgroupMode: Int? = 2,
    val storages: List<StorageChoice> = emptyList(),
    val usedIds: Set<Int> = emptySet(),
    val nodeCpus: Int? = null,
    val nodeMemoryBytes: Long? = null,
    val nodeMemoryUsedBytes: Long? = null,
    val identityIsRootPam: Boolean = false,
    val idCheck: IdCheck = IdCheck.Idle,
    val tagRules: ClusterTagRules? = null,
    val choiceNode: String? = null,
    val volumes: Map<Pair<String, String>, List<StorageVolume>>? = null,
    val bridges: List<BridgeChoice>? = null,
    val cpuModels: List<CpuModelChoice>? = null,
    val cpuFlags: List<CpuFlagChoice>? = null,

)

/** The result of freezing a draft: a request ready to confirm, or the problems that stop it. */
sealed interface FreezeResult {
    data class Ready(val request: CreateRequest) : FreezeResult
    /** At least one entry. */
    data class Invalid(val errors: Map<FieldId, String>) : FreezeResult
}

/** One line of the live Parameters panel and of the confirmation's "Exact parameters". [value] of a secret is a state. */
data class ParamLine(val key: String, val value: String, val secret: Boolean = false)

/** A draft after a dependent change, and the muted notes to show for three seconds ("Firmware set to OVMF for Windows 11."). */
data class Applied<T>(val draft: T, val notes: List<String> = emptyList())

// ---------------------------------------------------------------------------------------------------------------------
// 6.3 Encoders. Each returns the exact text the Proxmox web interface sends for the same choice. Inputs are assumed
// valid (validate first); an encoder that meets an impossible input throws IllegalArgumentException.
// ---------------------------------------------------------------------------------------------------------------------

/** `startup`: `("2","30","")` → `order=2,up=30`; all blank → null (not sent); `("","","60")` → `down=60`. */
fun encodeStartup(order: String, up: String, down: String): String? = listOf("order" to order, "up" to up, "down" to down).filter { it.second.isNotBlank() }.joinToString(",") { "${it.first}=${it.second}" }.ifEmpty { null }

/** A CD line: `Iso(volid)` → `<volid>,media=cdrom`; `Physical` → `cdrom,media=cdrom`; `None` → `none,media=cdrom`. */
fun encodeCdDrive(drive: CdDrive): String = when (val m = drive.media) { is CdMedia.Iso -> m.volid; CdMedia.Physical -> "cdrom"; CdMedia.None -> "none" } + ",media=cdrom"

/**
 * `boot`, sent only with the Windows driver disc: `order=<first disk>;<install slot>;ide0[;net0]`, for example
 * `order=scsi0;ide2;ide0;net0`; without disks `order=ide2;ide0`. Null (server default order) when there is no driver disc.
 */
fun encodeBootOrder(disks: List<VmDisk>, install: CdDrive, driverDisc: CdDrive?, nic: VmNic?): String? = if (driverDisc == null) null else "order=" + (listOfNotNull(disks.firstOrNull()?.key, install.slot, driverDisc.slot, nic?.let { "net0" })).joinToString(";")

/**
 * One disk as its parameter: key [VmDisk.key], value such as `local-lvm:32,iothread=on`,
 * `fast:64,format=qcow2,discard=on,iothread=on,cache=writeback,mbps_rd=200`, `san:vm-105-disk-0` (existing) or
 * `local-lvm:0,import-from=imports:import/disk.qcow2,format=raw` (import). Backup off adds `backup=0`; skip
 * replication adds `replicate=no`. Throws IllegalArgumentException for an import on release 8.
 */
fun encodeVmDisk(disk: VmDisk, release: Int = 9): CreateParam = run {
    require(!disk.readOnly || disk.bus in listOf(DiskBus.Scsi, DiskBus.Virtio))
    val parts = mutableListOf(when (val src = disk.source) {
        is DiskSource.New -> "${src.storage}:${src.sizeGib}"
        is DiskSource.Existing -> { require(validCreateVolumeId(src.volid) && disk.format == null); src.volid }
        is DiskSource.Import -> { require(release >= 9 && validCreateVolumeId(src.fromVolid)); "${src.targetStorage}:0,import-from=${src.fromVolid}" }
    })
    disk.format?.let { parts += "format=$it" }
    if (disk.discard) parts += "discard=on"
    if (disk.ioThread && disk.bus in listOf(DiskBus.Scsi, DiskBus.Virtio)) parts += "iothread=on"
    disk.cache?.let { parts += "cache=$it" }
    if (disk.ssd && disk.bus != DiskBus.Virtio) parts += "ssd=on"
    if (disk.readOnly) parts += "ro=on"
    if (!disk.backup) parts += "backup=0"
    if (disk.skipReplication) parts += "replicate=no"
    disk.aio?.let { parts += "aio=$it" }
    val l = disk.limits
    listOf("mbps_rd" to l.mbpsRd, "mbps_wr" to l.mbpsWr, "iops_rd" to l.iopsRd, "iops_wr" to l.iopsWr,
        "mbps_rd_max" to l.mbpsRdMax, "mbps_wr_max" to l.mbpsWrMax, "iops_rd_max" to l.iopsRdMax, "iops_wr_max" to l.iopsWrMax)
        .filter { !it.second.isNullOrBlank() }.forEach { parts += "${it.first}=${it.second}" }
    CreateParam(disk.key, CreateValue.Text(parts.joinToString(",")))
}

/** `efidisk0`: `local-lvm:1,efitype=4m,pre-enrolled-keys=1`; with qcow2 `local:1,efitype=4m,pre-enrolled-keys=1,format=qcow2`. */
fun encodeEfiDisk(efi: EfiDisk): String = (listOf(efi.volume ?: "${efi.storage}:1", "efitype=4m", "pre-enrolled-keys=${if (efi.preEnrolledKeys) 1 else 0}") + listOfNotNull(efi.format?.let { "format=$it" })).joinToString(",")

/** `tpmstate0`: `local-lvm:1,version=v2.0`; with a format `local:1,version=v2.0,format=qcow2`. */
fun encodeTpm(tpm: TpmState): String = (listOf(tpm.volume ?: "${tpm.storage}:1", "version=${tpm.version}") + listOfNotNull(tpm.format?.let { "format=$it" })).joinToString(",")

/**
 * `net0` of a VM: `virtio,bridge=vmbr0,firewall=1`; with MAC and VLAN `virtio=02:00:00:00:00:01,bridge=vmbr0,tag=20,firewall=1`;
 * order `model[=mac], bridge, tag, firewall, rate, queues, link_down, mtu` (R§4.7).
 */
fun encodeVmNic(nic: VmNic): String = listOfNotNull(nic.model + nic.mac.takeIf { it.isNotBlank() }?.let { "=$it" }.orEmpty(),
    nic.bridge.takeIf { it.isNotBlank() }?.let { "bridge=$it" }, opt("tag", nic.vlan), if (nic.firewall) "firewall=1" else null,
    opt("rate", nic.rate), opt("queues", nic.queues), if (nic.disconnect) "link_down=1" else null, opt("mtu", nic.mtu)).joinToString(",")

/**
 * `cpu`: `("x86-64-v2-AES", [])` → `x86-64-v2-AES`; `("host", [+aes, -pcid])` → `host,flags=+aes;-pcid`; a blank type
 * gives the architecture's explicit default (`x86-64-v2-AES` on x86_64, `host` on aarch64).
 */
fun encodeCpu(type: String, flags: List<CpuFlag>, arch: String = "x86_64"): String = type.ifBlank { if (arch == "aarch64") "host" else "x86-64-v2-AES" } + if (flags.isEmpty()) "" else ",flags=" + flags.joinToString(";") { "${it.state}${it.name}" }

/** `tags`: `["web","prod"]` → `web;prod`; empty → null. */
fun encodeTags(tags: List<String>): String? = tags.joinToString(";").ifBlank { null }

/**
 * One container mount as its parameter: `rootfs` = `local-lvm:8`; `mp0` = `local-lvm:16,mp=/srv/data,backup=1`; options
 * in the order `quota, ro, mountoptions, acl, replicate, keepattrs, idmap`, for example
 * `local-lvm:8,quota=1,mountoptions=discard;noatime,acl=0,replicate=0`. Throws IllegalArgumentException for
 * `keepattrs` or `idmap` on release 8.
 */
fun encodeCtMount(mount: CtMount, release: Int = 9): CreateParam = run {
    require(mount.key != "rootfs" || !mount.readOnly)
    require(release >= 9 || (!mount.keepAttrs && mount.idMap == IdMap.None))
    val parts = mutableListOf(when (val src = mount.source) { is MountSource.New -> "${src.storage}:${src.sizeGib}"; is MountSource.Existing -> { require(validCreateVolumeId(src.volid)); src.volid } })
    if (mount.key != "rootfs") { parts += "mp=${mount.path}"; parts += "backup=${if (mount.backup) 1 else 0}" }
    if (mount.quota) parts += "quota=1"
    if (mount.readOnly && mount.key != "rootfs") parts += "ro=1"
    if (mount.mountOptions.isNotEmpty()) parts += "mountoptions=" + mount.mountOptions.sorted().joinToString(";")
    mount.acl?.let { parts += "acl=${if (it) 1 else 0}" }
    if (mount.skipReplication) parts += "replicate=0"
    if (mount.keepAttrs) parts += "keepattrs=1"
    when (val map = mount.idMap) {
        IdMap.None -> Unit
        IdMap.Passthrough -> parts += "idmap=passthrough"
        is IdMap.Ranges -> parts += "idmap=" + map.rows.joinToString(";") { "${it.type}:${it.containerId}:${it.hostId}:${it.count}" }
    }
    CreateParam(mount.key, CreateValue.Text(parts.joinToString(",")))
}

/**
 * `net0` of a container, keys in the order `name, hwaddr, bridge, tag, firewall, ip, gw, ip6, gw6, link_down, mtu, rate,
 * host-managed`: `name=eth0,bridge=vmbr0,firewall=1,ip=dhcp,ip6=auto`. Throws IllegalArgumentException for
 * `host-managed` on release 8.
 */
fun encodeCtNic(nic: CtNic, release: Int = 9): String = run {
    require(release >= 9 || !nic.hostManaged)
    listOfNotNull("name=${nic.name}", opt("hwaddr", nic.mac), opt("bridge", nic.bridge), opt("tag", nic.vlan), if (nic.firewall) "firewall=1" else null,
        when (val ip = nic.ipv4) { IpMode.Dhcp -> "ip=dhcp"; is IpMode.Static -> opt("ip", ip.cidr) },
        (nic.ipv4 as? IpMode.Static)?.let { opt("gw", it.gateway) },
        when (val ip = nic.ipv6) { Ip6Mode.Dhcp -> "ip6=dhcp"; Ip6Mode.Slaac -> "ip6=auto"; is Ip6Mode.Static -> opt("ip6", ip.cidr) },
        (nic.ipv6 as? Ip6Mode.Static)?.let { opt("gw6", it.gateway) },
        if (nic.disconnect) "link_down=1" else null, opt("mtu", nic.mtu), opt("rate", nic.rate), if (nic.hostManaged) "host-managed=1" else null).joinToString(",")
}

/** `features`: `(true,true)` → `nesting=1`; otherwise null (only nesting may be set by a non-root identity). */
fun encodeFeatures(unprivileged: Boolean, nesting: Boolean): String? = if (unprivileged && nesting) "nesting=1" else null

/** `nameserver`: split on space, comma or semicolon, sent space-joined; blank → null. */
fun encodeNameservers(text: String): String? = text.split(Regex("[ ,;\\s]+ ".trim())).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

/**
 * The full VM parameter list, in the order it will be sent, `vmid` included and `start` excluded. The default Linux VM
 * gives exactly `vmid, ostype=l26, ide2, scsihw=virtio-scsi-single, scsi0, sockets=1, cores=1, cpu=x86-64-v2-AES,
 * numa=0, memory=2048, net0`. Release 8 never produces a 9.x-only key. Used by the live Parameters panel on an
 * unfinished draft too: fields that are not valid yet are left out rather than thrown on.
 */
fun vmCreateParams(draft: VmDraft, ctx: CreateContext): List<CreateParam> = buildList {
    fun text(k: String, v: String?) { if (!v.isNullOrBlank() && v.none { it.isISOControl() }) add(CreateParam(k, CreateValue.Text(v))) }
    fun num(k: String, v: String) { v.toLongOrNull()?.let { add(CreateParam(k, CreateValue.Int(it))) } }
    num("vmid", draft.vmid); text("name", draft.name); text("pool", draft.pool)
    if (ctx.release >= 9 && draft.haManaged) num("ha-managed", "1")
    if (draft.onBoot) num("onboot", "1")
    text("startup", encodeStartup(draft.startOrder, draft.startUp, draft.startDown))
    if (ctx.release >= 9 && draft.arch != null) { text("arch", draft.arch); num("kvm", "0") }
    text("tags", encodeTags(draft.tags)); text("ostype", draft.osType)
    text(draft.install.slot, encodeCdDrive(draft.install)); draft.driverDisc?.let { text(it.slot, encodeCdDrive(it)) }
    text("boot", encodeBootOrder(draft.disks, draft.install, draft.driverDisc, draft.nic))
    text("vga", draft.vga); draft.vga?.takeIf { it.matches(Regex("serial[0-3]")) }?.let { text(it, "socket") }
    text("machine", draft.machine); text("bios", draft.bios)
    if (draft.bios == "ovmf" && draft.addEfi) text("efidisk0", encodeEfiDisk(draft.efi))
    text("scsihw", draft.scsiHw); if (draft.agent) num("agent", "1")
    draft.tpm?.let { text("tpmstate0", encodeTpm(it)) }
    draft.disks.forEach { disk -> if (ctx.release >= 9 || disk.source !is DiskSource.Import) add(encodeVmDisk(disk, ctx.release)) }
    num("sockets", draft.sockets); num("cores", draft.cores); text("cpu", encodeCpu(draft.cpuType, draft.cpuFlags, draft.arch ?: ctx.hostArch))
    num("vcpus", draft.vcpus); text("cpulimit", draft.cpuLimit); text("affinity", draft.affinity); num("cpuunits", draft.cpuUnits); num("numa", if (draft.numa) "1" else "0")
    num("memory", draft.memoryMib)
    if (!draft.ballooning) num("balloon", "0") else if (draft.minMemoryMib != draft.memoryMib) num("balloon", draft.minMemoryMib)
    if (draft.ballooning) num("shares", draft.shares)
    if (ctx.release >= 9 && !draft.allowKsm) num("allow-ksm", "0")
    draft.nic?.let { text("net0", encodeVmNic(it)) }
}

/**
 * The full container parameter list, `vmid` included, `start` and the secrets excluded. The default container gives
 * `vmid, ostemplate, unprivileged=1, features=nesting=1, rootfs, cores=1, memory=512, swap=512, net0`.
 */
fun ctCreateParams(draft: CtDraft, ctx: CreateContext): List<CreateParam> = buildList {
    fun text(k: String, v: String?) { if (!v.isNullOrBlank() && v.none { it.isISOControl() }) add(CreateParam(k, CreateValue.Text(v))) }
    fun num(k: String, v: String) { v.toLongOrNull()?.let { add(CreateParam(k, CreateValue.Int(it))) } }
    num("vmid", draft.vmid); text("hostname", draft.hostname); text("pool", draft.pool); text("tags", encodeTags(draft.tags))
    if (ctx.release >= 9 && draft.haManaged) num("ha-managed", "1")
    text("ostemplate", draft.template); num("unprivileged", if (draft.unprivileged) "1" else "0"); text("features", encodeFeatures(draft.unprivileged, draft.nesting))
    draft.mounts.forEach { add(encodeCtMount(if (ctx.release < 9) it.copy(keepAttrs = false, idMap = IdMap.None) else it, ctx.release)) }
    num("cores", draft.cores); text("cpulimit", draft.cpuLimit); num("cpuunits", draft.cpuUnits); num("memory", draft.memoryMib); num("swap", draft.swapMib)
    text("net0", encodeCtNic(if (ctx.release < 9) draft.nic.copy(hostManaged = false) else draft.nic, ctx.release))
    text("searchdomain", draft.searchDomain); text("nameserver", encodeNameservers(draft.nameservers))
}

/** The Parameters panel lines: each parameter, then `password = set` / `ssh-public-keys = 2 keys` from [secrets], then `start = 1`. */
fun createParamLines(params: List<CreateParam>, secrets: SecretState, start: Boolean = false): List<ParamLine> = params.map { ParamLine(it.key, createValueText(it.value)) } + listOfNotNull(if (secrets.password) ParamLine("password", "set", true) else null, if (secrets.keyCount > 0) ParamLine("ssh-public-keys", "${secrets.keyCount} keys", true) else null, if (start) ParamLine("start", "1") else null)

/**
 * The form-encoded request body: scalar values followed by `start` and secrets encoded directly from CharArrays
 * into UTF-8 percent-encoded bytes, never through a String. The caller zero-fills the returned array after the transport writes it. Throws IllegalStateException when a
 * secret was already wiped.
 */
fun createBody(request: CreateRequest, secrets: CreateSecrets): ByteArray = encodeCreateForm(request, secrets)

// ---------------------------------------------------------------------------------------------------------------------
// 6.4 Validation (pure). Messages are the plain sentences of design section 3. An empty map means valid.
// ---------------------------------------------------------------------------------------------------------------------

/** Every rule of design 6.4 for a VM draft. */
fun validateVm(draft: VmDraft, ctx: CreateContext): Map<FieldId, String> = validateVmFields(draft, ctx)

/** Every rule of design 6.4 for a container draft, except the secrets ([validateCtSecrets]). */
fun validateCt(draft: CtDraft, ctx: CreateContext): Map<FieldId, String> = validateCtFields(draft, ctx)

/**
 * The container secret rules, read straight from the text fields without copying them into a String: password at least
 * 5 characters and equal to its confirmation, or at least one valid OpenSSH key line; each non-blank key line must be a
 * key ("Line 2 is not an OpenSSH public key."). Keys: [CreateFields.PASSWORD], [CreateFields.PASSWORD_CONFIRM],
 * [CreateFields.SSH_KEYS]. Messages never quote the text.
 */
fun validateCtSecrets(password: CharSequence, confirmation: CharSequence, sshKeys: CharSequence): Map<FieldId, String> = validateCreateSecretFields(password, confirmation, sshKeys)

/** Validate and, when valid, freeze a VM draft into a request (with `start` off; the confirmation sets it). */
fun freezeVm(draft: VmDraft, ctx: CreateContext): FreezeResult = validateVm(draft, ctx).let { if (it.isNotEmpty()) FreezeResult.Invalid(it) else FreezeResult.Ready(CreateRequest(NewGuestKind.Vm, draft.node, draft.vmid.toInt(), draft.name, vmCreateParams(draft, ctx))) }

/** Validate and, when valid, freeze a container draft. The secrets are checked separately with [validateCtSecrets]. */
fun freezeCt(draft: CtDraft, ctx: CreateContext): FreezeResult = validateCt(draft, ctx).let { if (it.isNotEmpty()) FreezeResult.Invalid(it) else FreezeResult.Ready(CreateRequest(NewGuestKind.Container, draft.node, draft.vmid.toInt(), draft.hostname, ctCreateParams(draft, ctx))) }

/**
 * The draft field a server error key belongs to (design 4.4): `vmid` → [CreateFields.VMID], `scsi0` → `disks.scsi0`,
 * `ide2` → [CreateFields.INSTALL], `efidisk0` → [CreateFields.EFI], `net0` → [CreateFields.NIC], `password` →
 * [CreateFields.PASSWORD], and so on. Null for a key the form has no field for: it goes to the "Server said" panel.
 * `storage` is resolved by the caller against the disk rows.
 */
fun createErrorTarget(serverKey: String): FieldId? = when (serverKey) {
    "vmid" -> CreateFields.VMID; "name", "hostname" -> CreateFields.NAME; "node" -> CreateFields.NODE; "pool" -> CreateFields.POOL
    "ide2", "scsi2" -> CreateFields.INSTALL; "ide0" -> CreateFields.DRIVER_DISC; "efidisk0" -> CreateFields.EFI; "tpmstate0" -> CreateFields.TPM
    "net0" -> CreateFields.NIC; "password" -> CreateFields.PASSWORD; "ssh-public-keys" -> CreateFields.SSH_KEYS; "ostemplate" -> CreateFields.TEMPLATE_VOLUME
    "ostype" -> CreateFields.OS_TYPE; "memory" -> CreateFields.MEMORY_MIB; "balloon" -> CreateFields.BALLOON; "swap" -> CreateFields.SWAP
    "cores" -> CreateFields.CORES; "sockets" -> CreateFields.SOCKETS; "cpu" -> CreateFields.CPU_TYPE; "cpulimit" -> CreateFields.CPU_LIMIT
    "cpuunits" -> CreateFields.CPU_UNITS; "vcpus" -> CreateFields.VCPUS; "affinity" -> CreateFields.AFFINITY; "shares" -> CreateFields.SHARES; "startup" -> CreateFields.START_ORDER; "arch" -> CreateFields.ARCH; "tags" -> CreateFields.TAGS
    "searchdomain" -> CreateFields.SEARCH_DOMAIN; "nameserver" -> CreateFields.NAMESERVERS; "bios" -> CreateFields.BIOS; "machine" -> CreateFields.MACHINE
    "scsihw" -> CreateFields.SCSI_HW; "vga" -> CreateFields.VGA; "agent" -> CreateFields.AGENT; "ha-managed" -> CreateFields.HA
    else -> if (serverKey.matches(Regex("net[0-9]+"))) CreateFields.NIC else if (serverKey.matches(Regex("(?:ide|sata|scsi|virtio)[0-9]+|rootfs|mp[0-9]+"))) CreateFields.disk(serverKey) else null
}

// ---------------------------------------------------------------------------------------------------------------------
// 6.5 Defaults and dependencies (pure). A dependent change never overwrites a field in `touched`.
// ---------------------------------------------------------------------------------------------------------------------

/** The Proxmox defaults for a new VM on [node] (R§4), adjusted for the host architecture and the first `images` storage. */
fun defaultVmDraft(node: String, ctx: CreateContext): VmDraft = applyOsDefaults(VmDraft(node, isoStorage = ctx.storages.firstOrNull { "iso" in it.content }?.id.orEmpty(), disks = listOf(VmDisk(source = DiskSource.New(ctx.storages.firstOrNull { "images" in it.content }?.id.orEmpty())))), "l26", if (ctx.hostArch == "aarch64") "aarch64" else null).draft

/** The Proxmox defaults for a new container on [node] (R§5), with the first `rootdir` and `vztmpl` storages. */
fun defaultCtDraft(node: String, ctx: CreateContext): CtDraft = CtDraft(node, templateStorage = ctx.storages.firstOrNull { "vztmpl" in it.content }?.id.orEmpty(), mounts = listOf(CtMount(source = MountSource.New(ctx.storages.firstOrNull { "rootdir" in it.content }?.id.orEmpty()))))

/**
 * Re-apply the OS defaults of R§4.10 (and R§4.9 for [arch]) to untouched fields: `l26` → scsi / virtio /
 * virtio-scsi-single; `win11` → q35, ovmf, TPM v2.0, 4096 MiB, ide disk, e1000; `w2k` → rtl8139, lsi.
 */
fun applyOsDefaults(draft: VmDraft, osType: String, arch: String?): Applied<VmDraft> = applyCreateOsDefaults(draft, osType, arch)

/** Switch the guest architecture (R§4.9): ARM → virt, OVMF only, scsi2 install disc, `host` CPU, no IDE, TPM v2.0 only. */
fun applyArch(draft: VmDraft, arch: String?): Applied<VmDraft> = applyCreateOsDefaults(draft, draft.osType, arch)

/** Turn the Windows VirtIO driver disc on or off; on also sets the first disk to SCSI and the NIC to VirtIO (as Proxmox does). */
fun applyDriverDisc(draft: VmDraft, enabled: Boolean): Applied<VmDraft> = Applied(draft.copy(driverDisc = if (enabled) CdDrive("ide0") else null,
    disks = if (enabled) draft.disks.mapIndexed { i, d -> if (i == 0 && CreateFields.DISKS !in draft.touched) applyBus(d, DiskBus.Scsi, "virtio-scsi-single") else d } else draft.disks,
    nic = if (enabled && CreateFields.MODEL !in draft.touched) draft.nic?.copy(model = "virtio") else draft.nic))

/** Choose a storage for a disk: format qcow2 if offered, else raw, else the storage default (null); an existing-volume source on `select_existing` storages. */
fun applyStorage(disk: VmDisk, storage: StorageChoice): VmDisk = disk.copy(source = if (storage.selectExisting) DiskSource.Existing("") else DiskSource.New(storage.id, (disk.source as? DiskSource.New)?.sizeGib ?: "32"), format = if (storage.selectExisting) null else storage.formats?.let { if ("qcow2" in it.supported) "qcow2" else if ("raw" in it.supported) "raw" else it.default })

/**
 * Change a disk's bus: IO thread default by bus and controller (on for VirtIO, on for SCSI with virtio-scsi-single),
 * SSD and read-only off where the bus has none. The index is kept; a collision is a validation error.
 */
fun applyBus(disk: VmDisk, bus: DiskBus, scsiHw: String?): VmDisk = disk.copy(bus = bus, ioThread = bus == DiskBus.Virtio || (bus == DiskBus.Scsi && scsiHw == "virtio-scsi-single"), ssd = disk.ssd && bus != DiskBus.Virtio, readOnly = disk.readOnly && bus in listOf(DiskBus.Scsi, DiskBus.Virtio))

/** The disk "Add disk" creates: the first free slot on the most used bus, ties broken by the OS bus priority (R§4.4). Null when no slot is free. */
fun nextFreeDisk(draft: VmDraft): VmDisk? = run {
    val buses = (if (draft.arch == "aarch64") listOf(DiskBus.Scsi, DiskBus.Sata, DiskBus.Virtio) else if (draft.osType == "l26") listOf(DiskBus.Scsi, DiskBus.Virtio, DiskBus.Sata, DiskBus.Ide) else listOf(DiskBus.Ide, DiskBus.Sata, DiskBus.Scsi, DiskBus.Virtio)).filter { draft.arch != "aarch64" || it != DiskBus.Ide }.sortedByDescending { bus -> draft.disks.count { it.bus == bus } }
    val used = draft.disks.map { it.key } + listOfNotNull(draft.install.slot, draft.driverDisc?.slot)
    buses.firstNotNullOfOrNull { bus -> (0..bus.maxIndex).firstOrNull { bus.prefix + it !in used }?.let { VmDisk(bus = bus, index = it) } }
}

/** The mount point "Add mount point" creates: the first free `mpN` (0 to 255). Null when none is free. */
fun nextFreeMount(draft: CtDraft): CtMount? = (0..255).firstOrNull { n -> draft.mounts.none { it.key == "mp$n" } }?.let { CtMount(key = "mp$it", source = MountSource.New(sizeGib = "8")) }

private fun opt(key: String, value: String): String? = value.takeIf { it.isNotBlank() }?.let { "$key=$it" }
internal fun createValueText(value: CreateValue): String = when(value) { is CreateValue.Text -> value.value; is CreateValue.Int -> value.value.toString(); is CreateValue.Bool -> if(value.value) "1" else "0" }

/** Retains the import source while reconciling the target format. */
fun applyImportStorage(disk: VmDisk, storage: StorageChoice): VmDisk {
    val source = disk.source as? DiskSource.Import ?: return applyStorage(disk, storage)
    return disk.copy(source = source.copy(targetStorage = storage.id), format = storage.formats?.let { if (disk.format in it.supported) disk.format else it.default })
}

fun validCreateVolumeId(value: String): Boolean = value.length in 3..1024 && value.matches(Regex("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./+@:-]+"))
