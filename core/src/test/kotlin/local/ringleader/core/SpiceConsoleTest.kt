package local.ringleader.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpiceConsoleTest {
    private val remote = "https://192.0.2.10:8006"
    private val vm = Guest(remote, 113, "fixture", "pve1", "VM", "running")

    private fun body(vararg extra: Pair<String, String>, proxy: String = "http://192.0.2.10:3128", type: String = "spice"): String {
        val fields = linkedMapOf("type" to type, "proxy" to proxy, "host" to "pvespiceproxy:abc:113:pve1:61002::0f", "password" to "one-time",
            "tls-port" to "61002", "ca" to "-----BEGIN CERTIFICATE-----\\\\nAAAA\\\\n-----END CERTIFICATE-----\\\\n", "delete-this-file" to "1", "title" to "VM 113 - fixture") + extra
        return fields.entries.joinToString(",", "{\"data\":{", "}}") { (k, v) -> "\"$k\":\"$v\"" }.replace("\"tls-port\":\"61002\"", "\"tls-port\":61002")
    }

    @Test fun ticketBecomesAViewerFileAndNeverPrintsThePassword() {
        val ticket = parseSpiceTicket(body(), "192.0.2.10")
        val file = ticket.viewerFile()
        assertTrue(file.startsWith("[virt-viewer]\n"))
        assertTrue("password=one-time\n" in file)
        assertTrue("tls-port=61002\n" in file)
        assertTrue("delete-this-file=1\n" in file)
        // The CA stays one line with escaped newlines, as remote-viewer expects.
        assertTrue("ca=-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\\n\n" in file)
        assertEquals("VM 113 - fixture", ticket.title)
        assertFalse("one-time" in ticket.toString())
    }

    @Test fun unusableTicketsAreRefused() {
        assertFailsWith<IllegalStateException> { parseSpiceTicket(body(proxy = "http://203.0.113.9:3128"), "192.0.2.10") }
        assertFailsWith<IllegalStateException> { parseSpiceTicket(body(type = "vnc"), "192.0.2.10") }
        assertFailsWith<IllegalStateException> { parseSpiceTicket(body("title" to "x\\nproxy=http://evil:3128"), "192.0.2.10") }
        assertFailsWith<IllegalStateException> { parseSpiceTicket(body("Bad Key" to "1"), "192.0.2.10") }
        assertFailsWith<IllegalStateException> { parseSpiceTicket("""{"data":null}""", "192.0.2.10") }
    }

    @Test fun onlyQxlDisplaysServeSpice() {
        requireSpiceDisplay("qxl"); requireSpiceDisplay("qxl2,memory=32"); requireSpiceDisplay("type=qxl,memory=16")
        assertEquals("qxl", spiceDisplayType("""{"data":{"vga":"qxl","cores":2}}"""))
        assertNull(spiceDisplayType("""{"data":{"cores":2}}"""))
        assertTrue(assertFailsWith<IllegalArgumentException> { requireSpiceDisplay(null) }.message!!.contains("Default (VGA)"))
        assertTrue(assertFailsWith<IllegalArgumentException> { requireSpiceDisplay("std") }.message!!.contains("SPICE (qxl)"))
        assertFailsWith<IllegalArgumentException> { requireSpiceDisplay("virtio") }
    }

    @Test fun pathAndBodyTargetTheConnectedHost() {
        assertEquals("/api2/json/nodes/pve1/qemu/113/spiceproxy", spiceProxyPath(vm))
        assertFailsWith<IllegalArgumentException> { spiceProxyPath(vm.copy(kind = "Container")) }
        assertEquals("""{"proxy":"192.0.2.10"}""", String(spiceRequestBody(remote)))
        assertEquals(POWER_CONTENT_TYPE, "application/json")
        assertEquals("pve.example.test", spiceProxyHost("https://pve.example.test:8006"))
    }

    @Test fun workspaceRefusesDemoAndUnknownGuestsAndPassesTheLiveGuest() = runTest {
        val calls = mutableListOf<Guest>()
        val spice = object : SpiceGateway {
            override suspend fun ticket(connection: Connection, guest: Guest): SpiceTicket { calls += guest; return parseSpiceTicket(body(), "192.0.2.10") }
        }
        val moved = vm.copy(node = "pve6")
        val inventory = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(1, listOf(moved))
            override suspend fun details(connection: Connection, guest: Guest) = GuestDetails(guest, "running", 2, null, null, null, null, null, null, null, null)
        }
        val w = Workspace(this, gateway = inventory, spiceGateway = spice)
        w.connect(Connection(remote, "tester@pve!fixture", "fixture-secret", "")); runCurrent()
        w.spiceTicket(vm)
        assertEquals("pve6", calls.single().node, "the ticket uses the guest's current node")
        assertFailsWith<IllegalStateException> { w.spiceTicket(vm.copy(id = 999)) }
        assertEquals(1, calls.size)
        w.close()

        val demo = Workspace(this, gateway = inventory, spiceGateway = spice)
        demo.exploreDemo(); runCurrent()
        assertEquals(SPICE_DEMO, assertFailsWith<IllegalStateException> { demo.spiceTicket(demoOverview.guests.first()) }.message)
        assertEquals(1, calls.size)
        demo.close()
    }

    @Test fun expiredSessionDisconnects() = runTest {
        val spice = object : SpiceGateway { override suspend fun ticket(connection: Connection, guest: Guest): SpiceTicket = throw SessionExpiredException() }
        val inventory = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(1, listOf(vm))
            override suspend fun details(connection: Connection, guest: Guest) = GuestDetails(guest, "running", 2, null, null, null, null, null, null, null, null)
        }
        val w = Workspace(this, gateway = inventory, spiceGateway = spice)
        w.connect(Connection(remote, "tester@pve!fixture", "fixture-secret", "")); runCurrent()
        assertFailsWith<SessionExpiredException> { w.spiceTicket(vm) }
        assertFalse(w.state.value.connected)
        w.close()
    }
}
