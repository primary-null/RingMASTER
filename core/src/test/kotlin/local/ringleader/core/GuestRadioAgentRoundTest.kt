package local.ringleader.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.*

/**
 * A small HTTPS/1.1 server on loopback that stands in for the Proxmox guest-agent endpoints. It keeps every request whole
 * (line, head and body) so a test can say what was sent and how often. [answer] decides the reply per request.
 */
internal class AgentFakeServer(private val context: SSLContext, private val answer: (Request) -> Pair<Int, String>) : AutoCloseable {
    data class Request(val line: String, val head: String, val body: String)

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val address get() = "https://127.0.0.1:${listener.localPort}"
    private val thread = Thread {
        while (!listener.isClosed) {
            val raw = try { listener.accept() } catch (_: Exception) { break }
            Thread { serve(raw) }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    val posts get() = requests.filter { it.line.startsWith("POST ") }

    private fun serve(raw: Socket) {
        try {
            raw.soTimeout = 10_000
            val tls = context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
            tls.useClientMode = false
            tls.startHandshake()
            val head = readHead(tls.inputStream) ?: return
            val line = head.lineSequence().first().substringBeforeLast(" HTTP/")
            val length = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = tls.inputStream.readNBytes(length).toString(Charsets.UTF_8)
            val request = Request(line, head, body)
            requests += request
            val (status, text) = answer(request)
            val bytes = text.toByteArray()
            tls.outputStream.apply {
                write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
            tls.close()
        } catch (_: Exception) {
        } finally { runCatching { raw.close() } }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= 65536) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val a = out.toByteArray(); val s = a.size
            if (s >= 4 && a[s - 4] == 13.toByte() && a[s - 3] == 10.toByte() && a[s - 2] == 13.toByte() && a[s - 1] == 10.toByte()) return a.toString(Charsets.ISO_8859_1)
        }
        return null
    }

    override fun close() { listener.close(); thread.join(2000) }
}

class GuestRadioAgentRoundTest {
    private val store = AuditCerts.generate("leaf")
    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val hardware = HardwareSummary(mapOf("ostype" to "l26", "agent" to "1"), emptyList(), emptyList())
    private fun connect(address: String) = Connection(address, "root@pam!audit", "token-secret", pin)
    private fun vm(c: Connection, status: String = "running", kind: String = "VM") = Guest(c.remoteKey, 112, "fixture", "node-a", kind, status)
    private fun server(answer: (AgentFakeServer.Request) -> Pair<Int, String>) = AgentFakeServer(AuditCerts.serverContext(store), answer)
    private fun exited(out: String, truncated: Boolean = false) =
        buildJsonObject { putJsonObject("data") { put("exited", 1); put("exitcode", 0); put("out-data", out); put("out-truncated", if (truncated) 1 else 0) } }.toString()
    private val pid = """{"data":{"pid":4242}}"""
    private val running = """{"data":{"exited":0}}"""
    private val client get() = PveClient(1500, 4000)

    @Test fun theProbeRoundSendsOneExecThenPollsThatPidAndParsesTheAnswer() = runBlocking {
        val statusReads = java.util.concurrent.atomic.AtomicInteger()
        server { r ->
            when {
                r.line.startsWith("POST ") -> 200 to pid
                statusReads.incrementAndGet() == 1 -> 200 to running
                else -> 200 to exited(WifiSamples.text("probe-constructed-tree-tools.txt"))
            }
        }.use { s ->
            val c = connect(s.address)
            val sleeps = mutableListOf<Long>()
            val gateway = LiveGuestRadioGateway(GuestAgentTransport(client) { sleeps += it })
            val probe = gateway.probe(c, vm(c), hardware)
            assertEquals(3, probe.radios.size); assertEquals("Sample Host", probe.bluetooth.single().name)
            assertEquals(1, s.posts.size, "the exec went out once")
            assertEquals("POST /api2/json/nodes/node-a/qemu/112/agent/exec", s.posts.single().line)
            val body = Json.parseToJsonElement(s.posts.single().body).jsonObject
            val command = body.getValue("command").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(guestRadioProbeCommand(), command, "the transport sends exactly the command any transport would run")
            assertEquals(listOf("sh", "-c"), command.take(2)); assertTrue("RINGMASTER-RADIO 1 probe" in command[2])
            assertEquals(2, s.requests.count { it.line == "GET /api2/json/nodes/node-a/qemu/112/agent/exec-status?pid=4242" }, "polls that pid only, until it has exited")
            assertEquals(listOf(1000L, 1000L), sleeps, "one second before the first poll, one second between polls")
            assertTrue(s.requests.all { "Authorization: PVEAPIToken=" in it.head }, "every call carries the token")
        }
    }

    @Test fun theWifiScanRoundParsesTheScriptOutputIntoAScan() = runBlocking {
        server { r -> if (r.line.startsWith("POST ")) 200 to pid else 200 to exited(WifiSamples.text("wifi-constructed-tree-tools.txt")) }.use { s ->
            val c = connect(s.address)
            val scan = LiveGuestRadioGateway(GuestAgentTransport(client) {}).wifiScan(c, vm(c), hardware, 20)
            assertEquals(8, scan.networks.size); assertEquals("Example Home", scan.connectedLink?.ssid); assertNull(scan.issue)
            val sent = Json.parseToJsonElement(s.posts.single().body).jsonObject.getValue("command").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(guestWifiScanCommand(20), sent); assertTrue("ringmaster-radio '20'" in sent[2])
        }
    }

