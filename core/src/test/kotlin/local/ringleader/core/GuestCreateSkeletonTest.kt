package local.ringleader.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the phase A0 skeleton: defaults, equality, invariants, the D1 format table, secrets and the demo session. */
class GuestCreateSkeletonTest {
    @Test fun vmDraftDefaultsAreTheProxmoxLinuxDefaults() {
        val draft = VmDraft(node = "node-a")
        assertEquals("", draft.vmid)
        assertEquals("l26", draft.osType)
        assertEquals(CdDrive("ide2", CdMedia.Iso()), draft.install)
        assertNull(draft.driverDisc)
        assertNull(draft.machine)
        assertNull(draft.bios)
        assertTrue(draft.addEfi)
        assertEquals(EfiDisk(storage = "", format = null, preEnrolledKeys = true), draft.efi)
        assertEquals("virtio-scsi-single", draft.scsiHw)
        assertNull(draft.tpm)
        assertEquals(listOf(VmDisk(DiskBus.Scsi, 0, DiskSource.New("", "32"))), draft.disks)
        assertEquals("scsi0", draft.disks.single().key)
        assertTrue(draft.disks.single().ioThread)
        assertTrue(draft.disks.single().backup)
        assertEquals("1", draft.sockets)
        assertEquals("1", draft.cores)
        assertEquals("x86-64-v2-AES", draft.cpuType)
        assertFalse(draft.numa)
        assertEquals("2048", draft.memoryMib)
        assertTrue(draft.ballooning)
        assertTrue(draft.allowKsm)
        assertEquals(VmNic(model = "virtio", bridge = "", firewall = true), draft.nic)
        assertTrue(draft.touched.isEmpty())
    }

    @Test fun ctDraftDefaultsAreTheProxmoxDefaults() {
        val draft = CtDraft(node = "node-a")
        assertTrue(draft.unprivileged)
        assertTrue(draft.nesting)
        assertEquals(listOf(CtMount(key = "rootfs", source = MountSource.New("", "8"))), draft.mounts)
        assertEquals("1", draft.cores)
        assertEquals("512", draft.memoryMib)
        assertEquals("512", draft.swapMib)
        assertEquals("eth0", draft.nic.name)
        assertTrue(draft.nic.firewall)
        assertEquals(IpMode.Static("", ""), draft.nic.ipv4)
        assertEquals(Ip6Mode.Static("", ""), draft.nic.ipv6)
        assertEquals(IdMap.None, draft.mounts.single().idMap)
    }

    @Test fun draftsHaveValueEquality() {
        assertEquals(VmDraft("node-a", name = "web-1"), VmDraft("node-a", name = "web-1"))
        assertNotEquals(VmDraft("node-a"), VmDraft("node-b"))
        assertEquals(CtDraft("node-a").copy(hostname = "db"), CtDraft("node-a", hostname = "db"))
    }

    @Test fun diskBusesCarryTheirPrefixesAndLimits() {
        assertEquals(listOf("ide" to 3, "sata" to 5, "scsi" to 30, "virtio" to 15), DiskBus.entries.map { it.prefix to it.maxIndex })
        assertEquals("virtio1", VmDisk(bus = DiskBus.Virtio, index = 1).key)
    }

    @Test fun fieldIdsNameTheirStep() {
        assertEquals("general", CreateFields.step(CreateFields.VMID))
        assertEquals("disks", CreateFields.step(CreateFields.disk("scsi0", "size")))
        assertEquals("disks.scsi0", CreateFields.disk("scsi0"))
        assertEquals("network", CreateFields.step(CreateFields.IP4))
        assertEquals(7, CreateFields.VM_STEPS.size)
        assertEquals(7, CreateFields.CT_STEPS.size)
    }

    @Test fun kindsMapToTheExistingKindStrings() {
        assertEquals("VM", NewGuestKind.Vm.guestKind)
        assertEquals("Container", NewGuestKind.Container.guestKind)
        assertEquals("qemu", NewGuestKind.Vm.apiSegment)
        assertEquals("lxc", NewGuestKind.Container.apiSegment)
    }

