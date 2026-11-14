package local.ringleader.desktop

import local.ringleader.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sin

/**
 * Invented data for the layout tests and the size-matrix probe: a three-node cluster with twelve guests, some with the
 * awkward values real servers have (a very long name, many tags, a high-availability state, a lock, a template, a guest
 * that is off). Nothing here is read from a server, and every name and address is made up. The state is that of a
 * connected, ordinary (not demo) session, so the screens show every control a real session shows.
 */
internal class MatrixLab(val sandbox: Path) {
    val origin = "https://pve-east.example.test:8006"
    val gib = 1073741824L
    /** Fixed, so two runs draw the same times. */
    val now: Instant = Instant.parse("2026-11-12T10:30:00Z")

    private data class Row(val id: Int, val name: String, val kind: String, val node: String, val status: String, val cpu: Double, val mem: Double, val max: Int,
        val tags: List<String> = emptyList(), val template: Boolean = false, val ha: String? = null, val lock: String? = null, val vcpus: Double = 2.0)

    private val rows = listOf(
        Row(120, "debian-workbench", "VM", "pve-east", "running", .22, 2.6, 8, listOf("dev"), vcpus = 4.0),
        Row(121, "dns-service", "Container", "pve-east", "running", .03, 0.3, 1, listOf("infra")),
        Row(122, "build-runner", "VM", "pve-east", "running", .61, 9.8, 16, listOf("ci", "linux"), vcpus = 8.0),
        Row(123, "metrics-and-observability-collector-production-eu-west", "Container", "pve-east", "running", .14, 1.7, 4, listOf("infra", "monitoring", "production", "eu-west", "managed")),
        Row(124, "gateway", "VM", "pve-west", "running", .07, 0.9, 2, listOf("infra", "edge"), ha = "started"),
        Row(125, "database-primary", "VM", "pve-west", "running", .34, 11.2, 16, listOf("data"), lock = "backup", vcpus = 6.0),
        Row(126, "web", "Container", "pve-west", "running", .18, 0.8, 2, listOf("edge")),
        Row(127, "media-library", "VM", "pve-archive-03", "running", .09, 3.1, 8),
        Row(128, "archive", "VM", "pve-archive-03", "stopped", .0, .0, 4, listOf("data")),
        Row(129, "ubuntu-template", "VM", "pve-archive-03", "stopped", .0, .0, 4, template = true),
        Row(130, "very-long-hostname-for-the-nightly-reporting-job.internal.example.test", "Container", "pve-archive-03", "stopped", .0, .0, 2, listOf("reports", "nightly", "finance", "retired")),
        Row(131, "windows-11-desktop", "VM", "pve-west", "running", .41, 6.2, 8, listOf("desktop"), ha = "error", vcpus = 4.0),
    )

    private val upSince = mapOf(120 to 3 * 86_400L + 4 * 3_600, 121 to 11 * 86_400L, 122 to 5_400L, 123 to 42 * 86_400L, 124 to 9 * 86_400L, 125 to 190 * 86_400L,
        126 to 3 * 3_600L, 127 to 86_400L, 131 to 600L)

    val guests: List<Guest> = rows.mapIndexed { n, r ->
        val running = r.status == "running"
        Guest(origin, r.id, r.name, r.node, r.kind, r.status, template = r.template, tags = r.tags, haState = r.ha, lock = r.lock,
            metrics = GuestListMetrics(cpuFraction = if (running) r.cpu else null, maxCpu = r.vcpus, memoryBytes = if (running) (r.mem * gib).toLong() else null,
                memoryLimitBytes = r.max * gib, diskBytes = if (running) (r.max * gib * .4).toLong() else null, diskLimitBytes = r.max * 4 * gib,
                uptimeSeconds = if (running) upSince[r.id] else null, netIn = if (running) 40_000L + n * 9_000 else null, netOut = if (running) 12_000L + n * 4_000 else null))
    }

