package local.ringleader.core

import kotlin.test.*

class PveClientTest {
    @Test fun rejectsAddressesThatCouldLeakCredentials() {
        listOf("http://example.test:8006", "https://user:pass@example.test", "https://example.test/?token=secret",
            "https://example.test/#fragment", "https://example.test/api2/json", "https://example.test:99999").forEach {
            assertFailsWith<IllegalArgumentException>(it) { serverOrigin(it) }
        }
    }
    @Test fun preservesExplicitPortAndIpv6() {
        assertEquals("https://example.test:8006", serverOrigin(" https://example.test:8006/ ").toString())
        assertEquals("https://[::1]:8006", serverOrigin("https://[::1]:8006").toString())
    }
    @Test fun distinguishesNodesGuestsAndStorageWithoutInventingGuests() {
        val remote = "https://example.test:8006"
        val result = parseOverview("""{"data":[{"type":"node","node":"lab"},{"type":"storage"},{"type":"lxc","vmid":12,"name":"service","node":"lab","status":"running"},{"type":"qemu","vmid":10,"name":"desktop","node":"lab","status":"stopped"}]}""", remote)
        assertEquals(1, result.nodes)
        assertEquals(listOf(10, 12), result.guests.map { it.id })
        assertEquals(listOf("VM", "Container"), result.guests.map { it.kind })
        assertEquals(listOf(remote, remote), result.guests.map { it.remote })
    }
    @Test fun readsGuestHaStateAndLockAndDropsBlankOrUnsafeText() {
        val remote = "https://example.test:8006"
        val result = parseOverview("""{"data":[
            {"type":"qemu","vmid":10,"name":"desktop","node":"lab","status":"running","hastate":" started ","lock":"backup"},
            {"type":"lxc","vmid":11,"name":"blank","node":"lab","status":"stopped","hastate":"  ","lock":""},
            {"type":"qemu","vmid":12,"name":"plain","node":"lab","status":"running"},
            {"type":"qemu","vmid":13,"name":"bad","node":"lab","status":"stopped","hastate":"err\u0000or","lock":"snap\nshot"},
            {"type":"lxc","vmid":14,"name":"numbers","node":"lab","status":"running","hastate":1,"lock":2}
        ]}""", remote)
        val byId = result.guests.associateBy { it.id }
        assertEquals(listOf(10, 11, 12, 13, 14), byId.keys.sorted())
        assertEquals("started", byId.getValue(10).haState)
        assertEquals("backup", byId.getValue(10).lock)
        assertNull(byId.getValue(11).haState)
        assertNull(byId.getValue(11).lock)
        assertNull(byId.getValue(12).haState)
        assertNull(byId.getValue(12).lock)
        assertNull(byId.getValue(13).haState)
        assertNull(byId.getValue(13).lock)
        assertNull(byId.getValue(14).haState)
        assertNull(byId.getValue(14).lock)
    }

    @Test fun demoOverviewShowsOneHaGuestAndOneLockedGuest() {
        val ha = demoOverview.guests.filter { it.haState != null }
        val locked = demoOverview.guests.filter { it.lock != null }
        assertEquals(listOf(121), ha.map { it.id })
        assertEquals("started", ha.single().haState)
        assertEquals("running", ha.single().status)
        assertEquals(listOf(130), locked.map { it.id })
        assertEquals("backup", locked.single().lock)
        assertNull(demoOverview.guests.single { it.id == 120 }.haState)
        assertNull(demoOverview.guests.single { it.id == 120 }.lock)
    }

    @Test fun rejectsBrokenPayloadInsteadOfDisplayingEmptySuccess() {
        val remote = "https://example.test:8006"
        assertFails { parseOverview("""{"errors":"denied"}""", remote) }
        assertFails { parseOverview("""{"data":[{"type":"qemu"}]}""", remote) }
        assertFails { parseOverview("""{"data":[]}""", "https://example.test:8006/") }
        assertFails { parseOverview("""{"data":[]}""", DEMO_REMOTE_KEY) }
    }
}
