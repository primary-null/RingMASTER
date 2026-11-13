package local.ringleader.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage view's pure model (design sections 1.1, 2.1 and 5.3). Bodies are handwritten in the shape of the API schema with
 * invented names and documentation addresses; none is a captured response.
 */
class StorageModelTest {
    private val gib = 1024L * 1024 * 1024

    private fun dump(json: String) = parsePermissions("""{"data":$json}""")

    private fun volume(volid: String, content: String? = "images", format: String? = "raw", size: Long? = null, vmid: Int? = null,
        ctime: Long? = null, protected: Boolean = false, approximate: Long? = null) =
        StorageVolume(volid, content, format, size, approximate, null, ctime?.let(Instant::ofEpochSecond), vmid, null, protected, null)

    // ---- visibility (the table in section 1.1) ----

    @Test fun withoutAPermissionListTheVerdictIsUnknown() {
        assertEquals(StorageVisibility.Unknown, storageVisibility(null, "local", setOf("iso"), listOf(120)))
    }

    @Test fun datastoreAllocateShowsEverything() {
        val verdict = storageVisibility(dump("""{"/storage":{"Datastore.Allocate":1}}"""), "local", setOf("backup", "images", "snippets"), listOf(120, 130))
        assertEquals(StorageVisibility.Complete, verdict)
    }

    @Test fun isoAndTemplatesAreNeverHiddenOnceTheListingIsAllowed() {
        val verdict = storageVisibility(dump("""{"/storage/local":{"Datastore.Audit":0}}"""), "local", setOf("iso", "vztmpl", "import"), listOf(120))
        assertEquals(StorageVisibility.Complete, verdict)
    }

    @Test fun anAuditorSeesNoBackupsBecauseItLacksAllocateSpace() {
        val auditor = dump("""{"/":{"Datastore.Audit":1,"VM.Audit":1}}""")
        val verdict = storageVisibility(auditor, "local", setOf("backup", "iso"), listOf(120))
        assertEquals(StorageVisibility.Partial(listOf(HiddenPart("backup", "Datastore.AllocateSpace", "/storage/local", allGuests = true))), verdict)
    }

    @Test fun aRoleOnOneGuestReplacesTheInheritedBackupPrivilege() {
        val permissions = dump("""{"/storage":{"Datastore.AllocateSpace":1},"/vms":{"VM.Backup":1},"/vms/130":{"VM.Audit":0}}""")
        val verdict = storageVisibility(permissions, "local", setOf("backup"), listOf(120, 130))
        assertEquals(StorageVisibility.Partial(listOf(HiddenPart("backup", "VM.Backup", "/vms/130", listOf(130)))), verdict)
    }

    @Test fun aPrivilegeOnOneGuestOnlyLeavesGuestsThisTokenCannotSee() {
        val permissions = dump("""{"/storage":{"Datastore.AllocateSpace":1},"/vms/120":{"VM.Backup":0}}""")
        val verdict = storageVisibility(permissions, "local", setOf("backup"), listOf(120)) as StorageVisibility.Partial
        val part = verdict.parts.single()
        assertEquals("backup", part.content)
        assertEquals("VM.Backup", part.missing)
        assertTrue(part.allGuests, "/vms/0 lacks VM.Backup, so guests this token cannot see may be hidden")
        assertEquals(emptyList(), part.guests, "120 holds VM.Backup on its own path")
    }

    @Test fun snippetsNeedDatastoreAllocate() {
        val verdict = storageVisibility(dump("""{"/storage":{"Datastore.AllocateSpace":1}}"""), "local", setOf("snippets"), emptyList())
        assertEquals(StorageVisibility.Partial(listOf(HiddenPart("snippets", "Datastore.Allocate", "/storage/local"))), verdict)
    }

    @Test fun disksAreCompleteWithAuditAndConfigDiskOnEveryGuest() {
        val permissions = dump("""{"/storage/local":{"Datastore.Audit":1},"/vms":{"VM.Config.Disk":1}}""")
        assertEquals(StorageVisibility.Complete, storageVisibility(permissions, "local", setOf("images", "rootdir"), listOf(120, 121)))
    }

