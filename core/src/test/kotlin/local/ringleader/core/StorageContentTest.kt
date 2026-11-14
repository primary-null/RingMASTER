package local.ringleader.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handwritten bodies shaped like the storage content schema in the API viewer. Not captured server responses. */
class StorageContentTest {
    private val body = """{"data":[
        {"volid":"local:iso/debian.iso","content":"iso","format":"iso","size":790626304,"ctime":1790000000},
        {"volid":"local:backup/vzdump-qemu-100-2026_11_01-02_00_00.vma.zst","content":"backup","format":"vma.zst","size":2147483648,"ctime":1790100000,"vmid":100,"notes":"nightly\nsecond line","protected":1},
        {"volid":"pbs:backup/vm/100/2026-11-01T02:00:00Z","content":"backup","format":"pbs-vm","size":1,"vmid":100,"verification":{"state":"ok","upid":"UPID:node-a:0000:0000:0000:verify:x:root@pam:"},"encrypted":"1","protected":true},
        {"volid":"local-lvm:vm-100-disk-0","content":"images","format":"raw","size":34359738368,"used":4194304,"vmid":100},
        {"volid":"zfs:subvol-101-disk-0","format":"subvol","size":8589934592,"vmid":101},
        {"volid":"dir:100/vm-100-disk-0.qcow2","format":"qcow2","size":10,"used":2},
        {"volid":"cephfs:iso/x.iso","content":"iso","format":"iso","approximate-size":123},
        {"volid":"local:snippets/user-data.yaml","content":"snippets","format":"snippet","size":2048},
        {"volid":"local:weird/thing","content":"future-type","format":"x"}
    ]}"""

    // ---- parser ----

    @Test fun volumesKeepEveryFieldTheScreenShows() {
        val rows = parseStorageContent(body).associateBy { it.volid }
        val iso = rows.getValue("local:iso/debian.iso")
        assertEquals("iso", iso.content)
        assertEquals("iso", iso.format)
        assertEquals(790_626_304L, iso.sizeBytes)
        assertEquals(Instant.ofEpochSecond(1_790_000_000), iso.createdAt)
        assertNull(iso.vmid)
        assertFalse(iso.protected)
        val backup = rows.getValue("local:backup/vzdump-qemu-100-2026_11_01-02_00_00.vma.zst")
        assertEquals(100, backup.vmid)
        assertEquals("nightly", backup.notes, "Only the first line of the notes is shown")
        assertTrue(backup.protected, "1 reads as protected")
        val pbs = rows.getValue("pbs:backup/vm/100/2026-11-01T02:00:00Z")
        assertEquals("ok", pbs.verification)
        assertTrue(pbs.protected, "true reads as protected")
        val disk = rows.getValue("local-lvm:vm-100-disk-0")
        assertEquals(4_194_304L, disk.usedBytes)
        assertEquals(34_359_738_368L, disk.sizeBytes)
        assertNull(disk.createdAt)
    }

    @Test fun theVolumeTypeComesFromTheRowOrTheVolumeId() {
        val rows = parseStorageContent(body).associateBy { it.volid }
        assertEquals("rootdir", rows.getValue("zfs:subvol-101-disk-0").content)
        assertEquals("images", rows.getValue("dir:100/vm-100-disk-0.qcow2").content)
        assertEquals("future-type", rows.getValue("local:weird/thing").content, "A type this client does not know is kept")
        assertEquals("backup", contentFromVolid("store:backup/anything"))
        assertEquals("vztmpl", contentFromVolid("store:vztmpl/t.tar.zst"))
        assertEquals("snippets", contentFromVolid("store:snippets/x"))
        assertEquals("import", contentFromVolid("store:import/x.qcow2"))
        assertNull(contentFromVolid("store:whatever"))
        assertNull(contentFromVolid("no-colon"))
    }

    @Test fun anApproximateSizeStandsInForAMissingExactOne() {
        val approx = parseStorageContent(body).single { it.volid == "cephfs:iso/x.iso" }
        assertNull(approx.sizeBytes)
        assertEquals(123L, approx.approximateSizeBytes)
        assertEquals(123L, approx.shownSize)
        val exact = parseStorageContent("""{"data":[{"volid":"s:iso/a.iso","size":5,"approximate-size":9}]}""").single()
        assertEquals(5L, exact.shownSize, "An exact size wins")
    }

