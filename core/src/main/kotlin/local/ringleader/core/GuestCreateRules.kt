package local.ringleader.core

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** Form encoder with a wipeable backing buffer. No secret character is ever materialized as a String. */
internal fun encodeCreateForm(request: CreateRequest, secrets: CreateSecrets): ByteArray {
    require((secrets.password?.length ?: 0) <= CREATE_PASSWORD_MAX_CHARS && (secrets.sshKeys?.length ?: 0) <= CREATE_SSH_KEYS_MAX_CHARS) { "Secret input is too large." }
    require(request.params.size <= 1024 && request.params.all { it.key.length <= 256 && createValueText(it.value).length <= CREATE_SSH_KEYS_MAX_CHARS }) { "Create input is too large." }
    var bytes = ByteArray(1024)
    var size = 0
    fun write(b: Int) {
        require(size < CREATE_FORM_MAX_BYTES) { "Create body is too large." }
        if (size == bytes.size) { val old = bytes; bytes = old.copyOf(old.size * 2); old.fill(0) }
        bytes[size++] = b.toByte()
    }
    fun encodedByte(byte: Byte) {
            val b = byte.toInt() and 255
            if (b in 65..90 || b in 97..122 || b in 48..57 || b in listOf(45, 46, 95, 42)) write(b)
            else if (b == 32) write(43)
            else { write(37); write("0123456789ABCDEF"[b shr 4].code); write("0123456789ABCDEF"[b and 15].code) }
    }
    fun encoded(raw: ByteArray) { try { raw.forEach { encodedByte(it) } } finally { raw.fill(0) } }
    fun key(k: String) { if (size > 0) write(38); encoded(k.toByteArray()); write(61) }
    try {
        for (p in request.params) { key(p.key); encoded(createValueText(p.value).toByteArray(StandardCharsets.UTF_8)) }
        if (request.start) { key("start"); write(49) }
        for ((name, secret) in listOf("password" to secrets.password, "ssh-public-keys" to secrets.sshKeys)) {
            if (secret == null) continue
            secret.use { chars ->
                if (chars.isNotEmpty()) {
                    key(name)
                    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
                    try { while (buffer.hasRemaining()) { val b = buffer.get(); encodedByte(b) } }
                    finally { if (buffer.hasArray()) buffer.array().fill(0) }
                }
            }
        }
        return bytes.copyOf(size)
    } finally { bytes.fill(0) }
}

internal fun validateCreateSecretFields(password: CharSequence, confirmation: CharSequence, keys: CharSequence): Map<FieldId, String> = buildMap {
    if (keys.length > CREATE_SSH_KEYS_MAX_CHARS) { put(CreateFields.SSH_KEYS, "SSH key input is too large."); return@buildMap }
    if (password.length > CREATE_PASSWORD_MAX_CHARS || confirmation.length > CREATE_PASSWORD_MAX_CHARS) { put(CreateFields.PASSWORD, "Password is too large."); return@buildMap }
    if (password.isEmpty() && keys.all { it.isWhitespace() }) put(CreateFields.PASSWORD, "Set a password or an SSH public key.")
    if (password.isNotEmpty() && password.length < 5) put(CreateFields.PASSWORD, "Use at least 5 characters.")
    if (password.length != confirmation.length || password.indices.any { it >= confirmation.length || password[it] != confirmation[it] }) put(CreateFields.PASSWORD_CONFIRM, "The passwords do not match.")
    if (password.length > CREATE_PASSWORD_MAX_CHARS || confirmation.length > CREATE_PASSWORD_MAX_CHARS) put(CreateFields.PASSWORD, "Password is too large.")
    var start = 0; var line = 1
    while (start < keys.length) {
        var end = start; while (end < keys.length && keys[end] != '\n') end++
        if (end - start > CREATE_SSH_KEY_LINE_MAX_CHARS || !validSshLine(keys, start, end)) put(CreateFields.SSH_KEYS, "Line $line is not an OpenSSH public key.")
        start = end + 1; line++
    }
}

