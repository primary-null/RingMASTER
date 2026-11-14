@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import local.ringleader.core.*
import java.time.Instant
import kotlin.test.*

class CreateVmInteractionTest {
    @Test fun windowsArmStorageMediaFlagsAndNodeChangesReachDraft() = runSceneTest {
        val storage=StorageChoice("demo-node-a","sample-disks","dir",setOf("images","iso","import"),false,null,null,"available")
        var session by mutableStateOf(demoCreateSession(NewGuestKind.Vm,"demo-node-a",1,Instant.EPOCH).copy(cpuFlags=ReadSection(value=listOf(CpuFlagChoice("aes","AES support")))))
        var seen: VmDraft?=null
        val reads=mutableListOf<CreateRead>()
        var osCalls=0; var archCalls=0; var storageCalls=0; var busCalls=0; var freezes=0
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) {
                CreateVmWizard(session,CreateContext(storages=listOf(storage)),listOf("demo-node-a","node-b"),{session=session.copy(node=it,generation=session.generation+1)},{reads+=it},{},{},{},
                    initialAdvanced=true,
                    defaults={node,_->VmDraft(node,vmid="140",name="test-vm",cpuType="host",install=CdDrive(media=CdMedia.None),disks=listOf(VmDisk(source=DiskSource.New("sample-disks"))))},
                    validate={_,_->emptyMap()},parameters={d,_->seen=d;emptyList()},freeze={_,_->freezes++;FreezeResult.Invalid(emptyMap())},
                    osDefaults={d,os,_->osCalls++;Applied(d.copy(osType=os,bios="ovmf",addEfi=true,tpm=TpmState(),memoryMib="4096"))},
                    archDefaults={d,arch->archCalls++;Applied(d.copy(arch=arch,osType="l26",install=CdDrive("scsi2",CdMedia.None),driverDisc=null))},
                    storageDefaults={disk,choice->storageCalls++;disk.copy(source=(disk.source as DiskSource.New).copy(storage=choice.id),format="raw")},
                    busDefaults={disk,bus,_->busCalls++;disk.copy(bus=bus,ioThread=false)},preferences=CreateWizardPreferences.memory())
            }
        }
        val h=CreateScene(scene)
        try {
            h.frames(); h.click("2  OS"); h.click("ISO IMAGE")
            assertTrue(seen!!.install.media is CdMedia.Iso)
            h.click("Choose\u2026"); h.click("sample-disks \u00b7 dir \u00b7 total minus used: unknown")
            assertEquals(CdMedia.Iso("sample-disks"),seen!!.install.media)
            assertTrue(reads.contains(CreateRead.Volumes("sample-disks","iso")))
            h.click("NONE"); assertEquals(CdMedia.None,seen!!.install.media)
            h.click(OS_LINUX); h.click(OS_WINDOWS)
            assertEquals(1,osCalls); assertEquals("win11",seen!!.osType); assertEquals("4096",seen!!.memoryMib)
            h.click("1  General"); h.click("Default (host)"); h.click("ARM 64-bit (tech preview)")
            assertEquals(1,archCalls); assertEquals("aarch64",seen!!.arch)
            assertTrue(reads.any { it==CreateRead.CpuModels("aarch64") }); assertTrue(reads.any { it==CreateRead.CpuFlags("aarch64","tcg") })
            h.click("2  OS"); h.click(OS_LINUX)
            assertFalse(h.nodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { t->t.text==OS_WINDOWS }==true })
            h.key(Key.Escape)
            h.click("4  Disks"); h.click("scsi"); h.click("sata")
            assertEquals(1,busCalls); assertEquals(DiskBus.Sata,seen!!.disks.single().bus); assertFalse(seen!!.disks.single().ioThread)
            h.click("sample-disks · dir · total minus used: unknown"); h.key(Key.Enter)
            assertEquals(1,storageCalls); assertEquals("raw",seen!!.disks.single().format)
            h.click("5  CPU"); h.click("ON"); assertEquals(listOf(CpuFlag("aes",'+')),seen!!.cpuFlags)
            h.click("DEFAULT"); assertTrue(seen!!.cpuFlags.isEmpty())
            h.click("1  General"); h.click("demo-node-a"); h.click("node-b")
            assertEquals("node-b",seen!!.node); assertEquals("",seen!!.cpuType)
            assertEquals("",(seen!!.disks.single().source as DiskSource.New).storage)
            assertEquals("",seen!!.nic!!.bridge); assertEquals("",seen!!.tpm!!.storage)
            h.key(Key.Enter,ctrl=true); assertEquals(0,freezes,"Stale selections must not reach freeze")
        } finally { scene.close() }
    }

    @Test fun invalidationKeepsTypedValuesButDropsAllNodeScopedDefaults() {
        val original=VmDraft("old",name="keep-me",memoryMib="8192",cpuType="host",cpuFlags=listOf(CpuFlag("aes",'+')),install=CdDrive(media=CdMedia.Iso("old-iso","old-iso:iso/a.iso")),driverDisc=CdDrive(media=CdMedia.Iso("old-iso","old-iso:iso/driver.iso")),efi=EfiDisk("old-efi","qcow2",volume="old-efi:vm-140-1"),tpm=TpmState("old-tpm"),disks=listOf(VmDisk(source=DiskSource.New("old-disk")),VmDisk(index=1,source=DiskSource.Existing("old-disk:volume")),VmDisk(index=2,source=DiskSource.Import("old-disk:import/a","old-disk"))),nic=VmNic(bridge="old-bridge"))
        val d=invalidateVmNode(original,"new")
        assertEquals("keep-me",d.name);assertEquals("8192",d.memoryMib)
        assertEquals(CdMedia.Iso(),d.install.media);assertEquals(CdMedia.Iso(),d.driverDisc!!.media)
        assertEquals("",d.efi.storage);assertNull(d.efi.volume);assertEquals("",d.tpm!!.storage)
        assertEquals(DiskSource.New(),d.disks[0].source);assertEquals(DiskSource.Existing(""),d.disks[1].source);assertEquals(DiskSource.Import("",""),d.disks[2].source)
        assertTrue(d.cpuFlags.isEmpty());assertEquals("",d.nic!!.bridge)
        val errors=vmSelectionErrors(d,CreateContext(),CreateSession(NewGuestKind.Vm,"new",1))
        assertTrue(CreateFields.INSTALL in errors);assertTrue(CreateFields.CPU_TYPE in errors);assertTrue(CreateFields.BRIDGE in errors)
    }
}