    @Test fun aLinkedCloneKeepsItsBaseVolume() {
        val rows = parseStorageContent("""{"data":[
            {"volid":"local-lvm:base-900-disk-0/vm-140-disk-0","content":"images","format":"raw","size":10,"vmid":140,"parent":"base-900-disk-0"},
            {"volid":"local-lvm:vm-141-disk-0","content":"images","size":10,"vmid":141,"parent":"bad\u0007name"},
            {"volid":"local-lvm:vm-142-disk-0","content":"images","size":10,"vmid":142,"parent":17},
            {"volid":"local-lvm:vm-143-disk-0","content":"images","size":10,"vmid":143}
        ]}""").associateBy { it.vmid }
        assertEquals("base-900-disk-0", rows.getValue(140).parent)
        assertNull(rows.getValue(141).parent, "A control character drops the parent, not the row")
        assertNull(rows.getValue(142).parent, "A parent that is not text is no parent")
        assertNull(rows.getValue(143).parent)
    }

    @Test fun theVisibilityWordingNamesEveryRule() {
        // The five rules of check_volume_access, as research section 5 states them.
        for (rule in listOf(
            "ISO images, templates and import files need Datastore.Audit or Datastore.AllocateSpace",
            "backups also need Datastore.AllocateSpace there plus VM.Backup on their guest",
            "disks need Datastore.Audit plus VM.Config.Disk on their guest",
            "snippets need Datastore.Allocate, which reveals everything",
            "left out without an error",
        )) assertTrue(STORAGE_VISIBILITY_WHY.contains(rule), rule)
        assertEquals(2, STORAGE_VISIBILITY_WHY.split(". ").size, "Two sentences")
        // The "?" beside the volume count keeps its plain reason first and the rules after it.
        assertTrue(STORAGE_VISIBILITY_HELP.startsWith("This list shows the files this account is allowed to see."))
        assertTrue(STORAGE_VISIBILITY_HELP.endsWith("\n\n" + STORAGE_VISIBILITY_WHY))
    }

    @Test fun rowsAreCheckedBeforeTheyAreShown() {
        val rows = parseStorageContent("""{"data":[
            {"volid":"a:iso/one.iso","size":-5,"used":"12","ctime":-1,"vmid":0,"protected":"0","format":"iso"},
            {"volid":"a:iso/one.iso","size":9},
            {"volid":""},{"volid":"a:b\u0007c"},{"format":"raw"},7,"x",null,
            {"volid":"a:iso/two.iso","protected":0},
            {"volid":"a:iso/three.iso","protected":false,"vmid":12345678901},
            {"volid":"a:iso/four.iso","notes":"  tab\there\u0000 "}
        ]}""")
        assertEquals(listOf("a:iso/one.iso", "a:iso/two.iso", "a:iso/three.iso", "a:iso/four.iso"), rows.map { it.volid })
        val one = rows[0]
        assertNull(one.sizeBytes, "A negative size is not a size")
        assertEquals(12L, one.usedBytes)
        assertNull(one.createdAt)
        assertNull(one.vmid)
        assertFalse(one.protected)
        assertNull(rows[2].vmid, "A VMID beyond the integer range is dropped")
        assertEquals("tab here", rows[3].notes)
        assertEquals(1, parseStorageContent("""{"data":[{"volid":"a:b"},{"volid":"a:b"}]}""").size, "A repeated volume id is shown once")
    }

    @Test fun contentNeedsADataArray() {
        assertTrue(parseStorageContent("""{"data":[]}""").isEmpty())
        assertTrue(parseStorageContent("""{"data":null}""").isEmpty())
        assertEquals(STORAGE_CONTENT_EMPTY_BODY, assertFailsWith<IllegalStateException> { parseStorageContent("""{}""") }.message)
        assertFailsWith<IllegalStateException> { parseStorageContent("""{"data":{}}""") }
        assertFailsWith<IllegalStateException> { parseStorageContent("nope") }
    }

    // ---- path ----