    @Test fun disksWithoutAuditOnTheStorageAreHiddenPerType() {
        val permissions = dump("""{"/storage/local":{"Datastore.AllocateSpace":1},"/vms":{"VM.Config.Disk":1}}""")
        val verdict = storageVisibility(permissions, "local", setOf("rootdir", "images", "future"), listOf(120)) as StorageVisibility.Partial
        assertEquals(listOf("images", "rootdir"), verdict.parts.map { it.content }, "Known types only, in chip order")
        assertTrue(verdict.parts.all { it.missing == "Datastore.Audit" && it.path == "/storage/local" })
    }

    @Test fun guestsTheDumpListsCountEvenWhenTheInventoryDoesNot() {
        val permissions = dump("""{"/storage":{"Datastore.Audit":1},"/vms":{"VM.Config.Disk":1},"/vms/140":{"VM.Audit":0},"/vms/141":{"VM.Audit":0}}""")
        val part = (storageVisibility(permissions, "local", setOf("images"), listOf(120)) as StorageVisibility.Partial).parts.single()
        assertEquals(listOf(140, 141), part.guests)
        assertEquals("/vms", part.path)
        assertFalse(part.allGuests)
    }

    @Test fun thePartialVerdictAlwaysNamesAPart() {
        assertFailsWith<IllegalArgumentException> { StorageVisibility.Partial(emptyList()) }
    }

    @Test fun eachHiddenPartHasItsPlainSentence() {
        assertEquals("Backups are hidden: this token lacks Datastore.AllocateSpace on local.",
            hiddenPartText(HiddenPart("backup", "Datastore.AllocateSpace", "/storage/local", allGuests = true)))
        assertEquals("Backups of 2 guests are hidden: this token lacks VM.Backup on 130 and 131.",
            hiddenPartText(HiddenPart("backup", "VM.Backup", "/vms", listOf(130, 131))))
        assertEquals("Backups of guests this token cannot see may be hidden: it lacks VM.Backup on /vms.",
            hiddenPartText(HiddenPart("backup", "VM.Backup", "/vms", allGuests = true)))
        assertEquals("Disk images of 1 guest are hidden: this token lacks VM.Config.Disk on 120.",
            hiddenPartText(HiddenPart("images", "VM.Config.Disk", "/vms/120", listOf(120))))
        assertEquals("Snippets are hidden: they need Datastore.Allocate on local.",
            hiddenPartText(HiddenPart("snippets", "Datastore.Allocate", "/storage/local")))
        assertEquals("Container volumes of 3 guests are hidden: this token lacks VM.Config.Disk on 120, 121 and 122.",
            hiddenPartText(HiddenPart("rootdir", "VM.Config.Disk", "/vms", listOf(120, 121, 122))))
        assertEquals("Backups of 4 guests are hidden: this token lacks VM.Backup on 4 guests.",
            hiddenPartText(HiddenPart("backup", "VM.Backup", "/vms", listOf(120, 121, 122, 123))))
    }

    @Test fun theStorageListIsCompleteOnlyWhenAuditReachesEveryStorage() {
        assertNull(storageListComplete(null))
        assertEquals(true, storageListComplete(dump("""{"/storage":{"Datastore.Audit":1}}""")))
        assertEquals(true, storageListComplete(dump("""{"/":{"Datastore.Audit":1}}""")))
        assertEquals(false, storageListComplete(dump("""{"/storage/local":{"Datastore.Audit":0}}""")), "Audit on one storage says nothing of the others")
        assertEquals(false, storageListComplete(dump("""{"/storage":{"Datastore.Audit":1},"/storage/backup-nfs":{"Datastore.AllocateSpace":0}}""")))
        assertEquals(false, storageListComplete(dump("""{"/storage":{"Datastore.Audit":0}}""")), "Without Propagate it reaches no storage")
    }

    // ---- fill levels, free, the thin rule ----

