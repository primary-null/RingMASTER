package local.ringleader.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Field report, 0.25.9: a second password sign-in, opened from the Connect form while a first password session stays
 * live, answered "The server refused this user name or password." This drives the window's own path ([Sessions] with
 * live [Workspace]s, the [ProfileDesk] bound to it, Trust and connect) against two loopback HTTPS servers that have
 * different self-signed certificates and different passwords, and records what each server receives.
 */
class TwoSessionSignInTest {
    /** One request as the fake server saw it. Fields are null when absent. */
    private data class Seen(val path: String, val method: String, val username: String?, val password: String?, val cookie: String?)

    /** A fake Proxmox VE server: one account, its own certificate, its own ticket value. */
    private class FakePve(val name: String, val user: String, val password: String, dir: Path) : AutoCloseable {
        val seen = CopyOnWriteArrayList<Seen>()
        val pin: String
        private val server: HttpsServer
        val origin: String
        val ticket = "ticket-$name"
        val cookie = "PVEAuthCookie=ticket-$name"

        init {
            val key = dir.resolve("$name.p12")
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=$name.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
                "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            check(process.waitFor() == 0)
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(name).encoded).joinToString("") { "%02x".format(it) }
            server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.httpsConfigurator = HttpsConfigurator(context)
            server.executor = Executors.newCachedThreadPool { Thread(it, "fake-pve-$name").apply { isDaemon = true } }
            server.createContext("/") { e -> try { respond(e) } finally { e.close() } }
            server.start()
            origin = "https://127.0.0.1:${server.address.port}"
        }

        private fun respond(e: HttpExchange) {
            val path = e.requestURI.path
            val cookie = e.requestHeaders.getFirst("Cookie")
            val (status, body) = if (path == "/api2/json/access/ticket") {
                val form = e.requestBody.bufferedReader().readText().split('&').filter { it.isNotEmpty() }.associate {
                    val parts = it.split('=', limit = 2)
                    URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
                }
                seen += Seen(path, e.requestMethod, form["username"], form["password"], cookie)
                if (form["username"] == user && form["password"] == password)
                    200 to """{"data":{"ticket":"$ticket","CSRFPreventionToken":"csrf-$name","username":"$user"}}"""
                else 401 to """{"data":null}"""
            } else {
                e.requestBody.readAllBytes()
                seen += Seen(path, e.requestMethod, null, null, cookie)
                when {
                    cookie != this.cookie -> 401 to """{"data":null}"""
                    path == "/api2/json/cluster/resources" -> 200 to
                        """{"data":[{"type":"node","node":"$name-node","status":"online","id":"node/$name-node"},{"type":"qemu","vmid":100,"name":"$name-vm","node":"$name-node","status":"running","id":"qemu/100"}]}"""
                    else -> 200 to """{"data":[]}"""
                }
            }
            val bytes = body.toByteArray()
            e.sendResponseHeaders(status, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }

        val logins get() = seen.filter { it.path == "/api2/json/access/ticket" }
        val reads get() = seen.filter { it.path != "/api2/json/access/ticket" }

        override fun close() { server.stop(0) }
    }

    private class Fixture(val ui: ExecutorCoroutineDispatcher, val scope: CoroutineScope, val sessions: Sessions, val desk: ProfileDesk,
                          val a: FakePve, val b: FakePve, val dir: Path) {
        val state get() = sessions.state.value
        val active get() = state.active.workspace
        fun <T> onUi(block: suspend () -> T): T = runBlocking(ui) { block() }
    }

    private val passwordA = "alpha password & A"
    private val passwordB = "bravo-password=B"