    @Test fun theContentPathIsOneStoragesListNeverTheStorageIndex() {
        assertEquals("/api2/json/nodes/node-a/storage/local-lvm/content", storageContentPath("node-a", "local-lvm"))
        assertEquals("/api2/json/nodes/node-a/storage/local/content?content=iso", storageContentPath("node-a", "local", "iso"))
        for (type in STORAGE_CONTENT_TYPES) assertTrue(storageContentPath("n1", "st", type).endsWith("?content=$type"))
        assertFalse(isForbiddenNodeRead(storageContentPath("node-a", "local")))
        assertFalse(isForbiddenNodeRead(storageContentPath("node-a", "local", "backup")))
        assertTrue(isForbiddenNodeRead("/api2/json/nodes/node-a/storage"), "The storage index stays forbidden")
        assertTrue(isForbiddenNodeRead("/api2/json/nodes"))
    }

    @Test fun theContentPathRefusesNamesThatAreNotNodesStoragesOrTypes() {
        assertEquals(listOf("node.example", "a/b", "", "node_a", "https://node"), listOf("node.example", "a/b", "", "node_a", "https://node").filter {
            runCatching { storageContentPath(it, "local") }.isFailure
        })
        for (bad in listOf("x", "-bad", "a b", "a/b", "../x", "", "a?b", "7up")) assertFailsWith<IllegalArgumentException>(bad) { storageContentPath("node-a", bad) }
        for (bad in listOf("foo", "ISO", "iso,backup", "iso&x=1", "")) assertFailsWith<IllegalArgumentException>(bad) { storageContentPath("node-a", "local", bad) }
    }

    // ---- sort, filter, counts ----

    private fun volume(volid: String, content: String? = "images", format: String? = "raw", size: Long? = null, vmid: Int? = null, ctime: Long? = null,
        approximate: Long? = null) = StorageVolume(volid, content, format, size, approximate, null, ctime?.let(Instant::ofEpochSecond), vmid, null, false, null)

    private val rows = listOf(
        volume("s:vm-100-disk-10", size = 5, vmid = 100, ctime = 30),
        volume("s:vm-100-disk-2", size = 50, vmid = 100, ctime = 10),
        volume("s:iso/b.iso", "iso", "iso", size = 20, ctime = 20),
        volume("s:iso/A.iso", "iso", "iso", approximate = 40),
        volume("s:backup/x", "backup", null, size = null, vmid = 7),
        volume("s:odd", null, "weird"),
    )

    @Test fun sortingUsesNaturalNamesAndKeepsMissingValuesLastInBothDirections() {
        assertEquals(listOf("s:backup/x", "s:iso/A.iso", "s:iso/b.iso", "s:odd", "s:vm-100-disk-2", "s:vm-100-disk-10"), sortVolumes(rows, "Name", false).map { it.volid })
        assertEquals(listOf("s:vm-100-disk-10", "s:vm-100-disk-2", "s:odd", "s:iso/b.iso", "s:iso/A.iso", "s:backup/x"), sortVolumes(rows, "Name", true).map { it.volid })
        // Size: the approximate size counts; backup/x and odd have none and go last either way.
        assertEquals(listOf("s:vm-100-disk-10", "s:iso/b.iso", "s:iso/A.iso", "s:vm-100-disk-2"), sortVolumes(rows, "Size", false).map { it.volid }.take(4))
        assertEquals(listOf("s:vm-100-disk-2", "s:iso/A.iso", "s:iso/b.iso", "s:vm-100-disk-10"), sortVolumes(rows, "Size", true).map { it.volid }.take(4))
        assertEquals(setOf("s:backup/x", "s:odd"), sortVolumes(rows, "Size", true).takeLast(2).map { it.volid }.toSet())
        assertEquals(setOf("s:backup/x", "s:odd"), sortVolumes(rows, "Size", false).takeLast(2).map { it.volid }.toSet())
    }

