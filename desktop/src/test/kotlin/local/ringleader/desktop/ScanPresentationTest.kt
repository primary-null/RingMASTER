package local.ringleader.desktop
import kotlin.test.*
import local.ringleader.core.*
class ScanPresentationTest {
    private fun hit(ip: String="192.168.1.2", subject: String?=null, kind: ScanKind=ScanKind.PVE)=ScanHit(ip,8006,true,kind,subject=subject)
    @Test fun commonNameAndFallback() {
        assertEquals("pve.local",scanHostname(hit(subject="CN=pve.local,O=Lab")))
        assertEquals("a,b",scanHostname(hit(subject="CN=a\\,b,O=Lab")))
        assertEquals("192.168.1.2",scanHostname(hit(subject="invalid")))
    }
    @Test fun liveSubjectTitleAndInterfaceLabels() {
        // Exact subject form returned by X500Principal.getName() for a Proxmox VE node.
        assertEquals("PVE1.local",scanHostname(hit(subject="CN=PVE1.local,O=Proxmox Virtual Environment,OU=PVE Cluster Node")))
        assertEquals("PVE1",scanHostname(ScanHit("192.168.50.21",8006,true,ScanKind.PVE,title="PVE1 - Proxmox Virtual Environment")))
        assertEquals(null,subjectCommonName("O=Lab,OU=Only"))
        assertEquals("ens18",scanInterfaceLabel("ens18 ens18"))
        assertEquals("eth0 Intel(R) Ethernet",scanInterfaceLabel("eth0 Intel(R) Ethernet"))
    }
    @Test fun serviceNamesAvoidAddressesAndRedirectTitles() {
        assertEquals("Web page",serviceName(ScanHit("192.168.50.100",80,false,ScanKind.WEB)))
        assertEquals("Web page",serviceName(ScanHit("192.168.50.147",80,false,ScanKind.WEB,title="Moved Temporarily")))
        assertEquals("Home Assistant",serviceName(ScanHit("192.168.50.78",80,false,ScanKind.HOME_ASSISTANT,title="Home Assistant")))
        assertEquals("router.local",serviceName(ScanHit("192.168.50.1",443,true,ScanKind.WEB,subject="CN=router.local")))
    }
    @Test fun countsPartitionResults() { assertEquals(listOf(2,1,1),scanCounts(listOf(hit(),hit(kind=ScanKind.WEB)))) }
    @Test fun servicesSortNumerically() { assertEquals(listOf("192.168.1.2","192.168.1.10"),sortedServices(listOf(hit("192.168.1.10",kind=ScanKind.WEB),hit(kind=ScanKind.WEB),hit()),true).map { it.ip }) }

    @Test fun onlyProxmoxVeAndPdmAreConnectable() {
        val hit = { k: ScanKind -> ScanHit("192.0.2.10", 8006, true, k) }
        assertEquals(listOf(ScanKind.PVE, ScanKind.PDM), ScanKind.entries.filter { scanConnectable(hit(it)) })
    }
}
