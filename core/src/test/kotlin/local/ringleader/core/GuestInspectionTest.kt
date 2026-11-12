package local.ringleader.core

import kotlin.test.*

class GuestInspectionTest {
    private val vm = Guest("https://example.test:8006",100,"fixture","node-a","VM","running")
    private fun confirmation(action: PowerAction, request: SnapshotRequest? = null) = PowerConfirmation(1,vm.remote,vm.remote,"Fixture",vm.resource,vm.node,vm.name,vm.kind,action,vm.status,false,snapshot=request)
    @Test fun vmHardwareToleratesOddKeysAndMultipleDevices() {
        val h = parseHardware("""{"data":{"cores":4,"sockets":2,"balloon":1024,"memory":4096,"boot":"order=scsi0;net0","ostype":"l26","onboot":1,"tags":"a;b","description":"plain <b>text</b>","scsi0":"local:disk,size=20G","sata1":"other:disk,size=30G","net0":"virtio=AA:BB,bridge=vmbr0,tag=5,firewall=1","net1":"e1000=CC:DD,bridge=vmbr1","unknown":{"nested":true},"pending":{"memory":8192}}}""")
        assertEquals(2,h.disks.size); assertEquals("local",h.disks.single { it.key == "scsi0" }.storage); assertEquals(2,h.nics.size)
        assertEquals("1",h.nics[0].properties["firewall"]); assertEquals("5",h.nics[0].properties["tag"])
        assertEquals("plain <b>text</b>",h.fields["description"])
        assertTrue(h.pending.isEmpty())
    }
    @Test fun lxcHardwareHandlesRootAndMounts() {
        val h = parseHardware("""{"data":{"rootfs":"local:subvol,size=8G","mp0":"data:subvol,size=16G,mp=/data","net0":"name=eth0,hwaddr=AA:BB,bridge=vmbr0","cores":2,"swap":512}}""")
        assertEquals(2,h.disks.size); assertEquals("AA:BB",h.nics.single().properties["hwaddr"])
    }
    @Test fun snapshotTreePreservesCurrentOrphansAndCycles() {
        val rows = parseSnapshots("""{"data":[{"name":"base","snaptime":10,"vmstate":1},{"name":"current","parent":"base"},{"name":"orphan","parent":"missing"}]}""")
        assertTrue(rows[0].ram); assertTrue(rows[1].current); assertEquals(1,snapshotDepth(rows[1],rows)); assertEquals(0,snapshotDepth(rows[2],rows))
        val cycle = listOf(rows[0].copy(parent="base")); assertEquals(0,snapshotDepth(cycle[0],cycle))
    }
    @Test fun backupsFilterForeignGuestsAndNonBackups() {
        val b = parseBackups("""{"data":[{"volid":"s:backup/a","vmid":100,"content":"backup","protected":1,"notes":"sample","ctime":20,"size":30},{"vmid":101,"content":"backup"},{"vmid":100,"content":"images"}]}""","s",100)
        assertEquals(1,b.size); assertTrue(b.single().protected); assertEquals(30L,b.single().size)
    }
    @Test fun historyNullsRemainGaps() {
        val h = parseHistory("""{"data":[{"time":2,"cpu":null,"mem":42},{"time":1,"cpu":0.1,"netin":2},{"cpu":4}]}""")
        assertEquals(2,h.size); assertEquals(1.0,h[0].time); assertNull(h[1].values["cpu"]); assertNull(h[0].values["diskread"])
    }
    @Test fun addressesUseDifferentShapes() {
        assertEquals(listOf("192.0.2.1"),parseAddresses("""{"data":{"result":[{"ip-addresses":[{"ip-address":"192.0.2.1"}]}]}}""",true))
        assertEquals(listOf("192.0.2.2/24"),parseAddresses("""{"data":[{"inet":"192.0.2.2/24"}]}""",false))
        assertFailsWith<IllegalStateException> { parseAddresses("""{"data":{}}""",true) }
        val agent = """{"data":{"result":[{"name":"lo","ip-addresses":[{"ip-address":"127.0.0.1"},{"ip-address":"::1"}]},{"name":"eth0","ip-addresses":[{"ip-address":"192.0.2.5"},{"ip-address":"fe80::1"}]},{"name":"tailscale0","ip-addresses":[{"ip-address":"100.64.0.7"}]},{"name":"wg0","ip-addresses":[{"ip-address":"10.8.0.2"}]}]}}"""
        assertEquals(listOf("192.0.2.5","100.64.0.7 (Tailscale)","10.8.0.2 (WireGuard)"),parseAddresses(agent,true))
        assertEquals(listOf("10.9.0.2/24 (VPN)"),parseAddresses("""{"data":[{"name":"tun0","inet":"10.9.0.2/24"},{"name":"lo","inet":"127.0.0.1/8"}]}""",false))
        assertEquals("Container not running", assertFailsWith<IllegalStateException> { parseAddresses("""{"data":null}""",false) }.message)
    }
    @Test fun newPowerPathsBodiesAndStateGates() {
        listOf(PowerAction.Reboot,PowerAction.Suspend,PowerAction.Resume,PowerAction.Reset).forEach { action ->
            assertTrue(powerActionPath(vm.node,vm.kind,vm.id,action).endsWith("/"+action.name.lowercase()))
            assertEquals("{}",powerRequestBody(action).decodeToString())
            assertFalse(actionMatches(vm.copy(template=true),action))
        }
        assertFalse(actionMatches(vm.copy(kind="Container"),PowerAction.Reset))
        assertFalse(actionMatches(vm.copy(kind="Container"),PowerAction.Suspend))
        assertTrue(actionMatches(vm.copy(status="paused"),PowerAction.Resume))
    }
    @Test fun snapshotRequestsHaveVerifiedMethodsAndBodies() {
        val create = snapshotMutation(confirmation(PowerAction.SnapshotCreate,SnapshotRequest("baseline","quote \"",true)))
        assertEquals("POST",create.method); assertTrue(create.path.endsWith("/snapshot")); assertTrue(create.body.decodeToString().contains("\"vmstate\":true"))
        assertEquals("DELETE",snapshotMutation(confirmation(PowerAction.SnapshotDelete,SnapshotRequest("baseline"))).method)
        assertTrue(snapshotMutation(confirmation(PowerAction.SnapshotRollback,SnapshotRequest("baseline"))).path.endsWith("/snapshot/baseline/rollback"))
    }
    @Test fun invalidSnapshotRequestsAreRefused() {
        listOf("current","vzdump","pending","Pending","PENDING","../bad","a b","1bad","x".repeat(41)).forEach { assertFalse(snapshotNameValid(it)) }
        assertFalse(validSnapshotRequest(confirmation(PowerAction.SnapshotCreate)))
        assertFalse(validSnapshotRequest(confirmation(PowerAction.Start,SnapshotRequest("good"))))
        assertFalse(validSnapshotRequest(confirmation(PowerAction.SnapshotRollback,SnapshotRequest("good",includeRam=true))))
    }