    fun guest(id: Int) = guests.first { it.id == id }
    /** The guests to inspect: a running VM, a running container, a VM that is off, and the worst case for width. */
    val vm get() = guest(122)
    val container get() = guest(121)
    val stoppedVm get() = guest(128)
    val longVm get() = guests.first { it.id == 131 }.copy(name = "windows-11-engineering-desktop-with-a-very-long-name", tags = listOf("desktop", "engineering", "managed", "windows"), lock = "snapshot")
    val longContainer get() = guest(123)

    private fun nodeMember(name: String, status: String, cpu: Double, memUsed: Double, memMax: Double, root: Double, ha: String? = null, uptime: Long) =
        RecordedMember(origin, name, status, ha, cpu, 16.0, (memUsed * gib).toLong(), (memMax * gib).toLong(), (root * gib).toLong(), 94 * gib, uptime)
    val members = listOf(
        nodeMember("pve-east", "online", .26, 14.4, 64.0, 21.0, uptime = 40 * 86_400L),
        nodeMember("pve-west", "online", .19, 12.9, 32.0, 33.0, ha = "maintenance", uptime = 12 * 86_400L),
        nodeMember("pve-archive-03", "offline", .0, .0, 32.0, 0.0, uptime = 0L),
    )
    private val storage = listOf(
        RecordedStorage(origin, "pve-east", "local-lvm", false, 210 * gib, 800 * gib, "available", "lvmthin", "images,rootdir"),
        RecordedStorage(origin, "pve-east", "local", false, 18 * gib, 94 * gib, "available", "dir", "iso,vztmpl,backup"),
        RecordedStorage(origin, "pve-east", "shared-backup-storage-primary", true, 1_200 * gib, 4_000 * gib, "available", "nfs", "backup"),
        RecordedStorage(origin, "pve-west", "local-lvm", false, 64 * gib, 480 * gib, "available", "lvmthin", "images,rootdir"),
        RecordedStorage(origin, "pve-west", "shared-backup-storage-primary", true, 1_190 * gib, 4_000 * gib, "available", "nfs", "backup"),
        RecordedStorage(origin, "pve-archive-03", "archive-pool", false, null, null, "unknown", "zfspool", "images"),
    )

    val overview = Overview(3, guests, members = members, storage = storage)

    private fun series(seed: Int, level: Double, memUsed: Double, memMax: Int) = Series((0..59).map { n ->
        val wave = sin(n * (.21 + seed * .037) + seed) * .35 + sin(n * (.83 + seed * .05) + seed * 2.1) * .18
        Sample(now.minusSeconds((59 - n) * 10L), (level * (1 + wave)).coerceIn(.004, .98), (memUsed * gib * (1 + sin(n * .11 + seed) * .03)).toLong(),
            memMax * gib, (40_000 + seed * 9_000) * (1 + wave), (12_000 + seed * 4_000) * (1 + sin(n * .4 + seed) * .4))
    })
    val history = FleetHistory(
        rows.mapIndexedNotNull { n, r -> if (r.status != "running" || r.template) null else guests[n].resource to series(n + 1, r.cpu, r.mem, r.max) }.toMap() + (longVm.resource to series(40, .41, 6.2, 8)),
        members.mapIndexed { n, m -> m.name to series(20 + n, listOf(.26, .19, .05)[n], listOf(14.4, 12.9, 3.1)[n], 32) }.toMap(),
        series(60, .19, 30.4, 128))

    private fun hourHistory(seed: Int, level: Double, memUsed: Double, memMax: Int, cores: Int) = (0..59).map { n ->
        val wave = sin(n * .19 + seed) * .35 + sin(n * .71 + seed * 2.1) * .18
        HistoryPoint((now.epochSecond - (59 - n) * 60L).toDouble(), mapOf(
            "maxcpu" to cores.toDouble(), "cpu" to (level * (1 + wave)).coerceIn(.004, .98), "maxmem" to (memMax * gib).toDouble(), "mem" to memUsed * gib * (1 + sin(n * .13 + seed) * .04),
            "netin" to 42_000.0 * (1 + wave), "netout" to 13_000.0 * (1 + abs(sin(n * .4 + seed)) * .6),
            "diskread" to 60_000.0 + 45_000.0 * abs(sin(n * .5 + seed)), "diskwrite" to 30_000.0 + 22_000.0 * abs(sin(n * .37 + seed * 1.3))))
    }
    val nodeHistory = demoNodeHistory("hour", "AVERAGE")

