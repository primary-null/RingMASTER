package local.ringleader.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GuestBluetoothTest {
    private val remote = "https://192.0.2.10:8006"
    private val vm = Guest(remote, 113, "fixture", "pve1", "VM", "running")

    @Test fun usbAttachmentsCoverSpiceHostAndMappings() {
        val row = Json.parseToJsonElement("""{"usb0":"spice","usb1":"host=13d3:3585,usb3=1","usb2":"host=1-2","usb10":"mapping=bt-dongle","usb3":"host=spice","net0":"virtio=AA"}""").jsonObject
        val usb = parseUsbAttachments(row)
        assertEquals(listOf("usb0", "usb1", "usb2", "usb3", "usb10"), usb.map { it.key })
        assertEquals(UsbKind.Spice, usb[0].kind)
        assertEquals(UsbKind.Host, usb[1].kind); assertEquals(0x13D3, usb[1].vendorId); assertEquals(0x3585, usb[1].productId); assertTrue(usb[1].usb3)
        assertEquals("1-2", usb[2].target); assertNull(usb[2].vendorId)
        assertEquals(UsbKind.Spice, usb[3].kind)
        assertEquals(UsbKind.Mapping, usb[4].kind); assertEquals("bt-dongle", usb[4].target)
        val hw = parseHardware("""{"data":{"cores":2,"usb0":"spice","hostpci0":"0000:03:00,pcie=1"}}""")
        assertEquals(1, hw.usb.size); assertEquals("hostpci0", hw.pci.single().key)
    }

    @Test fun agentExecRepliesAreRead() {
        assertEquals(4242, parseAgentExecPid("""{"data":{"pid":4242}}"""))
        assertFailsWith<IllegalStateException> { parseAgentExecPid("""{"data":null}""") }
        val running = parseAgentExecStatus("""{"data":{"exited":0}}""")
        assertFalse(running.exited)
        val done = parseAgentExecStatus("""{"data":{"exited":1,"exitcode":0,"out-data":"RINGMASTER-BLUEZ:MISSING\n"}}""")
        assertTrue(done.exited); assertEquals(0, done.exitCode)
        assertEquals("BlueZ tools are not installed (install the bluez package).", parseBluezScanOutput(done.out, guestBluetoothSource(vm), java.time.Instant.EPOCH).note)
        assertEquals("""{"command":["sh","-c","echo hi"]}""", String(agentExecBody(listOf("sh", "-c", "echo hi"))))
        assertEquals("VM 113 · fixture", guestBluetoothSource(vm).label)
    }

    @Test fun qemuAgentOptionAndGuestPreflight() {
        assertFalse(qemuAgentEnabled(null))
        assertFalse(qemuAgentEnabled(""))
        assertFalse(qemuAgentEnabled("0"))
        assertFalse(qemuAgentEnabled("enabled=0"))
        assertFalse(qemuAgentEnabled("enabled=0,type=virtio"))
        assertTrue(qemuAgentEnabled("1"))
        assertTrue(qemuAgentEnabled("1,fstrim_cloned_disks=1"))
        assertTrue(qemuAgentEnabled("enabled=1"))
        assertTrue(qemuAgentEnabled("true"))
        val parsed = parseHardware("""{"data":{"cores":2,"ostype":"l26","agent":"1"}}""")
        assertEquals("1", parsed.fields["agent"])
        assertEquals("l26", parsed.fields["ostype"])
        fun hw(agent: String?, os: String?) = HardwareSummary(
            buildMap { if (os != null) put("ostype", os); if (agent != null) put("agent", agent) },
            emptyList(), emptyList(),
        )
        assertEquals("Guest scans need a QEMU VM with the guest agent.", guestBluetoothRefusal(vm.copy(kind = "CT"), true, hw("1", "l26")))
        assertEquals(GUEST_BT_NOT_RUNNING, guestBluetoothRefusal(vm.copy(status = "stopped"), true, hw("1", "l26")))
        assertEquals(GUEST_BT_NEED_DIRECT, guestBluetoothRefusal(vm, false, hw("1", "l26")))
        assertEquals(GUEST_BT_LOADING, guestBluetoothRefusal(vm, true, null))
        assertEquals(GUEST_BT_WINDOWS, guestBluetoothRefusal(vm, true, hw("1", "win11")))
        assertEquals(GUEST_BT_WINDOWS, guestBluetoothRefusal(vm, true, hw("1", "Win10")))
        assertEquals(GUEST_BT_AGENT_OFF, guestBluetoothRefusal(vm, true, hw("0", "l26")))
        assertEquals(GUEST_BT_AGENT_OFF, guestBluetoothRefusal(vm, true, hw(null, "l26")))
        assertNull(guestBluetoothRefusal(vm, true, hw("1", "l26")))
        assertNull(guestBluetoothRefusal(vm, true, hw("1", null)))
        assertNull(guestBluetoothRefusal(vm, true, hw("enabled=1,type=virtio", "l26")))
    }

    @Test fun workspaceUsesTheLiveGuestAndServesDemoData() = runTest {
        val calls = mutableListOf<Pair<Guest, Int>>()
        val bt = object : GuestBluetoothGateway {
            override suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan { calls += guest to seconds; return demoGuestBluetoothScan(guest) }
        }
        val moved = vm.copy(node = "pve6")
        val inventory = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(1, listOf(moved))
            override suspend fun details(connection: Connection, guest: Guest) = GuestDetails(guest, "running", 2, null, null, null, null, null, null, null, null)
        }
        val w = Workspace(this, gateway = inventory, guestBluetoothGateway = bt)
        w.connect(Connection(remote, "tester@pve!fixture", "fixture-secret", "")); runCurrent()
        w.guestBluetoothScan(vm, 12)
        assertEquals("pve6", calls.single().first.node); assertEquals(12, calls.single().second)
        assertFailsWith<IllegalStateException> { w.guestBluetoothScan(vm.copy(id = 999)) }
        w.close()
        val demo = Workspace(this, gateway = inventory, guestBluetoothGateway = bt)
        demo.exploreDemo(); runCurrent()
        assertEquals("Sample data", demo.guestBluetoothScan(demoOverview.guests.first()).note)
        assertEquals(1, calls.size, "demo never calls the gateway")
        demo.close()
    }
}
