package local.ringleader.core.guesthelper

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import local.ringleader.core.AuditCerts
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.integrations.MemoryVault
import local.ringleader.core.integrations.Received
import local.ringleader.core.integrations.Reply
import local.ringleader.core.integrations.ScriptedServer
import local.ringleader.core.integrations.ServiceWire
import local.ringleader.core.integrations.WireLimits
import kotlin.test.*

/**
 * The Engine's client, pairing steps and pairing record against a fake Engine on loopback that speaks the helper's
 * protocol (native/ringmaster-engine/src/server.rs): every step and every refusal.
 */
class GuestHelperTest {
    private companion object {
        /** Made once: keytool takes about two seconds each time. */
        val store by lazy { AuditCerts.generate("engine") }
        val other by lazy { AuditCerts.generate("other") }
    }
    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val otherPin = AuditCerts.pin(AuditCerts.cert(other))
    private val wire = ServiceWire("RingMASTER/0.0.0-test")

    private val token = "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"
    private val canonical = "AAAAABBBBBCCCCCDDDDDEEEEE"

    private fun identity(version: String = GUEST_HELPER_VERSION, api: Int = GUEST_HELPER_API, sha: String = GuestHelperScripts.probeSha256) =
        """{"product":"ringmaster-engine","version":"$version","api":$api,"probe_sha256":"$sha","host":"lab-guest","os_id":"debian",""" +
            """"os_version":"13","os_name":"Debian GNU/Linux 13 (trixie)","kind":"vm","kind_detail":"qemu","arch":"x86_64","uid":999,"root":false,""" +
            """"sees":{"bluetooth_controllers":1,"wireless_interfaces":0,"system_bus":true,"rfkill":true,"bluetoothctl":true,"iw":false,"nmcli":false},""" +
            """"started":1700000000,"now":1700000100}"""

    private fun run(mode: String, output: String = "RINGMASTER-RADIO 1 probe 0123\n0123\tdone\n") =
        """{"api":1,"mode":"$mode","seconds":0,"exit":0,"timed_out":false,"truncated":false,"ms":42,""" +
            """"probe_sha256":"${GuestHelperScripts.probeSha256}","output":${kotlinx.serialization.json.JsonPrimitive(output)}}"""