    @Test fun pendingConfigKeepsLiveValuesAndOffersSerial() {
        val body = """{"data":[
            {"key":"memory","value":"4096","pending":"8192"},
            {"key":"balloon","value":"0"},
            {"key":"serial0","value":"socket"},
            {"key":"serial1","value":"socket","delete":1},
            {"key":"vga","value":"std"},
            {"key":"cpu","value":"host"},
            {"key":"machine","value":"q35"},
            {"key":"bios","value":"ovmf"},
            {"key":"agent","value":"1"},
            {"key":"scsihw","value":"virtio-scsi-single"},
            {"key":"net0","value":"virtio=AA:BB:CC:DD:EE:FF,bridge=vmbr0"},
            {"key":"scsi2","value":"local-lvm:vm-100-disk-2,size=8G"},
            {"key":"scsi10","value":"local-lvm:vm-100-disk-10,size=8G"},
            {"key":"digest","value":"abc123"}
        ]}"""
        val h = hardwareFromPending(body)
        assertEquals("socket", h.fields["serial0"])
        assertEquals("std", h.fields["vga"])
        assertEquals("host", h.fields["cpu"])
        assertEquals("4096", h.fields["memory"])
        assertEquals("Off", formatHardwareValue("balloon", h.fields.getValue("balloon")))
        assertEquals(iec(8192.0 * 1048576.0), pendingCaption(h.pending.single { it.key == "memory" }).removePrefix("Pending "))
        assertTrue(h.pending.single { it.key == "serial1" }.delete)
        assertEquals("Pending removal", pendingCaption(h.pending.single { it.key == "serial1" }))
        assertTrue(h.pending.none { it.key == "digest" || it.key == "serial0" })
        assertEquals(listOf("scsi2", "scsi10"), h.disks.map { it.key })
        assertFails { hardwareFromPending("""{"data":{}}""") }
    }

    @Test fun devicesSortNaturallyAndMemoryUsesThePropertyString() {
        val h = parseHardware("""{"data":{
            "memory":"current=2048",
            "rootfs":"local:subvol,size=8G",
            "scsi10":"local:vm-100-disk-10,size=1G",
            "scsi2":"local:vm-100-disk-2,size=1G",
            "mp10":"data:subvol,mp=/late,backup=0",
            "mp0":"data:subvol,mp=/data,ro=1,shared=1",
            "efidisk0":"local-lvm:vm-100-disk-0,size=1M",
            "tpmstate0":"local-lvm:vm-100-disk-1,size=4M"
        }}""")
        assertEquals(listOf("rootfs", "efidisk0", "mp0", "mp10", "scsi2", "scsi10", "tpmstate0"), h.disks.map { it.key })
        assertEquals(iec(2048.0 * 1048576.0), formatHardwareValue("memory", h.fields.getValue("memory")))
        assertEquals("mount /data · read-only, shared", mountCaption(h.disks.single { it.key == "mp0" }))
        assertEquals("mount /late · no backup", mountCaption(h.disks.single { it.key == "mp10" }))
        nicModels.forEach { model ->
            assertEquals(model, nicModel(mapOf(model to "AA:BB")))
            assertEquals("AA:BB", nicAddress(mapOf(model to "AA:BB")))
        }
        assertEquals("CC:DD", nicAddress(mapOf("macaddr" to "CC:DD")))
        assertEquals("EE:FF", nicAddress(mapOf("hwaddr" to "EE:FF")))
    }