    private fun hardwareFor(g: Guest): HardwareSummary = parseHardware(
        if (g.kind == "VM") """{"data":{"cores":4,"sockets":1,"cpu":"x86-64-v2-AES","memory":8192,"balloon":2048,"ostype":"l26","machine":"q35","bios":"ovmf","agent":"1","scsihw":"virtio-scsi-single","boot":"order=scsi0;ide2;net0","vga":"qxl","onboot":1,"tags":"${g.tags.joinToString(";")}","description":"Build agent for the nightly and release jobs. Owner: platform team. Do not migrate during the backup window.","scsi0":"shared-storage-ceph-pool-1:vm-${g.id}-disk-0,size=512G,ssd=1,discard=on,iothread=1","scsi1":"local-lvm:vm-${g.id}-disk-1,size=64G","ide2":"local:iso/debian-13.2.0-amd64-netinst.iso,media=cdrom,size=780M","net0":"virtio=02:00:00:00:01:20,bridge=vmbr0,firewall=1,tag=10","net1":"e1000=02:00:00:00:01:21,bridge=vmbr1,link_down=1","usb0":"host=1234:abcd","hostpci0":"0000:01:00.0,pcie=1"}}"""
        else """{"data":{"cores":2,"memory":2048,"swap":512,"ostype":"debian","arch":"amd64","hostname":"${g.name.take(40)}","onboot":1,"unprivileged":1,"tags":"${g.tags.joinToString(";")}","rootfs":"local-lvm:vm-${g.id}-disk-0,size=8G","mp0":"shared-backup-storage-primary:121/vm-${g.id}-disk-1.raw,mp=/srv/data,size=200G","net0":"name=eth0,bridge=vmbr0,hwaddr=02:00:00:00:02:21,ip=192.0.2.21/24,gw=192.0.2.1,type=veth"}}""")

    fun inspectionFor(g: Guest): GuestInspection {
        val running = g.status == "running" && !g.template
        val rowOf = rows.first { it.id == g.id }
        return demoInspection(g, "hour").copy(
            hardware = InspectionSection(hardwareFor(g)), history = InspectionSection(hourHistory(g.id % 7, rowOf.cpu, rowOf.mem, rowOf.max, rowOf.vcpus.toInt())),
            snapshots = InspectionSection(listOf(
                GuestSnapshot("clean-install", null, now.epochSecond - 86_400 * 21, "Fresh install, updates applied", false, false),
                GuestSnapshot("before-the-quarterly-kernel-upgrade-and-driver-update", "clean-install", now.epochSecond - 86_400 * 6, "Before the kernel upgrade", true, false),
                GuestSnapshot("toolchain", "before-the-quarterly-kernel-upgrade-and-driver-update", now.epochSecond - 2_580, "Compiler toolchain installed", false, false, snapstate = "prepare"),
                GuestSnapshot("current", "toolchain", null, null, false, true))),
            backups = InspectionSection((0..4).map { n -> GuestBackup("shared-backup-storage-primary:backup/vzdump-qemu-${g.id}-2026_11_0${n + 1}-03_00_01.vma.zst", "shared-backup-storage-primary", now.epochSecond - 14_400 - n * 86_400, 2 * gib + n * 73_400_320L,
                if (n == 0) "Scheduled backup of the build agent before the weekly maintenance window with a long note" else "Scheduled backup", n == 1, verification = if (n == 2) "failed" else if (n < 2) "ok" else null, verifiedAt = now.epochSecond - 3_600) }),
            addresses = InspectionSection(if (running) listOf("192.0.2.${g.id % 200}", "fd00:1234:5678:9abc:def0:1234:5678:9abc") else emptyList()))
    }