    @Test fun fillLevelsFollowTheThresholds() {
        assertEquals(FillLevel.Normal, fillLevel(0, 100))
        assertEquals(FillLevel.Normal, fillLevel(84, 100))
        assertEquals(FillLevel.High, fillLevel(85, 100))
        assertEquals(FillLevel.Critical, fillLevel(95, 100))
        assertNull(fillLevel(10, 0))
        assertNull(fillLevel(null, 100))
        assertNull(fillLevel(10, null))
        assertEquals(FillLevel.Critical, fillLevel(120, 100), "An over-full sample is drawn full")
        assertEquals(1.0, usedFraction(120, 100))
        assertEquals(0.25, usedFraction(25, 100))
        assertEquals(FillLevel.High, fillLevel(RecordedStorage(DEMO_REMOTE_KEY, "node-a", "local", false, 90, 100, "available", "dir", "iso")))
    }

    @Test fun freeIsTotalMinusUsedAndIsNamedSo() {
        assertEquals(60L, freeTotalMinusUsed(40, 100))
        assertEquals(0L, freeTotalMinusUsed(120, 100), "Floored at 0")
        assertNull(freeTotalMinusUsed(null, 100))
        assertNull(freeTotalMinusUsed(40, null))
        assertTrue(STORAGE_FREE_LABEL.contains("total − used"))
    }

    @Test fun thinIsATypeRuleOrAQcow2Disk() {
        assertTrue(isThinStorage("lvmthin", null))
        assertTrue(isThinStorage("zfspool", emptyList()))
        assertTrue(isThinStorage("rbd", null))
        assertTrue(isThinStorage("dir", listOf(volume("d:100/vm-100-disk-0.qcow2", format = "qcow2"))))
        assertFalse(isThinStorage("dir", listOf(volume("d:100/vm-100-disk-0.raw", format = "raw"))))
        assertFalse(isThinStorage("nfs", listOf(volume("n:iso/a.iso", "iso", "iso"))))
        assertFalse(isThinStorage(null, null))
        assertFalse(isThinStorage("dir", listOf(volume("d:import/a.qcow2", "import", "qcow2"))), "An import image is not a guest disk")
    }

    @Test fun provisionedBytesCountsGuestDisksOnly() {
        val rows = listOf(
            volume("s:vm-100-disk-0", size = 32 * gib, vmid = 100),
            volume("s:subvol-101-disk-0", "rootdir", "subvol", size = 8 * gib, vmid = 101),
            volume("s:vm-102-disk-0", size = null, vmid = 102),
            volume("s:iso/a.iso", "iso", "iso", size = 2 * gib),
            volume("s:backup/vzdump-qemu-100-2026_11_01-02_00_00.vma.zst", "backup", "vma.zst", size = 3 * gib, vmid = 100),
        )
        assertEquals(40 * gib, provisionedBytes(rows))
        assertEquals(0L, provisionedBytes(emptyList()))
    }

    // ---- breakdowns ----

    @Test fun theBreakdownSplitsListedSpaceNotListedAndFree() {
        val rows = listOf(volume("s:iso/a.iso", "iso", "iso", size = 2 * gib), volume("s:backup/b", "backup", "vma.zst", size = 3 * gib, vmid = 1))
        val split = storageBreakdown(rows, 6 * gib, 10 * gib)
        assertEquals(listOf("backup" to 3 * gib, "iso" to 2 * gib), split.byContent, "Largest first")
        assertEquals(5 * gib, split.listedBytes)
        assertEquals(1 * gib, split.notListed)
        assertEquals(4 * gib, split.freeTotalMinusUsed)
        assertEquals(10 * gib, split.scale)
        assertFalse(split.overProvisioned)
    }

    @Test fun aThinStorageCanListMoreThanItHolds() {
        val rows = listOf(volume("s:vm-1-disk-0", size = 8 * gib, vmid = 1), volume("s:vm-2-disk-0", size = 4 * gib, vmid = 2))
        val split = storageBreakdown(rows, 6 * gib, 10 * gib)
        assertEquals(0L, split.notListed)
        assertEquals(12 * gib, split.scale)
        assertTrue(split.overProvisioned)
        assertEquals(4 * gib, split.freeTotalMinusUsed)
    }