private fun commonCreateErrors(node: String, id: String, name: String, tags: List<String>, ctx: CreateContext): MutableMap<FieldId, String> {
    val errors = linkedMapOf<FieldId, String>()
    if (pveNodeNameOrNull(node) == null) errors[CreateFields.NODE] = "Choose a node that is online."
    if (ctx.choiceNode != null && node != ctx.choiceNode) errors[CreateFields.NODE] = "Choose the current node."
    val n = id.toIntOrNull()
    if (!id.matches(Regex("[0-9]+")) || n == null || n !in CREATE_VMID_MIN..CREATE_VMID_MAX) errors[CreateFields.VMID] = "Use a number from 100 to 999999999."
    else if (n in ctx.usedIds || (ctx.idCheck as? IdCheck.Taken)?.id == n) errors[CreateFields.VMID] = "ID $n is in use."
    if (name.length > 255 || (name.isNotEmpty() && !dnsName(name))) errors[CreateFields.NAME] = "Use letters, digits, hyphens and dots, like web-1."
    if (tags.any { !it.matches(Regex("[a-zA-Z0-9+_.-]+")) } || tags.distinct().size != tags.size) errors[CreateFields.TAGS] = "Tags use letters, digits and + _ . - only; no duplicates."
    return errors
}
private fun dnsName(s: String) = s.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*"))
private fun MutableMap<FieldId, String>.number(field: FieldId, value: String, min: Double, max: Double = Long.MAX_VALUE.toDouble(), optional: Boolean = true, whole: Boolean = true) {
    if (optional && value.isBlank()) return
    val n = value.toDoubleOrNull()
    if (n == null || !n.isFinite() || n < min || n > max || (whole && !value.matches(Regex("[0-9]+")))) this[field] = "Use ${if (whole) "a whole number" else "a number"} from $min to $max."
}
private fun MutableMap<FieldId, String>.storage(field: String, id: String, content: String, ctx: CreateContext) {
    if (ctx.storages.none { (ctx.choiceNode == null || it.node == ctx.choiceNode) && it.id == id && content in it.content && it.status == "available" }) this[field] = "Choose a recorded storage for $content."
}
private fun MutableMap<FieldId, String>.network(mac: String, vlan: String, mtu: String, rate: String, vm: Boolean, virtio: Boolean = true) {
    if (mac.isNotBlank() && (!mac.matches(Regex("([a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}")) || (mac.substringBefore(':').toIntOrNull(16) ?: 1) and 1 != 0)) this[CreateFields.MAC] = "Use a unicast MAC address."
    number(CreateFields.VLAN, vlan, 1.0, 4094.0)
    if (mtu.isNotBlank() && !(vm && virtio && mtu == "1")) number(CreateFields.MTU, mtu, 576.0, if (vm) 65520.0 else 65535.0)
    if (vm && !virtio && mtu.isNotBlank()) this[CreateFields.MTU] = "MTU needs VirtIO."
    number(CreateFields.RATE, rate, 0.0, 10240.0, whole = false)
}
private fun ipAddress(value: String, v6: Boolean): Boolean {
    val raw = value.substringBefore('%')
    if (v6) return raw.contains(':') && raw.matches(Regex("[a-fA-F0-9:.]+")) && runCatching { java.net.InetAddress.getByName(raw) is java.net.Inet6Address }.getOrDefault(false)
    return raw.split('.').let { it.size == 4 && it.all { s -> s.matches(Regex("[0-9]{1,3}")) && s.toInt() in 0..255 } }
}
private fun MutableMap<FieldId, String>.address(cidr: String, gateway: String, v6: Boolean) {
    val f = if (v6) CreateFields.IP6 else CreateFields.IP4
    if (cidr.isNotBlank() && (cidr.count { it == '/' } != 1 || !ipAddress(cidr.substringBefore('/'), v6) || (cidr.substringAfter('/').toIntOrNull() ?: -1) !in 0..(if (v6) 128 else 32))) this[f] = "Use an address with a network prefix."
    if (gateway.isNotBlank() && (cidr.isBlank() || !ipAddress(gateway, v6))) this[if (v6) CreateFields.GW6 else CreateFields.GW4] = "Use a gateway of the same address family with a static address."
}
internal fun validateVmFields(d: VmDraft, c: CreateContext): Map<FieldId, String> = commonCreateErrors(d.node, d.vmid, d.name, d.tags, c).apply {
    number(CreateFields.SOCKETS, d.sockets, 1.0, optional = false); number(CreateFields.CORES, d.cores, 1.0, 256.0, false)
    number(CreateFields.VCPUS, d.vcpus, 1.0, (d.sockets.toDoubleOrNull() ?: 1.0) * (d.cores.toDoubleOrNull() ?: 1.0))
    number(CreateFields.CPU_LIMIT, d.cpuLimit, 0.0, 128.0, whole = false)
    number(CreateFields.CPU_UNITS, d.cpuUnits, if (c.cgroupMode == 1) 2.0 else 1.0, if (c.cgroupMode == 1) 262144.0 else 10000.0)
    number(CreateFields.MEMORY_MIB, d.memoryMib, 16.0, optional = false); number(CreateFields.BALLOON, d.minMemoryMib, 1.0, d.memoryMib.toDoubleOrNull() ?: 1.0); number(CreateFields.SHARES, d.shares, 0.0, 50000.0)
    listOf(CreateFields.START_ORDER to d.startOrder, CreateFields.START_UP to d.startUp, CreateFields.START_DOWN to d.startDown).forEach { number(it.first, it.second, 0.0) }
    if (d.affinity.isNotBlank() && (!c.identityIsRootPam || !d.affinity.matches(Regex("[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*")))) this[CreateFields.AFFINITY] = "Only root@pam can set a valid CPU affinity."
    if (d.osType !in OS_CHOICES.map { it.value }) this[CreateFields.OS_TYPE] = "Choose an OS type."
    for (drive in listOfNotNull(d.install, d.driverDisc)) if (drive.media is CdMedia.Iso && (drive.media.storage != drive.media.volid.substringBefore(':') || !currentVolume(drive.media.volid, "iso", c))) this[if (drive == d.install) CreateFields.INSTALL else CreateFields.DRIVER_DISC] = "Choose an ISO image."
    if (d.bios == "ovmf" && d.addEfi) storage(CreateFields.EFI, d.efi.storage, "images", c)
    d.tpm?.let { storage(CreateFields.TPM, it.storage, "images", c); if (d.arch == "aarch64" && it.version != "v2.0") this[CreateFields.TPM] = "ARM needs TPM v2.0." }
    val slots = mutableSetOf(d.install.slot); d.driverDisc?.let { if (!slots.add(it.slot)) this[CreateFields.DRIVER_DISC] = "Choose a free CD slot." }
    for (disk in d.disks) {
        val f = CreateFields.disk(disk.key)
        if (disk.index !in 0..disk.bus.maxIndex || !slots.add(disk.key) || (d.arch == "aarch64" && disk.bus == DiskBus.Ide)) this[f] = "Choose a free disk slot."
        when (val src = disk.source) {
            is DiskSource.New -> { storage("$f.storage", src.storage, "images", c)
                if (c.storages.firstOrNull { it.id == src.storage }?.selectExisting == true) this["$f.storage"] = "Choose an existing volume on this storage."
                if (disk.format != null && c.storages.firstOrNull { it.id == src.storage }?.formats?.supported?.contains(disk.format) == false) this["$f.format"] = "Choose a format this storage supports."
                number("$f.size", src.sizeGib, 0.001, 131072.0, false, false); if (!src.sizeGib.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?"))) this["$f.size"] = "Use at most three decimals." }
            is DiskSource.Existing -> if (!currentVolume(src.volid, "images", c)) this["$f.volume"] = "Choose a volume."
            is DiskSource.Import -> { if (c.storages.firstOrNull { it.id == src.targetStorage }?.selectExisting == true) this["$f.storage"] = "This storage requires an existing volume."
                if (c.release < 9 || !currentVolume(src.fromVolid, "import", c)) this["$f.import"] = "Choose an import image on Proxmox VE 9."; storage("$f.storage", src.targetStorage, "images", c) }
        }
        if (disk.readOnly && disk.bus !in listOf(DiskBus.Scsi, DiskBus.Virtio)) this[f] = "Read-only needs SCSI or VirtIO."
        val target = when (val src = disk.source) { is DiskSource.New -> src.storage; is DiskSource.Import -> src.targetStorage; is DiskSource.Existing -> null }
        if (disk.format != null && (target == null || c.storages.firstOrNull { it.id == target }?.formats?.supported?.contains(disk.format) != true)) this["$f.format"] = "Choose a supported format."
        val l = disk.limits
        listOf(l.mbpsRd, l.mbpsWr, l.mbpsRdMax, l.mbpsWrMax).forEach { number("$f.limits", it.orEmpty(), 1.0, whole = false) }
        listOf(l.iopsRd, l.iopsWr, l.iopsRdMax, l.iopsWrMax).forEach { number("$f.limits", it.orEmpty(), 10.0) }
    }
    if (d.arch == "aarch64" && (d.osType !in listOf("l26", "l24", "other") || d.bios != "ovmf" || d.disks.any { it.bus == DiskBus.Ide })) this[CreateFields.ARCH] = "ARM needs Linux or Other, OVMF and no IDE disks."
    if (d.vga !in VGA_CHOICES.map { it.value }) this[CreateFields.VGA] = "Choose a graphics device."
    if (d.scsiHw !in SCSI_CONTROLLER_CHOICES.map { it.value }) this[CreateFields.SCSI_HW] = "Choose a SCSI controller."
    if (d.cpuFlags.any { !it.name.matches(Regex("[a-zA-Z0-9_.-]+")) }) this[CreateFields.CPU_FLAGS] = "Choose valid CPU flags."
    if (d.cpuType.contains(',') || d.cpuType.contains(';')) this[CreateFields.CPU_TYPE] = "Choose a CPU model."
    if (c.cpuModels != null && c.cpuModels.none { it.name == d.cpuType }) this[CreateFields.CPU_TYPE] = "Choose a current CPU model."
    if (c.cpuFlags != null && d.cpuFlags.any { flag -> c.cpuFlags.none { it.name == flag.name } }) this[CreateFields.CPU_FLAGS] = "Choose current CPU flags."
    d.nic?.let { if (c.bridges != null && c.bridges.none { b -> b.name == it.bridge }) this[CreateFields.BRIDGE] = "Choose a current bridge."
 if (!it.bridge.matches(Regex("[a-zA-Z0-9_.-]*"))) this[CreateFields.BRIDGE] = "Choose a bridge."
        if (it.model !in NIC_MODEL_CHOICES.map { choice -> choice.value }) this[CreateFields.MODEL] = "Choose a network model."
        network(it.mac, it.vlan, it.mtu, it.rate, true, it.model == "virtio"); number(CreateFields.QUEUES, it.queues, 1.0, 64.0) }
    if (c.release < 9 && d.arch != null) this[CreateFields.ARCH] = "Architecture selection needs Proxmox VE 9."
}
internal fun validateCtFields(d: CtDraft, c: CreateContext): Map<FieldId, String> = commonCreateErrors(d.node, d.vmid, d.hostname, d.tags, c).apply {
    if (d.hostname.isBlank()) this[CreateFields.NAME] = "Enter a hostname."
    if (d.templateStorage != d.template.substringBefore(':') || !currentVolume(d.template, "vztmpl", c)) this[CreateFields.TEMPLATE_VOLUME] = "Choose a container template."
    number(CreateFields.CORES, d.cores, 1.0, 8192.0); number(CreateFields.CPU_LIMIT, d.cpuLimit, 0.0, 8192.0, whole = false)
    number(CreateFields.CPU_UNITS, d.cpuUnits, 8.0, if (c.cgroupMode == 1) 500000.0 else 10000.0)
    number(CreateFields.MEMORY_MIB, d.memoryMib, 16.0, optional = false); number(CreateFields.SWAP, d.swapMib, 0.0, optional = false)
    val used = mutableSetOf<String>(); val paths = mutableSetOf<String>()
    if (d.mounts.count { it.key == "rootfs" } != 1) this[CreateFields.DISKS] = "Keep exactly one root disk."
    for (m in d.mounts) {
        val f = CreateFields.disk(m.key)
        if (!used.add(m.key) || (m.key != "rootfs" && (!m.key.startsWith("mp") || (m.key.removePrefix("mp").toIntOrNull() ?: -1) !in 0..255))) this[f] = "Choose a free mount ID."
        if (m.key != "rootfs" && (!m.path.startsWith('/') || m.path.any { it == ',' || it.isISOControl() } || ".." in m.path.split('/') || !paths.add(m.path))) this["$f.path"] = "Use a unique absolute path without .. segments."
        when (val src = m.source) {
            is MountSource.New -> { storage("$f.storage", src.storage, "rootdir", c); number("$f.size", src.sizeGib, 0.001, 131072.0, false, false) }
            is MountSource.Existing -> if (!currentVolume(src.volid, "rootdir", c)) this["$f.volume"] = "Choose a volume."
        }
        val storageId = when (val src = m.source) { is MountSource.New -> src.storage; is MountSource.Existing -> src.volid.substringBefore(':') }
        if (m.quota && (d.unprivileged || c.storages.firstOrNull { it.id == storageId }?.pluginType in listOf("zfs", "zfspool"))) this["$f.quota"] = "Quota is unavailable for this container or storage."
        if (m.key == "rootfs" && m.readOnly) this[f] = "The root disk cannot be read-only."
        if (m.mountOptions.any { it !in if (m.key == "rootfs") CT_ROOTFS_MOUNT_OPTIONS else CT_MOUNT_OPTIONS }) this["$f.options"] = "Choose supported mount options."
        if (c.release < 9 && (m.keepAttrs || m.idMap != IdMap.None)) this["$f.idmap"] = "ID mapping needs Proxmox VE 9."
        (m.idMap as? IdMap.Ranges)?.let { map -> if (map.rows.isEmpty()) this["$f.idmap"] = "Add a complete ID range."
            map.rows.forEach { r -> number("$f.idmap", r.containerId, 0.0, optional = false); number("$f.idmap", r.hostId, 0.0, optional = false); number("$f.idmap", r.count, 1.0, optional = false) } }
    }
    if (!d.nic.bridge.matches(Regex("[a-zA-Z0-9_.-]+"))) this[CreateFields.BRIDGE] = "Choose a bridge."
    if (c.bridges != null && c.bridges.none { it.name == d.nic.bridge }) this[CreateFields.BRIDGE] = "Choose a current bridge."
    network(d.nic.mac, d.nic.vlan, d.nic.mtu, d.nic.rate, false)
    if (d.nic.bridge.isBlank()) this[CreateFields.BRIDGE] = "Choose a bridge."
    if (!d.nic.name.matches(Regex("[-_.a-zA-Z0-9]+"))) this[CreateFields.NIC_NAME] = "Use an interface name."
    (d.nic.ipv4 as? IpMode.Static)?.let { address(it.cidr, it.gateway, false) }; (d.nic.ipv6 as? Ip6Mode.Static)?.let { address(it.cidr, it.gateway, true) }
    encodeNameservers(d.nameservers)?.split(' ')?.forEach { if (!ipAddress(it, it.contains(':'))) this[CreateFields.NAMESERVERS] = "Use IP addresses for DNS servers." }
    if (d.searchDomain.isNotBlank() && d.searchDomain.split(Regex("[ ,;]+ ".trim())).any { !dnsName(it) }) this[CreateFields.SEARCH_DOMAIN] = "Use DNS domain names."
}
internal fun applyCreateOsDefaults(d: VmDraft, os: String, arch: String?): Applied<VmDraft> {
    val arm = arch == "aarch64"; val windows = os.startsWith("win") || os in listOf("w2k", "w2k8", "wxp")
    fun free(f: String) = f !in d.touched
    val bus = if (os == "l26" || arm) DiskBus.Scsi else DiskBus.Ide
    return Applied(d.copy(osType = os, arch = arch,
        machine = if (free(CreateFields.MACHINE)) if (arm) "virt" else if (os == "win11") "q35" else null else d.machine,
        bios = if (free(CreateFields.BIOS)) if (arm || os == "win11") "ovmf" else null else d.bios,
        tpm = if (free(CreateFields.TPM)) if (os == "win11") TpmState() else null else d.tpm,
        memoryMib = if (free(CreateFields.MEMORY_MIB)) if (os == "win11") "4096" else "2048" else d.memoryMib,
        scsiHw = if (free(CreateFields.SCSI_HW)) if (os in listOf("w2k", "wxp")) "lsi" else "virtio-scsi-single" else d.scsiHw,
        cpuType = if (free(CreateFields.CPU_TYPE)) if (arm) "host" else "x86-64-v2-AES" else d.cpuType,
        install = d.install.copy(slot = if (arm) "scsi2" else "ide2"),
        disks = d.disks.map { if (free(CreateFields.DISKS) && free(CreateFields.disk(it.key)) && d.touched.none { f -> f.startsWith(CreateFields.disk(it.key) + ".") }) applyBus(it, bus, if (os in listOf("w2k", "wxp")) "lsi" else "virtio-scsi-single") else it },
        nic = if (free(CreateFields.MODEL)) d.nic?.copy(model = if (os in listOf("w2k", "wxp")) "rtl8139" else if (os == "l26") "virtio" else "e1000") else d.nic),
        listOf("Applied defaults for $os."))
}

private fun currentVolume(id: String, content: String, c: CreateContext): Boolean {
    if (!validCreateVolumeId(id)) return false
    val storage = id.substringBefore(':')
    if (c.storages.none { it.id == storage && content in it.content && it.status == "available" && (c.choiceNode == null || it.node == c.choiceNode) }) return false
    return c.volumes?.get(storage to content)?.any { it.volid == id && it.content == content } ?: (c.volumes == null)
}
