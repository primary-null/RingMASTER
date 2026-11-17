package local.ringleader.core

import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Owner decision A (2026-11-16): normal TLS trusts the bundled Java roots AND this computer's store. The "operating system
 * store" here is always a test store handed to [PlatformTrust]; no global JVM setting changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformTrustTest {
    private fun osStore(vararg certificates: java.security.cert.X509Certificate) = { OsTrustStore(certificates.toList(), "test store") }
    private val noOsStore = { OsTrustStore(emptyList(), "", "none") }

    @Test fun aLeafSignedByACaInThisComputersStoreIsAcceptedAndLabelled() = TrustFixtures.caSigned().use { signed ->
        FixtureHttps(signed.leaf.serverContext()).use { server ->
            val trusting = PlatformTrust(osStore(signed.ca))
            runBlocking {
                assertEquals(NormalTls.Accepted, probeTlsValidation(server.address, 4000, trusting.sslContext()))
                assertEquals(NormalTls.Refused, probeTlsValidation(server.address, 4000, PlatformTrust(noOsStore).sslContext()),
                    "the bundled Java roots alone do not know a private CA")
            }
            assertEquals(TrustSource.ThisComputer, trusting.sourceFor(server.address), "recorded during the handshake itself")
            assertEquals(0, server.requests.get(), "the probe sends no request")
            // A real request with the default host name verifier, as PveClient.open makes one without a pin.
            assertEquals(200, get(server.address, trusting))
            assertEquals(1, server.requests.get())
            assertEquals(TrustSource.ThisComputer, trusting.sourceFor(server.address))
        }
    }

    @Test fun theJavaRootsAnswerFirstAndSayWhichStoreItWas() = TrustFixtures.caSigned().use { signed ->
        FixtureHttps(signed.leaf.serverContext()).use { server ->
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", signed.ca) }
            val trust = PlatformTrust(noOsStore, javaRoots = roots)
            assertEquals(200, get(server.address, trust))
            assertEquals(TrustSource.JavaRoots, trust.sourceFor(server.address))
            assertNull(PlatformTrust(noOsStore).sourceFor(server.address), "a fresh instance has seen nothing")
        }
    }

    @Test fun theHostNameCheckStaysOnForThisComputersStore() = TrustFixtures.caSigned(san = "dns:other.example.test").use { signed ->
        FixtureHttps(signed.leaf.serverContext()).use { server ->
            val trust = PlatformTrust(osStore(signed.ca))
            runBlocking { assertEquals(NormalTls.Refused, probeTlsValidation(server.address, 4000, trust.sslContext())) }
            val failure = assertFailsWith<SSLException> { get(server.address, trust) }
            // The chain is trusted by this computer; the refusal that reaches the user is the name, not "untrusted".
            assertFalse(chainProblem(failure), "the more telling refusal is thrown: $failure")
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun aSelfSignedServerStaysRefused() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        FixtureHttps(keys.serverContext()).use { server ->
            val failure = assertFailsWith<SSLException> { get(server.address, PlatformTrust(noOsStore)) }
            assertTrue(chainProblem(failure))
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun thisComputersStoreIsReadAgainAfterAFewMinutesOnly() {
        val reads = AtomicInteger()
        var now = 1_000_000L
        val trust = PlatformTrust({ reads.incrementAndGet(); OsTrustStore(emptyList(), "", null) }, clock = { now })
        val first = trust.sslContext()
        assertSame(first, trust.sslContext())
        now += 4 * 60_000L
        assertSame(first, trust.sslContext()); assertEquals(1, reads.get())
        now += 61_000L
        assertNotSame(first, trust.sslContext()); assertEquals(2, reads.get(), "update-ca-certificates is picked up without a restart")
        // A clock that went backwards is not a reason to keep an old store for ever.
        now -= 3_600_000L
        trust.sslContext(); assertEquals(3, reads.get())
    }

    @Test fun aFailingStoreDegradesToTheJavaRootsWithAReason() {
        val trust = PlatformTrust({ error("boom") })
        trust.sslContext()
        assertTrue(assertNotNull(trust.osStore().problem).contains("bundled roots"))
    }

    @Test fun windowsWithoutTheModuleDegradesWithARecordedReason() {
        val missing = readOsTrustStore("Windows 11", windowsStore = { throw KeyStoreException("Windows-ROOT not found") })
        assertTrue(missing.certificates.isEmpty())
        assertEquals("Windows certificate store", missing.source)
        assertTrue(assertNotNull(missing.problem).contains("missing from this build"), missing.problem)
        val broken = readOsTrustStore("Windows 11", windowsStore = { throw java.io.IOException("access denied") })
        assertTrue(assertNotNull(broken.problem).contains("could not be read"))
    }

    @Test fun theRealWindowsStoreLoadsInThisJvm() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        // The test JVM is a full JDK, so jdk.crypto.mscapi is present: the store must load and hold roots.
        val store = readOsTrustStore()
        assertNull(store.problem, store.problem)
        assertTrue(store.certificates.size > 10, "only ${store.certificates.size} roots")
    }

    @Test fun linuxReadsTheFirstBundleThatExistsAndSkipsBrokenEntries() = TrustFixtures.caSigned().use { a ->
        TrustFixtures.selfSigned("pve").use { b ->
            val dir = Files.createTempDirectory("ring-bundle")
            try {
                val bundle = dir.resolve("ca-certificates.crt")
                Files.writeString(bundle, "# comment\n" + TrustFixtures.pem(a.ca) +
                    "-----BEGIN CERTIFICATE-----\nnot base64 at all!\n-----END CERTIFICATE-----\n" + TrustFixtures.pem(b.certificate()) + "trailing junk")
                val read = readOsTrustStore("Linux", bundles = listOf(dir.resolve("missing.pem"), bundle))
                assertEquals(listOf(a.ca, b.certificate()), read.certificates)
                assertEquals(bundle.toString(), read.source)
                assertTrue(assertNotNull(read.problem).startsWith("1 entries"))
                val none = readOsTrustStore("Linux", bundles = listOf(dir.resolve("missing.pem")))
                assertTrue(none.certificates.isEmpty()); assertNotNull(none.problem)
                val big = dir.resolve("big.pem"); Files.writeString(big, TrustFixtures.pem(a.ca).repeat(4))
                val bounded = readCertificateBundle(big, maxBytes = 1000)
                assertTrue(bounded.certificates.isEmpty()); assertTrue(assertNotNull(bounded.problem).contains("too large"))
                assertEquals(2, readCertificateBundle(big, maxCertificates = 2).certificates.size)
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    @Test fun otherSystemsUseTheBundledRootsOnly() {
        val mac = readOsTrustStore("Mac OS X")
        assertTrue(mac.certificates.isEmpty()); assertNotNull(mac.problem)
    }

    @Test fun theSessionSaysHowTheServerIsTrusted() = runTest {
        val overview = Overview(1, listOf(Guest("https://pve.example.test:8006", 100, "alpha", "node-a", "VM", "running")))
        val gateway = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = overview
            override suspend fun details(connection: Connection, guest: Guest) = sampleDetails(guest)
        }
        val workspace = Workspace(this, gateway, trustSource = { TrustSource.ThisComputer })
        workspace.connect(Connection("https://pve.example.test:8006", "root@pam!read", "secret", ""))
        runCurrent()
        assertEquals(TrustSource.ThisComputer, workspace.state.value.tlsTrust)
        assertEquals("Normal TLS · trusted by this computer", connectionTrustLabel(workspace.state.value))
        workspace.connect(Connection("https://pve.example.test:8006", "root@pam!read", "secret", "ab".repeat(32)))
        runCurrent()
        assertNull(workspace.state.value.tlsTrust, "a pinned connection consults no store")
        assertEquals("Certificate pin", connectionTrustLabel(workspace.state.value))
        workspace.close()
        val public = Workspace(this, gateway, trustSource = { TrustSource.JavaRoots })
        public.connect(Connection("https://pve.example.test:8006", "root@pam!read", "secret", ""))
        runCurrent()
        assertEquals("Normal TLS · public certificate", connectionTrustLabel(public.state.value))
        public.close()
    }

    private fun get(address: String, trust: PlatformTrust): Int {
        val connection = URI.create("$address/api2/json/version").toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = trust.sslContext().socketFactory
        connection.connectTimeout = 4000; connection.readTimeout = 4000
        return try { connection.responseCode } finally { connection.disconnect() }
    }
}
