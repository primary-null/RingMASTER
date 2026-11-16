package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Network audit 2026-11-16: [probeDiagnosticReachability] reads an unauthenticated `GET /api2/json/version` with a 3000 ms read
 * timeout. Proxmox holds every unauthorized API answer for 3 s on purpose (pve-http-server AnyEvent.pm:
 * "always delay unauthorized calls by 3 seconds"), so a healthy server lands exactly on the timeout. A field bundle from
 * 0.25.9 shows both outcomes for two healthy servers: one probe got 401, the other "SocketTimeoutException: Read timed out".
 * On an authorized lab server the 401 arrived 3.002 to 3.005 s after the TLS handshake, five times out of five.
 */
class NetAuditDiagnosticTest {
    private fun delayedUnauthorized(delayMs: Long): (SeenHttp) -> String = { seen ->
        if ("/api2/json/version" in seen.requestLine) {
            Thread.sleep(delayMs)
            "HTTP/1.1 401 No ticket\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }

    /** N-02, fixed: Proxmox delays an unauthorized answer by 3 s on purpose; the probe's default wait is now longer than that. */
    @Test fun aServerThatDelaysItsUnauthorizedAnswerByThreeSecondsIsReportedReachableAtTheDefaultTimeout() = runBlocking {
        RawHttpServer(NetAuditCerts.loopback.server, reply = delayedUnauthorized(3100)).use { server ->
            val address = "https://127.0.0.1:${server.port}"
            val atDefault = probeDiagnosticReachability(address)
            assertTrue(atDefault.tcpSuccess)
            assertTrue(atDefault.tlsHandshakeSuccess)
            assertEquals(401, atDefault.httpStatusCode)
            assertNull(atDefault.httpError)
            // The same server, asked to wait long enough, is plainly healthy: 401 means "reachable, wants a login".
            val patient = probeDiagnosticReachability(address, 8000)
            assertEquals(401, patient.httpStatusCode)
            assertNull(patient.httpError)
        }
    }

    @Test fun theRootPageIsNotDelayedSoItCouldAnswerWithinTheDefaultTimeout() = runBlocking {
        RawHttpServer(NetAuditCerts.loopback.server, reply = delayedUnauthorized(3100)).use { server ->
            // Pins the recommendation: GET / answers at once on Proxmox VE (35 to 65 ms measured), so a 3 s budget is ample for it.
            val conn = (java.net.URI("https://127.0.0.1:${server.port}/").toURL().openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                sslSocketFactory = NetAuditCerts.loopback.trusting.socketFactory
                connectTimeout = 3000; readTimeout = 3000
            }
            assertEquals(200, conn.responseCode)
        }
    }
}
