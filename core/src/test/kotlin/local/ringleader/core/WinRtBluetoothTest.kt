package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class WinRtBluetoothTest {
    private val at = Instant.ofEpochSecond(1_800_000_000)
    private val classic = "{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}"
    private val le = "{bb7bb05e-5972-42b5-94fc-76eaa7084d49}"
    private val adapter = """{"id":"\\\\?\\USB#VID_13D3&PID_3585&MI_00#6&1&0&0000#{92383b0e}","name":"WORKSTATION","classic":true,"le":true,"address":"60FF9E000001","powered":true}"""

    @Test fun mergesDualModeEntriesAndKeepsOnlyRealSignal() {
        val json = """{"note":null,"adapters":[$adapter],"devices":[
            {"name":"Fixture Keyboard","address":"dc:2c:26:00:00:01","paired":true,"connected":true,"present":true,"rssi":0,"protocol":"$classic","major":5,"minor":16,"leCategory":null},
            {"name":"Fixture Phone","address":"f0:f5:64:00:00:02","paired":true,"connected":false,"present":true,"rssi":0,"protocol":"$classic","major":2,"minor":3,"leCategory":null},
            {"name":"Fixture Phone","address":"f0:f5:64:00:00:02","paired":true,"connected":false,"present":true,"rssi":-36,"protocol":"$le","major":null,"minor":null,"leCategory":0},
            {"name":"","address":"27:fa:b7:00:00:03","paired":false,"connected":false,"present":true,"rssi":-61,"protocol":"$le","major":null,"minor":null,"leCategory":0},
            {"name":"Fixture TV","address":"b0:f2:f6:00:00:04","paired":false,"connected":false,"present":true,"rssi":-70,"protocol":"$classic","major":4,"minor":15,"leCategory":null},
            {"name":"Fixture Mouse","address":"fa:43:67:00:00:05","paired":true,"connected":false,"present":false,"rssi":-49,"protocol":"$le","major":null,"minor":null,"leCategory":15},
            {"name":"broken","address":"not-a-mac","protocol":"$le"}
        ]}"""
        val scan = parseWinRtScan(json, at)
        assertNull(scan.note)
        val a = scan.adapters.single()
        assertEquals("WORKSTATION", a.name); assertEquals("60:FF:9E:00:00:01", a.address); assertEquals(true, a.powered)
        assertEquals(0x13D3, a.usbVendorId); assertEquals(0x3585, a.usbProductId)
        assertEquals(5, scan.devices.size, "the phone's two endpoints merge and the bad address is dropped")
        val byName = scan.devices.associateBy { it.name }
        val keyboard = byName.getValue("Fixture Keyboard")
        assertNull(keyboard.rssi, "0 dBm means not heard in this scan"); assertEquals(true, keyboard.connected); assertEquals("input-keyboard", keyboard.category); assertEquals(BtKind.Classic, keyboard.kind)
        val phone = byName.getValue("Fixture Phone")
        assertEquals(BtKind.Dual, phone.kind); assertEquals(-36, phone.rssi); assertEquals("phone", phone.category)
        assertEquals("video-display", byName.getValue("Fixture TV").category)
        assertEquals("input-mouse", byName.getValue("Fixture Mouse").category)
        val beacon = scan.devices.single { it.address == "27:FA:B7:00:00:03" }
        assertNull(beacon.name); assertEquals(BtKind.LowEnergy, beacon.kind)
        assertEquals("Fixture Keyboard", scan.devices.first().name, "connected devices sort first")
    }

    @Test fun notesForMissingOrOffAdapters() {
        assertEquals(BT_NO_ADAPTER, parseWinRtScan("""{"note":"NO_ADAPTER","adapters":[],"devices":[]}""", at).note)
        val off = parseWinRtScan("""{"note":"ADAPTER_OFF","adapters":[$adapter],"devices":[]}""", at)
        assertEquals(BT_ADAPTER_OFF, off.note); assertEquals(1, off.adapters.size)
        assertFailsWith<IllegalStateException> { parseWinRtScan("Add-Type : something failed", at) }
    }

    @Test fun classOfDeviceCategories() {
        assertEquals("computer", btCategoryFromClassOfDevice(1, 3))
        assertEquals("audio-headset", btCategoryFromClassOfDevice(4, 1))
        assertEquals("audio-card", btCategoryFromClassOfDevice(4, 5))
        assertEquals("input-mouse", btCategoryFromClassOfDevice(5, 0x20))
        assertNull(btCategoryFromClassOfDevice(31, 0))
    }
}