    @Test fun sortingByDateVmTypeAndFormat() {
        assertEquals(listOf("s:vm-100-disk-2", "s:iso/b.iso", "s:vm-100-disk-10"), sortVolumes(rows, "Date", false).map { it.volid }.take(3))
        assertEquals(listOf("s:vm-100-disk-10", "s:iso/b.iso", "s:vm-100-disk-2"), sortVolumes(rows, "Date", true).map { it.volid }.take(3))
        assertEquals("s:backup/x", sortVolumes(rows, "VM", false).first().volid)
        assertEquals("s:odd", sortVolumes(rows, "Type", false).last().volid)
        assertEquals("s:backup/x", sortVolumes(rows, "Format", false).last().volid, "No format sorts last")
        assertEquals(rows.size, sortVolumes(rows, "Unknown key", false).size)
        assertEquals(rows.map { it.volid }.toSet(), sortVolumes(rows, "Type", true).map { it.volid }.toSet())
    }

    @Test fun filteringByType() {
        assertEquals(rows, filterVolumes(rows, VOLUME_TYPE_ALL))
        assertEquals(listOf("s:iso/b.iso", "s:iso/A.iso"), filterVolumes(rows, "iso").map { it.volid })
        assertEquals(listOf("s:backup/x"), filterVolumes(rows, "backup").map { it.volid })
        assertEquals(listOf("s:odd"), filterVolumes(rows, VOLUME_TYPE_OTHER).map { it.volid })
        assertTrue(filterVolumes(rows, "snippets").isEmpty())
    }

    @Test fun aSharedIdCountsOnceAndOtherRowsCountEach() {
        fun row(node: String, id: String, shared: Boolean?) =
            RecordedStorage(DEMO_REMOTE_KEY, node, id, shared, 1, 2, "available", "dir", "images")
        val rows = listOf(
            row("demo-node-a", "shared-data", true),
            row("demo-node-b", "shared-data", true),
            row("demo-node-a", "local", false),
            row("demo-node-b", "local", false),
            row("demo-node-a", "mystery", null),
        )
        assertEquals(4, recordedStorageCount(rows))
        assertEquals(0, recordedStorageCount(emptyList()))
    }

    @Test fun chipCountsListOnlyTypesThatHaveRows() {
        assertEquals(listOf(VOLUME_TYPE_ALL to 6, "iso" to 2, "backup" to 1, "images" to 2, VOLUME_TYPE_OTHER to 1), volumeTypeCounts(rows))
        assertEquals(listOf(VOLUME_TYPE_ALL to 0), volumeTypeCounts(emptyList()))
        assertEquals(listOf(VOLUME_TYPE_ALL to 1, "snippets" to 1), volumeTypeCounts(listOf(volume("s:snippets/a", "snippets"))))
    }

    // ---- demo ----

    private fun recorded(id: String, content: String?, plugin: String? = "dir") =
        RecordedStorage(DEMO_REMOTE_KEY, "demo-node-a", id, false, 1, 2, "available", plugin, content)

    @Test fun demoContentFollowsTheTypesTheRecordedStorageLists() {
        val all = demoStorageContent(recorded("full", "iso,vztmpl,backup,images,rootdir,snippets,import,nonsense"))
        assertEquals(STORAGE_CONTENT_TYPES.toSet(), all.mapNotNull { it.content }.toSet())
        assertTrue(all.all { it.volid.startsWith("full:") })
        assertEquals(all.map { it.volid }.size, all.map { it.volid }.toSet().size)
        assertEquals(listOf("images"), demoStorageContent(recorded("lvm", "images", "lvmthin")).map { it.content }.distinct())
        assertTrue(demoStorageContent(recorded("none", null)).isEmpty())
        assertTrue(demoStorageContent(recorded("odd", "nonsense")).isEmpty())
        // The demo inventory's own storages produce samples.
        for (storage in demoOverview.storage) assertTrue(demoStorageContent(storage).isNotEmpty(), storage.id)
    }

    @Test fun onlyBackupServerSamplesCarryVerificationStates() {
        val pbs = demoStorageContent(recorded("pbs", "backup", "pbs"))
        assertEquals(setOf("ok", "failed"), pbs.mapNotNull { it.verification }.toSet())
        assertTrue(pbs.all { it.format?.startsWith("pbs-") == true })
        assertTrue(demoStorageContent(recorded("dir", "backup")).all { it.verification == null })
        assertTrue(demoStorageContent(recorded("dir", "backup")).any { it.protected })
    }
}
