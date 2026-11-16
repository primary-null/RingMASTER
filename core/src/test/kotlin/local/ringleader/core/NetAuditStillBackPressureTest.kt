package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Network audit 2026-11-16, N-07, now FIXED: [captureConsoleStill] used to read through a private WebSocket listener that called
 * `request(1)` on every message while its queue held only 256 chunks. A server that sent a big raw frame faster than the pixel loop
 * consumed it could overflow the queue, which ended the capture as "The console channel closed before a frame arrived." (1 of the
 * first 5 runs on loopback, 0 of the next 5; machine dependent). It now reads through [VncSocket], which asks for the next message only
 * while its queue has room, so every run must succeed. Opt in with RINGMASTER_NET_AUDIT_SLOW=1; ConsoleStillSocketTest has the fast,
 * deterministic cases.
 */
class NetAuditStillBackPressureTest {
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun rfbServer(width: Int, height: Int): (WsConn) -> Unit = { conn ->
        try {
            conn.sendBinary("RFB 003.008\n".toByteArray())
            conn.received.poll(10, TimeUnit.SECONDS)                    // the client's version
            conn.sendBinary(byteArrayOf(1, 1))                          // one security type: none
            conn.received.poll(10, TimeUnit.SECONDS)
            conn.sendBinary(byteArrayOf(0, 0, 0, 0))                    // security result
            conn.received.poll(10, TimeUnit.SECONDS)                    // ClientInit
            val init = ByteArrayOutputStream()
            init.write(u16(width)); init.write(u16(height)); init.write(ByteArray(16)); init.write(byteArrayOf(0, 0, 0, 4)); init.write("test".toByteArray())
            conn.sendBinary(init.toByteArray())
            repeat(3) { conn.received.poll(10, TimeUnit.SECONDS) }      // pixel format, encodings, update request
            val header = ByteArrayOutputStream()
            header.write(byteArrayOf(0, 0, 0, 1)); header.write(u16(0)); header.write(u16(0)); header.write(u16(width)); header.write(u16(height)); header.write(byteArrayOf(0, 0, 0, 0))
            conn.sendBinary(header.toByteArray())
            val chunk = ByteArray(16384)
            var left = width.toLong() * height * 4
            while (left > 0) { val n = minOf(chunk.size.toLong(), left).toInt(); conn.sendBinary(chunk.copyOf(n)); left -= n }
            Thread.sleep(2000)
        } catch (_: Exception) { }
    }

    @Test fun aFastServerSendingABigRawFrameNoLongerOverflowsTheStillQueue() {
        assumeSlowAudit()
        val cert = NetAuditCerts.loopback
        var ok = 0; var overflow = 0; var other = 0
        val messages = mutableListOf<String>()
        repeat(10) {
            MiniWsServer(cert.server, onConnected = rfbServer(4096, 4096)).use { server ->
                val connection = Connection("https://127.0.0.1:${server.port}", "root@pam!audit", "SECRET-VALUE", cert.pin)
                val started = System.nanoTime()
                val outcome = runCatching { captureConsoleStill(connection, "/api2/json/nodes/n1/qemu/100") { """{"data":{"port":5900,"ticket":"T","password":"pw"}}""" } }
                val ms = (System.nanoTime() - started) / 1_000_000
                when {
                    outcome.isSuccess -> { ok++; messages += "ok in $ms ms" }
                    outcome.exceptionOrNull()?.message == "The console channel closed before a frame arrived." -> { overflow++; messages += "queue overflow after $ms ms" }
                    else -> { other++; messages += "${outcome.exceptionOrNull()?.javaClass?.simpleName}: ${outcome.exceptionOrNull()?.message} after $ms ms" }
                }
            }
        }
        println("NETAUDIT still capture of a 4096x4096 raw frame x10: ok=$ok overflow=$overflow other=$other :: $messages")
        assertEquals(10, ok, "every capture succeeds: $messages")
        assertEquals(0, overflow)
    }
}