    @Test fun aRequestCarriesItsIdAndNeverASecretOrStart() {
        val params = listOf(CreateParam("vmid", CreateValue.Int(105)), CreateParam("memory", CreateValue.Int(2048)))
        val request = CreateRequest(NewGuestKind.Vm, "node-a", 105, "web-1", params)
        assertFalse(request.start)
        assertEquals(request, request.copy())
        assertFailsWith<IllegalArgumentException> { CreateParam("password", CreateValue.Text("x")) }
        assertFailsWith<IllegalArgumentException> { CreateParam("ssh-public-keys", CreateValue.Text("x")) }
        assertFailsWith<IllegalArgumentException> { CreateParam("name", CreateValue.Text("a\nb")) }
        assertFailsWith<IllegalArgumentException> { CreateRequest(NewGuestKind.Vm, "node-a", 105, "", params + CreateParam("start", CreateValue.Bool(true))) }
        assertFailsWith<IllegalArgumentException> { CreateRequest(NewGuestKind.Vm, "node-a", 106, "", params) }
        assertFailsWith<IllegalArgumentException> { CreateRequest(NewGuestKind.Vm, "node-a", 99, "", listOf(CreateParam("vmid", CreateValue.Int(99)))) }
        assertFailsWith<IllegalArgumentException> { CreateRequest(NewGuestKind.Vm, "node_a", 105, "", params) }
        assertFailsWith<IllegalArgumentException> { CreateRequest(NewGuestKind.Vm, "node-a", 105, "", params + params.last()) }
    }

    @Test fun diskFormatsComeFromThePluginTypeTable() {
        assertEquals(listOf("raw", "qcow2", "vmdk"), storageFormats("dir")?.supported)
        assertEquals(listOf("raw", "qcow2", "vmdk"), storageFormats("nfs")?.supported)
        assertEquals(listOf("raw", "qcow2", "vmdk"), storageFormats("cifs")?.supported)
        for (type in listOf("lvm", "lvmthin", "zfspool", "zfs", "rbd", "btrfs")) {
            assertEquals(listOf("raw"), storageFormats(type)?.supported, type)
            assertFalse(storageFormats(type)!!.selectExisting, type)
        }
        assertTrue(storageFormats("iscsi")!!.selectExisting)
        assertTrue(storageFormats("iscsidirect")!!.selectExisting)
        assertEquals("raw", storageFormats("dir")?.default)
        assertEquals(storageFormats("dir"), storageFormats(" DIR "))
        assertNull(storageFormats(null))
        assertNull(storageFormats("pbs"))
        assertNull(storageFormats("something-new"))
        assertTrue(STORAGE_FORMATS_BY_PLUGIN.values.all { it.default in it.supported })
    }

    @Test fun aStorageChoiceShowsTotalMinusUsedAndItsFormats() {
        val choice = StorageChoice("node-a", "local-lvm", "lvmthin", setOf("images", "rootdir"), false, 10L, 40L, "available")
        assertEquals(30L, choice.totalMinusUsedBytes)
        assertEquals(storageFormats("lvmthin"), choice.formats)
        assertFalse(choice.selectExisting)
        assertNull(choice.copy(usedBytes = null).totalMinusUsedBytes)
        assertEquals(0L, choice.copy(usedBytes = 50L).totalMinusUsedBytes)
        assertTrue(StorageChoice("node-a", "san", "iscsi", setOf("images"), true, null, null, "available").selectExisting)
    }

    @Test fun choiceListsHaveDistinctValues() {
        listOf(VGA_CHOICES, MACHINE_CHOICES, BIOS_CHOICES, SCSI_CONTROLLER_CHOICES, CACHE_CHOICES, AIO_CHOICES, NIC_MODEL_CHOICES, ARCH_CHOICES, TPM_VERSION_CHOICES)
            .forEach { list -> assertEquals(list.size, list.map { it.value }.distinct().size) }
        assertEquals("l26", OS_CHOICES.first().value)
        assertEquals(11, OS_CHOICES.map { it.value }.distinct().size)
        assertEquals(listOf("discard", "lazytime", "noatime", "nosuid"), CT_ROOTFS_MOUNT_OPTIONS)
    }

