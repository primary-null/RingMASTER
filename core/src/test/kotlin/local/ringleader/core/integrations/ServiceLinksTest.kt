package local.ringleader.core.integrations

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.ringleader.core.ForgetOnlyVault
import local.ringleader.core.GuestHints
import local.ringleader.core.NoVault
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultStatus
import kotlin.test.*

internal class MemoryVault : SecretVault {
    val entries = HashMap<String, String>(); val labels = HashMap<String, String>()
    override val name = "the test safe"
    override fun status() = VaultStatus.Available
    override fun store(profileId: String, login: String, label: String, secret: CharArray) { entries[profileId] = String(secret); labels[profileId] = label }
    override fun read(profileId: String) = entries[profileId]?.toCharArray()
    override fun forget(profileId: String) { entries.remove(profileId) }
    override fun forgetAll() = entries.clear()
}

/** Links and credentials: the file, the password safe, Test's precise failures and proposals. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceLinksTest {
    private val template = ServiceTemplateParser.parse("t.json", ServiceTemplateParserTest.GOOD)
    private val templates = { id: String -> template.takeIf { it.id == id } }
    private val dir: Path = Files.createTempDirectory("ring-services")
    private val file = dir.resolve("cfg").resolve("services.json")
    private val secret = "KEY-must-never-be-written-9f3"
    private val stats = """{"total": 10, "part": 4, "state": "on", "h": [], "list": []}"""
    private fun service(status: (WireRequest) -> Int = { 200 }) = FakeService { r -> status(r) to if (r.path.contains("/api/ping")) """{"ok": true}""" else stats }
    private fun credential(s: String = secret) = ServiceCredential(emptyMap(), s.toCharArray())
    private val ack = PlainHttpAck.forOrigin(ServiceOrigin.parseOrThrow("http://192.0.2.5:8080"), Instant.EPOCH, "Demo key", "Reads only.")
    private fun draft(origin: String = "http://192.0.2.5:8080", trust: LinkTrust = LinkTrust.PlainHttp(ack)) =
        LinkDraft("demo", "v1", "My demo", origin, GuestRef("Container", 105), trust, params = mapOf("pick" to listOf("abc")))

    private fun TestScope.links(vault: SecretVault, svc: FakeService = service(), proxmox: Set<String> = emptySet()) =
        ServiceLinks(ServiceLinkStore(file), vault, svc, backgroundScope, templates, listOf(template), { Instant.ofEpochMilli(testScheduler.currentTime) },
            StandardTestDispatcher(testScheduler), { proxmox })

    private fun fileText() = if (Files.exists(file)) Files.readString(file) else ""

    @Test fun aLinkRoundTripsWithoutAnySecret() = runTest {
        val l = links(MemoryVault())
        val saved = l.save(draft(), credential(), remember = true).getOrThrow()
        assertFalse(secret in fileText())
        val back = ServiceLinkStore(file).load().single()
        assertEquals(saved, back)
        assertEquals("http://192.0.2.5:8080", back.origin.canonical)
        assertEquals(ack, (back.trust as LinkTrust.PlainHttp).ack)
        assertFalse(back.remembered, "not remembered before a successful read")
        val names = Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("services.json"), names, "the temporary file is gone after the atomic move")
    }

    @Test fun theFileIsOwnerOnly() = runTest {
        links(MemoryVault()).save(draft(), null, false).getOrThrow()
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        } else {
            val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
            assertEquals(1, acl.size, "one entry: the owner"); assertEquals(System.getProperty("user.name").lowercase(), acl.single().principal().name.substringAfterLast('\\').lowercase())
        }
    }

    @Test fun aNewerOrCorruptFileIsLeftUntouched() = runTest {
        Files.createDirectories(file.parent)
        for (text in listOf("""{"version": 2, "links": []}""", "{not json", """{"version": 1, "links": [{"id": "x"}]}""")) {
            Files.writeString(file, text)
            val l = links(MemoryVault())
            assertNotNull(l.blockedReason)
            if (text.contains("2")) assertEquals("services.json was written by a newer RingMASTER and is left untouched.", l.blockedReason)
            assertTrue(l.save(draft(), null, false).isFailure)
            assertEquals(text, Files.readString(file), "never overwritten")
        }
    }

    @Test fun theSecretIsRememberedOnlyAfterTheFirstSuccessfulRead() = runTest {
        val vault = MemoryVault()
        var ok = false
        val l = links(vault, service { if (ok) 200 else 503 })
        val link = l.save(draft(), credential(), remember = true).getOrThrow()
        assertTrue(vault.entries.isEmpty())
        l.setInterest(link.id, Interest.Full); advanceTimeBy(1000); runCurrent()
        assertTrue(vault.entries.isEmpty(), "a failed read stores nothing")
        ok = true; advanceTimeBy(3_600_000); runCurrent()
        assertEquals(secret, vault.entries["service:${link.id}"])
        assertEquals("RingMASTER service \u00b7 Demo \u00b7 My demo", vault.labels["service:${link.id}"])
        assertTrue(ServiceLinkStore(file).load().single().remembered)
        assertFalse(secret in fileText())
        assertTrue(link.id in fileText())
    }

    @Test fun aRememberedSecretIsForgottenWhenAddressTemplateOrTrustChanges() = runTest {
        val vault = MemoryVault()
        val l = links(vault)
        val link = l.save(draft(), credential(), remember = true).getOrThrow()
        l.setInterest(link.id, Interest.Compact); advanceTimeBy(1000); runCurrent()
        assertTrue("service:${link.id}" in vault.entries)
        l.update(link.id, draft().copy(name = "Renamed"), null, false).getOrThrow()
        assertTrue("service:${link.id}" in vault.entries, "a new name keeps it")
        val moved = l.update(link.id, draft(origin = "http://192.0.2.6:8080", trust = LinkTrust.PlainHttp(ack)), null, false).getOrThrow()
        assertFalse("service:${link.id}" in vault.entries, "a new address forgets it")
        assertFalse(moved.remembered)
        assertNull((moved.trust as LinkTrust.PlainHttp).ack, "the acknowledgement belonged to the old address")
    }

    @Test fun unlinkDropsSessionSecretAndSchedule() = runTest {
        val vault = MemoryVault(); val svc = service()
        val l = links(vault, svc)
        val link = l.save(draft(), credential(), remember = true).getOrThrow()
        l.setInterest(link.id, Interest.Full); advanceTimeBy(1000); runCurrent()
        l.unlink(link.id).getOrThrow(); runCurrent()
        assertTrue(vault.entries.isEmpty()); assertTrue(l.list().isEmpty()); assertTrue(ServiceLinkStore(file).load().isEmpty())
        val n = svc.sent.size; advanceTimeBy(3_600_000); runCurrent()
        assertEquals(n, svc.sent.size, "no schedule after unlink")
    }

    @Test fun withoutAUsablePasswordSafeSecretsStayInMemory() = runTest {
        for (vault in listOf(ForgetOnlyVault(MemoryVault()), NoVault("No Secret Service here."))) {
            Files.deleteIfExists(file)
            val l = links(vault)
            assertIs<VaultStatus.Unavailable>(l.vaultStatus)
            val link = l.save(draft(), credential(), remember = true).getOrThrow()
            l.setInterest(link.id, Interest.Compact); advanceTimeBy(1000); runCurrent()
            assertEquals(PanelStatus.Ok, l.panel(link.id)!!.value.status, "the in-memory secret works this run")
            assertFalse(ServiceLinkStore(file).load().single().remembered)
        }
    }

    @Test fun testNamesThePreciseFailure() = runTest {
        fun test(svc: FakeService, c: ServiceCredential? = credential(), d: LinkDraft = draft(), proxmox: Set<String> = emptySet()) =
            links(MemoryVault(), svc, proxmox).test(d, c).problem?.kind
        assertEquals(null, test(service()))
        assertEquals(ProblemKind.NotReachable, test(service().apply { failNext = WireFailure.ConnectionRefused }))
        assertEquals(ProblemKind.NotThisService, test(service { r -> if (r.path.contains("ping")) 404 else 200 }))
        assertEquals(ProblemKind.CredentialRefused, test(service { r -> if (r.path.contains("stats")) 401 else 200 }))
        assertEquals(ProblemKind.Forbidden, test(service { r -> if (r.path.contains("stats")) 403 else 200 }))
        assertEquals(ProblemKind.CredentialNeeded, test(service(), c = null))
        assertEquals(ProblemKind.ProxmoxAddress, test(service(), proxmox = setOf("http://192.0.2.5:8080")))
        assertEquals(ProblemKind.RequestRefused, test(service(), d = draft(trust = LinkTrust.NormalTls)))
        assertEquals(ProblemKind.ApiChanged, test(FakeService { r -> 200 to if (r.path.contains("ping")) """{"ok": true}""" else """{"renamed": 1}""" }))
        val pinChanged = FakeService { 200 to "{}" }.apply { failNext = WireFailure.CertificateChanged }
        assertEquals(ProblemKind.CertificateChanged, test(pinChanged, d = draft("https://192.0.2.5:8443", LinkTrust.Pinned("ab".repeat(32)))))
    }

    @Test fun testFollowsThePlainHttpRuleOnTheRealWire() = runTest {
        ScriptedServer { r -> Reply.json(200, if (r.line.contains("ping")) """{"ok": true}""" else stats) }.use { s ->
            val origin = "http://127.0.0.1:${s.port}"
            val l = ServiceLinks(ServiceLinkStore(null), MemoryVault(), ServiceWire("RingMASTER/0.0-test").asSender(), backgroundScope, templates, listOf(template))
            val without = l.test(draft(origin, LinkTrust.PlainHttp(null)), credential())
            assertEquals(ProblemKind.PlainHttpNeedsAcknowledgement, without.problem?.kind)
            assertTrue(s.received.none { it.header("X-Api-Key") != null }, "the key never left before the acknowledgement")
            val acked = LinkTrust.PlainHttp(PlainHttpAck.forOrigin(ServiceOrigin.parseOrThrow(origin), Instant.EPOCH, "Demo key", "Reads only."))
            val with = l.test(draft(origin, acked), credential())
            assertTrue(with.ok, "${with.problem}"); assertEquals(UnencryptedMark.Unencrypted, with.unencrypted)
            assertEquals(4.0 / 10 * 100, with.state!!.reading("share")!!.number)
        }
    }

    @Test fun proposalsComeFromTheNotesAndWhatAnswered() = runTest {
        val l = links(MemoryVault())
        val notes = "<a href='https://community-scripts.org/scripts/pihole'>Open script page</a><h2>Pi-hole LXC</h2>"
        val p = l.propose(GuestHints("dns1", description = notes), GuestRef("Container", 105), listOf("192.0.2.5")).single()
        assertEquals("demo", p.templateId); assertEquals("http://192.0.2.5:8080", p.origin); assertEquals(100, p.score)
        assertTrue(p.plainHttp, "the credential would cross plain http"); assertFalse(p.fullPower)
        assertTrue(l.propose(GuestHints("web"), null, listOf("192.0.2.5")).isEmpty())
        assertEquals("http://[fd00::5]:8080", l.propose(GuestHints("pihole"), null, listOf("fd00::5")).single().origin)
        assertEquals(95, l.propose(GuestHints("box"), null, listOf("192.0.2.5"), listOf(FoundEndpoint(8080, false, "pihole"))).single().score)
    }

    @Test fun saveRefusesWhatCannotBeStored() = runTest {
        val l = links(MemoryVault())
        assertTrue(l.save(draft().copy(variantId = null), null, false).isFailure, "test first")
        assertTrue(l.save(draft().copy(identity = mapOf("secret" to secret)), null, false).isFailure, "a secret field is never identity")
        assertTrue(l.save(draft().copy(params = mapOf("pick" to listOf("UPPER"))), null, false).isFailure)
        assertTrue(l.save(draft(origin = "https://192.0.2.5"), null, false).isFailure, "plain-http trust on an https address")
        assertFalse(secret in fileText())
    }
}
