package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Owner decision B (2026-11-16): an approval remembers the server's public key (SPKI SHA-256) as well as the exact leaf.
 * TLS still requires the exact leaf; the key only lets the desk say "renewed with the same key".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyMemoryTest {
    private val origin = "https://pve.example.test:8006"
    private val a = "ab".repeat(32)
    private val b = "cd".repeat(32)
    private val keyA = "11".repeat(32)
    private val keyB = "22".repeat(32)

    private fun temp(): Path = Files.createTempDirectory("ring-key-memory")

    // ---- certificates.json version 2 ----

    @Test fun aVersionOneFileIsReadUnchangedAndBecomesVersionTwoOnTheNextSaveWithEveryEntry() {
        val dir = temp()
        try {
            val file = dir.resolve("certificates.json")
            val v1 = """{"version":1,"certificates":[{"origin":"$origin","pin":"$a"},{"origin":"https://pve-b.example.test:8006","pin":"$b"}]}""" + "\n"
            Files.writeString(file, v1)
            val store = CertificateTrustStore(file)
            val loaded = store.load()
            assertEquals(listOf(ApprovedCertificate(origin, a), ApprovedCertificate("https://pve-b.example.test:8006", b)), loaded)
            assertEquals(v1, Files.readString(file), "reading never rewrites the file")
            store.save(loaded.map { if (it.origin == origin) it.copy(spki = keyA) else it })
            val written = Files.readString(file)
            assertTrue(written.startsWith("""{"version":2,"""), written)
            assertEquals(listOf(ApprovedCertificate(origin, a, keyA), ApprovedCertificate("https://pve-b.example.test:8006", b)), CertificateTrustStore(file).load())
            assertEquals("""{"version":2,"certificates":[{"origin":"$origin","pin":"$a","spki":"$keyA"},{"origin":"https://pve-b.example.test:8006","pin":"$b"}]}""" + "\n", written)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aNewerSchemaIsRefusedAsWrittenByANewerRingmasterAndLeftUntouched() {
        val dir = temp()
        try {
            val file = dir.resolve("certificates.json")
            val v3 = """{"version":3,"certificates":[],"future":true}"""
            Files.writeString(file, v3)
            var told: String? = null
            val store = CertificateTrustStore(file).apply { onNewerSchema = { told = it } }
            val refused = assertFailsWith<CertificateTrustStoreException> { store.load() }
            assertTrue("newer RingMASTER" in refused.message!!); assertNotNull(told)
            assertFailsWith<CertificateTrustStoreException> { store.save(listOf(ApprovedCertificate(origin, a))) }
            assertEquals(v3, Files.readString(file))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun theStrictSchemaStillRefusesWhatEachVersionDoesNotHold() {
        val dir = temp()
        try {
            val file = dir.resolve("certificates.json")
            for (bad in listOf(
                """{"version":1,"certificates":[{"origin":"$origin","pin":"$a","spki":"$keyA"}]}""", // v1 never had keys
                """{"version":2,"certificates":[{"origin":"$origin","pin":"$a","other":"x"}]}""",
                """{"version":2,"certificates":[{"origin":"$origin","pin":"$a","spki":"not-hex"}]}""",
                """{"version":2,"certificates":[{"origin":"$origin","spki":"$keyA"}]}""",
                """{"version":2,"certificates":[{"origin":"$origin","pin":"$a","spki":7}]}""",
            )) {
                Files.writeString(file, bad)
                assertFailsWith<CertificateTrustStoreException>(bad) { CertificateTrustStore(file).load() }
                assertEquals(bad, Files.readString(file))
            }
            assertFailsWith<IllegalArgumentException> { ApprovedCertificate(origin, a, "XY") }
        } finally { dir.toFile().deleteRecursively() }
    }

    // ---- profiles.json version 2 ----

    @Test fun profilesStayVersionOneWithoutKeysAndBecomeVersionTwoWithOne() {
        val id = UUID.randomUUID().toString()
        val plain = ConnectionProfile(id, "Lab", DIRECT_PVE_KIND, origin, "root@pam", a)
        assertTrue(encodeProfiles(listOf(plain)).startsWith("""{"version":1,"""), "an older build can still read a profile set without keys")
        val keyed = plain.copy(sha256Spki = keyA)
        val text = encodeProfiles(listOf(keyed))
        assertTrue(text.startsWith("""{"version":2,"""), text)
        assertEquals(listOf(keyed), decodeProfiles(text))
        assertEquals(listOf(plain), decodeProfiles(encodeProfiles(listOf(plain))))
        assertTrue(keyed.definesSameConnection(plain), "learning the key does not redefine the connection")
        // A version 1 document never carried a key; a key without a pin is meaningless.
        assertFailsWith<ProfileStoreException> { decodeProfiles(text.replace("\"version\":2", "\"version\":1")) }
        assertFailsWith<IllegalArgumentException> { ConnectionProfile(id, "Lab", DIRECT_PVE_KIND, origin, "root@pam", null, keyA) }
        assertFailsWith<ProfileStoreException> { decodeProfiles(text.replace(keyA, "zz")) }
    }

    @Test fun aNewerProfileDocumentIsLeftUntouched() {
        val dir = temp()
        try {
            val file = dir.resolve("profiles.json")
            val v3 = """{"version":3,"profiles":[]}"""
            Files.writeString(file, v3)
            var told: String? = null
            val store = ProfileStore(file).apply { onNewerSchema = { told = it } }
            assertEquals(emptyList(), store.load()); assertNotNull(told)
            assertFailsWith<ProfileStoreException> { store.save(emptyList()) }
            assertEquals(v3, Files.readString(file))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aDraftKeepsItsKeyOnlyWhileThePinStillSpellsIt() {
        val id = UUID.randomUUID().toString()
        val draft = ProfileDraft(id, "Lab", origin, "root@pam", a.uppercase().chunked(2).joinToString(":"), key = CertificateKey(a, keyA))
        assertEquals(keyA, profileFromDraft(draft).sha256Spki)
        assertNull(profileFromDraft(draft.copy(sha256Pin = b)).sha256Spki, "a typed pin drops the key of another one")
        assertNull(profileFromDraft(draft.copy(sha256Pin = "")).sha256Spki)
    }

    // ---- the probe ----

    @Test fun theCertificateCheckReadsFingerprintKeySubjectIssuerAndDates() = TrustFixtures.selfSigned("pve", "-ext", "SAN=dns:pve.example.test,ip:127.0.0.1").use { keys ->
        FixtureHttps(keys.serverContext()).use { server ->
            val read = assertNotNull(runBlocking { probeTlsCertificate(server.address) })
            val certificate = keys.certificate()
            assertEquals(keys.pin(), read.fingerprint); assertEquals(keys.spki(), read.spki)
            assertEquals("CN=pve.example.test", read.subject); assertEquals("CN=pve.example.test", read.issuer)
            assertEquals(certificate.notBefore.toInstant(), read.notBefore); assertEquals(certificate.notAfter.toInstant(), read.notAfter)
            assertEquals(listOf("pve.example.test", "127.0.0.1"), read.names)
            assertTrue(read.validAt(Instant.now())); assertFalse(read.validAt(read.notAfter.plusSeconds(1)))
            // The old function is a thin wrapper with its old spelling.
            assertEquals(keys.pin().chunked(2).joinToString(":") { it.uppercase() }, runBlocking { probeTlsFingerprint(server.address) })
            assertEquals(0, server.requests.get(), "reading a certificate sends no request")
        }
    }

    @Test fun aRenewalWithTheSameKeyIsSeenAsSuchAndANewKeyIsNot() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        val first = ProbedCertificate.of(keys.certificate())
        keys.reissue("-ext", "SAN=ip:127.0.0.1")
        val renewed = ProbedCertificate.of(keys.certificate())
        assertNotEquals(first.fingerprint, renewed.fingerprint, "a new certificate")
        assertEquals(first.spki, renewed.spki, "the same key")
        TrustFixtures.selfSigned("pve").use { other -> assertNotEquals(first.spki, ProbedCertificate.of(other.certificate()).spki) }
    }

    // ---- the desk ----

    private fun TestScope.desk(): Triple<ProfileDesk, Workspace, Path> {
        val root = temp()
        val gateway = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(0, emptyList())
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        }
        val workspace = Workspace(this, gateway)
        return Triple(ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json"))), workspace, root)
    }

    private fun probed(pin: String, key: String) = ProbedCertificate(pin, key, "CN=pve.example.test", "CN=pve.example.test",
        Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2028-11-01T00:00:00Z"))

    @Test fun anApprovalInTheConnectFormRecordsTheKeyAndAChangedCertificateWithTheSameKeyIsADistinctState() = runTest {
        val (desk, workspace, root) = desk()
        try {
            desk.router = { _, _ -> }
            desk.setManualSecret("secret")
            val review = desk.manualTrust(origin, a, probed(a, keyA))
            assertEquals(keyA, review.observedSpki)
            assertTrue(desk.connectManual(origin, "root@pam", "", passwordLogin = true, observed = a, approval = review))
            assertEquals(ApprovedCertificate(origin, a, keyA), CertificateTrustStore(root.resolve("certificates.json")).load().single())
            val renewed = desk.manualTrust(origin, b, probed(b, keyA))
            assertTrue(renewed.changed); assertTrue(renewed.sameKey); assertFalse(renewed.keyChanged); assertTrue(renewed.canApprove)
            val replaced = desk.manualTrust(origin, b, probed(b, keyB))
            assertTrue(replaced.changed); assertFalse(replaced.sameKey); assertTrue(replaced.keyChanged)
            val unread = desk.manualTrust(origin, b)
            assertTrue(unread.changed); assertFalse(unread.sameKey); assertFalse(unread.keyChanged, "no key read: neither same nor different")
            // A certificate read for another leaf is not attached to this one.
            assertNull(desk.manualTrust(origin, b, probed(a, keyA)).certificate)
            // The same-key state is not trust: ordinary Connect still refuses the changed leaf.
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual(origin, "root@pam", "", passwordLogin = true, observed = b))
            assertTrue(desk.connectManual(origin, "root@pam", "", passwordLogin = true, observed = b, approval = renewed))
            assertEquals(ApprovedCertificate(origin, b, keyA), CertificateTrustStore(root.resolve("certificates.json")).load().single())
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun aSavedProfileApprovalRecordsTheKeyToo() = runTest {
        val (desk, workspace, root) = desk()
        try {
            desk.router = { _, _ -> }
            desk.beginCreate(); desk.updateDraft("Lab", origin, "root@pam", ""); assertTrue(desk.saveDraft(passwordLogin = true))
            desk.setProfileSecret("secret")
            val review = desk.manualTrust(origin, a, probed(a, keyA))
            assertTrue(desk.connectSelected(observed = a, approval = review))
            assertEquals(ApprovedCertificate(origin, a, keyA), CertificateTrustStore(root.resolve("certificates.json")).load().single())
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun theEditorsUseThisCertificateFillsThePinAndKeyAndSaveIsTheApproval() = runTest {
        val (desk, workspace, root) = desk()
        try {
            desk.beginCreate(); desk.updateDraft("Lab", origin, "root@pam", a); assertTrue(desk.saveDraft(passwordLogin = true))
            desk.beginEdit()
            desk.useCertificateInDraft(probed(b, keyB))
            assertEquals(b, desk.state.value.draft.sha256Pin)
            assertEquals(a, ProfileStore(root.resolve("profiles.json")).load().single().sha256Pin, "nothing saved before Save")
            assertTrue(desk.saveDraft(passwordLogin = true))
            val saved = ProfileStore(root.resolve("profiles.json")).load().single()
            assertEquals(b, saved.sha256Pin); assertEquals(keyB, saved.sha256Spki)
            assertEquals(keyB, desk.manualTrust(origin).knownKeys[b], "profile keys take part in trust resolution")
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun aPinApprovedWithoutAKeyGetsItAfterTheNextSuccessfulPinnedConnection() = runTest {
        val (desk, workspace, root) = desk()
        try {
            desk.router = { _, _ -> }
            val pin = "ef".repeat(32); val key = "33".repeat(32)
            CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin, pin)))
            desk.beginCreate(); desk.updateDraft("Lab", origin, "root@pam", pin); assertTrue(desk.saveDraft(passwordLogin = true))
            assertTrue(Files.readString(root.resolve("profiles.json")).startsWith("""{"version":1,"""))
            recordPinnedKey(pin, TrustFixtures.selfSigned("pve").use { it.certificate() }.also { /* any leaf */ })
            val leafKey = pinnedKeyFor(pin)!!
            desk.setManualSecret("secret")
            assertTrue(desk.connectManual(origin, "root@pam", "", passwordLogin = true))
            desk.onWorkspace(); assertNull(CertificateTrustStore(root.resolve("certificates.json")).load().single().spki, "not before the first read")
            runCurrent()
            desk.onWorkspace()
            assertEquals(leafKey, CertificateTrustStore(root.resolve("certificates.json")).load().single().spki)
            assertEquals(leafKey, ProfileStore(root.resolve("profiles.json")).load().single().sha256Spki)
            assertNotEquals(key, leafKey)
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun thePinnedHandshakeRecordsTheLeafKey() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        FixtureHttps(keys.serverContext()).use { server ->
            runCatching { PveClient().overview(server.address, "root@pam!audit", "token-secret", keys.pin()) }
            assertEquals(keys.spki(), pinnedKeyFor(keys.pin()))
        }
    }
}
