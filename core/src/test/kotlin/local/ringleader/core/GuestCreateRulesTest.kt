package local.ringleader.core

import kotlin.test.*

class GuestCreateRulesTest {
    private val ctx = CreateContext(storages = listOf(StorageChoice("node-a", "local", "dir", setOf("images", "rootdir", "iso", "vztmpl"), false, 0, 100, "available")))
    private fun vm() = defaultVmDraft("node-a", ctx).copy(vmid = "105", install = CdDrive(media = CdMedia.None))
    private fun ct() = defaultCtDraft("node-a", ctx).copy(vmid = "105", hostname = "web-1", template = "local:vztmpl/a.tar.zst", nic = CtNic(bridge = "vmbr0"))
    @Test fun startupCdAndBootOrder() {
        assertNull(encodeStartup("", "", "")); assertEquals("order=2,up=30", encodeStartup("2", "30", "")); assertEquals("down=60", encodeStartup("", "", "60"))
        assertEquals("none,media=cdrom", encodeCdDrive(CdDrive(media = CdMedia.None))); assertEquals("cdrom,media=cdrom", encodeCdDrive(CdDrive(media = CdMedia.Physical)))
        assertEquals("order=scsi0;ide2;ide0;net0", encodeBootOrder(listOf(VmDisk()), CdDrive(), CdDrive("ide0"), VmNic()))
        assertEquals("order=ide2;ide0", encodeBootOrder(emptyList(), CdDrive(), CdDrive("ide0"), null))
        assertNull(encodeBootOrder(emptyList(), CdDrive(), null, null))
    }
    @Test fun diskEfiTpmAndLimits() {
        assertEquals(CreateValue.Text("local:32,iothread=on"), encodeVmDisk(VmDisk(source = DiskSource.New("local"))).value)
        val disk = VmDisk(DiskBus.Virtio, 1, DiskSource.New("fast", "64"), "qcow2", "writeback", true, true, limits = DiskLimits(mbpsRd = "200"))
        assertEquals(CreateValue.Text("fast:64,format=qcow2,discard=on,iothread=on,cache=writeback,mbps_rd=200"), encodeVmDisk(disk).value)
        assertEquals(CreateValue.Text("local:0,import-from=imports:import/a.qcow2,format=raw,iothread=on"), encodeVmDisk(VmDisk(source = DiskSource.Import("imports:import/a.qcow2", "local"), format = "raw")).value)
        assertFailsWith<IllegalArgumentException> { encodeVmDisk(VmDisk(source = DiskSource.Import("a", "b")), 8) }
        assertEquals("local:1,efitype=4m,pre-enrolled-keys=1", encodeEfiDisk(EfiDisk("local")))
        assertEquals("local:1,version=v2.0,format=qcow2", encodeTpm(TpmState("local", "qcow2")))
    }
    @Test fun networkEncodings() {
        assertEquals("virtio,bridge=vmbr0,firewall=1", encodeVmNic(VmNic(bridge = "vmbr0")))
        assertEquals("virtio=02:00:00:00:00:01,bridge=vmbr0,tag=20,firewall=1", encodeVmNic(VmNic(mac = "02:00:00:00:00:01", bridge = "vmbr0", vlan = "20")))
        assertEquals("name=eth0,bridge=vmbr0,firewall=1,ip=dhcp,ip6=auto", encodeCtNic(CtNic(bridge = "vmbr0", ipv4 = IpMode.Dhcp, ipv6 = Ip6Mode.Slaac)))
        assertEquals("host,flags=+aes;-pcid", encodeCpu("host", listOf(CpuFlag("aes", '+'), CpuFlag("pcid", '-'))))
        assertEquals("192.0.2.53 2001:db8::53 192.0.2.54", encodeNameservers("192.0.2.53, 2001:db8::53;192.0.2.54"))
    }
    @Test fun mountEncodings() {
        assertEquals(CreateValue.Text("local:8"), encodeCtMount(CtMount(source = MountSource.New("local"))).value)
        assertEquals(CreateValue.Text("local:16,mp=/srv/data,backup=1"), encodeCtMount(CtMount("mp0", MountSource.New("local", "16"), "/srv/data")).value)
        assertEquals(CreateValue.Text("local:8,quota=1,mountoptions=discard;noatime,acl=0,replicate=0,idmap=u:0:100000:65536"), encodeCtMount(CtMount(source = MountSource.New("local"), quota = true, mountOptions = setOf("noatime", "discard"), acl = false, skipReplication = true, idMap = IdMap.Ranges(listOf(IdRange('u', "0", "100000", "65536"))))).value)
        assertFailsWith<IllegalArgumentException> { encodeCtMount(CtMount(keepAttrs = true), 8) }
    }
    @Test fun completeDefaultsFreezeAndVersionGates() {
        assertTrue(validateVm(vm(), ctx).isEmpty(), validateVm(vm(), ctx).toString())
        assertTrue(validateCt(ct(), ctx).isEmpty(), validateCt(ct(), ctx).toString())
        assertTrue(freezeVm(vm(), ctx) is FreezeResult.Ready); assertTrue(freezeCt(ct(), ctx) is FreezeResult.Ready)
        assertEquals(listOf("vmid", "ostype", "ide2", "scsihw", "scsi0", "sockets", "cores", "cpu", "numa", "memory", "net0"), vmCreateParams(vm(), ctx).map { it.key })
        assertFalse(vmCreateParams(vm().copy(haManaged = true, arch = "aarch64", allowKsm = false), ctx.copy(release = 8)).any { it.key in listOf("ha-managed", "arch", "kvm", "allow-ksm") })
    }
    @Test fun numericIdentityAndSlotBoundaries() {
        for (id in listOf("99", "1000000000", "-1", "1e3", "")) assertTrue(CreateFields.VMID in validateVm(vm().copy(vmid = id), ctx))
        assertTrue(CreateFields.VMID in validateVm(vm(), ctx.copy(usedIds = setOf(105))))
        for (v in listOf("0", "-1", "a")) assertTrue(CreateFields.CORES in validateVm(vm().copy(cores = v), ctx))
        assertTrue(CreateFields.MEMORY_MIB in validateVm(vm().copy(memoryMib = "15"), ctx))
        assertTrue(CreateFields.BALLOON in validateVm(vm().copy(minMemoryMib = "2049"), ctx))
        assertTrue(validateVm(vm().copy(disks = listOf(VmDisk(DiskBus.Ide, 2))), ctx).keys.any { it.startsWith("disks") })
        assertTrue(CreateFields.NAME in validateCt(ct().copy(hostname = "bad name"), ctx))
        assertTrue(CreateFields.TAGS in validateVm(vm().copy(tags = listOf("dup", "dup")), ctx))
    }
    @Test fun networkAndMountValidation() {
        assertTrue(CreateFields.MAC in validateVm(vm().copy(nic = VmNic(mac = "01:00:00:00:00:00")), ctx))
        assertTrue(CreateFields.VLAN in validateCt(ct().copy(nic = CtNic(bridge = "vmbr0", vlan = "4095")), ctx))
        assertTrue(CreateFields.GW4 in validateCt(ct().copy(nic = CtNic(bridge = "vmbr0", ipv4 = IpMode.Static(gateway = "192.0.2.1"))), ctx))
        assertTrue(CreateFields.IP6 in validateCt(ct().copy(nic = CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8::1/129"))), ctx))
        assertTrue(validateCt(ct().copy(mounts = ct().mounts + CtMount("mp0", path = "/a/../b")), ctx).keys.any { it.endsWith(".path") })
        assertTrue(CreateFields.NAMESERVERS in validateCt(ct().copy(nameservers = "example.test"), ctx))
    }
    @Test fun dependentDefaultsRespectTouchedFields() {
        val win = applyOsDefaults(vm(), "win11", null).draft
        assertEquals("q35", win.machine); assertEquals("ovmf", win.bios); assertNotNull(win.tpm); assertEquals("4096", win.memoryMib); assertEquals(DiskBus.Ide, win.disks.single().bus)
        assertEquals("rtl8139", applyOsDefaults(vm(), "w2k", null).draft.nic?.model)
        assertEquals("8192", applyOsDefaults(vm().copy(memoryMib = "8192", touched = setOf(CreateFields.MEMORY_MIB)), "win11", null).draft.memoryMib)
        val arm = applyArch(vm(), "aarch64").draft
        assertEquals("virt", arm.machine); assertEquals("scsi2", arm.install.slot); assertEquals("host", arm.cpuType)
        assertEquals("qcow2", applyStorage(VmDisk(), ctx.storages.first()).format)
        assertFalse(applyBus(VmDisk(), DiskBus.Sata, null).ioThread)
        assertEquals("scsi1", nextFreeDisk(vm())?.key); assertEquals("mp0", nextFreeMount(ct())?.key)
    }
    @Test fun secretValidationNeverUsesToStringOrSubsequence() {
        class Input(private val chars: CharArray) : CharSequence {
            override val length get() = chars.size
            override fun get(index: Int) = chars[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No copying")
            override fun toString(): String = error("No String")
        }
        val password = Input(charArrayOf('h','e','l','l','o'))
        assertTrue(validateCtSecrets(password, password, Input(charArrayOf())).isEmpty())
        assertTrue(CreateFields.PASSWORD_CONFIRM in validateCtSecrets(password, Input(charArrayOf()), Input(charArrayOf())))
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", Input(charArrayOf('b','a','d'))))
    }
    @Test fun secretsBodyAndRedactionDoNotLeakIntoState() {
        val secret = SecretChars.copyOf("abc +&é")
        val secrets = CreateSecrets(secret, SecretChars.copyOf("ssh-ed25519 AAAA comment\nssh-rsa BBBB"))
        val req = (freezeCt(ct(), ctx) as FreezeResult.Ready).request
        val body = createBody(req, secrets)
        assertTrue(body.toString(Charsets.UTF_8).contains("password=abc+%2B%26%C3%A9"))
        assertFalse(createParamLines(req.params, secrets.state).toString().contains("abc"))
        assertFalse(redactSecrets("bad abc +&é and ssh-rsa BBBB", secrets).contains("abc"))
        body.fill(0); secrets.close(); assertFailsWith<IllegalStateException> { createBody(req, secrets) }
    }
    @Test fun outcomesAndParserShapes() {
        assertEquals(CreateTaskOutcome.FinishedWithWarnings(2), createTaskOutcome(PowerTaskStatus(PowerPhase.Unsuccessful, "WARNINGS: 2")))
        assertTrue(createTaskOutcome(PowerTaskStatus(PowerPhase.Unsuccessful, "WARNINGS: 0")) is CreateTaskOutcome.Failed)
        assertEquals(CreateTaskOutcome.Succeeded, createTaskOutcome(PowerTaskStatus(PowerPhase.Succeeded, "OK")))
        assertEquals(140, parseNextId("""{"data":"140"}""")); assertFailsWith<IllegalArgumentException> { parseNextId("{}") }
        assertEquals("vmbr0", parseBridges("""{"data":[{"iface":"vmbr0","type":"bridge","active":1}]}""").single().name)
        assertTrue(storageChoices(demoOverview.storage, "demo-node-a", "iso").any { it.id == "local" })
        assertTrue(isForbiddenNodeRead("/api2/json/nodes/node-a/storage?content=images&format=1"))
    }
    @Test fun tagRulesUseUserTagAccessNotTagStyle() {
        assertEquals(ClusterTagRules("list", listOf("web", "prod"), listOf("admin")), parseClusterTagRules("""{"data":{"user-tag-access":{"user-allow":"list","user-allow-list":["web","prod"]},"registered-tags":["admin"],"tag-style":{"shape":"full"}}}"""))
    }

}