    /** A fake Engine: the protocol's routes, the token check and the lockout after five wrong tokens. */
    private class FakeEngine(val token: String, val whoami: () -> String, val runs: (String) -> String) {
        val failures = AtomicInteger()
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)
        fun reply(r: Received): Reply {
            val path = r.line.substringAfter(' ').substringBefore('?')
            if (r.line == "GET /v1/ping") return Reply.json(200, """{"product":"ringmaster-engine","api":1}""")
            if (failures.get() >= 5) return Reply.json(429, """{"error":"locked"}""", "Retry-After: 900\r\n")
            if (r.header("Authorization") != "Bearer $token") { failures.incrementAndGet(); return Reply.json(401, """{"error":"token"}""") }
            if (busy.get() && path.startsWith("/v1/scan/")) return Reply.json(409, """{"error":"busy"}""", "Retry-After: 5\r\n")
            return when (r.line) {
                "GET /v1/whoami" -> Reply.json(200, whoami())
                "GET /v1/radios" -> Reply.json(200, runs("probe"))
                else -> when {
                    r.line.startsWith("POST /v1/scan/bluetooth?seconds=") -> Reply.json(200, runs("bt-scan"))
                    r.line.startsWith("POST /v1/scan/wifi?seconds=") -> Reply.json(200, runs("wifi-scan"))
                    else -> Reply.json(404, """{"error":"not-found"}""")
                }
            }
        }
    }

    private fun engine(whoami: () -> String = { identity() }, runs: (String) -> String = { run(it) }, block: (ScriptedServer, FakeEngine) -> Unit) {
        val fake = FakeEngine(canonical, whoami, runs)
        ScriptedServer(AuditCerts.serverContext(store)) { fake.reply(it) }.use { block(it, fake) }
    }

    private fun client(port: Int, p: String = pin, t: String? = token) = GuestHelperClient("127.0.0.1", port, p, wire) { t?.toCharArray() }

    private fun failure(block: () -> Unit): GuestHelperException = assertFailsWith<GuestHelperException> { block() }

    // Pairing, step by step.

    @Test fun pairingReadsTheCertificateConfirmsChecksTheTokenAndMakesTheRecord() = engine { s, _ ->
        val pairing = GuestHelperPairing(wire)
        val seen = pairing.readCertificate("127.0.0.1", s.port)
        assertEquals(pin, seen.fingerprint)
        assertEquals(1, seen.api)
        assertNull(seen.matchesExpected)
        assertEquals(95, seen.shown.length) // 32 pairs and 31 colons
        val confirmed = pairing.confirm(seen, userConfirmed = true)
        val typed = token.lowercase().toCharArray()
        val who = pairing.checkToken(confirmed, typed)
        assertTrue(typed.all { it == Char(0) }, "the typed token is wiped")
        assertEquals("lab-guest", who.host)
        assertEquals(GuestHelperAge.Current, guestHelperAge(who))
        val link = pairing.link(GuestHelperPlace.Guest(GuestRef("VM", 990)), confirmed, who, Instant.EPOCH, "id-1")
        val place = GuestHelperPlace.Guest(GuestRef("VM", 990))
        val expected = GuestHelperLink("id-1", place, "127.0.0.1", s.port, pin, GUEST_HELPER_VERSION, "lab-guest", Instant.EPOCH, Instant.EPOCH)
        assertEquals(expected, link)
    }

    @Test fun noTokenIsSentBeforeTheUserConfirms() = engine { s, _ ->
        val pairing = GuestHelperPairing(wire)
        val seen = pairing.readCertificate("127.0.0.1", s.port)
        assertEquals(GuestHelperFailure.Refused, failure { pairing.confirm(seen, userConfirmed = false) }.failure)
        assertTrue(s.received.none { it.header("Authorization") != null }, "only the token-free ping went out")
    }

    @Test fun aFingerprintReadBackFromTheInstallerMustMatchEvenWhenTheUserSaysYes() = engine { s, _ ->
        val pairing = GuestHelperPairing(wire)
        assertEquals(true, pairing.readCertificate("127.0.0.1", s.port, expected = pin.uppercase().chunked(2).joinToString(":")).matchesExpected)
        val wrong = pairing.readCertificate("127.0.0.1", s.port, expected = otherPin)
        assertEquals(false, wrong.matchesExpected)
        assertEquals(GuestHelperFailure.CertificateChanged, failure { pairing.confirm(wrong, userConfirmed = true) }.failure)
    }

    @Test fun aWrongTokenIsRefusedAndAMistypedOneNeverLeaves() = engine { s, fake ->
        val pairing = GuestHelperPairing(wire)
        val confirmed = pairing.confirm(pairing.readCertificate("127.0.0.1", s.port), true)
        assertEquals(GuestHelperFailure.WrongToken, failure { pairing.checkToken(confirmed, "ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ".toCharArray()) }.failure)
        assertEquals(1, fake.failures.get())
        assertEquals(GuestHelperFailure.WrongToken, failure { pairing.checkToken(confirmed, "not a token".toCharArray()) }.failure)
        assertEquals(1, fake.failures.get(), "a token of the wrong shape is caught before it is sent")
    }

    @Test fun lockoutIsReportedWithItsTime() = engine { s, _ ->
        val wrong = client(s.port, t = "ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ")
        repeat(5) { assertEquals(GuestHelperFailure.WrongToken, failure { wrong.whoami() }.failure) }
        val locked = failure { client(s.port).whoami() }
        assertEquals(GuestHelperFailure.LockedOut, locked.failure)
        assertEquals(900, locked.retryAfterSeconds)
        assertTrue("15 minutes" in locked.message!!, locked.message)
    }

    @Test fun aChangedCertificateIsRefusedBeforeTheTokenLeaves() = engine { s, _ ->
        assertEquals(GuestHelperFailure.CertificateChanged, failure { client(s.port, p = otherPin).whoami() }.failure)
        assertTrue(s.received.isEmpty(), "no request reached the server")
    }

    @Test fun plainHttpOnThePortIsNotAnEngine() = ScriptedServer { Reply.json(200, """{"product":"ringmaster-engine","api":1}""") }.use { s ->
        assertEquals(GuestHelperFailure.NotTls, failure { GuestHelperPairing(wire).readCertificate("127.0.0.1", s.port) }.failure)
        val c = client(s.port).apply { timeLimits = WireLimits(readMs = 1_000, totalMs = 2_000) }
        assertEquals(GuestHelperFailure.NotTls, failure { c.whoami() }.failure)
    }

    @Test fun anotherTlsServiceOnThePortIsNotAnEngine() = ScriptedServer(AuditCerts.serverContext(store)) { Reply.json(200, """{"status":"ok"}""") }.use { s ->
        assertEquals(GuestHelperFailure.NotAnEngine, failure { GuestHelperPairing(wire).readCertificate("127.0.0.1", s.port) }.failure)
    }

    @Test fun nothingListeningIsUnreachable() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(GuestHelperFailure.Unreachable, failure { client(port).whoami() }.failure)
        assertEquals(GuestHelperFailure.NotTls, failure { GuestHelperPairing(wire).readCertificate("127.0.0.1", port) }.failure)
    }

    // Reads.

    @Test fun theProbeAndBothScansReturnTheScriptOutputUnchanged() {
        val text = "RINGMASTER-RADIO 1 probe 0123\n0123\tbt\tid=hci0\tdriver=btusb\n0123\tbegin\tbluetoothctl-list\né \"quoted\" \\ tab\there\n0123\tdone\n"
        engine(runs = { run(it, text) }) { s, _ ->
            val c = client(s.port)
            assertEquals(text, c.radios().output)
            assertEquals(GuestHelperMode.BluetoothScan, c.scanBluetooth(10).mode)
            assertEquals(GuestHelperMode.WifiScan, c.scanWifi(4).mode)
            assertTrue(s.received.any { it.line == "POST /v1/scan/wifi?seconds=4" })
            val leaks = s.received.flatMap { it.head.lineSequence().toList() }
                .filter { l -> l.startsWith("GET /v1/radios?") || l.contains(canonical) && !l.startsWith("Authorization:") }
            assertEquals(emptyList(), leaks, "the token travels only in the Authorization header")
        }
    }

    @Test fun aScanOutsideItsSecondsIsNotSent() = engine { s, _ ->
        assertFailsWith<IllegalArgumentException> { client(s.port).scanWifi(3) }
        assertFailsWith<IllegalArgumentException> { client(s.port).scanBluetooth(31) }
        assertTrue(s.received.isEmpty())
    }

    @Test fun aBusyEngineSaysSo() = engine { s, fake ->
        fake.busy.set(true)
        val e = failure { client(s.port).scanBluetooth(10) }
        assertEquals(GuestHelperFailure.Busy, e.failure)
        assertEquals(5, e.retryAfterSeconds)
    }

    @Test fun anOversizedAnswerIsRefused() = ScriptedServer(AuditCerts.serverContext(store)) { r ->
        if (r.line == "GET /v1/radios") Reply.Huge(3L * 1024 * 1024) else Reply.Huge(100L * 1024)
    }.use { s ->
        assertEquals(GuestHelperFailure.TooLarge, failure { client(s.port).whoami() }.failure, "whoami is capped at 64 KiB")
        assertEquals(GuestHelperFailure.TooLarge, failure { client(s.port).radios() }.failure, "a run is capped at 2 MiB")
    }

    @Test fun aSlowAnswerTimesOut() = ScriptedServer(AuditCerts.serverContext(store)) { Reply.Hang }.use { s ->
        val c = client(s.port).apply { timeLimits = WireLimits(readMs = 500, totalMs = 1_000) }
        val started = System.nanoTime()
        assertEquals(GuestHelperFailure.TooSlow, failure { c.radios() }.failure)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun anAnswerOutsideTheProtocolIsMalformedNotTrusted() {
        for (bad in listOf(
            identity().replace("\"lab-guest\"", "\"" + "h".repeat(300) + "\""),
            identity().replace("\"version\":\"$GUEST_HELPER_VERSION\"", "\"version\":\"1.0; rm\""),
            identity().replace("\"uid\":999", "\"uid\":\"999\""),
            identity().replace("\"lab-guest\"", "\"lab\\u001b[2Jguest\""),
            identity().replace(",\"sees\"", ",\"x\""),
            "[1,2,3]",
        )) engine(whoami = { bad }) { s, _ -> assertEquals(GuestHelperFailure.Malformed, failure { client(s.port).whoami() }.failure, bad) }
        engine(runs = { run("wifi-scan") }) { s, _ ->
            assertEquals(GuestHelperFailure.Malformed, failure { client(s.port).radios() }.failure, "a run of another mode")
        }
        engine(whoami = { identity().replace("ringmaster-engine", "something-else") }) { s, _ ->
            assertEquals(GuestHelperFailure.NotAnEngine, failure { client(s.port).whoami() }.failure)
        }
    }

    // Versions.

    @Test fun anOlderEngineIsToldApart() = engine(whoami = { identity(version = "0.0.9") }) { s, _ ->
        val who = client(s.port).whoami()
        assertEquals(GuestHelperAge.Older, guestHelperAge(who))
        assertEquals("The RingMASTER Engine here (0.0.9) is older than this app's ($GUEST_HELPER_VERSION). Update it to see everything this app can show.",
            guestHelperAgeSentence(guestHelperAge(who), who.version))
    }

    @Test fun theSameVersionWithAnotherProbeIsOlderAndANewerOneIsNewer() {
        engine(whoami = { identity(sha = "0".repeat(64)) }) { s, _ -> assertEquals(GuestHelperAge.Older, guestHelperAge(client(s.port).whoami())) }
        engine(whoami = { identity(version = "9.0") }) { s, _ -> assertEquals(GuestHelperAge.Newer, guestHelperAge(client(s.port).whoami())) }
        assertTrue(compareVersions("0.10.0", "0.9.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }

    @Test fun anUnknownApiIsRefused() = engine(whoami = { identity(api = 2) }) { s, _ ->
        assertEquals(GuestHelperFailure.UnknownApi, failure { client(s.port).whoami() }.failure)
    }

    @Test fun theShippedVersionIsTheHelpersOwn() {
        val cargo = listOf(Path.of("../native/ringmaster-engine/Cargo.toml"), Path.of("native/ringmaster-engine/Cargo.toml")).first { Files.exists(it) }
        val version = Files.readAllLines(cargo).first { it.startsWith("version = ") }.substringAfter('"').substringBefore('"')
        assertEquals(version, GUEST_HELPER_VERSION)
    }

    @Test fun tokensAreReadAsTheHelperReadsThem() {
        assertEquals("ABCDEFGHJKMNPQRSTVWXYZ011", GuestHelperClient.canonicalToken("abcde-fghjk-mnpqr-stvwx-yzoil".toCharArray()))
        assertNull(GuestHelperClient.canonicalToken("ABCDE-FGHJK-MNPQR-STVWX-YZ01U".toCharArray()))
        assertNull(GuestHelperClient.canonicalToken("ABCD".toCharArray()))
    }

    // The record.

    @Test fun theRecordKeepsNoSecretAndTheTokenGoesToTheSafe() {
        val dir = Files.createTempDirectory("ring-helpers")
        try {
            val file = dir.resolve("cfg").resolve("guest-helpers.json")
            val vault = MemoryVault()
            val pairings = GuestHelperPairings(GuestHelperStore(file), vault, wire)
            val place = GuestHelperPlace.Guest(GuestRef("Container", 991), "https://192.0.2.1:8006")
            val link = GuestHelperLink("id-1", place, "192.0.2.7", 8696, pin, "0.1.0", "lab-guest", Instant.EPOCH, null)
            val typed = token.toCharArray()
            pairings.save(link, typed).getOrThrow()
            assertTrue(typed.all { it == Char(0) })
            val text = Files.readString(file)
            assertFalse(canonical in text || token in text, text)
            assertEquals(token, vault.entries["guesthelper:id-1"])
            assertEquals(listOf(link), GuestHelperStore(file).load())
            val node = link.copy(id = "id-2", place = GuestHelperPlace.Node("node-a"), lastSeen = Instant.EPOCH)
            pairings.save(node, token.toCharArray()).getOrThrow()
            assertEquals(listOf(link, node), GuestHelperStore(file).load())
            // A new pairing of the same place replaces the old one and forgets its token.
            pairings.save(link.copy(id = "id-3"), token.toCharArray()).getOrThrow()
            assertNull(vault.entries["guesthelper:id-1"])
            assertEquals(listOf("id-2", "id-3"), pairings.list().map { it.id })
            pairings.unpair("id-3").getOrThrow()
            assertNull(vault.entries["guesthelper:id-3"])
            assertEquals(listOf("id-2"), GuestHelperStore(file).load().map { it.id })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aNewerOrDamagedRecordIsLeftUntouched() {
        val dir = Files.createTempDirectory("ring-helpers")
        try {
            val file = dir.resolve("guest-helpers.json")
            Files.writeString(file, """{"version":2,"helpers":[]}""")
            val newer = GuestHelperStore(file)
            assertEquals(emptyList(), newer.load())
            assertEquals("guest-helpers.json was written by a newer RingMASTER and is left untouched.", newer.blockedReason)
            assertFailsWith<IllegalStateException> { newer.save(emptyList()) }
            Files.writeString(file, """{"version":1,"helpers":[{"id":"x","host":"192.0.2.7"}]}""")
            val damaged = GuestHelperStore(file)
            assertEquals(emptyList(), damaged.load())
            assertNotNull(damaged.blockedReason)
            assertEquals("""{"version":1,"helpers":[{"id":"x","host":"192.0.2.7"}]}""", Files.readString(file))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aSavedPairingReadsWithTheTokenFromTheSafe() = engine { s, _ ->
        val vault = MemoryVault()
        val pairings = GuestHelperPairings(GuestHelperStore(null), vault, wire)
        val link = GuestHelperLink("id-1", GuestHelperPlace.Guest(GuestRef("VM", 990)), "127.0.0.1", s.port, pin, "0.1.0", "", Instant.EPOCH, null)
        pairings.save(link, token.toCharArray()).getOrThrow()
        val who = pairings.client(link).whoami()
        pairings.seen("id-1", who, Instant.EPOCH.plusSeconds(60)).getOrThrow()
        assertEquals(Instant.EPOCH.plusSeconds(60), pairings.link("id-1")!!.lastSeen)
        assertEquals("lab-guest", pairings.link("id-1")!!.hostName)
    }

    @Test fun theProbeScriptIsTheSharedOne() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(local.ringleader.core.RadioScripts.probe.toByteArray())
        assertEquals(digest.joinToString("") { "%02x".format(it) }, GuestHelperScripts.probeSha256)
    }

    // Pairing after an install through the guest agent.

    private val labGuest = local.ringleader.core.Guest("https://192.0.2.1:8006", 990, "lab-vm", "node-a", "VM", "running")

    @Test fun anAgentInstallPairsByItselfWithWhatTheInstallerPrinted() = engine { s, _ ->
        val pairings = GuestHelperPairings(GuestHelperStore(null), MemoryVault(), wire)
        val typed = token.toCharArray()
        val installed = GuestHelperInstalled(GUEST_HELPER_VERSION, s.port, pin, typed, listOf("127.0.0.1"))
        val link = pairFromInstaller(GuestHelperPairing(wire), pairings, labGuest, installed)
        assertEquals(guestHelperPlace(labGuest), link.place)
        assertEquals(pin, link.pin)
        assertTrue(typed.all { it == Char(0) }, "the installer's token is wiped")
        assertEquals(token, pairings.token(link.id)?.concatToString())
    }

    @Test fun anInstallWhoseCertificateDiffersOnTheNetworkIsNotPaired() = engine { s, _ ->
        val pairings = GuestHelperPairings(GuestHelperStore(null), MemoryVault(), wire)
        val installed = GuestHelperInstalled(GUEST_HELPER_VERSION, s.port, otherPin, token.toCharArray(), listOf("127.0.0.1"))
        val e = failure { pairFromInstaller(GuestHelperPairing(wire), pairings, labGuest, installed) }
        assertEquals(GuestHelperFailure.CertificateChanged, e.failure)
        assertTrue(pairings.list().isEmpty())
        assertTrue(s.received.none { it.header("Authorization") != null }, "no token went to the wrong certificate")
    }

    @Test fun anUpgradeOrAMissingAddressSaysWhatToDo() = engine { s, _ ->
        val pairings = GuestHelperPairings(GuestHelperStore(null), MemoryVault(), wire)
        val upgrade = GuestHelperInstalled(GUEST_HELPER_VERSION, s.port, pin, null, listOf("127.0.0.1"))
        assertEquals(GuestHelperFailure.WrongToken, failure { pairFromInstaller(GuestHelperPairing(wire), pairings, labGuest, upgrade) }.failure)
        val nowhere = GuestHelperInstalled(GUEST_HELPER_VERSION, s.port, pin, token.toCharArray(), emptyList())
        val e = failure { pairFromInstaller(GuestHelperPairing(wire), pairings, labGuest, nowhere) }
        assertEquals(GUEST_HELPER_NO_ADDRESS, e.message)
    }
}