    @Test fun withoutAUsedFigureNothingIsComputedFromIt() {
        val split = storageBreakdown(listOf(volume("s:iso/a.iso", "iso", "iso", size = 2 * gib)), null, 10 * gib)
        assertNull(split.notListed)
        assertNull(split.freeTotalMinusUsed)
        assertEquals(10 * gib, split.scale)
        val unknown = storageBreakdown(listOf(volume("s:odd", null, "x", size = 5), volume("s:none", "iso", "iso")), null, null)
        assertEquals(listOf(VOLUME_TYPE_OTHER to 5L), unknown.byContent, "Unknown types go under other; no size counts nothing")
        assertEquals(5L, unknown.scale)
    }

    @Test fun guestUsageSumsDisksPerGuest() {
        val rows = listOf(
            volume("s:vm-120-disk-0", size = 32 * gib, vmid = 120),
            volume("s:vm-120-disk-1", size = 128 * gib, vmid = 120),
            volume("s:vm-130-disk-0", size = 16 * gib, vmid = 130),
            volume("s:base-disk", size = 99 * gib),
            volume("s:backup/vzdump-qemu-130-2026_11_01-02_00_00.vma.zst", "backup", "vma.zst", size = 500 * gib, vmid = 130),
        )
        assertEquals(listOf(GuestUsage(120, 160 * gib, 2), GuestUsage(130, 16 * gib, 1)), guestUsage(rows))
    }

    @Test fun largestVolumesAreBySizeThenNaturalName() {
        val rows = listOf(
            volume("s:vm-100-disk-10", size = 5), volume("s:vm-100-disk-2", size = 5), volume("s:big", size = 50),
            volume("s:unknown", size = null), volume("s:iso/x.iso", "iso", "iso", approximate = 20),
        )
        assertEquals(listOf("s:big", "s:iso/x.iso", "s:vm-100-disk-2", "s:vm-100-disk-10"), largestVolumes(rows).map { it.volid })
        assertEquals(listOf("s:big"), largestVolumes(rows, 1).map { it.volid })
        assertTrue(largestVolumes(rows, 0).isEmpty())
    }

    @Test fun backupTimesComeFromCtimeThenTheName() {
        assertEquals(Instant.ofEpochSecond(1_790_000_000), backupTime(volume("s:backup/vzdump-qemu-1-2026_11_01-02_00_00.vma.zst", "backup", ctime = 1_790_000_000)))
        assertEquals(Instant.parse("2026-11-07T02:00:01Z"), backupTime(volume("s:backup/vzdump-qemu-120-2026_11_07-02_00_01.vma.zst", "backup")))
        assertEquals(Instant.parse("2026-11-06T02:00:02Z"), backupTime(volume("s:backup/vzdump-lxc-121-2026_11_06-02_00_02.tar.zst", "backup")))
        assertEquals(Instant.parse("2026-11-07T02:00:01Z"), backupTime(volume("pbs:backup/vm/120/2026-11-07T02:00:01Z", "backup")))
        assertNull(backupTime(volume("s:backup/manual.tar", "backup")))
        assertNull(backupTime(volume("s:backup/vzdump-qemu-1-2026_13_45-02_00_00.vma", "backup")), "An impossible date is no date")
    }

    @Test fun backupSpansGroupPerGuest() {
        val rows = listOf(
            volume("s:backup/vzdump-qemu-120-2026_11_07-02_00_01.vma.zst", "backup", "vma.zst", size = 3, vmid = 120),
            volume("s:backup/vzdump-qemu-120-2026_10_01-02_00_01.vma.zst", "backup", "vma.zst", size = 2, vmid = 120, protected = true),
            volume("s:backup/copied-by-hand.vma.zst", "backup", "vma.zst", size = 1, vmid = 120),
            volume("s:backup/vzdump-lxc-121-2026_11_06-02_00_02.tar.zst", "backup", "tar.zst", size = null, vmid = 121),
            volume("s:backup/orphan.tar", "backup", "tar", size = 9),
            volume("s:vm-120-disk-0", size = 100, vmid = 120),
        )
        val spans = backupSpans(rows)
        assertEquals(listOf(120, 121), spans.map { it.vmid })
        val first = spans.first()
        assertEquals(3, first.count, "A backup without a time still counts")
        assertEquals(6L, first.bytes)
        assertEquals(1, first.protected)
        assertEquals(Instant.parse("2026-10-01T02:00:01Z"), first.oldest)
        assertEquals(Instant.parse("2026-11-07T02:00:01Z"), first.newest)
        assertEquals(2, first.times.size)
        assertEquals(0L, spans[1].bytes)
    }