    private fun twoServers(test: Fixture.() -> Unit) {
        val dir = Files.createTempDirectory("two-session-sign-in")
        val ui = Executors.newSingleThreadExecutor { Thread(it, "fake-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        FakePve("alpha", "root@pam", passwordA, dir).use { a ->
            FakePve("bravo", "root@pam", passwordB, dir).use { b ->
                // The window's construction (Main.kt): live gateways, the desk bound to the sessions.
                val (sessions, desk) = runBlocking(ui) {
                    val sessions = Sessions(scope) { Workspace(scope) }
                    sessions to ProfileDesk(sessions.state.value.active.workspace, ProfileStore(dir.resolve("profiles.json"))).also(sessions::bind)
                }
                val fixture = Fixture(ui, scope, sessions, desk, a, b, dir)
                try { fixture.test() }
                finally {
                    runBlocking(ui) { desk.close(); sessions.close() }
                    scope.cancel(); ui.close()
                }
            }
        }
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private suspend fun Workspace.settles(): WorkspaceState = withTimeout(15_000) {
        state.first { !it.loading && (it.overview != null || it.error != null) }
    }

    /** Trust and connect on [server] with [password] typed in the Connect form, as the button does it. */
    private fun Fixture.trustAndConnect(server: FakePve, password: String, user: String = "root@pam"): Workspace = onUi {
        desk.setManualSecret(password)
        val review = desk.manualTrust(server.origin, server.pin)
        assertTrue(review.needsApproval, "a certificate nobody approved yet")
        assertTrue(review.known.isEmpty())
        assertTrue(desk.connectManual(server.origin, user, "", passwordLogin = true, observed = server.pin, approval = review), desk.state.value.formError)
        active
    }

    private fun Fixture.openA(): Workspace {
        val workspace = trustAndConnect(a, passwordA)
        val settled = onUi { workspace.settles() }
        assertNotNull(settled.overview, settled.error)
        assertEquals("alpha-vm", settled.overview.guests.single().name)
        return workspace
    }

    private fun Fixture.assertAUntouched(wsA: Workspace) {
        val now = wsA.state.value
        assertTrue(now.connected, "session A stays connected")
        assertNotNull(now.overview)
        assertNull(now.error, "session A shows no error")
        assertTrue(sessionOpen(now))
        assertTrue(state.open.any { it.workspace === wsA }, "session A is still open")
        assertEquals(listOf(Seen("/api2/json/access/ticket", "POST", "root@pam", passwordA, null)), a.logins, "A signed in once, with its own password")
        assertTrue(a.reads.isNotEmpty() && a.reads.all { it.cookie == a.cookie }, "every read on A carries A's own ticket")
        assertTrue(a.seen.none { it.password == passwordB || it.cookie == b.cookie }, "nothing of B reaches A")
    }

    @Test fun aSecondPasswordSessionSignsInWithItsOwnPasswordBesideTheFirst() = twoServers {
        val wsA = openA()
        val wsB = trustAndConnect(b, passwordB)
        assertNotSame(wsA, wsB, "B opens in a session of its own")
        // A keeps working while B signs in.
        onUi { wsA.refresh(force = true) }
        val settledB = onUi { wsB.settles() }
        assertNotNull(settledB.overview, settledB.error)
        assertEquals("bravo-vm", settledB.overview.guests.single().name)
        assertEquals(listOf(Seen("/api2/json/access/ticket", "POST", "root@pam", passwordB, null)), b.logins, "B got exactly the typed user and B's password, once")
        assertTrue(b.reads.all { it.cookie == b.cookie })
        assertTrue(b.seen.none { it.password == passwordA || it.cookie == a.cookie }, "nothing of A reaches B")
        assertEquals(2, state.open.size)
        assertTrue(state.open.all { sessionOpen(it.workspace.state.value) })
        assertSame(wsB, active)
        // A still serves a later refresh with its own cookie, and never signs in again.
        val before = a.reads.size
        onUi { wsA.refresh(force = true) }
        onUi { withTimeout(10_000) { while (a.reads.size <= before) delay(20) } }
        onUi { wsA.settles() }
        assertAUntouched(wsA)
        assertEquals("alpha-vm", wsA.state.value.overview!!.guests.single().name)
        assertEquals(2, CertificateTrustStore(dir.resolve("certificates.json")).load().size, "both approvals remembered")
    }

    @Test fun aWrongPasswordOnTheSecondServerLeavesTheFirstAloneAndTheRightOneThenConnects() = twoServers {
        val wsA = openA()
        val wsB = trustAndConnect(b, "not B's password")
        val refused = onUi { withTimeout(15_000) { wsB.state.first { !it.connected } } }
        assertEquals("The server refused this user name or password.", refused.error)
        assertFalse(sessionOpen(refused))
        onUi { delay(300) }
        assertEquals(listOf(Seen("/api2/json/access/ticket", "POST", "root@pam", "not B's password", null)), b.logins, "one ticket request, never retried")
        assertTrue(b.reads.isEmpty())
        assertFalse(desk.hasManualSecret(), "the typed secret is gone after the attempt")
        assertFalse(desk.state.value.manualSecretPresent)
        assertSame(wsB, active, "the refused session stays in view with its message")
        assertEquals(2, state.open.size)
        assertAUntouched(wsA)

        // The form after the refusal: the scan's certificate is now known, so no approval is asked and Connect pins it.
        val retry = onUi {
            desk.setManualSecret(passwordB)
            val trust = desk.manualTrust(b.origin, b.pin)
            assertFalse(trust.needsApproval); assertEquals(listOf(b.pin), trust.known)
            assertTrue(desk.connectManual(b.origin, "root@pam", "", passwordLogin = true, observed = b.pin), desk.state.value.formError)
            active
        }
        assertSame(wsB, retry, "the refused session is reused, nothing else is replaced")
        val settled = onUi { retry.settles() }
        assertNotNull(settled.overview, settled.error)
        assertEquals(listOf("not B's password", passwordB), b.logins.map { it.password })
        assertEquals(2, state.open.size)
        assertTrue(state.open.all { sessionOpen(it.workspace.state.value) })
        assertAUntouched(wsA)
    }

    @Test fun normalTlsToAnUnknownSelfSignedSecondServerFailsOnTheCertificateBeforeAnyLogin() = twoServers {
        val wsA = openA()
        val wsB = onUi {
            desk.setManualSecret(passwordB)
            assertTrue(desk.manualTrust(b.origin).known.isEmpty())
            // "Connect with normal TLS": no observed certificate, no approval, nothing known, no pasted fingerprint.
            assertTrue(desk.connectManual(b.origin, "root@pam", "", passwordLogin = true, observed = null, approval = null), desk.state.value.formError)
            active
        }
        assertNotSame(wsA, wsB)
        val failed = onUi { wsB.settles() }
        // Today's state for this case (field trace: connected true, overview not loaded, certificate error).
        assertTrue(failed.connected)
        assertNull(failed.overview)
        // Since the failure taxonomy: the cause is named, from the JDK's own exception chain against a real server.
        assertEquals(NetworkFailure.Untrusted, failed.failure)
        assertEquals(NetworkFailure.Untrusted.sentence, failed.error)
        assertFalse(sessionOpen(failed))
        assertEquals(emptyList(), b.seen, "no HTTP request, so no password, reached B")
        assertAUntouched(wsA)
    }

    @Test fun anApprovalSurvivesARefusedSignInOnTheSecondServer() = twoServers {
        val wsA = openA()
        val wsB = trustAndConnect(b, "not B's password")
        onUi { withTimeout(15_000) { wsB.state.first { !it.connected } } }
        val trust = onUi { desk.manualTrust(b.origin, b.pin) }
        assertEquals(listOf(b.pin), trust.known, "the approved certificate is remembered")
        assertFalse(trust.needsApproval, "no second approval is asked")
        assertFalse(trust.changed)
        // With the certificate known, the form never offers "Connect with normal TLS" (DeskScreen shows it only while
        // nothing is known), and even that path would pin: the core re-reads trust on every connect.
        val unpinnedAttempt = onUi {
            desk.setManualSecret(passwordB)
            assertTrue(desk.connectManual(b.origin, "root@pam", "", passwordLogin = true, observed = null, approval = null), desk.state.value.formError)
            active
        }
        val settled = onUi { unpinnedAttempt.settles() }
        assertNotNull(settled.overview, settled.error)
        assertEquals(listOf("not B's password", passwordB), b.logins.map { it.password }, "the second attempt reached B over the pinned certificate")
        val remembered = CertificateTrustStore(dir.resolve("certificates.json")).load().associate { it.origin to it.pin }
        assertEquals(mapOf(certificateOrigin(a.origin) to a.pin, certificateOrigin(b.origin) to b.pin), remembered)
        assertAUntouched(wsA)
    }
}
