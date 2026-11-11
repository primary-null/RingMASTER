package local.ringleader.core

import kotlin.test.*

class GuestDetailsTest {
    private val vm = Guest("https://example.test:8006", 112, "desktop", "node-a", "VM", "running")
    @Test fun routesEachGuestKindWithoutAcceptingPathInjection() {
        assertEquals("/api2/json/nodes/node-a/qemu/112/status/current", guestStatusPath(vm))
        assertEquals("/api2/json/nodes/node-a/lxc/112/status/current", guestStatusPath(vm.copy(kind = "Container")))
        listOf("../other", "node/../../access", "//example.test", "..", "node?x=y").forEach { node ->
            assertFailsWith<IllegalArgumentException> { guestStatusPath(vm.copy(node = node)) }
        }
        assertFailsWith<IllegalArgumentException> { guestStatusPath(vm.copy(id = -1)) }
        assertFailsWith<IllegalStateException> { guestStatusPath(vm.copy(kind = "Unknown")) }
    }
    @Test fun missingMetricsRemainUnknownRatherThanZero() {
        val details = parseDetails(vm, """{"data":{"status":"stopped","cpus":8,"maxmem":17179869184}}""")
        assertEquals(8, details.cpus)
        assertEquals(17179869184L, details.memoryLimitBytes)
        assertNull(details.cpuFraction)
        assertNull(details.memoryBytes)
        assertNull(details.uptimeSeconds)
        assertNull(details.diskBytes)
    }
    @Test fun containerDiskUsageIsDifferentFromUnavailableVmFilesystemUsage() {
        val json = """{"data":{"status":"running","cpu":0.25,"mem":1073741824,"maxmem":8589934592,"disk":2147483648,"maxdisk":68719476736,"uptime":1234,"netin":20,"netout":40}}"""
        assertNull(parseDetails(vm, json).diskBytes)
        val container = parseDetails(vm.copy(kind = "Container"), json)
        assertEquals(2147483648L, container.diskBytes)
        assertEquals(0.25, container.cpuFraction)
        assertEquals(1234L, container.uptimeSeconds)
    }
    @Test fun foreignRemoteIsRejectedBeforeRoutingAndMigrationChangesOnlyTheNode() {
        Connection("https://a.example.test:8006", "tester@pve!read", "test-only-secret", "").use { connection ->
            val foreign = Guest("https://b.example.test:8006", 112, "desktop", "node-a", "VM", "running")
            val error = assertFailsWith<IllegalArgumentException> { PveClient().details(connection, foreign) }
            assertTrue(error.message!!.contains("different connection"))
            assertFailsWith<IllegalArgumentException> { boundGuestPath(connection, foreign) }
            val moved = Guest(connection.remoteKey, 112, "desktop", "node-b", "VM", "running")
            assertEquals(vm.resource.copy(remote = connection.remoteKey), moved.resource)
            assertEquals("/api2/json/nodes/node-b/qemu/112/status/current", boundGuestPath(connection, moved))
        }
    }
    @Test fun detailsRequireAnExplicitGuestState() {
        assertFails { parseDetails(vm, """{"data":{}}""") }
        assertFails { parseDetails(vm, """{"error":"no permission"}""") }
    }
}