    // ---- the list: per node and shared ----

    private val remote = "https://pve.example.test:8006"
    private fun member(name: String, status: String = "online") = RecordedMember(remote, name, status, null, null, null, null, null, null, null, null)
    private fun row(node: String, id: String, shared: Boolean?, used: Long?, total: Long?, type: String = "dir") =
        RecordedStorage(remote, node, id, shared, used, total, if (used == null) "unknown" else "available", type, "images")

    @Test fun sharedIdsAreDrawnOnceAndLocalStorageStaysPerNode() {
        val rows = listOf(
            row("node-b", "nas", true, 40, 100), row("node-a", "nas", true, 40, 100),
            row("node-a", "local", false, 10, 50), row("node-b", "local", false, 20, 50), row("node-a", "local-lvm", null, 1, 2, "lvmthin"),
        )
        val model = storageListModel(rows, listOf(member("node-a"), member("node-b")))
        assertEquals(5, model.perNode.size, "Every row the server reported stays")
        val nas = model.shared.single()
        assertEquals("nas", nas.id)
        assertEquals(listOf("node-a", "node-b"), nas.nodes)
        assertEquals("node-a", nas.shown.node)
        assertFalse(nas.disagree)
        assertEquals(0.4..0.4, nas.usedRange)
        assertEquals(listOf("node-a" to listOf("local", "local-lvm"), "node-b" to listOf("local")), model.nodes.map { it.node to it.rows.map { r -> r.id } },
            "An unknown shared flag is not drawn as shared")
        assertTrue(model.disabled.isEmpty())
    }

    @Test fun nodesThatReportDifferentFiguresDisagreeAndNothingIsAdded() {
        val rows = listOf(row("node-a", "nas", true, 410, 1000), row("node-b", "nas", true, 398, 1000), row("node-c", "nas", true, null, null))
        val nas = storageListModel(rows, listOf(member("node-a"), member("node-b"), member("node-c"))).shared.single()
        assertTrue(nas.disagree)
        assertEquals(0.398..0.41, nas.usedRange)
        assertEquals(3, nas.rows.size, "Each node's figures stay reachable")
        assertTrue(sharedStorageReport(rows).disagreements.contains("nas"), "The existing report agrees")
        val quiet = storageListModel(listOf(row("node-a", "nas", true, 5, 10), row("node-b", "nas", true, null, null)), listOf(member("node-a"), member("node-b"))).shared.single()
        assertFalse(quiet.disagree, "A node with no sample yet does not disagree")
    }

    @Test fun theSharedCardUsesTheFirstOnlineNodeWithASample() {
        val rows = listOf(row("node-a", "nas", true, 1, 10), row("node-b", "nas", true, 2, 10), row("node-c", "nas", true, 3, 10))
        val shown = storageListModel(rows, listOf(member("node-a", "offline"), member("node-b"), member("node-c"))).shared.single().shown
        assertEquals("node-b", shown.node)
        val noSample = storageListModel(listOf(row("node-a", "nas", true, null, null), row("node-b", "nas", true, 2, 10)), listOf(member("node-a"), member("node-b", "offline")))
        assertEquals("node-b", noSample.shared.single().shown.node, "A sampled row beats an unsampled one even on an offline node")
        val allOffline = storageListModel(listOf(row("node-a", "nas", true, null, null)), listOf(member("node-a", "offline")))
        assertEquals("node-a", allOffline.shared.single().shown.node)
    }

