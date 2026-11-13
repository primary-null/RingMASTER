package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Explicit local, read-only opt-in. Ordinary tests never inspect the host or resolve an external name. */
class ConnectionRouteHostProbeTest {
    @Test fun inspectSelectedHostRoute() {
        val origin = System.getenv("RINGMASTER_ROUTE_HOST_PROBE") ?: return
        val target = assertNotNull(routeTarget(origin))
        val addresses = javaResolve(target.host)
        val route = assertNotNull(localRouteProbe()).route(addresses.first().hostAddress)
        assertNotNull(route.iface, "The local OS must report an interface for this acceptance target")
        val vpn = VpnInventory(localVpnBackend()?.snapshot())
        val result = classifyConnectionRoute(target, Instant.now(), javaProxyFor(java.net.URI(target.origin)),
            Result.success(addresses), Result.success(route), vpn)
        val receipt = Path.of("build", "route-host-probe.txt")
        Files.createDirectories(receipt.parent)
        // Keep workstation identities out of the receipt and test output.
        Files.writeString(receipt, "kind=${result.kind}\nmedium=${result.medium}\nvpnComplete=${result.vpnComplete}\ninterfacePresent=${result.interfaceName != null}\n")
    }
}
