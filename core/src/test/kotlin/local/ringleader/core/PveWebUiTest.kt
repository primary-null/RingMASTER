package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PveWebUiTest {
    private val origin = "https://pve.example.test:8006"

    @Test fun guestNodeStorageAndDatacenterRoutes() {
        assertEquals("https://pve.example.test:8006/#v1:0:=qemu/112", PveWebUi.guest(origin, "VM", 112))
        assertEquals("https://pve.example.test:8006/#v1:0:=lxc/115", PveWebUi.guest(origin, "CT", 115))
        assertEquals("https://pve.example.test:8006/#v1:0:=node/PVE1", PveWebUi.node(origin, "PVE1"))
        assertEquals("https://pve.example.test:8006/#v1:0:=storage/PVE1/local%20lvm", PveWebUi.storage(origin, "PVE1", "local lvm"))
        assertEquals("https://pve.example.test:8006/#v1:0:", PveWebUi.datacenter("$origin/api2/json"))
    }

    @Test fun tasksOpenTheirGuestOrNode() {
        assertEquals(PveWebUi.guest(origin, "VM", 113), PveWebUi.task(origin, "PVE1", "qmstart", "113"))
        assertEquals(PveWebUi.guest(origin, "CT", 115), PveWebUi.task(origin, "PVE1", "vzstop", "115"))
        // vzdump covers both kinds, so it opens the node.
        assertEquals(PveWebUi.node(origin, "PVE1"), PveWebUi.task(origin, "PVE1", "vzdump", "115"))
        assertEquals(PveWebUi.node(origin, "PVE1"), PveWebUi.task(origin, "PVE1", "aptupdate", ""))
    }

    @Test fun refusesAnythingButAPlainHttpsOrigin() {
        assertNull(PveWebUi.guest("http://pve.example.test:8006", "VM", 1))
        assertNull(PveWebUi.guest("https://user:pw@pve.example.test:8006", "VM", 1))
        assertNull(PveWebUi.guest("javascript:alert(1)", "VM", 1))
        assertNull(PveWebUi.node(origin, " "))
        assertEquals("https://[fd00::5]:8006/#v1:0:=qemu/1", PveWebUi.guest("https://[fd00::5]:8006", "VM", 1))
    }
}