    @Test fun nodeGroupsCarryTheirMembershipAndDisabledStorageComesFromTheConfiguration() {
        val config = listOf(
            StorageConfigRow("old-nfs", "nfs", setOf("iso"), true, true, null, null, null),
            StorageConfigRow("local", "dir", setOf("images"), true, false, null, null, null),
            StorageConfigRow("spare", "dir", setOf("iso"), false, false, null, null, null),
        )
        val model = storageListModel(listOf(row("node-a", "local", false, 1, 2), row("node-b", "local", false, 1, 2)), listOf(member("node-a"), member("node-b", "offline")), config)
        assertEquals(listOf(true, false), model.nodes.map { it.online })
        assertEquals(listOf("old-nfs"), model.disabled.map { it.storage }, "Only disabled rows the resource list does not show")
    }

    @Test fun searchAndSortWorkAcrossNodes() {
        val rows = listOf(row("node-a", "local", false, 90, 100), row("node-b", "backup-nfs", true, 10, 100, "nfs"), row("node-a", "zeta", false, null, null))
        assertEquals(listOf("backup-nfs"), rows.filter { storageMatches(it, "NFS") }.map { it.id })
        assertEquals(listOf("local", "zeta"), rows.filter { storageMatches(it, "node-a") }.map { it.id })
        assertEquals(3, rows.count { storageMatches(it, "  ") })
        assertEquals(listOf("local"), rows.filter { storageMatches(it, "loc node-a") }.map { it.id })
        assertEquals(listOf("backup-nfs", "local", "zeta"), sortStorageRows(rows, StorageSort.Name).map { it.id })
        assertEquals(listOf("local", "backup-nfs", "zeta"), sortStorageRows(rows, StorageSort.UsedPercent).map { it.id })
        assertEquals(listOf("backup-nfs", "local", "zeta"), sortStorageRows(rows, StorageSort.Free).map { it.id })
    }

    // ---- the storage configuration ----

    private val configBody = """{"data":[
        {"storage":"local","type":"dir","content":"iso,vztmpl,backup","path":"/srv/fixture","digest":"0123456789abcdef0123456789abcdef01234567","shared":0,
         "prune-backups":"keep-last=3,keep-daily=7","max-protected-backups":-1},
        {"storage":"backup-nfs","type":"nfs","content":"backup","server":"198.51.100.7","export":"/export/fixture","path":"/mnt/pve/backup-nfs",
         "options":"vers=4.2","disable":1,"nodes":"node-a, node-b,bad_name","shared":1,"max-protected-backups":4},
        {"storage":"pbs-store","type":"pbs","content":"backup","server":"backup.example.test","datastore":"fixture","namespace":"ns1",
         "username":"fixture@pbs","fingerprint":"aa:bb:cc","encryption-key":"1","prune-backups":"keep-all=1"},
        {"storage":"iscsi-lun","type":"iscsi","portal":"192.0.2.10","target":"iqn.2026-11.test.example:fixture","content":"none","nodes":"bad_name"},
        {"storage":"ceph","type":"rbd","pool":"fixture-pool","monhost":"192.0.2.11 192.0.2.12","keyring":"secret","username":"admin","krbd":0,"content":"images,rootdir"},
        {"storage":"x","type":"dir"},
        {"storage":"dup","type":"dir"},{"storage":"dup","type":"lvm"},
        {"storage":"odd-rules","type":"dir","prune-backups":"keep-last=3,keep-forever=1","disable":"yes","shared":"maybe"},
        "not an object"
    ]}"""

