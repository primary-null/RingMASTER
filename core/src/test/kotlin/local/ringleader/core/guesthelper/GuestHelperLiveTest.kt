package local.ringleader.core.guesthelper

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import local.ringleader.core.Connection
import local.ringleader.core.PveClient
import local.ringleader.core.TerminalTarget
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.integrations.MemoryVault
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

/**
 * Pairs a real installed Engine through the real client and reads it. Skipped unless RINGMASTER_ENGINE_LIVE names a file
 * with `host=<address>` and the installer's `RINGMASTER-ENGINE-PAIRING ...` line (or its fields). Writes what it saw,
 * without the token, to RINGMASTER_ENGINE_LIVE_OUT when that is set.
 */
class GuestHelperLiveTest {
    @Test fun pairAndReadARealEngine() {
        val file = System.getenv("RINGMASTER_ENGINE_LIVE")?.let(Path::of) ?: return
        val fields = Files.readString(file).split(Regex("\\s+")).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val host = fields.getValue("host")
        val port = fields["port"]?.toInt() ?: GUEST_HELPER_PORT
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s); println(s) }
        fun <T> timed(label: String, block: () -> T): T {
            val t = System.nanoTime(); val r = block(); note("$label: ${(System.nanoTime() - t) / 1_000_000} ms"); return r
        }
        val wire = ServiceWire("RingMASTER/0.0.0-live")
        val pairing = GuestHelperPairing(wire)
        val seen = timed("read certificate and ping") { pairing.readCertificate(host, port, expected = fields.getValue("sha256")) }
        assertEquals(true, seen.matchesExpected)
        note("fingerprint ${seen.shown}")
        val pin = pairing.confirm(seen, userConfirmed = true)
        val who = timed("check token (whoami)") { pairing.checkToken(pin, fields.getValue("token").toCharArray()) }
        note("whoami version=${who.version} api=${who.api} kind=${who.kind}/${who.kindDetail} os=${who.osName} arch=${who.arch}")
        note("whoami uid=${who.uid} root=${who.root} sees=${who.sees}")
        note("age ${guestHelperAge(who)}; app probe ${GuestHelperScripts.probeSha256}; engine probe ${who.probeSha256}")
        val vault = MemoryVault()
        val pairings = GuestHelperPairings(GuestHelperStore(null), vault, wire)
        val link = pairings.save(pairing.link(GuestHelperPlace.Guest(GuestRef(fields["kind"] ?: "VM", fields["vmid"]?.toInt() ?: 0)), pin, who),
            fields.getValue("token").toCharArray()).getOrThrow()
        val client = pairings.client(link)
        val probe = timed("probe") { client.radios() }
        note("probe exit=${probe.exit} ms=${probe.millis} bytes=${probe.output.length} first=${probe.output.lineSequence().first()}")
        assertTrue(probe.output.startsWith("RINGMASTER-RADIO 1 probe "))
        val bt = timed("bluetooth scan 4 s") { client.scanBluetooth(4) }
        note("bt-scan exit=${bt.exit} timedOut=${bt.timedOut} ms=${bt.millis}")
        val wifi = timed("wifi scan 4 s") { client.scanWifi(4) }
        note("wifi-scan exit=${wifi.exit} timedOut=${wifi.timedOut} ms=${wifi.millis}")
        val otherPin = GuestHelperClient(host, port, "0".repeat(64), wire) { fields.getValue("token").toCharArray() }
        val wrongPin = assertFailsWith<GuestHelperException> { otherPin.whoami() }
        note("wrong pin: ${wrongPin.failure}")
        val otherToken = GuestHelperClient(host, port, pin.pin, wire) { "ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ".toCharArray() }
        val wrongToken = assertFailsWith<GuestHelperException> { otherToken.whoami() }
        note("wrong token: ${wrongToken.failure}")
        System.getenv("RINGMASTER_ENGINE_LIVE_OUT")?.let { Files.writeString(Path.of(it), out) }
    }

    /**
     * Times sending the Engine bundle into a container through the app's own console paste (echo-paced, as a user's paste
     * goes). Skipped unless PVE_HOST, PVE_TOKEN, RINGMASTER_LIVE_NODE, RINGMASTER_ENGINE_PASTE_CT, RINGMASTER_ENGINE_PASTE_PASSWORD
     * (the container's root password) and RINGMASTER_ENGINE_PASTE_FILE (the bundle) are set.
     */
    @Test fun pasteTheBundleThroughAContainerConsole() {
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val node = System.getenv("RINGMASTER_LIVE_NODE") ?: return
        val ct = System.getenv("RINGMASTER_ENGINE_PASTE_CT")?.toInt() ?: return
        val password = System.getenv("RINGMASTER_ENGINE_PASTE_PASSWORD") ?: return
        val bundle = Files.readAllBytes(Path.of(System.getenv("RINGMASTER_ENGINE_PASTE_FILE") ?: return))
        val pin = System.getenv("RINGMASTER_ENGINE_PASTE_PIN") ?: return
        val digest = MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
        val cr = "\r"
        val lines = Base64.getMimeEncoder(76, cr.toByteArray()).encodeToString(bundle)
        // RINGMASTER_ENGINE_PASTE_QUIET: the tty echo off and base64 reading the console directly (ended by Ctrl-D), so no
        // echo has to come back; otherwise a here-document into the interactive shell, echoed line by line.
        val quiet = System.getenv("RINGMASTER_ENGINE_PASTE_QUIET") == "1"
        val text = if (quiet) "$lines$cr" + Char(4) else "base64 -d > /root/rme.tgz <<'RMEOF'$cr$lines${cr}RMEOF$cr"
        Connection(host, raw.substringBefore('='), raw.substringAfter('='), pin).use { c ->
            PveClient().terminalSession(c, TerminalTarget(node, "lxc", ct)).use { t ->
                fun screen() = t.model.buffer.getScreenLines()
                fun waitFor(what: String, ms: Long = 15_000): Boolean {
                    val end = System.currentTimeMillis() + ms
                    while (System.currentTimeMillis() < end) { if (screen().contains(what)) return true; Thread.sleep(100) }
                    return false
                }
                val readyBy = System.currentTimeMillis() + 15_000
                while (!t.ready && t.reason == null && System.currentTimeMillis() < readyBy) Thread.sleep(100)
                t.input(Char(3).toString()); Thread.sleep(300) // ends whatever an earlier attempt left half typed
                t.input(cr)
                if (waitFor("login:", 5_000)) { t.input("root$cr"); waitFor("Password:"); t.input(password + cr) }
                assertTrue(waitFor("#", 30_000), "a root shell; the screen ends: " + screen().lines().filter { it.isNotBlank() }.takeLast(4))
                t.input("clear$cr"); Thread.sleep(500)
                if (quiet) { t.input("stty -echo; base64 -d > /root/rme.tgz; stty echo$cr"); Thread.sleep(500) }
                val started = System.nanoTime()
                assertNull(t.paste(text))
                while (t.pasteLeft > 0 && t.reason == null) Thread.sleep(50)
                val sent = (System.nanoTime() - started) / 1_000_000
                // The marker is split in the command, so only the command's output can show it whole.
                t.input("clear; echo RM\"\"SUM-$(sha256sum /root/rme.tgz | cut -c1-16)-END$cr")
                assertTrue(waitFor("RMSUM-", 300_000), "the shell came back")
                val total = (System.nanoTime() - started) / 1_000_000
                val sum = screen().substringAfter("RMSUM-").substringBefore("-END")
                val hashOk = sum == digest.take(16)
                val line = "paste (quiet=$quiet) of ${text.length} bytes (base64 of ${bundle.size}): sent in $sent ms, " +
                    "shell done in $total ms, hash ok $hashOk"
                println("LIVE-PASTE $line reason=${t.reason}")
                System.getenv("RINGMASTER_ENGINE_LIVE_OUT")?.let { Files.writeString(Path.of(it), line + System.lineSeparator()) }
                assertEquals(digest.take(16), sum)
            }
        }
    }
}
