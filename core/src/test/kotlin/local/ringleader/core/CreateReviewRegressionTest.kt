package local.ringleader.core

import kotlin.test.*
import java.nio.ByteBuffer
import java.util.Base64

class CreateReviewRegressionTest {
    private val ctx = CreateContext(storages = listOf(StorageChoice("a", "local", "dir", setOf("images", "rootdir", "iso", "vztmpl", "import"), false, 0, 100, "available")))
    private fun vm() = defaultVmDraft("a", ctx).copy(vmid = "105", install = CdDrive(media = CdMedia.None), nic = null)
    private fun ct() = defaultCtDraft("a", ctx).copy(vmid = "105", hostname = "test", template = "local:vztmpl/a", nic = CtNic(bridge = "br-a"))
    private fun volume(id: String, content: String) = StorageVolume(id, content, null, null, null, null, null, null, null, false, null)
    private fun strict(node: String) = ctx.copy(choiceNode = node, bridges = listOf(BridgeChoice("br-$node", "bridge")), cpuModels = listOf(CpuModelChoice("cpu-$node")), cpuFlags = emptyList(), volumes = mapOf(("local" to "iso") to listOf(volume("local:iso/$node", "iso")), ("local" to "vztmpl") to listOf(volume("local:vztmpl/$node", "vztmpl"))))
    @Test fun disjointNodeEvidenceRefusesStaleValuesAndAllowsNoMediaOrNetwork() {
        val a = vm().copy(cpuType = "cpu-a", install = CdDrive(media = CdMedia.Iso("local", "local:iso/a")), nic = VmNic(bridge = "br-a"))
        assertTrue(validateVm(a, strict("a")).isEmpty(), validateVm(a, strict("a")).toString())
        val bErrors = validateVm(a.copy(node = "b"), strict("b").copy(storages = ctx.storages.map { it.copy(node = "b") }))
        assertTrue(CreateFields.INSTALL in bErrors); assertTrue(CreateFields.BRIDGE in bErrors); assertTrue(CreateFields.CPU_TYPE in bErrors)
        assertIs<FreezeResult.Ready>(freezeVm(vm().copy(cpuType = "cpu-a"), strict("a")))
        assertIs<FreezeResult.Invalid>(freezeCt(ct().copy(template = "local:vztmpl/b"), strict("a")))
        assertIs<FreezeResult.Invalid>(freezeVm(a, strict("a").copy(volumes = emptyMap())))
    }
    @Test fun realCreateContextDoesNotFallbackWhenListsAreUnreadOrLoading() {
        val session = CreateSession(NewGuestKind.Vm, "a", 1)
        val context = createContext(WorkspaceState(overview = demoOverview), session)
        assertEquals("a", context.choiceNode); assertEquals(emptyList(), context.bridges); assertEquals(emptyMap(), context.volumes)
        assertIs<FreezeResult.Invalid>(freezeVm(vm().copy(nic = VmNic(bridge = "br-a")), context))
        val populated = session.copy(bridges = ReadSection(value = listOf(BridgeChoice("br-a", "bridge")), loading = true))
        assertEquals(emptyList(), createContext(WorkspaceState(), populated).bridges)
    }
    @Test fun existingVolumeMustBeReturnedAndCannotInjectProperties() {
        val context = ctx.copy(volumes = mapOf(("local" to "images") to listOf(volume("local:disk", "images")), ("local" to "rootdir") to listOf(volume("local:root", "rootdir"))))
        val disk = VmDisk(source = DiskSource.Existing("local:disk"), ioThread = false)
        assertIs<FreezeResult.Ready>(freezeVm(vm().copy(disks = listOf(disk)), context))
        assertEquals(CreateValue.Text("local:disk"), encodeVmDisk(disk).value)
        for (id in listOf("local:unlisted", "local:disk,cache=unsafe", "local:disk\n", "local:disk;foo", "local:disk=foo")) {
            assertIs<FreezeResult.Invalid>(freezeVm(vm().copy(disks = listOf(disk.copy(source = DiskSource.Existing(id)))), context))
            assertIs<FreezeResult.Invalid>(freezeCt(ct().copy(mounts = listOf(CtMount(source = MountSource.Existing(id)))), context))
        }
        assertFailsWith<IllegalArgumentException> { encodeVmDisk(disk.copy(format = "raw")) }
        assertFailsWith<IllegalArgumentException> { encodeCtMount(CtMount(source = MountSource.Existing("local:root,quota=1"))) }
        assertNull(applyStorage(disk, ctx.storages.single().copy(pluginType = "iscsi", formats = storageFormats("iscsi"))).format)
    }
    @Test fun dependentTransitionsAndUnsupportedCombinationsCannotFreeze() {
        val disk = VmDisk(source = DiskSource.Import("local:import/a", "local"), format = "qcow2", readOnly = true)
        val raw = ctx.storages.single().copy(id = "block", pluginType = "lvm", formats = storageFormats("lvm"))
        val changed = applyImportStorage(disk, raw)
        assertEquals("raw", changed.format); assertIs<DiskSource.Import>(changed.source)
        for (bus in listOf(DiskBus.Sata, DiskBus.Ide)) {
            assertFalse(applyBus(disk, bus, null).readOnly)
            assertIs<FreezeResult.Invalid>(freezeVm(vm().copy(disks = listOf(disk.copy(bus = bus))), ctx))
        }
        assertIs<FreezeResult.Invalid>(freezeVm(vm().copy(disks = listOf(disk.copy(source = DiskSource.Import("local:import/a", "block")))), ctx.copy(storages = ctx.storages + raw)))
        assertIs<FreezeResult.Invalid>(freezeCt(ct().copy(mounts = listOf(CtMount(source = MountSource.New("local"), quota = true))), ctx))
        assertIs<FreezeResult.Invalid>(freezeCt(ct().copy(unprivileged = false, mounts = listOf(CtMount(source = MountSource.New("local"), quota = true))), ctx.copy(storages = ctx.storages.map { it.copy(pluginType = "zfspool") })))
        assertIs<FreezeResult.Invalid>(freezeCt(ct().copy(mounts = listOf(CtMount(source = MountSource.New("local"), readOnly = true))), ctx))
        assertIs<FreezeResult.Invalid>(freezeVm(vm().copy(driverDisc = CdDrive(vm().install.slot)), ctx))
    }
    private fun key(type: String, vararg fields: ByteArray): String {
        val parts = listOf(type.toByteArray()) + fields
        val bytes = ByteBuffer.allocate(parts.sumOf { it.size + 4 }); parts.forEach { bytes.putInt(it.size); bytes.put(it) }
        return type + " " + Base64.getEncoder().encodeToString(bytes.array())
    }
    @Test fun keysValidateActualBlobsTypesAndQuotedOptionsWithoutSecretStrings() {
        val ed = key("ssh-ed25519", ByteArray(32) { 1 })
        assertTrue(validateCtSecrets("", "", ed).isEmpty())
        assertTrue(validateCtSecrets("", "", "command=\"echo hello\",no-pty " + ed).isEmpty())
        val dsa = java.security.KeyPairGenerator.getInstance("DSA").apply { initialize(1024) }.generateKeyPair().public as java.security.interfaces.DSAPublicKey
        assertTrue(validateCtSecrets("", "", key("ssh-dss", dsa.params.p.toByteArray(), dsa.params.q.toByteArray(), dsa.params.g.toByteArray(), dsa.y.toByteArray())).isEmpty())
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        assertTrue(validateCtSecrets("", "", key("ssh-rsa", rsa.publicExponent.toByteArray(), rsa.modulus.toByteArray())).isEmpty())
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", key("ssh-rsa", byteArrayOf(1), byteArrayOf(2))))
        assertTrue(validateCtSecrets("", "", key("sk-ssh-ed25519@openssh.com", ByteArray(32) { 1 }, "ssh:".toByteArray())).isEmpty())
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", "ssh-ed25519 AAAA"))
        for ((curve, ssh, width) in listOf(Triple("secp256r1", "nistp256", 32), Triple("secp384r1", "nistp384", 48), Triple("secp521r1", "nistp521", 66))) {
            val pub = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec(curve)) }.generateKeyPair().public as java.security.interfaces.ECPublicKey
            fun coordinate(n: java.math.BigInteger) = n.toByteArray().let { b -> ByteArray(width).also { out -> val length = minOf(width, b.size); b.copyInto(out, width - length, b.size - length) } }
            val point = byteArrayOf(4) + coordinate(pub.w.affineX) + coordinate(pub.w.affineY)
            assertTrue(validateCtSecrets("", "", key("ecdsa-sha2-$ssh", ssh.toByteArray(), point)).isEmpty())
            assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", key("ecdsa-sha2-$ssh", ssh.toByteArray(), ByteArray(point.size) { if (it == 0) 4 else 0 })))
        }
        val atLineCap = ed + " " + "x".repeat(CREATE_SSH_KEY_LINE_MAX_CHARS - ed.length - 1)
        assertTrue(validateCtSecrets("", "", atLineCap).isEmpty())
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", atLineCap + "x"))
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("", "", ed.replace("ssh-ed25519 ", "ssh-rsa ")))
        val seq = object : CharSequence { override val length = ed.length; override fun get(index: Int) = ed[index]; override fun subSequence(startIndex: Int,endIndex: Int):CharSequence = error("copy"); override fun toString():String = error("string") }
        assertTrue(validateCtSecrets("", "", seq).isEmpty())
    }
    @Test fun secretAndBodyCapsAreCheckedBeforeGrowth() {
        assertTrue(CreateFields.SSH_KEYS in validateCtSecrets("hello", "hello", " ".repeat(CREATE_SSH_KEYS_MAX_CHARS + 1)))
        val chars = SecretChars.copyOf(" ".repeat(CREATE_SSH_KEYS_MAX_CHARS)); assertEquals(CREATE_SSH_KEYS_MAX_CHARS, chars.length); chars.close()
        assertFailsWith<IllegalArgumentException> { SecretChars.copyOf("x".repeat(CREATE_SSH_KEYS_MAX_CHARS + 1)) }
        val request = CreateRequest(NewGuestKind.Vm, "a", 105, "", listOf(CreateParam("vmid", CreateValue.Int(105))) + List(200) { CreateParam("field$it", CreateValue.Text("%".repeat(65536))) })
        assertFailsWith<IllegalArgumentException> { createBody(request, CreateSecrets.None) }
    }
}
