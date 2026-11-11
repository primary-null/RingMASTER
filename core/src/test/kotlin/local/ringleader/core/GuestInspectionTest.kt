package local.ringleader.core

import kotlin.test.*

class GuestInspectionTest {
    private val vm = Guest("https://example.test:8006",100,"fixture","node-a","VM","running")
    private fun confirmation(action: PowerAction, request: SnapshotRequest? = null) = PowerConfirmation(1,vm.remote,vm.remote,"Fixture",vm.resource,vm.node,vm.name,vm.kind,action,vm.status,false,snapshot=request)
    @Test fun vmHardwareToleratesOddKeysAndMultipleDevices() {
        val h = parseHardware("""{"data":{"cores":4,"sockets":2,"balloon":1024,"memory":4096,"boot":"order=scsi0;net0","ostype":"l26","onboot":1,"tags":"a;b","description":"plain <b>text</b>","scsi0":"local:disk,size=20G","sata1":"other:disk,size=30G","net0":"virtio=AA:BB,bridge=vmbr0,tag=5,firewall=1","net1":"e1000=CC:DD,bridge=vmbr1","unknown":{"nested":true},"pending":{"memory":8192}}}""")
        assertEquals(2,h.disks.size); assertEquals("local",h.disks.single { it.key == "scsi0" }.storage); assertEquals(2,h.nics.size)
        assertEquals("1",h.nics[0].properties["firewall"]); assertEquals("5",h.nics[0].properties["tag"])
        assertEquals("plain <b>text</b>",h.fields["description"]); assertNotNull(h.pending)
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
        listOf("current","vzdump","../bad","a b","1bad","x".repeat(41)).forEach { assertFalse(snapshotNameValid(it)) }
        assertFalse(validSnapshotRequest(confirmation(PowerAction.SnapshotCreate)))
        assertFalse(validSnapshotRequest(confirmation(PowerAction.Start,SnapshotRequest("good"))))
        assertFalse(validSnapshotRequest(confirmation(PowerAction.SnapshotRollback,SnapshotRequest("good",includeRam=true))))
    }
}