    @Test fun theConfigurationKeepsOnlyTheAllowlist() {
        val rows = parseStorageConfig(configBody).associateBy { it.storage }
        assertEquals(listOf("local", "backup-nfs", "pbs-store", "iscsi-lun", "ceph", "odd-rules"), parseStorageConfig(configBody).map { it.storage },
            "A bad id and a duplicated id are skipped")
        val local = rows.getValue("local")
        assertEquals(StorageConfigRow("local", "dir", setOf("iso", "vztmpl", "backup"), false, false, null, KeepRules(last = 3, daily = 7), null), local)
        val nfs = rows.getValue("backup-nfs")
        assertTrue(nfs.disabled)
        assertEquals(true, nfs.shared)
        assertEquals(setOf("node-a", "node-b"), nfs.nodes)
        assertEquals(4, nfs.maxProtected)
        assertTrue(rows.getValue("pbs-store").pruneBackups!!.keepAll)
        assertEquals(emptySet(), rows.getValue("iscsi-lun").content, "content=none is no content")
        assertEquals(emptySet(), rows.getValue("iscsi-lun").nodes, "A restriction to nodes this client cannot name is not 'every node'")
        val odd = rows.getValue("odd-rules")
        assertNull(odd.pruneBackups, "An unknown rule makes the rules unknown")
        assertFalse(odd.disabled)
        assertNull(odd.shared)
        // Nothing that names a server, path or credential survives, in any form.
        val text = parseStorageConfig(configBody).toString()
        for (secret in listOf("198.51.100.7", "/export", "/srv/fixture", "/mnt/pve", "backup.example.test", "fixture@pbs", "aa:bb:cc", "ns1",
            "192.0.2.10", "iqn.", "fixture-pool", "192.0.2.11", "secret", "admin", "vers=4.2", "0123456789abcdef")) {
            assertFalse(text.contains(secret), secret)
        }
    }

    @Test fun aConfigurationBodyThatIsNotAListFailsClosed() {
        assertEquals(emptyList(), parseStorageConfig("""{"data":null}"""))
        assertEquals(emptyList(), parseStorageConfig("""{"data":[]}"""))
        for (bad in listOf("""{}""", """{"data":{}}""", "nope", """{"data":"x"}""")) {
            assertEquals(STORAGE_CONFIG_EMPTY_BODY, assertFailsWith<IllegalStateException>(bad) { parseStorageConfig(bad) }.message)
        }
    }

    @Test fun keepRulesReadAndWriteTheirText() {
        assertEquals(KeepRules(last = 3, daily = 7), parseKeepRules("keep-last=3,keep-daily=7"))
        assertEquals(KeepRules(), parseKeepRules("keep-all=1"))
        assertEquals(KeepRules(weekly = 2), parseKeepRules("keep-all=0,keep-weekly=2"))
        assertNull(parseKeepRules(""))
        assertNull(parseKeepRules(null))
        assertNull(parseKeepRules("keep-last=-1"))
        assertNull(parseKeepRules("keep-last=three"))
        assertEquals("keep-last=3,keep-weekly=2", keepRulesText(KeepRules(last = 3, weekly = 2)))
        assertEquals("keep-all=1", keepRulesText(KeepRules()))
        assertEquals(KeepRules(1, 2, 3, 4, 5, 6), parseKeepRules(keepRulesText(KeepRules(1, 2, 3, 4, 5, 6))))
    }

    @Test fun aConfigurationRowSaysWhichNodesMayUseIt() {
        val everywhere = StorageConfigRow("s1", "dir", emptySet(), false, false, null, null, null)
        assertTrue(storageEnabledOn(everywhere, "node-a"))
        assertFalse(storageEnabledOn(everywhere.copy(disabled = true), "node-a"))
        assertTrue(storageEnabledOn(everywhere.copy(nodes = setOf("node-a")), "node-a"))
        assertFalse(storageEnabledOn(everywhere.copy(nodes = setOf("node-a")), "node-b"))
        assertFalse(storageEnabledOn(everywhere.copy(nodes = emptySet()), "node-a"))
    }

    // ---- the storage history ----

    @Test fun historyKeepsUsedAndTotalOnlySortedByTime() {
        val points = parseStorageHistory("""{"data":[
            {"time":1790000060,"used":200.0,"total":1000,"avail":800,"cpu":0.5},
            {"time":1790000000,"used":100,"total":1000},
            {"time":1790000120,"used":-5,"total":"1000"},
            {"time":1790000180},
            {"used":1,"total":2},
            {"time":"1790000240","used":1},
            7
        ]}""")
        assertEquals(listOf(1_790_000_000.0, 1_790_000_060.0, 1_790_000_120.0, 1_790_000_180.0), points.map { it.time })
        assertEquals(setOf("used", "total"), points.flatMap { it.values.keys }.toSet())
        assertEquals(mapOf("used" to 100.0, "total" to 1000.0), points[0].values)
        assertEquals(mapOf("used" to null, "total" to null), points[2].values, "A negative value and a string are gaps")
        assertEquals(mapOf("used" to null, "total" to null), points[3].values, "An inactive period is a gap, not a drop to zero")
    }

