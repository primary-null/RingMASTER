package local.ringleader.core

import java.time.Instant

/*
 * What the create wizard is offered on the sample fleet: a free ID, ISO images and templates on `local`, bridges, CPU models
 * and flags, and a session with all of them already read.
 */

const val SAMPLE_CREATE_NEXT_ID = 140

private fun sampleVolume(volid: String, content: String, format: String, sizeBytes: Long) = StorageVolume(
    volid = volid, content = content, format = format, sizeBytes = sizeBytes, approximateSizeBytes = null, usedBytes = null,
    createdAt = null, vmid = null, notes = null, protected = false, verification = null,
)

private const val MIB = 1024L * 1024

/** ISO images, templates and import images on the storage `local`, by (storage, content). */
val sampleCreateVolumes: Map<Pair<String, String>, List<StorageVolume>> = mapOf(
    ("local" to "iso") to listOf(
        sampleVolume("local:iso/debian-13.2.0-amd64-netinst.iso", "iso", "iso", 754 * MIB),
        sampleVolume("local:iso/ubuntu-26.04-live-server-amd64.iso", "iso", "iso", 3_100 * MIB),
        sampleVolume("local:iso/virtio-win-0.1.285.iso", "iso", "iso", 754 * MIB),
    ),
    ("local" to "vztmpl") to listOf(
        sampleVolume("local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst", "vztmpl", "tzst", 126 * MIB),
        sampleVolume("local:vztmpl/alpine-3.22-default_20250617_amd64.tar.xz", "vztmpl", "txz", 3 * MIB),
        sampleVolume("local:vztmpl/ubuntu-26.04-standard_26.04-1_arm64.tar.zst", "vztmpl", "tzst", 140 * MIB),
    ),
    ("local" to "import") to listOf(
        sampleVolume("local:import/appliance-disk.qcow2", "import", "qcow2", 2_048 * MIB),
    ),
)

val sampleCreateBridges: List<BridgeChoice> = listOf(
    BridgeChoice("vmbr0", "bridge", active = true, vlanAware = true, comment = "LAN"),
    BridgeChoice("vmbr1", "bridge", active = true, comment = "Lab"),
    BridgeChoice("lab10", "vnet", active = true, comment = "SDN vnet"),
)

val sampleCreateCpuModels: List<CpuModelChoice> = listOf(
    CpuModelChoice("x86-64-v2-AES", "default", abstract = true),
    CpuModelChoice("x86-64-v3", "default", abstract = true),
    CpuModelChoice("host", "default"),
    CpuModelChoice("kvm64", "default"),
    CpuModelChoice("qemu64", "default"),
)

val sampleCreateCpuFlags: List<CpuFlagChoice> = listOf(
    CpuFlagChoice("md-clear", "Required to let the guest OS know if MDS is mitigated correctly", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("pcid", "Meltdown fix cost reduction on Westmere, Sandy- and Ivy Bridge Intel CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("spec-ctrl", "Allows improved Spectre mitigation with Intel CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("ssbd", "Protection for Speculative Store Bypass for Intel models", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("ibpb", "Allows improved Spectre mitigation with AMD CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("virt-ssbd", "Basis for Speculative Store Bypass protection for AMD models", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("pdpe1gb", "Allow the guest OS to use 1 GB pages, if the host supports it", listOf("demo-node-a")),
    CpuFlagChoice("aes", "Activate the AES instruction set for hardware acceleration", listOf("demo-node-a", "demo-node-b")),
)

/**
 * A wizard session whose reads have all arrived: next ID 140, pool `lab`, bridges `vmbr0` (VLAN-aware), `vmbr1` and vnet
 * `lab10`, five CPU models, eight flags, free tags, and the volumes of [sampleCreateVolumes]. Storage choices come from the
 * inventory, as for a server (D1).
 */
fun sampleCreateSession(kind: NewGuestKind, node: String, epoch: Long, at: Instant): CreateSession = CreateSession(
    kind = kind,
    node = node,
    epoch = epoch,
    nextId = ReadSection.loaded(SAMPLE_CREATE_NEXT_ID, at),
    pools = ReadSection.loaded(listOf(PoolChoice("lab", "Sample pool")), at),
    volumes = sampleCreateVolumes.mapValues { (_, rows) -> ReadSection.loaded(rows, at) },
    bridges = ReadSection.loaded(sampleCreateBridges, at),
    cpuModels = ReadSection.loaded(if (kind == NewGuestKind.Vm) sampleCreateCpuModels else emptyList(), at),
    cpuFlags = ReadSection.loaded(if (kind == NewGuestKind.Vm) sampleCreateCpuFlags else emptyList(), at),
    tagRules = ReadSection.loaded(ClusterTagRules(userAllow = "free"), at),
)
