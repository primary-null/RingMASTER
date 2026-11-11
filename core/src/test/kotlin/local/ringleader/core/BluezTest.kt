package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class BluezTest {
    private val at = Instant.parse("2026-11-08T12:00:00Z")
    private val sample = """{"type":"a{oa{sa{sv}}}","data":[{"/org/bluez/hci0":{"org.bluez.Adapter1":{"Address":{"type":"s","data":"02:00:00:00:00:10"},"Alias":{"type":"s","data":"test-hci0"},"Name":{"type":"s","data":"test-hci0"},"Powered":{"type":"b","data":true},"Discovering":{"type":"b","data":false}}},"/org/bluez/hci0/dev_F0_F5_64_00_00_02":{"org.bluez.Device1":{"Address":{"type":"s","data":"F0:F5:64:00:00:02"},"AddressType":{"type":"s","data":"public"},"Name":{"type":"s","data":"Pixel"},"Alias":{"type":"s","data":"Pixel"},"Class":{"type":"u","data":5898764},"Icon":{"type":"s","data":"phone"},"Paired":{"type":"b","data":true},"Connected":{"type":"b","data":false},"RSSI":{"type":"n","data":-61},"Adapter":{"type":"o","data":"/org/bluez/hci0"},"UUIDs":{"type":"as","data":["0000110a-0000-1000-8000-00805f9b34fb"]}}}}]}"""
    private fun scan(json: String) = parseBluezScanOutput("RINGMASTER-BLUEZ:OBJECTS\n$json", BtSource.Local, at)
    private fun device(properties: String) = """{"data":[{"/org/bluez/hci1/dev_a":{"org.bluez.Device1":{$properties},"org.bluez.GattService1":{}}}]}"""

    @Test fun sampleFields() {
        val result = scan(sample)
        assertNull(result.note)
        assertEquals(BtSource.Local, result.source)
        assertEquals(at, result.at)
        assertEquals(BtAdapter("hci0", "test-hci0", "02:00:00:00:00:10", true, false), result.adapters.single())
        assertEquals(BtDevice("F0:F5:64:00:00:02", "Pixel", -61, true, false,
            BtKind.Classic, "phone", "hci0"), result.devices.single())
    }

    @Test fun randomLeWithoutName() {
        val d = scan(device(""""Address":{"type":"s","data":"a1-b2-c3-d4-e5-f6"},"AddressType":{"type":"s","data":"random"},"Future":{"type":"future","data":{"x":1}}""")).devices.single()
        assertEquals("A1:B2:C3:D4:E5:F6", d.address)
        assertEquals(BtKind.LowEnergy, d.kind)
        assertNull(d.name)
        assertNull(d.rssi)
        assertNull(d.paired)
    }

    @Test fun addressAliasIsNotName() {
        val d = scan(device(""""Address":{"type":"s","data":"AA:BB:CC:DD:EE:FF"},"Alias":{"type":"s","data":"aa-bb-cc-dd-ee-ff"}""")).devices.single()
        assertNull(d.name)
        assertEquals(BtKind.Unknown, d.kind)
    }

    @Test fun unavailableNotes() {
        assertEquals("BlueZ tools are not installed (install the bluez package).",
            parseBluezScanOutput("RINGMASTER-BLUEZ:MISSING\n", BtSource.Local, at).note)
        assertEquals("The Bluetooth service (bluetoothd) is not running.",
            parseBluezScanOutput("RINGMASTER-BLUEZ:NODAEMON\n", BtSource.Local, at).note)
        assertEquals(BT_NO_ADAPTER, scan("""{"data":[{}]}""").note)
        assertEquals(BT_NO_ADAPTER, parseBluezScanOutput("RINGMASTER-BLUEZ:NOADAPTER", BtSource.Local, at).note)
        assertTrue("/sys/class/bluetooth" in bluezScanScript(10))
        assertEquals(BT_ADAPTER_OFF, scan(sample.replace("\"data\":true", "\"data\":false")).note)
    }

    @Test fun missingOrInvalidAddressSkipped() {
        assertTrue(scan(device(""""Name":{"type":"s","data":"Unnamed"}""")).devices.isEmpty())
        assertTrue(scan(device(""""Address":{"type":"s","data":"invalid"}""")).devices.isEmpty())
    }

    @Test fun garbageThrows() {
        assertFailsWith<IllegalStateException> { scan("garbage") }
        assertFailsWith<IllegalStateException> { scan("{}") }
        assertFailsWith<IllegalStateException> { parseBluezScanOutput("garbage", BtSource.Local, at) }
    }

    @Test fun scriptIsBoundedAndDiscoveryOnly() {
        assertContains(bluezScanScript(-10), "--timeout 3 scan on")
        assertContains(bluezScanScript(100), "--timeout 30 scan on")
        assertContains(bluezScanScript(12), "--timeout 12 scan on")
        for (forbidden in listOf("power on", "pair", "trust", "connect")) {
            assertFalse(bluezScanScript(10).contains(forbidden))
        }
        assertEquals(listOf("sh", "-c", bluezScanScript(10)), bluezScanCommand(10))
    }

    @Test fun fakeRunner() {
        val scanner = BluezScanner { command, timeout ->
            assertEquals(bluezScanCommand(7), command)
            assertEquals(27L, timeout)
            "RINGMASTER-BLUEZ:OBJECTS\n$sample"
        }
        assertEquals("Linux · BlueZ", scanner.platform)
        val result = scanner.scan(7)
        assertEquals(BtSource.Local, result.source)
        assertEquals("Pixel", result.devices.single().name)
        assertNull(result.note)
    }

    @Test fun liveScriptStreamsDiscovery() {
        val script = bluezLiveScript(12)
        assertTrue(script.indexOf("RINGMASTER-BLUEZ:MISSING") < script.indexOf("RINGMASTER-BLUEZ:NOADAPTER"))
        assertTrue(script.indexOf("RINGMASTER-BLUEZ:NOADAPTER") < script.indexOf("RINGMASTER-BLUEZ:LIVE"))
        assertTrue(script.indexOf("RINGMASTER-BLUEZ:LIVE") < script.indexOf("--timeout 12 scan on"))
        assertTrue(script.indexOf("scan on") < script.indexOf("RINGMASTER-BLUEZ:OBJECTS"))
        assertContains(script, "RINGMASTER-BLUEZ:NODAEMON")
        assertContains(script, "stdbuf -oL")
        assertContains(script, "| cat")
        assertFalse(script.contains("scan on >/dev/null"))
        assertContains(bluezScanScript(12), "scan on >/dev/null")
        assertContains(bluezLiveScript(-10), "--timeout 3 scan on")
        assertContains(bluezLiveScript(100), "--timeout 30 scan on")
        for (forbidden in listOf("power on", "pair", "trust", "connect")) assertFalse(script.contains(forbidden))
    }

    @Test fun liveTranscriptMergesWithoutDroppingSamples() {
        val parser = BluezLiveParser(BtSource.Local, 10_000L, at)
        assertFalse(parser.accept("\u001B[0;94m[bluetoothctl]\u001B[0m# \r", 0))
        assertFalse(parser.accept("noise before the marker", 0))
        assertFalse(parser.accept("RINGMASTER-BLUEZ:LIVE", 0))
        assertTrue(parser.accept("\u001B[0;91m[CHG]\u001B[0m Device 4C:87:5D:12:34:56 RSSI: 0xffffffbd (-67)", 10))
        assertTrue(parser.accept("[bluetooth]# [NEW] Device 4C:87:5D:12:34:56 Pixel 8", 20))
        val early = parser.partial(20).devices.single()
        assertEquals("Pixel 8", early.name)
        assertEquals(-67, early.rssi)
        assertEquals(10L, early.firstSeenMs)
        assertEquals(listOf(BtSample(10, -67)), early.samples)
        assertFalse(parser.partial(20).finished)

        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 RSSI: -70", 80))
        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 Name: Pixel", 90))
        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 Alias: Pixel 8", 95))
        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 Connected: yes", 100))
        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 Paired: yes", 110))
        assertTrue(parser.accept("[CHG] Device 4C:87:5D:12:34:56 Icon: phone", 120))
        assertTrue(parser.accept("[bluetooth]# \r[NEW] Device 6A:11:22:33:44:55 6A-11-22-33-44-55", 130))
        assertFalse(parser.accept("[CHG] Device 6A:11:22:33:44:55 Alias: 6A-11-22-33-44-55", 140))
        assertTrue(parser.accept("[CHG] Device 6A:11:22:33:44:55 RSSI: -80", 150))
        assertFalse(parser.accept("[DEL] Device 6A:11:22:33:44:55", 160))
        assertFalse(parser.accept("[CHG] Controller 02:00:00:00:00:10 Discovering: yes", 170))
        assertFalse(parser.accept("[CHG] Device 4C:87:5D:12:34:56 RSSI: 0", 180))

        val mid = parser.partial(180)
        val pixel = mid.devices[0]
        assertEquals(listOf(BtSample(10, -67), BtSample(80, -70)), pixel.samples)
        assertEquals(10L, pixel.firstSeenMs)
        assertEquals(80L, pixel.lastSeenMs)
        assertEquals(-70, pixel.rssi)
        assertEquals("Pixel 8", pixel.name)
        assertEquals(true, pixel.connected)
        assertEquals(true, pixel.paired)
        assertEquals("phone", pixel.category)
        val dash = mid.devices[1]
        assertEquals("6A:11:22:33:44:55", dash.address)
        assertNull(dash.name)
        assertEquals(listOf(BtSample(150, -80)), dash.samples)

        assertFalse(parser.accept("RINGMASTER-BLUEZ:OBJECTS", 500))
        assertFalse(parser.accept(mergeDump, 500))
        val done = parser.finish(500)
        assertTrue(done.finished)
        assertEquals(500L, done.elapsedMs)
        assertEquals(10_000L, done.windowMs)
        assertEquals(at, done.at)
        assertNull(done.note)
        assertEquals(BtAdapter("hci0", "test-hci0", "02:00:00:00:00:10", true, false), done.adapters.single())
        val merged = done.devices[0]
        assertEquals("Pixel 8 Pro", merged.name)
        assertEquals(-55, merged.rssi)
        assertEquals(false, merged.connected)
        assertEquals(true, merged.paired)
        assertEquals(BtKind.Classic, merged.kind)
        assertEquals("computer", merged.category)
        assertEquals("hci0", merged.adapterId)
        assertEquals(listOf(BtSample(10, -67), BtSample(80, -70)), merged.samples)
        assertEquals(10L, merged.firstSeenMs)
        assertEquals(80L, merged.lastSeenMs)
        assertEquals(listOf(BtSample(150, -80)), done.devices[1].samples)
        assertNull(done.devices[1].name)
        val beacon = done.devices[2]
        assertEquals("Beacon", beacon.name)
        assertEquals(BtKind.LowEnergy, beacon.kind)
        assertEquals(-40, beacon.rssi)
        assertEquals(500L, beacon.firstSeenMs)
        assertEquals(listOf(BtSample(500, -40)), beacon.samples)
        val phones = done.devices[3]
        assertEquals("Headphones", phones.name)
        assertEquals(true, phones.paired)
        assertNull(phones.rssi)
        assertNull(phones.firstSeenMs)
        assertTrue(phones.samples.isEmpty())
    }

    @Test fun liveMarkersAndAdapterNotes() {
        val missing = BluezLiveParser(BtSource.Local, 3_000, at)
        assertTrue(missing.accept("RINGMASTER-BLUEZ:MISSING", 0))
        assertEquals("BlueZ tools are not installed (install the bluez package).", missing.finish(0).note)
        val daemon = BluezLiveParser(BtSource.Local, 3_000, at)
        assertTrue(daemon.accept("RINGMASTER-BLUEZ:NODAEMON", 4))
        assertEquals("The Bluetooth service (bluetoothd) is not running.", daemon.finish(4).note)
        val none = BluezLiveParser(BtSource.Local, 3_000, at)
        assertTrue(none.accept("RINGMASTER-BLUEZ:NOADAPTER", 1))
        assertEquals(BT_NO_ADAPTER, none.finish(1).note)
        val off = BluezLiveParser(BtSource.Local, 3_000, at)
        off.accept("RINGMASTER-BLUEZ:OBJECTS", 0)
        off.accept(poweredOnly.replace("\"data\":true", "\"data\":false"), 0)
        assertEquals(BT_ADAPTER_OFF, off.finish(9).note)
        val empty = BluezLiveParser(BtSource.Local, 3_000, at)
        empty.accept("RINGMASTER-BLUEZ:OBJECTS", 0)
        empty.accept("""{"data":[{}]}""", 0)
        assertEquals(BT_NO_ADAPTER, empty.finish(9).note)
        assertFailsWith<IllegalStateException> { BluezLiveParser(BtSource.Local, 3_000, at).finish(1) }
        val garbage = BluezLiveParser(BtSource.Local, 3_000, at)
        garbage.accept("RINGMASTER-BLUEZ:OBJECTS", 0)
        garbage.accept("garbage", 0)
        assertFailsWith<IllegalStateException> { garbage.finish(1) }
    }

    @Test fun liveUpdatesAtMostEvery150ms() {
        var nowNs = 0L
        val scanner = BluezScanner(
            stream = { command, timeout, onLine ->
                assertEquals(listOf("sh", "-c", bluezLiveScript(10)), command)
                assertEquals(30L, timeout)
                onLine("RINGMASTER-BLUEZ:LIVE")
                onLine("[NEW] Device 4C:87:5D:12:34:56 Pixel 8")
                nowNs = 20_000_000L
                onLine("[CHG] Device 4C:87:5D:12:34:56 RSSI: -67")
                nowNs = 149_000_000L
                onLine("[CHG] Device 4C:87:5D:12:34:56 RSSI: -66")
                nowNs = 150_000_000L
                onLine("[CHG] Device 4C:87:5D:12:34:56 RSSI: -65")
                nowNs = 160_000_000L
                onLine("[DEL] Device 4C:87:5D:12:34:56")
                onLine("[CHG] Controller 02:00:00:00:00:10 Discovering: yes")
                nowNs = 400_000_000L
                onLine("RINGMASTER-BLUEZ:OBJECTS")
                onLine(poweredOnly)
            },
            clock = { nowNs },
            now = { at },
        )
        val updates = mutableListOf<BtScan>()
        val result = scanner.scan(10) { updates += it }
        val partials = updates.filter { !it.finished }
        assertEquals(listOf(0L, 150L), partials.map { it.elapsedMs })
        assertTrue(partials[0].devices.single().samples.isEmpty())
        assertEquals("Pixel 8", partials[0].devices.single().name)
        assertEquals(listOf(BtSample(20, -67), BtSample(149, -66), BtSample(150, -65)), partials[1].devices.single().samples)
        assertEquals(3, updates.size)
        assertTrue(result.finished)
        assertEquals(400L, result.elapsedMs)
        assertEquals(10_000L, result.windowMs)
        assertEquals(at, result.at)
        assertNull(result.note)
        assertEquals(result, updates.last())
        assertEquals(-65, result.devices.single().rssi)
        assertEquals(20L, result.devices.single().firstSeenMs)
        assertEquals(150L, result.devices.single().lastSeenMs)
        nowNs = 0L
        assertEquals(result, scanner.scan(10))
    }

    private val poweredOnly = """{"type":"a{oa{sa{sv}}}","data":[{"/org/bluez/hci0":{"org.bluez.Adapter1":{"Address":{"type":"s","data":"02:00:00:00:00:10"},"Alias":{"type":"s","data":"test-hci0"},"Powered":{"type":"b","data":true}}}}]}"""
    private val mergeDump = """{"type":"a{oa{sa{sv}}}","data":[{"/org/bluez/hci0":{"org.bluez.Adapter1":{"Address":{"type":"s","data":"02:00:00:00:00:10"},"Alias":{"type":"s","data":"test-hci0"},"Name":{"type":"s","data":"test-hci0"},"Powered":{"type":"b","data":true},"Discovering":{"type":"b","data":false}}},"/org/bluez/hci0/dev_4C_87_5D_12_34_56":{"org.bluez.Device1":{"Address":{"type":"s","data":"4C:87:5D:12:34:56"},"Name":{"type":"s","data":"Pixel 8 Pro"},"Alias":{"type":"s","data":"Pixel 8 Pro"},"Class":{"type":"u","data":5898764},"Icon":{"type":"s","data":"computer"},"Paired":{"type":"b","data":true},"Connected":{"type":"b","data":false},"RSSI":{"type":"n","data":-55},"Adapter":{"type":"o","data":"/org/bluez/hci0"}}},"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01":{"org.bluez.Device1":{"Address":{"type":"s","data":"AA:BB:CC:DD:EE:01"},"AddressType":{"type":"s","data":"random"},"Name":{"type":"s","data":"Beacon"},"RSSI":{"type":"n","data":-40},"Adapter":{"type":"o","data":"/org/bluez/hci0"}}},"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_02":{"org.bluez.Device1":{"Address":{"type":"s","data":"AA:BB:CC:DD:EE:02"},"Name":{"type":"s","data":"Headphones"},"Paired":{"type":"b","data":true},"Adapter":{"type":"o","data":"/org/bluez/hci0"}}}}]}"""
}
