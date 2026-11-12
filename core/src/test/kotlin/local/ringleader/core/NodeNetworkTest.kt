package local.ringleader.core

import kotlin.test.*

class NodeNetworkTest {
    @Test fun parsesBridgesBondsVlansAndNics() {
        val list = parseNodeNetwork("""{"data":[
          {"iface":"enp2s0","type":"eth","active":1},
          {"iface":"vmbr10","type":"bridge","active":1,"autostart":1,"bridge_ports":"none"},
          {"iface":"vmbr0","type":"bridge","active":1,"autostart":1,"address":"10.0.0.5","netmask":"255.255.255.0","gateway":"10.0.0.1","bridge_ports":"bond0","bridge_vlan_aware":1,"comments":"LAN\n"},
          {"iface":"vmbr2","type":"bridge","active":0,"autostart":1},
          {"iface":"bond0","type":"bond","active":1,"slaves":"enp1s0 enp2s0","bond_mode":"active-backup","mtu":"9000"},
          {"iface":"vmbr0.30","type":"vlan","active":1,"cidr":"10.30.0.5/24"},
          {"type":"eth"}
        ]}""")
        assertEquals(listOf("vmbr0", "vmbr2", "vmbr10", "bond0", "vmbr0.30", "enp2s0"), list.map { it.name })
        val lan = list.first()
        assertEquals("10.0.0.5/24", lan.cidr); assertEquals("10.0.0.1", lan.gateway); assertEquals(listOf("bond0"), lan.members)
        assertTrue(lan.vlanAware); assertEquals("LAN", lan.comment)
        assertEquals(emptyList(), list.single { it.name == "vmbr10" }.members)
        assertFalse(list.single { it.name == "vmbr2" }.active)
        val bond = list.single { it.type == "bond" }
        assertEquals(listOf("enp1s0", "enp2s0"), bond.members); assertEquals("active-backup", bond.bondMode); assertEquals(9000, bond.mtu)
        val vlan = list.single { it.type == "vlan" }
        assertEquals(30, vlan.vlanId); assertEquals("vmbr0", vlan.vlanRaw)
    }

    @Test fun netmasksAndPaths() {
        assertEquals(24, netmaskBits("255.255.255.0")); assertEquals(16, netmaskBits("255.255.0.0")); assertNull(netmaskBits("255.0.255.0"))
        assertEquals("/api2/json/nodes/pve1/network", nodeNetworkPath("pve1"))
        assertEquals("/api2/json/nodes/node-a/network", nodeNetworkPath("node-a"))
        assertFailsWith<IllegalArgumentException> { nodeNetworkPath("../x") }
        for (bad in listOf("node.example", "node_a", "a/b", "", "https://node")) {
            assertFailsWith<IllegalArgumentException>(bad) { nodeNetworkPath(bad) }
        }
        assertTrue(demoNodeNetwork.isNotEmpty())
    }

    @Test fun stagedChangesAndAddressMethodsStayWithTheRows() {
        val report = parseNodeNetworkReport("""{"data":[
          {"iface":"eth0","type":"eth","active":1,"method":"dhcp","method6":"manual","exists":0,"gateway6":"fe80::1"},
          {"iface":"vmbr0","type":"bridge","active":1,"exists":1,"method":"static","address":"10.0.0.5","netmask":"255.255.255.0","gateway":"10.0.0.1"}
        ],"changes":"--- live\n+++ staged\u0007"}""")
        assertEquals("--- live\n+++ staged", report.pendingChanges)
        val eth = report.interfaces.single { it.name == "eth0" }
        assertEquals("dhcp", eth.method)
        assertEquals("manual", eth.method6)
        assertEquals(false, eth.exists)
        assertEquals("fe80::1", eth.gateway6)
        assertEquals(true, report.interfaces.single { it.name == "vmbr0" }.exists)
        assertEquals(listOf("vmbr0", "eth0"), parseNodeNetwork("""{"data":[{"iface":"eth0","type":"eth"},{"iface":"vmbr0","type":"bridge"}],"changes":"x"}""").map { it.name })
        val omitted = parseNodeNetworkReport("""{"data":[{"iface":"eth0","type":"eth","active":1}]}""")
        assertNull(omitted.interfaces.single().exists)
        assertNull(omitted.pendingChanges)
        assertNull(parseNodeNetworkReport("""{"data":[],"changes":"  "}""").pendingChanges)
        assertNull(parseNodeNetworkReport("""{"data":[],"changes":1}""").pendingChanges)
        val cut = cleanNetworkChanges("x".repeat(4_001))
        assertTrue(cut!!.endsWith("\n… The diff is cut off."))
        assertTrue(cut.length < 4_100)
    }
}