    @Test fun aHistoryBodyThatIsNotAListFailsClosed() {
        assertEquals(emptyList(), parseStorageHistory("""{"data":null}"""))
        for (bad in listOf("""{}""", """{"data":{}}""", "nope")) {
            assertEquals(STORAGE_HISTORY_EMPTY_BODY, assertFailsWith<IllegalStateException>(bad) { parseStorageHistory(bad) }.message)
        }
    }

    @Test fun theHistoryPathIsOneStoragesRrdData() {
        assertEquals("/api2/json/nodes/node-a/storage/local-lvm/rrddata?timeframe=hour&cf=AVERAGE", storageHistoryPath("node-a", "local-lvm", "hour", "AVERAGE"))
        assertEquals("/api2/json/nodes/node-a/storage/nas/rrddata?timeframe=year&cf=MAX", storageHistoryPath("node-a", "nas", "year", "MAX"))
        assertFalse(isForbiddenNodeRead(storageHistoryPath("node-a", "local", "day", "MAX")))
        assertFalse(isForbiddenNodeRead(STORAGE_CONFIG_PATH))
        for (bad in listOf("node.example", "a/b", "")) assertFailsWith<IllegalArgumentException>(bad) { storageHistoryPath(bad, "local", "hour", "AVERAGE") }
        for (bad in listOf("x", "a/b", "../status")) assertFailsWith<IllegalArgumentException>(bad) { storageHistoryPath("node-a", bad, "hour", "AVERAGE") }
        assertFailsWith<IllegalArgumentException> { storageHistoryPath("node-a", "local", "decade", "AVERAGE") }
        assertFailsWith<IllegalArgumentException> { storageHistoryPath("node-a", "local", "hour", "MIN") }
    }

    // ---- demo ----

    @Test fun demoConfigurationAddsADisabledStorageAndRetention() {
        val model = storageListModel(demoOverview.storage, demoOverview.members, demoStorageConfig)
        assertEquals(listOf("old-nfs"), model.disabled.map { it.storage })
        assertEquals(listOf("shared-data"), model.shared.map { it.id })
        assertEquals(KeepRules(last = 3, weekly = 2), demoStorageConfig.single { it.storage == "shared-data" }.pruneBackups)
        assertTrue(demoStorageConfig.all { pveStorageIdOrNull(it.storage) != null && it.content.all { c -> c in STORAGE_CONTENT_TYPES } })
    }

    @Test fun demoHistoryIsDeterministicAndStaysWithinTheTotal() {
        val row = demoOverview.storage.first { it.id == "local-lvm" }
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val average = demoStorageHistory(row, "day", "AVERAGE", now)
        assertEquals(average, demoStorageHistory(row, "day", "AVERAGE", now))
        assertEquals(120, average.size)
        assertEquals(now.epochSecond.toDouble(), average.last().time)
        assertEquals(now.epochSecond - 86_400.0, average.first().time)
        val total = row.maxDiskBytes!!.toDouble()
        val max = demoStorageHistory(row, "day", "MAX", now)
        assertTrue(average.zip(max).all { (a, m) -> m.values["used"]!! >= a.values["used"]!! && m.values["used"]!! <= total })
        assertTrue(average.all { it.values["total"] == total })
        val used = average.map { it.values["used"]!! }
        assertTrue(used.first() < used.last(), "A slow climb")
        assertTrue(used.zipWithNext().any { (a, b) -> b < a * 0.95 }, "With a drop after a prune")
        val empty = demoStorageHistory(row.copy(diskBytes = null, maxDiskBytes = null), "hour", "AVERAGE", now)
        assertTrue(empty.all { it.values["used"] == null && it.values["total"] == null })
        assertFailsWith<IllegalArgumentException> { demoStorageHistory(row, "decade", "AVERAGE", now) }
    }
}
