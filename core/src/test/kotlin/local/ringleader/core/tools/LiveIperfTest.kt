package local.ringleader.core.tools

import kotlinx.coroutines.runBlocking
import local.ringleader.core.findExecutable
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Opt-in: RINGMASTER_LIVE_IPERF=1. Runs a loopback iperf3 server (127.0.0.1 only, one test) and the plug-in's client. */
class LiveIperfTest {
    @Test fun loopbackThroughput() {
        if (System.getenv("RINGMASTER_LIVE_IPERF") != "1") return
        val exe = findExecutable("iperf3") ?: return
        val server = ProcessBuilder(exe, "-s", "-1", "-B", "127.0.0.1", "-p", "52011").redirectErrorStream(true).start()
        try {
            Thread.sleep(800)
            val samples = mutableListOf<Double>()
            val result = runBlocking {
                Iperf3Plugin().run(mapOf("host" to "127.0.0.1", "port" to "52011", "duration" to "3", "parallel" to "1", "reverse" to "false", "udp" to "false", "server" to "false", "executable" to "")) { e ->
                    if (e is ToolEvent.Sample) samples += e.y
                }
            }
            println("LIVE-IPERF ok=${result.ok} samples=${samples.size} summary=${result.summary}")
            assertTrue(result.ok && samples.isNotEmpty())
        } finally { server.destroyForcibly() }
    }
}
