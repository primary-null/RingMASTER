package local.ringleader.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.core.ClusterTask
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.Guest
import local.ringleader.core.PowerAction
import local.ringleader.core.PowerPhase
import local.ringleader.core.PowerSubmission
import local.ringleader.core.TaskOutcome

class PinStoreTest {
    private val guest = Guest(DEMO_REMOTE_KEY, 120, "debian-workbench", "demo-node-a", "VM", "running")
    private val other = Guest(DEMO_REMOTE_KEY, 121, "adguard", "demo-node-a", "Container", "stopped", scope = "lab")
    private val profileA = "11111111-1111-1111-1111-111111111111"
    private val profileB = "22222222-2222-2222-2222-222222222222"
    private val origin = "https://pve.example.test:8006"

    @Test fun roundTripKeepsOrderKindAndService() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val store = PinStore(file)
        store.toggleGuest(profileA, guest)
        store.toggleView(profileA, guest, "Console")
        assertNull(store.addService(profileA, other, "AdGuard UI", "http://10.1.2.3:3000"))
        store.move(profileA, store.list(profileA)[2].id, -1)
        val again = PinStore(file)
        val pins = again.list(profileA)
        assertEquals(listOf(PIN_GUEST, PIN_SERVICE, PIN_VIEW), pins.map { it.target })
        assertEquals("debian-workbench", pins[0].guestName)
        assertEquals(120, pins[0].vmid)
        assertEquals("", pins[0].scope)
        assertEquals("Console", pins[2].tab)
        assertEquals("lab", pins[1].scope)
        assertEquals("http://10.1.2.3:3000", pins[1].serviceUrl)
        assertEquals("AdGuard UI", pins[1].serviceName)
        val moved = guest.copy(node = "other-node")
        assertTrue(pins[0].matches(moved))
        assertTrue(!pins[0].matches(guest.copy(id = 121)))
    }

    @Test fun corruptFileIsLeftUntouchedAndSavingIsBlocked() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val text = "{ this is not json"
        Files.writeString(file, text)
        val store = PinStore(file)
        assertEquals(emptyList(), store.list(PIN_CONNECTION_DEMO))
        assertTrue(SaveStatus.problem!!.contains("left unchanged"))
        store.toggleGuest(PIN_CONNECTION_DEMO, guest)
        assertEquals("pins.json could not be read. The file was left unchanged.", store.addService(PIN_CONNECTION_DEMO, guest, "UI", "http://192.0.2.8"))
        assertEquals(text, Files.readString(file))
        assertEquals(emptyList(), store.list(PIN_CONNECTION_DEMO))
        val unsupported = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        Files.writeString(unsupported, """{"connections":{}}""")
        val blocked = PinStore(unsupported)
        assertTrue(SaveStatus.problem!!.contains("unsupported version"))
        blocked.toggleGuest(PIN_CONNECTION_DEMO, guest)
        assertEquals("""{"connections":{}}""", Files.readString(unsupported))
        SaveStatus.dismiss()
    }

    @Test fun connectionsDoNotSharePins() {
        val store = PinStore(Files.createTempDirectory("ringmaster-pins").resolve("pins.json"))
        store.toggleGuest(profileA, guest)
        store.toggleView(profileB, guest, "Hardware")
        store.toggleGuest(origin, other)
        assertEquals(listOf(120), store.list(profileA).map { it.vmid })
        assertEquals(listOf("Hardware"), store.list(profileB).map { it.tab })
        assertEquals(listOf(121), store.list(origin).map { it.vmid })
        assertTrue(store.list("https://other.example.test:8006").isEmpty())
        store.move(profileA, store.list(profileA).single().id, 1)
        assertEquals(PIN_VIEW, store.list(profileB).single().target)
    }

    @Test fun serviceAddressesAndNamesAreValidated() {
        assertNull(serviceAddressError("https://adguard.example:3000/admin"))
        assertNull(serviceAddressError("http://192.0.2.8:3000"))
        assertEquals("An address cannot include a query or a fragment.", serviceAddressError("http://192.0.2.8/?token=secret"))
        assertEquals("An address cannot include a query or a fragment.", serviceAddressError("http://192.0.2.8/ui#session"))
        assertNotNull(serviceAddressError("http://user:pass@192.0.2.8"))
        assertNotNull(serviceAddressError("http://user@192.0.2.8/ui"))
        assertNotNull(serviceAddressError("ftp://192.0.2.8"))
        assertNotNull(serviceAddressError("javascript:alert(1)"))
        assertNotNull(serviceAddressError("http://"))
        assertNotNull(serviceAddressError("https://" + "a".repeat(200)))
        assertNotNull(serviceNameError(""))
        assertNotNull(serviceNameError("   "))
        assertNotNull(serviceNameError("a".repeat(33)))
        assertNotNull(serviceNameError("bad\nname"))
        assertNull(serviceNameError("AdGuard UI"))
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        Files.writeString(file, """
            {"version":1,"connections":{"$PIN_CONNECTION_DEMO":[
              {"id":"11111111-1111-1111-1111-111111111111","guestKind":"VM","vmid":120,"scope":"","guestName":"secret","target":"service","serviceName":"UI","serviceUrl":"http://user:pass@192.0.2.1"},
              {"id":"22222222-2222-2222-2222-222222222222","guestKind":"VM","vmid":121,"scope":"","guestName":"ok","target":"guest"}
            ]}}
        """.trimIndent())
        val store = PinStore(file)
        assertEquals(listOf(PIN_GUEST), store.list(PIN_CONNECTION_DEMO).map { it.target })
        assertNotNull(store.addService(PIN_CONNECTION_DEMO, guest, "UI", "http://root:pw@192.0.2.8"))
        assertEquals(1, store.list(PIN_CONNECTION_DEMO).size)
        assertNull(store.addService(PIN_CONNECTION_DEMO, guest, "UI", "http://192.0.2.8:3000"))
        assertNotNull(store.addService(PIN_CONNECTION_DEMO, guest, "UI", "http://192.0.2.8:3000"))
        assertFalse(Files.readString(file).contains("token"))
        assertNotNull(store.addService(PIN_CONNECTION_DEMO, guest, "UI", "http://192.0.2.8/?token=secret"))
        assertFalse(Files.readString(file).contains("token"))
    }

    @Test fun unknownRowsSurviveARewrite() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val text = """{"version":1,"theme":"lab","connections":{"$PIN_CONNECTION_DEMO":[
            {"id":"11111111-1111-1111-1111-111111111111","guestKind":"VM","vmid":120,"scope":"","guestName":"debian-workbench","target":"guest","note":"keep-me"},
            {"mystery":true}
        ],"bad\nkey":"leave-me"}}"""
        Files.writeString(file, text)
        val store = PinStore(file)
        assertEquals(listOf(PIN_GUEST), store.list(PIN_CONNECTION_DEMO).map { it.target })
        store.toggleView(PIN_CONNECTION_DEMO, guest, "Hardware")
        val written = Files.readString(file)
        assertTrue(written.contains("\"theme\""))
        assertTrue(written.contains("keep-me"))
        assertTrue(written.contains("mystery"))
        assertTrue(written.contains("leave-me"))
        assertTrue(written.contains("Hardware"))
        val again = PinStore(file)
        assertEquals(listOf(PIN_GUEST, PIN_VIEW), again.list(PIN_CONNECTION_DEMO).map { it.target })
    }

    @Test fun demoPinsStayApartFromRealConnections() {
        val store = PinStore(Files.createTempDirectory("ringmaster-pins").resolve("pins.json"))
        val real = Guest(origin, 130, "backup-lab", "node-a", "VM", "stopped")
        store.toggleGuest(PIN_CONNECTION_DEMO, guest)
        store.toggleView(PIN_CONNECTION_DEMO, guest, "Actions")
        store.toggleGuest(origin, real)
        assertEquals(listOf(120, 120), store.list(PIN_CONNECTION_DEMO).map { it.vmid })
        assertEquals(listOf(130), store.list(origin).map { it.vmid })
        assertTrue(store.list(profileA).isEmpty())
        assertEquals(PIN_CONNECTION_DEMO, pinConnectionKey(demo = true, profileId = profileA, origin = origin))
        assertEquals(profileA, pinConnectionKey(demo = false, profileId = profileA, origin = origin))
        assertEquals(origin, pinConnectionKey(demo = false, profileId = null, origin = origin))
    }

    @Test fun originPinsMoveOntoTheProfile() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val store = PinStore(file)
        store.toggleGuest(origin, guest)
        store.toggleView(origin, other, "Hardware")
        val ids = store.list(origin).map { it.id }
        store.adoptOriginPins(" $origin ", profileA)
        assertEquals(ids, store.list(profileA).map { it.id })
        assertTrue(store.list(origin).isEmpty())
        assertFalse(store.byConnection.containsKey(origin))
        val again = PinStore(file)
        assertEquals(ids, again.list(profileA).map { it.id })
        assertFalse(again.byConnection.containsKey(origin))
    }

    @Test fun overlappingPinsAreNotDuplicated() {
        val store = PinStore(Files.createTempDirectory("ringmaster-pins").resolve("pins.json"))
        store.toggleGuest(profileA, guest)
        val kept = store.list(profileA).single().id
        store.toggleGuest(origin, guest)
        store.toggleView(origin, guest, "Console")
        assertNull(store.addService(origin, other, "AdGuard UI", "http://10.1.2.3:3000"))
        store.adoptOriginPins(origin, profileA)
        val pins = store.list(profileA)
        assertEquals(listOf(PIN_GUEST, PIN_VIEW, PIN_SERVICE), pins.map { it.target })
        assertEquals(kept, pins[0].id)
        assertEquals(1, pins.count { it.target == PIN_GUEST && it.matches(guest) })
        assertTrue(store.list(origin).isEmpty())
    }

    @Test fun demoAndEqualKeysDoNothing() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val empty = PinStore(file)
        empty.adoptOriginPins(origin, profileA)
        assertFalse(Files.exists(file))
        val store = PinStore(file)
        store.toggleGuest(origin, guest)
        store.toggleGuest(PIN_CONNECTION_DEMO, other)
        val before = store.byConnection
        store.adoptOriginPins(origin, origin)
        store.adoptOriginPins(" $origin ", origin)
        store.adoptOriginPins(PIN_CONNECTION_DEMO, profileA)
        store.adoptOriginPins(origin, PIN_CONNECTION_DEMO)
        store.adoptOriginPins("   ", profileA)
        store.adoptOriginPins(origin, "")
        assertEquals(before, store.byConnection)
        assertTrue(store.list(profileA).isEmpty())
    }

    @Test fun pinConnectionKeyDoesNotMigrate() {
        val store = PinStore(Files.createTempDirectory("ringmaster-pins").resolve("pins.json"))
        store.toggleGuest(origin, guest)
        val key = pinConnectionKey(demo = false, profileId = profileA, origin = origin)
        assertEquals(profileA, key)
        assertTrue(store.list(key!!).isEmpty())
        assertEquals(listOf(120), store.list(origin).map { it.vmid })
        assertEquals(origin, pinConnectionKey(demo = false, profileId = null, origin = " $origin "))
        assertEquals(PIN_CONNECTION_DEMO, pinConnectionKey(demo = true, profileId = profileA, origin = origin))
    }

    @Test fun livenessFollowsTheGuestTaskAndSubmission() {
        val tasks = listOf(task(120, TaskOutcome.Running), task(121, TaskOutcome.Succeeded))
        val waiting = PowerSubmission("local-pin", DEMO_REMOTE_KEY, "demo-node-a", 122, "VM", "build", PowerAction.Start, PowerPhase.Accepted, "Waiting.", false, true)
        assertEquals(PinLiveness.Busy, pinLiveness(guest, tasks, emptyList()))
        assertEquals(PinLiveness.Stopped, pinLiveness(other.copy(scope = ""), tasks, emptyList()))
        assertEquals(PinLiveness.Running, pinLiveness(guest.copy(id = 121), tasks, emptyList()))
        assertEquals(PinLiveness.Busy, pinLiveness(guest.copy(id = 122), emptyList(), listOf(waiting)))
        assertEquals(PinLiveness.Missing, pinLiveness(null, tasks, emptyList()))
        val console = task(120, TaskOutcome.Running).copy(type = "vncproxy", upid = "demo:vnc120")
        assertEquals(PinLiveness.Running, pinLiveness(guest, listOf(console), emptyList()))
        assertEquals(PinLiveness.Busy, pinLiveness(guest, tasks, emptyList(), stale = true))
        assertEquals(PinLiveness.Stale, pinLiveness(guest, listOf(console), emptyList(), stale = true))
        assertEquals("Data is old", pinLivenessLabel(PinLiveness.Stale, guest))
        assertEquals(8, visiblePinCount(9, 252, 28, 28))
        assertEquals(7, visiblePinCount(9, 251, 28, 28))
        assertEquals(8, visiblePinCount(9, 1000, 28, 28))
        assertEquals(2, visiblePinCount(4, 28 * 3, 28, 28))
    }

    private fun task(vmid: Int, outcome: TaskOutcome) = ClusterTask(
        DEMO_REMOTE_KEY, "demo:pin$vmid", "demo-node-a", "qmstart", vmid.toString(), "demo@ringmaster",
        java.time.Instant.parse("2026-11-09T12:00:00Z"),
        if (outcome == TaskOutcome.Running) null else java.time.Instant.parse("2026-11-09T12:01:00Z"),
        when (outcome) { TaskOutcome.Running -> "running"; TaskOutcome.Succeeded -> "OK"; else -> "failed" },
        outcome,
    )
}