    val tasks: List<ClusterTask> = listOf(
        Triple("vzdump", "121", 95L), Triple("qmstart", "122", 4L), Triple("qmsnapshot", "120", 9L), Triple("vzstart", "126", 2L), Triple("qmshutdown", "128", 27L),
        Triple("vzdump", "125", 48L), Triple("qmrollback", "120", 14L), Triple("aptupdate", "", 11L), Triple("vzdump", "123", 196L), Triple("qmstart", "127", 5L),
    ).mapIndexed { n, (type, id, seconds) ->
        val start = now.minusSeconds(900L + n * 1_400L)
        val running = n == 0
        val failed = n == 5
        ClusterTask(origin, "UPID:pve-east:${"%08X".format(0x1000 + n)}:000000AB:${"%08X".format(start.epochSecond)}:$type:$id:root@pam:", "pve-east", type, id, "root@pam", start,
            if (running) null else start.plusSeconds(seconds), if (running) "running" else if (failed) "Backup storage unavailable: the storage 'shared-backup-storage-primary' is not online" else "OK",
            if (running) TaskOutcome.Running else if (failed) TaskOutcome.Unsuccessful else TaskOutcome.Succeeded)
    }

    val nodeStatus = NodeLiveStatus("pve-east", .27, (14.5 * gib).toLong(), 64 * gib, 21 * gib, 94 * gib, 40 * 86_400L + 3_600, "9.1.2")

    /** The last picture of a running VM's screen, as a session that has opened its Summary once holds it: an invented text console. */
    val still: ConsoleStill by lazy {
        val pixels = drawConsole(640, 400, listOf(plainLine("Debian GNU/Linux 13 build-runner tty1"), plainLine(), promptLine("ci", "build-runner", "uptime"),
            plainLine(" 10:29:41 up 1:30,  1 user,  load average: 0.61, 0.58, 0.40"), promptLine("ci", "build-runner", "")))
        val image = java.awt.image.BufferedImage(640, 400, java.awt.image.BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 640, 400, pixels, 0, 640) }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        ConsoleStill(640, 400, png, now.minusSeconds(95), blank = false)
    }

    /** A state like a real connected session's, with [selected] or [node] chosen and its reads loaded. */
    fun state(selected: Guest? = null, node: String? = null, demo: Boolean = false): WorkspaceState {
        val pick = selected?.let { s -> if (s.id == 131 && s.name != guest(131).name) s else guests.first { it.id == s.id } }
        val inspected = pick?.let(::inspectionFor)
        val with = if (pick != null && pick.id == 131 && pick.name != guest(131).name) overview.copy(guests = guests.map { if (it.id == 131) pick else it }) else overview
        return WorkspaceState(
            connected = !demo, demo = demo, address = if (demo) "" else origin, overview = with, updatedAt = now, selected = pick, selectedNode = node,
            details = pick?.let(::demoDetails), inspection = inspected?.copy(resource = pick.resource, node = pick.node), history = history, tasks = tasks, tasksUpdatedAt = now,
            chartHistory = if (node != null) InspectionSection(nodeHistory) else InspectionSection(inspected?.history?.value),
            nodeStatus = if (node == "pve-east") nodeStatus else null, nodeStatusUpdatedAt = if (node == "pve-east") now else null,
            consoleStills = if (pick != null && pick.kind == "VM" && pick.status == "running" && !pick.template) mapOf(pick.resource to still) else emptyMap(),
            connectionEpoch = 1, live = false)
    }

    val pins: PinStore = PinStore(Files.createDirectories(sandbox.resolve("lab")).resolve("pins.json")).also { pins ->
        pins.toggleGuest(origin, guest(120)); pins.toggleView(origin, guest(120), "Console")
        pins.addService(origin, guest(121), "DNS admin", "http://192.0.2.53:3000")
        pins.toggleGuest(origin, guest(125)); pins.toggleView(origin, guest(122), "Snapshots"); pins.toggleGuest(origin, guest(124))
    }
}