    @Test fun snapshotsFormATreeAndAnIncompleteOneCannotRollBack() {
        val rows = orderedSnapshots(parseSnapshots("""{"data":[
            {"name":"child","parent":"base","snaptime":20},
            {"name":"later","parent":"base","snaptime":30,"snapstate":"prepare"},
            {"name":"current","parent":"base"},
            {"name":"base","snaptime":10},
            {"name":"orphan","parent":"missing","snaptime":5}
        ]}"""))
        assertEquals(listOf("orphan", "base", "child", "later", "current"), rows.map { it.name })
        assertEquals("prepare", rows.single { it.name == "later" }.snapstate)
        assertFalse(snapshotRollbackAllowed(rows.single { it.name == "later" }))
        assertFalse(snapshotRollbackAllowed(rows.single { it.name == "current" }))
        assertTrue(snapshotRollbackAllowed(rows.single { it.name == "child" }))
        val cycle = orderedSnapshots(listOf(
            GuestSnapshot("a", "b", 1, null, false, false),
            GuestSnapshot("b", "a", 2, null, false, false),
        ))
        assertEquals(listOf("a", "b"), cycle.map { it.name })
    }

    @Test fun backupsKeepHealthyStoragesAndShowVerificationAge() {
        val merged = collectBackups(true, listOf("local", "pbs")) { id ->
            if (id == "pbs") throw IllegalStateException("offline")
            listOf(
                GuestBackup("local:backup/old", "local", 10, 1, null, false),
                GuestBackup("local:backup/new", "local", 50, 2, null, false),
            )
        }
        assertEquals(listOf(50L, 10L), merged.value!!.map { it.time })
        assertEquals("Could not read pbs: offline", merged.error)
        assertEquals(BACKUPS_NOT_SCANNED, collectBackups(false, emptyList()) { error("unused") }.error)
        assertEquals(BACKUPS_NONE_ACTIVE, collectBackups(true, emptyList()) { error("unused") }.error)
        assertTrue(backupStorageActive("available"))
        assertTrue(backupStorageActive("ACTIVE"))
        assertFalse(backupStorageActive("inactive"))
        val refused = collectBackups(true, listOf("local")) { throw GuestPermissionException() }
        assertTrue(refused.error!!.contains("Not permitted for this token"))
        assertFailsWith<SessionExpiredException> { collectBackups(true, listOf("local")) { throw SessionExpiredException() } }
        val old = 1_700_000_000L
        val upid = "UPID:node-a:00000001:00000002:${old.toString(16)}:vzdumpverify:100:tester@pve:"
        val parsed = parseBackups("""{"data":[
            {"volid":"pbs:backup/vm/100/2026-01-01T00:00:00Z","vmid":100,"content":"backup","ctime":$old,"size":10,"verification":{"state":"ok","upid":"$upid"}},
            {"volid":"pbs:backup/vm/100/failed","vmid":100,"content":"backup","ctime":${old + 1},"size":11,"verification":{"state":"failed"}},
            {"volid":"local:backup/vzdump-qemu-100.vma.zst","vmid":100,"content":"backup","ctime":${old + 2},"size":12}
        ]}""", "pbs", 100)
        assertEquals(listOf(null, "failed", "ok"), parsed.map { it.verification })
        assertEquals(old, parsed.first { it.verification == "ok" }.verifiedAt)
        val verified = parsed.first { it.verification == "ok" }
        assertEquals("Verified" to false, backupVerificationText(verified, old + 29L * 24 * 60 * 60))
        assertEquals("Verified, older than 30 days" to true, backupVerificationText(verified, old + 31L * 24 * 60 * 60))
        assertEquals("Verification failed" to true, backupVerificationText(parsed.first { it.verification == "failed" }, old))
        assertEquals("Not verified" to false, backupVerificationText(parsed.first { it.verification == null }, old))
    }

    @Test fun anEmptyRefreshKeepsSectionsThatHaveNotArrived() {
        val previous = demoInspection(vm, "hour")
        val partial = previous.copy(snapshots = InspectionSection(), backups = InspectionSection(), addresses = InspectionSection())
        val merged = mergeGuestInspection(previous, partial)
        assertEquals("baseline", merged.snapshots.value!!.first().name)
        assertEquals(previous.backups.value, merged.backups.value)
        val replaced = mergeGuestInspection(previous, previous.copy(snapshots = InspectionSection(listOf(GuestSnapshot("after", null, 1, null, false, false)))))
        assertEquals("after", replaced.snapshots.value!!.single().name)
    }
}