    @Test fun secretCharsAreRedactedComparedAndWiped() {
        val secret = SecretChars.copyOf(StringBuilder("test-only-password"))
        assertEquals("SecretChars(redacted)", secret.toString())
        assertEquals(18, secret.length)
        assertTrue(secret.matches("test-only-password"))
        assertFalse(secret.matches("test-only-passwore"))
        assertFalse(secret.matches("test-only"))
        val keys = SecretChars.copyOf("ssh-ed25519 AAAA one\n\n  \r\nssh-ed25519 BBBB two\n")
        assertEquals(2, keys.nonBlankLines())
        val secrets = CreateSecrets(secret, keys)
        assertEquals("CreateSecrets(redacted)", secrets.toString())
        assertFalse(secrets.toString().contains("test-only"))
        assertEquals(SecretState(password = true, keyCount = 2), secrets.state)
        var seen: CharArray? = null
        secret.use { seen = it }
        secrets.close()
        assertTrue(secret.closed && keys.closed)
        assertTrue(seen!!.all { it == '\u0000' })
        assertEquals(0, secret.length)
        assertFalse(secret.matches(""))
        assertFailsWith<IllegalStateException> { secret.use { it.size } }
        assertEquals(SecretState(password = false, keyCount = 0), secrets.state)
        assertEquals(SecretState(), CreateSecrets.None.state)
    }

    @Test fun aConfirmationHoldsNoSecretAndTakesStartFromItsRequest() {
        val request = CreateRequest(NewGuestKind.Container, "node-a", 105, "db", listOf(CreateParam("vmid", CreateValue.Int(105))), start = true)
        val confirmation = CreateConfirmation(3, "https://192.0.2.10:8006", "https://192.0.2.10:8006", "Lab", request, SecretState(true, 1))
        assertTrue(confirmation.start)
        assertEquals("CreateConfirmation(Container 105 on node-a)", confirmation.toString())
        assertNotEquals(confirmation.confirmationId, CreateConfirmation(3, "r", "o", "Lab", request, SecretState()).confirmationId)
        assertFailsWith<IllegalArgumentException> { CreateConfirmation(3, "r", "o", " ", request, SecretState()) }
    }

    @Test fun theWarningsOutcomeIsItsOwnResult() {
        val warned = CreateResult.FinishedWithWarnings(NewGuestKind.Container, "node-a", 105, "local-1", 2, listOf("a", "b"))
        assertEquals(GuestAppearance.Waiting, warned.appearance)
        assertFailsWith<IllegalArgumentException> { CreateResult.FinishedWithWarnings(NewGuestKind.Vm, "node-a", 105, "local-1", 0, emptyList()) }
        assertEquals(CreateTaskOutcome.FinishedWithWarnings(2), CreateTaskOutcome.FinishedWithWarnings(2))
        assertFailsWith<IllegalArgumentException> { CreateAvailability(false) }
        assertFailsWith<IllegalArgumentException> { CreateAvailability(true, "why") }
    }

    @Test fun theDemoSessionIsSampleDataOnly() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val vm = demoCreateSession(NewGuestKind.Vm, "demo-node-a", 7, at)
        assertTrue(vm.sample)
        assertEquals(DEMO_CREATE_NEXT_ID, vm.nextId.value)
        assertTrue(listOf(vm.nextId.sample, vm.pools.sample, vm.bridges.sample, vm.cpuModels.sample, vm.cpuFlags.sample, vm.tagRules.sample).all { it })
        assertTrue(vm.volumes.values.all { it.sample })
        assertEquals(3, vm.volumes.getValue("local" to "iso").value?.size)
        assertEquals(8, vm.cpuFlags.value?.size)
        assertTrue(vm.bridges.value!!.first { it.name == "vmbr0" }.vlanAware)
        assertNull(vm.result)
        val ct = demoCreateSession(NewGuestKind.Container, "demo-node-a", 7, at)
        assertEquals(emptyList(), ct.cpuModels.value)
        assertEquals(3, ct.volumes.getValue("local" to "vztmpl").value?.size)
    }

    @Test fun theWorkspaceStartsWithNoCreateSession() {
        assertNull(WorkspaceState().creation)
    }
}
