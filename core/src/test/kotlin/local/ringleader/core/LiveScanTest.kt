package local.ringleader.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Opt-in live LAN scan from this computer: RINGMASTER_LIVE_SCAN=1. Prints the plan, timing and hit kinds. */
class LiveScanTest {
    @Test fun scanThisNetwork() {
        if (System.getenv("RINGMASTER_LIVE_SCAN") != "1") return
        val plan = NetworkScanner.interfaces()
        plan.networks.forEach { println("LIVE-SCAN plan ${it.source.name.take(40)} ${it.source.ip}/${it.source.prefix} -> ${it.cidr ?: "skipped: " + it.reason}") }
        val started = System.nanoTime()
        val last = runBlocking { NetworkScanner().scan(plan).toList().last() }
        val seconds = (System.nanoTime() - started) / 1e9
        println("LIVE-SCAN scanned=${last.scanned}/${last.total} in ${"%.1f".format(seconds)} s finished=${last.finished}")
        last.hits.groupBy { it.kind }.forEach { (k, v) -> println("LIVE-SCAN ${k}: ${v.size} " + v.joinToString { "${it.ip}:${it.port}${if (it.fingerprint != null) " tls" else ""}" }) }
    }
}