    @Test fun aRefusedTokenIsTypedAndTheExecWasNotRepeated() = runBlocking {
        server { 403 to """{"data":null}""" }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<GuestAgentFailure> { LiveGuestRadioGateway(GuestAgentTransport(client) {}).probe(c, vm(c), hardware) }
            assertEquals(GuestRefusal.NoPermission, failure.refusal); assertEquals(GUEST_BT_NO_PERMISSION, failure.message)
            assertEquals(1, s.posts.size)
        }
    }

    @Test fun anExecWhoseAnswerIsLostIsReportedAndNeverResent() = runBlocking {
        server { 500 to """{"data":null,"message":"QEMU guest agent is not running"}""" }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<GuestAgentFailure> { LiveGuestRadioGateway(GuestAgentTransport(client) {}).wifiScan(c, vm(c), hardware, 10) }
            assertEquals(GuestRefusal.AgentNotAnswering, failure.refusal); assertEquals(GUEST_BT_AGENT, failure.message)
            assertEquals(1, s.posts.size, "a mutation-shaped request is sent once, whatever the server says")
        }
    }

    @Test fun anEndpointThatIsNotThereMeansTheAgentIsNotAnswering() = runBlocking {
        server { 404 to "{}" }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<GuestAgentFailure> { client.guestAgentRun(c, vm(c), listOf("true"), 0, 5) {} }
            assertEquals(GuestRefusal.AgentNotAnswering, failure.refusal)
            // And the reply that is not an exec answer at all.
            server { r -> if (r.line.startsWith("POST ")) 200 to """{"data":{}}""" else 200 to "{}" }.use { s2 ->
                val c2 = connect(s2.address)
                assertEquals(GuestRefusal.AgentNotAnswering, assertFailsWith<GuestAgentFailure> { client.guestAgentRun(c2, vm(c2), listOf("true"), 0, 5) {} }.refusal)
            }
        }
    }

    @Test fun aCommandThatNeverEndsTimesOutWithItsType() = runBlocking {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        server { r -> if (r.line.startsWith("POST ")) 200 to pid else { polls.incrementAndGet(); 200 to running } }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<GuestAgentFailure> { client.guestAgentRun(c, vm(c), listOf("sleep", "999"), 0, 1) { Thread.sleep(120) } }
            assertEquals(GuestRefusal.TimedOut, failure.refusal); assertEquals(GUEST_BT_TIMEOUT, failure.message)
            assertEquals(1, s.posts.size); assertTrue(polls.get() >= 2, "it kept polling until its budget ran out")
        }
    }

    @Test fun truncatedOutputIsAFailureNotAShortList() = runBlocking {
        server { r -> if (r.line.startsWith("POST ")) 200 to pid else 200 to exited("RINGMASTER-RADIO 1 probe abcd", truncated = true) }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<IllegalStateException> { LiveGuestRadioGateway(GuestAgentTransport(client) {}).probe(c, vm(c), hardware) }
            assertEquals(GUEST_RADIO_TRUNCATED, failure.message)
        }
    }

    @Test fun aGuestThatIsRefusedHereNeverReachesTheServer() = runBlocking {
        server { 200 to pid }.use { s ->
            val c = connect(s.address)
            val gateway = LiveGuestRadioGateway(GuestAgentTransport(client) {})
            assertEquals(GuestRefusal.WindowsGuest, assertFailsWith<GuestAgentFailure> { gateway.probe(c, vm(c), hardware.copy(fields = mapOf("ostype" to "win11", "agent" to "1"))) }.refusal)
            assertEquals(GuestRefusal.NotRunning, assertFailsWith<GuestAgentFailure> { gateway.wifiScan(c, vm(c, status = "stopped"), hardware, 10) }.refusal)
            assertEquals(GuestRefusal.Container, assertFailsWith<GuestAgentFailure> { gateway.probe(c, vm(c, kind = "Container"), hardware) }.refusal)
            assertTrue(s.requests.isEmpty(), "not one request reached the server")
        }
    }

    @Test fun theBluetoothScanStillSendsItsOwnCommandAndKeepsItsSentences() {
        server { r -> if (r.line.startsWith("POST ")) 200 to pid else 200 to exited("RINGMASTER-BLUEZ:NOADAPTER\n") }.use { s ->
            val c = connect(s.address)
            val sleeps = mutableListOf<Long>()
            val scan = client.guestBluetoothScan(c, vm(c), 3) { sleeps += it }
            assertEquals(BT_NO_ADAPTER, scan.note)
            assertEquals(bluezScanCommand(3), Json.parseToJsonElement(s.posts.single().body).jsonObject.getValue("command").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(3000L), sleeps, "the scan window is waited out before the first poll, as before")
        }
        server { 403 to "{}" }.use { s ->
            val c = connect(s.address)
            val failure = assertFailsWith<IllegalStateException> { client.guestBluetoothScan(c, vm(c), 3) {} }
            assertEquals(GUEST_BT_NO_PERMISSION, failure.message); assertIs<GuestAgentFailure>(failure)
            assertEquals("Bluetooth scans inside a guest need a QEMU VM with the guest agent.", assertFailsWith<IllegalArgumentException> { client.guestBluetoothScan(c, vm(c, kind = "Container"), 3) {} }.message)
            assertEquals(GUEST_BT_NOT_RUNNING, assertFailsWith<IllegalArgumentException> { client.guestBluetoothScan(c, vm(c, status = "stopped"), 3) {} }.message)
            assertEquals(1, s.posts.size)
        }
        server { r -> if (r.line.startsWith("POST ")) 200 to pid else 200 to exited("x", truncated = true) }.use { s ->
            val c = connect(s.address)
            assertEquals("The scan output from the VM was truncated.", assertFailsWith<IllegalStateException> { client.guestBluetoothScan(c, vm(c), 3) {} }.message)
        }
    }
}
