package local.ringleader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class DiagnosticBundleTest {

    @Test
    fun reportCapturesSystemFactsAndSerializesToJson() = runBlocking {
        val report = createDiagnosticReport(
            appVersion = "0.26.0",
            renderApi = "Direct3D",
            sessions = listOf(
                DiagnosticSessionInfo(
                    address = "192.168.20.44:8006",
                    authKind = "Password",
                    connected = false,
                    overviewLoaded = false,
                    lastError = "LoginRefusedException",
                    routeInterface = "Ethernet",
                    routeGateway = "On-link",
                    routeKind = "Direct",
                    isVpn = false,
                )
            ),
            targetAddresses = emptyList(),
            recentTraceLimit = 10,
        )

        assertEquals("0.26.0", report.appVersion)
        assertTrue(report.platform.isNotBlank())
        assertTrue(report.osName.isNotBlank())
        assertTrue(report.javaVersion.isNotBlank())
        assertEquals("Direct3D", report.renderApi)
        assertEquals(1, report.sessions.size)
        assertEquals("Password", report.sessions.first().authKind)

        val json = report.toJsonString()
        assertTrue(json.contains("\"appVersion\": \"0.26.0\""))
        assertTrue(json.contains("\"osName\": \"${report.osName}\""))
        assertTrue(json.contains("\"authKind\": \"Password\""))
        assertTrue(json.contains("\"lastError\": \"LoginRefusedException\""))
        assertFalse(json.contains("secret") || json.contains("password") && !json.contains("authKind"))
    }

    @Test
    fun reachabilityFailsGracefullyOnClosedPort() = runBlocking {
        // Find a closed port
        val s = ServerSocket(0)
        val port = s.localPort
        s.close()

        val reachability = probeDiagnosticReachability("127.0.0.1:$port", timeoutMs = 500)
        assertFalse(reachability.tcpSuccess)
        assertNotNull(reachability.tcpError)
        assertFalse(reachability.tlsHandshakeSuccess)
        assertNull(reachability.httpStatusCode)
    }
}
